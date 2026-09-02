package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ConnectionStore;
import com.datacube.config.CredentialCipher;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.DataBrowseService;
import com.datacube.service.DataEditService;
import com.datacube.service.DdlService;
import com.datacube.service.ObjectTreeService;
import com.datacube.service.TableDesignService;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.RoutineRef;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.TableRef;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.update.UpdateService;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 应用主壳：三栏式布局（顶部工具栏 + 左连接树 + 中内容区）。
 *
 * <p>持有并装配服务层（{@link ConnectionManager} 等），实现
 * {@link ConnectionTreePane.Actions} 将树操作转为内容标签。
 * 迁移功能作为一个常驻标签保留。
 */
public final class AppShell {

    static final String SQL_FILE_OPEN_FAILURE = "无法打开 SQL 文件。";

    private final BorderPane root = new BorderPane();

    private final CredentialCipher cipher = new CredentialCipher();
    private final ConnectionStore store = new ConnectionStore();
    private final AppSettings settings = new AppSettings();
    private final ThemeManager themeManager = new ThemeManager(settings);
    private final ConnectionManager connMgr = new ConnectionManager(cipher);
    private final ObjectTreeService treeSvc = new ObjectTreeService(connMgr);
    private final DataBrowseService browseSvc = new DataBrowseService(connMgr);
    private final DataEditService editSvc = new DataEditService(connMgr);
    private final DdlService ddlSvc = new DdlService(connMgr);
    private final TableDesignService designSvc = new TableDesignService(connMgr);
    private final SessionContext session = new SessionContext();
    private final FxTaskRunner tasks = new FxTaskRunner();
    private final FxTaskScope fileOpenTasks = tasks.scope();
    private final SqlScriptFileStore sqlScriptFileStore = new SqlScriptFileStore();
    private final RecentSqlFiles recentSqlFiles = new RecentSqlFiles(
            Path.of(System.getProperty("user.home"), ".datacube", "recent-sql-files.txt"));
    private final SqlFileTabRegistry sqlFileTabs = new SqlFileTabRegistry();
    private final SqlFileEntry sqlFileEntry = new SqlFileEntry(sqlScriptFileStore, recentSqlFiles,
            new ScopeSqlFileTaskDispatcher(fileOpenTasks), SessionContext::new,
            this::openLoadedSqlFile, ignored -> showSqlFileOpenFailure(), sqlFileTabs);

    private final ContentTabPane contentTabs = new ContentTabPane();
    private final AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
            contentTabs::closeAllManagedTabsMandatory,
            task -> Thread.startVirtualThread(task),
            this::shutdownRemaining,
            AppShell::reportShutdownFailure);
    private final LazyValue<MigrationPane> migrationPane = new LazyValue<>(() -> new MigrationPane(tasks));
    private final LazyValue<SqlDraftUi> sqlDrafts = new LazyValue<>(() ->
            new SqlDraftUi(java.nio.file.Path.of(System.getProperty("user.home"), ".datacube", "sql-drafts"), contentTabs));
    private final LazyValue<UpdateService> updateService =
            new LazyValue<>(() -> new UpdateService(tasks::submit, Platform::runLater));
    private final SqlHistoryStore sqlHistory = new SqlHistoryStore();
    private final ShortcutSettings shortcuts = new ShortcutSettings();
    private final TreeActions treeActions = new TreeActions();
    private ConnectionTreePane connectionTree;

    public AppShell() {
        build();
    }

    public BorderPane getRoot() {
        return root;
    }

    /** 主题管理器：供外层（{@link com.datacube.DataCubeFx}）注册主窗口场景。 */
    public ThemeManager getThemeManager() {
        return themeManager;
    }

    /**
     * 让 Windows 原生标题栏跟随明暗主题（非 Windows 静默 no-op）。
     *
     * <p>应在主窗口 {@code show()} 之后调用（此时按标题定位 HWND 才有效）；
     * 内部同时订阅主题变化，切换时实时重刷标题栏配色。
     *
     * @param windowTitle 主窗口标题（须与 {@code Stage.setTitle} 一致）
     */
    public void enableNativeTitleBarTheming(String windowTitle) {
        Runnable apply = () -> NativeTitleBar.apply(windowTitle,
                settings.getTheme() == AppSettings.Theme.DARK);
        apply.run();
        settings.themeProperty().addListener((obs, o, n) -> apply.run());
    }

    private void build() {
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        connectionTree = new ConnectionTreePane(store, connMgr, treeSvc, session, treeActions, tasks);
        root.setTop(topBar(connectionTree));

        SplitPane split = new SplitPane(connectionTree.getNode(),
                startWorkspace(contentTabs, connectionTree::newConnection,
                        connectionTree::focusConnections, this::openSqlDrafts));
        split.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(connectionTree.getNode(), false);
        root.setCenter(split);

        // 快捷键（默认 Ctrl+Shift+H）：找回近期使用的 SQL。用事件过滤器实时匹配
        // 当前绑定值，而非静态 accelerator，便于在设置里改绑后即时生效。
        root.sceneProperty().addListener((o, old, scene) -> {
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                    if (shortcuts.get(ShortcutAction.SQL_HISTORY).match(e)) {
                        e.consume();
                        openSqlHistory();
                    } else if (shortcuts.get(ShortcutAction.SQL_OPEN_FILE).match(e)) {
                        e.consume();
                        chooseSqlFileToOpen();
                    } else if (shortcuts.get(ShortcutAction.SQL_SAVE_FILE).match(e)) {
                        e.consume();
                        fireSelectedSqlFileAction("sql-file-save");
                    } else if (shortcuts.get(ShortcutAction.SQL_SAVE_AS).match(e)) {
                        e.consume();
                        fireSelectedSqlFileAction("sql-file-save-as");
                    }
                });
            }
        });
    }

    static Node startWorkspace(ContentTabPane tabs, Runnable create, Runnable focus) {
        return startWorkspace(tabs, create, focus, null);
    }

    static Node startWorkspace(ContentTabPane tabs, Runnable create, Runnable focus, Runnable recoverWorkspace) {
        WorkspaceStartPane start = new WorkspaceStartPane(create, focus, recoverWorkspace);
        start.visibleProperty().bind(tabs.emptyProperty());
        start.managedProperty().bind(start.visibleProperty());
        Node content = tabs.getNode();
        var hasTabs = Bindings.not(tabs.emptyProperty());
        content.visibleProperty().bind(hasTabs);
        content.managedProperty().bind(hasTabs);
        return new StackPane(content, start);
    }


    private HBox topBar(ConnectionTreePane treePane) {
        // 品牌以小立方体图标呈现（标题文字与系统标题栏重复，故省略）
        Node logo = BrandLogo.cube(20);

        Button addConnBtn = new Button("＋ 新建连接");
        addConnBtn.setOnAction(e -> treePane.newConnection());
        Button refreshBtn = new Button("⟳ 刷新");
        refreshBtn.setOnAction(e -> treePane.refresh());
        Button newSqlBtn = new Button("🗒 新建 SQL");
        newSqlBtn.setOnAction(e -> {
            ConnConfig active = session.getActiveConnection();
            treeActions.openSqlEditor(active != null && active.type() == DbType.REDIS ? null : active, null);
        });
        MenuButton sqlFilesMenu = sqlFilesMenu();
        Button historyBtn = new Button("🕘 SQL 历史");
        historyBtn.setOnAction(e -> openSqlHistory());
        Button draftsBtn = new Button("SQL 草稿");
        draftsBtn.setId("sql-drafts");
        draftsBtn.setOnAction(event -> openSqlDrafts());
        Separator sep = new Separator(Orientation.VERTICAL);

        // 弹性留白：把右侧功能按钮推向右端（“活动连接”不再在头部展示，改由各页面自行标识）
        Region spacer = new Region();

        Button themeBtn = new Button();
        Runnable syncThemeBtn = () -> themeBtn.setText(
                settings.getTheme() == AppSettings.Theme.DARK ? "☀ 亮色" : "🌙 暗色");
        syncThemeBtn.run();
        settings.themeProperty().addListener((obs, o, n) -> syncThemeBtn.run());
        themeBtn.setOnAction(e -> themeManager.toggle());
        Button migrationBtn = new Button("🔄 数据迁移");
        migrationBtn.setOnAction(e ->
                contentTabs.openSingletonTab("数据迁移", migrationPane.get().getNode()));
        Button aboutBtn = new Button("ℹ 关于");
        aboutBtn.setOnAction(e ->
                AboutDialog.show(updateService.get(),
                        root.getScene() == null ? null : root.getScene().getWindow(), themeManager));
        Button settingsBtn = new Button("⚙ 设置");
        settingsBtn.setOnAction(e ->
                SettingsDialog.show(settings, shortcuts, root.getScene() == null ? null : root.getScene().getWindow(), themeManager));

        HBox bar = new HBox(6, logo, addConnBtn, refreshBtn, newSqlBtn, sqlFilesMenu, historyBtn, draftsBtn, sep, spacer,
                migrationBtn, themeBtn, aboutBtn, settingsBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.getStyleClass().add("top-bar");
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return bar;
    }

    /** 是否有迁移任务在运行（供窗口关闭确认）。 */
    public boolean isRunning() {
        return migrationPane.peek().map(MigrationPane::isRunning).orElse(false);
    }

    /** 启动后台静默自检：仅在发现新版时在 UI 线程弹出更新提示（失败静默）。 */
    public void checkForUpdatesOnStartup() {
        UpdateService service = updateService.get();
        service.checkInBackground(info -> UpdateUI.promptUpdate(service, info,
                root.getScene() == null ? null : root.getScene().getWindow()));
    }

    /**
     * 异步释放全部资源。受守卫标签完成关闭后，其余潜在阻塞清理在虚拟线程执行。
     */
    public CompletionStage<ShutdownOutcome> shutdownAsync() {
        sqlFileEntry.close();
        sqlFileTabs.close();
        return shutdown.shutdown();
    }

    /** @deprecated 使用并等待 {@link #shutdownAsync()} 的显式结果。 */
    @Deprecated(forRemoval = false)
    public void shutdown() {
        shutdownAsync();
    }

    private void shutdownRemaining() {
        BestEffortCloseSequence.run(
                () -> sqlDrafts.ifInitialized(SqlDraftUi::closeFromBackground),
                connectionTree::close,
                () -> migrationPane.ifInitialized(MigrationPane::shutdown),
                () -> updateService.ifInitialized(UpdateService::close),
                tasks::close,
                connMgr::closeAll);
    }

    private MenuButton sqlFilesMenu() {
        MenuButton sqlFilesMenu = new MenuButton("SQL 文件");
        sqlFilesMenu.setId("sql-files");
        sqlFilesMenu.setOnShowing(event -> rebuildSqlFilesMenu(sqlFilesMenu));
        return sqlFilesMenu;
    }

    private void rebuildSqlFilesMenu(MenuButton sqlFilesMenu) {
        rebuildSqlFilesMenu(sqlFilesMenu, recentSqlFiles, this::chooseSqlFileToOpen, this::openSqlFile);
    }

    static void rebuildSqlFilesMenu(MenuButton sqlFilesMenu, RecentSqlFiles recentFiles,
            Runnable chooseOpen, Consumer<Path> openPath) {
        sqlFilesMenu.getItems().clear();
        MenuItem open = new MenuItem("打开 SQL 文件…");
        open.setId("sql-file-open");
        open.setOnAction(event -> chooseOpen.run());
        sqlFilesMenu.getItems().add(open);

        int index = 0;
        for (Path path : recentFiles.recent()) {
            MenuItem recent = new MenuItem(path.toString());
            recent.setId("sql-file-recent-" + index);
            recent.setOnAction(event -> openPath.accept(path));
            sqlFilesMenu.getItems().add(recent);
            index++;
        }
        MenuItem clear = new MenuItem("清空最近文件");
        clear.setId("sql-file-recent-clear");
        clear.setDisable(index == 0);
        clear.setOnAction(event -> {
            recentFiles.clear();
            rebuildSqlFilesMenu(sqlFilesMenu, recentFiles, chooseOpen, openPath);
        });
        sqlFilesMenu.getItems().add(clear);
    }

    private void chooseSqlFileToOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("打开 SQL 文件");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SQL 文件 (*.sql)", "*.sql"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
        java.io.File selected = chooser.showOpenDialog(
                root.getScene() == null ? null : root.getScene().getWindow());
        if (selected != null) openSqlFile(selected.toPath());
    }

    void openSqlFile(Path path) {
        sqlFileEntry.open(path);
    }

    private boolean openLoadedSqlFile(SqlScriptFileStore.Loaded loaded, SessionContext fileSession) {
        return openLoadedSqlFile(contentTabs, loaded, fileSession, connMgr, treeSvc, settings,
                treeActions::openTableDesigner, sqlHistory, shortcuts, tasks, sqlScriptFileStore,
                recentSqlFiles, new SqlFileDraftLifecycle() {
                    @Override public void bind(SqlEditorPane pane) { sqlDrafts.get().bind(pane); }
                    @Override public void installed(Node content) { sqlDrafts.get().installed(content); }
                }, sqlFileTabs);
    }

    /** Production file-tab transaction shared by AppShell and its package-level lifecycle contract. */
    static boolean openLoadedSqlFile(ContentTabPane contentTabs, SqlScriptFileStore.Loaded loaded,
            SessionContext fileSession, ConnectionManager connMgr, ObjectTreeService treeSvc,
            AppSettings settings, java.util.function.BiConsumer<String, TableRef> openDesigner,
            SqlHistoryStore sqlHistory, ShortcutSettings shortcuts, FxTaskRunner tasks,
            SqlScriptFileStore sqlScriptFileStore, RecentSqlFiles recentSqlFiles,
            SqlFileDraftLifecycle drafts) {
        return openLoadedSqlFile(contentTabs, loaded, fileSession, connMgr, treeSvc, settings,
                openDesigner, sqlHistory, shortcuts, tasks, sqlScriptFileStore, recentSqlFiles,
                drafts, null);
    }

    static boolean openLoadedSqlFile(ContentTabPane contentTabs, SqlScriptFileStore.Loaded loaded,
            SessionContext fileSession, ConnectionManager connMgr, ObjectTreeService treeSvc,
            AppSettings settings, java.util.function.BiConsumer<String, TableRef> openDesigner,
            SqlHistoryStore sqlHistory, ShortcutSettings shortcuts, FxTaskRunner tasks,
            SqlScriptFileStore sqlScriptFileStore, RecentSqlFiles recentSqlFiles,
            SqlFileDraftLifecycle drafts, SqlFileTabRegistry registry) {
        String fallbackTitle = "SQL";
        java.util.concurrent.atomic.AtomicReference<Tab> ownedTab = new java.util.concurrent.atomic.AtomicReference<>();
        SqlFileTabRegistry.Owner fileOwner = null;
        if (registry != null) {
            if (registry.select(loaded.path())) return true;
            fileOwner = registry.createOwner(() -> {
                Tab existing = ownedTab.get();
                if (existing != null) {
                    ((javafx.scene.control.TabPane) contentTabs.getNode())
                            .getSelectionModel().select(existing);
                }
            });
            if (!registry.install(fileOwner, loaded.path())) return true;
        }
        SqlFileTabRegistry.Owner installedOwner = fileOwner;
        Tab opened = contentTabs.openManagedTab(fallbackTitle, (tab, binding) -> {
            ownedTab.set(tab);
            SqlEditorPane pane = new SqlEditorPane(fileSession, connMgr, treeSvc, settings,
                    openDesigner, null, null, sqlHistory, shortcuts, tasks);
            binding.bind(pane::closeResources);
            try {
                if (registry == null) {
                    pane.installSqlScriptFileController(loaded, sqlScriptFileStore, recentSqlFiles,
                            tab::setText, fallbackTitle);
                } else {
                    pane.installSqlScriptFileController(loaded, sqlScriptFileStore, recentSqlFiles,
                            tab::setText, fallbackTitle, registry, installedOwner);
                }
                drafts.bind(pane);
                drafts.installed(pane.getNode());
                return new ContentTabPane.ManagedTabSpec(pane.getNode(), pane::requestClose,
                        pane::requestMandatoryClose, pane::finalizeCloseOnFx, pane::closeResources);
            } catch (Throwable failure) {
                pane.finalizeCloseOnFx();
                throw failure;
            }
        });
        if (opened == null && registry != null) registry.release(installedOwner);
        return opened != null;
    }

    private void showSqlFileOpenFailure() {
        Alert alert = new Alert(Alert.AlertType.ERROR, SQL_FILE_OPEN_FAILURE, ButtonType.OK);
        alert.setHeaderText(null);
        alert.setTitle("打开 SQL 文件");
        javafx.stage.Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
    }

    private void fireSelectedSqlFileAction(String actionId) {
        javafx.scene.control.TabPane tabPane = (javafx.scene.control.TabPane) contentTabs.getNode();
        fireSelectedSqlFileAction(tabPane, actionId);
    }

    static void fireSelectedSqlFileAction(javafx.scene.control.TabPane tabPane, String actionId) {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getContent() == null) return;
        Node candidate = selected.getContent().lookup("#" + actionId);
        if (candidate instanceof Button button && !button.isDisabled()) button.fire();
    }

    interface SqlFileTaskDispatcher extends AutoCloseable {
        <T> void submit(Callable<T> operation, Consumer<? super T> success,
                Consumer<? super Throwable> failure);
        @Override void close();
    }

    @FunctionalInterface
    interface SqlFileTabOpener {
        boolean open(SqlScriptFileStore.Loaded loaded, SessionContext fileSession);
    }

    interface SqlFileDraftLifecycle {
        void bind(SqlEditorPane pane);
        void installed(Node content);
    }

    /** App-owned admission gate for file reads and callbacks. It has no database knowledge. */
    static final class SqlFileEntry implements AutoCloseable {
        private final SqlScriptFileStore store;
        private final RecentSqlFiles recentFiles;
        private final SqlFileTaskDispatcher tasks;
        private final Supplier<SessionContext> fileSessionFactory;
        private final SqlFileTabOpener opener;
        private final Consumer<String> feedback;
        private final SqlFileTabRegistry registry;
        private final AtomicBoolean closed = new AtomicBoolean();

        SqlFileEntry(SqlScriptFileStore store, RecentSqlFiles recentFiles, SqlFileTaskDispatcher tasks,
                Supplier<SessionContext> fileSessionFactory, SqlFileTabOpener opener,
                Consumer<String> feedback) {
            this(store, recentFiles, tasks, fileSessionFactory, opener, feedback, null);
        }

        SqlFileEntry(SqlScriptFileStore store, RecentSqlFiles recentFiles, SqlFileTaskDispatcher tasks,
                Supplier<SessionContext> fileSessionFactory, SqlFileTabOpener opener,
                Consumer<String> feedback, SqlFileTabRegistry registry) {
            this.store = java.util.Objects.requireNonNull(store);
            this.recentFiles = java.util.Objects.requireNonNull(recentFiles);
            this.tasks = java.util.Objects.requireNonNull(tasks);
            this.fileSessionFactory = java.util.Objects.requireNonNull(fileSessionFactory);
            this.opener = java.util.Objects.requireNonNull(opener);
            this.feedback = java.util.Objects.requireNonNull(feedback);
            this.registry = registry;
        }

        void open(Path path) {
            if (closed.get()) return;
            if (path == null) { reportFailure(); return; }
            RecentSqlFiles.RecordAdmission admission = recentFiles.recordAdmission();
            try {
                tasks.submit(() -> store.load(path), loaded -> loaded(loaded, admission),
                        ignored -> reportFailure());
            } catch (RuntimeException ignored) {
                reportFailure();
            }
        }

        private void loaded(SqlScriptFileStore.Loaded loaded,
                RecentSqlFiles.RecordAdmission admission) {
            if (closed.get()) return;
            if (registry != null && registry.select(loaded.path())) return;
            final boolean opened;
            try {
                opened = opener.open(loaded, fileSessionFactory.get());
            } catch (RuntimeException ignored) {
                reportFailure();
                return;
            }
            if (!opened || closed.get()) { if (!closed.get()) reportFailure(); return; }
            try {
                tasks.submit(() -> {
                    if (!closed.get()) recentFiles.record(admission, loaded.path());
                    return null;
                }, ignored -> { }, ignored -> { });
            } catch (RuntimeException ignored) {
                // The opened editor remains usable when shutdown rejects recent-path persistence.
            }
        }

        private void reportFailure() {
            if (!closed.get()) feedback.accept(SQL_FILE_OPEN_FAILURE);
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) tasks.close();
        }
    }

    private static final class ScopeSqlFileTaskDispatcher implements SqlFileTaskDispatcher {
        private final FxTaskScope scope;
        private ScopeSqlFileTaskDispatcher(FxTaskScope scope) { this.scope = scope; }
        @Override public <T> void submit(Callable<T> operation, Consumer<? super T> success,
                Consumer<? super Throwable> failure) { scope.submit(operation, success, failure); }
        @Override public void close() { scope.close(); }
    }

    private static void reportShutdownFailure(Throwable failure) {
        System.err.println("[DataCube] shutdown failure: " + failure);
        failure.printStackTrace(System.err);
    }

    private void openSqlDrafts() {
        SqlDraftUi owner = sqlDrafts.get();
        SqlDraftRecoveryTabs recovery = new SqlDraftRecoveryTabs(contentTabs, owner,
                draft -> SqlEditorPane.recoverDraft(session, connMgr, treeSvc, settings,
                        treeActions::openTableDesigner, draft, sqlHistory, shortcuts, tasks),
                pane -> pane.installRecoveryConnectionChooser(connectionTree::connectionConfigsSnapshot),
                sqlScriptFileStore, recentSqlFiles, sqlFileTabs);
        SqlDraftManagerDialog.show(owner, root.getScene() == null ? null : root.getScene().getWindow(),
                themeManager, recovery::restore, new SqlWorkspaceRecoveryTabs(contentTabs, owner, recovery));
    }

    /**
     * 打开 SQL 历史找回对话框：选中一条则在新的 SQL 编辑标签中载入其 SQL，
     * 使用隔离的空会话离线打开；连接名仅用于标签标题，并回填其 schema。
     */
    private void openSqlHistory() {
        javafx.stage.Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        SqlHistoryDialog.show(sqlHistory, owner, themeManager).ifPresent(entry -> {
            SessionContext historySession = new SessionContext();
            String name = entry.connName() == null ? "SQL - 历史" : "SQL - " + entry.connName();
            openSqlTab(name,
                    () -> new SqlEditorPane(historySession, connMgr, treeSvc, settings,
                            treeActions::openTableDesigner, null, entry.schema(), sqlHistory,
                            shortcuts, tasks),
                    pane -> pane.setSqlText(entry.sql()));
        });
    }

    private void openSqlTab(String title, Supplier<SqlEditorPane> factory) {
        openSqlTab(title, factory, ignored -> { });
    }

    private void openSqlTab(
            String title,
            Supplier<SqlEditorPane> factory,
            Consumer<SqlEditorPane> initialize) {
        SqlDraftUi owner = sqlDrafts.get();
        openSqlTab(contentTabs, title, factory, initialize, sqlScriptFileStore, recentSqlFiles,
                new SqlFileDraftLifecycle() {
                    @Override public void bind(SqlEditorPane pane) { owner.bind(pane); }
                    @Override public void installed(Node content) { owner.installed(content); }
                }, sqlFileTabs);
    }

    static boolean openSqlTab(ContentTabPane contentTabs, String title,
            Supplier<SqlEditorPane> factory, Consumer<SqlEditorPane> initialize,
            SqlScriptFileStore sqlScriptFileStore, RecentSqlFiles recentSqlFiles,
            SqlFileDraftLifecycle drafts, SqlFileTabRegistry registry) {
        java.util.Objects.requireNonNull(registry, "registry");
        java.util.concurrent.atomic.AtomicReference<Tab> ownedTab = new java.util.concurrent.atomic.AtomicReference<>();
        SqlFileTabRegistry.Owner fileOwner = registry.createOwner(() -> {
            Tab existing = ownedTab.get();
            if (existing != null) {
                ((javafx.scene.control.TabPane) contentTabs.getNode())
                        .getSelectionModel().select(existing);
            }
        });
        Tab opened = contentTabs.openManagedTab(title, (tab, binding) -> {
            ownedTab.set(tab);
            SqlEditorPane pane = factory.get();
            binding.bind(pane::closeResources);
            try {
                initialize.accept(pane);
                pane.installSqlScriptFileController(null, sqlScriptFileStore, recentSqlFiles,
                        tab::setText, title, registry, fileOwner);
                drafts.bind(pane);
                drafts.installed(pane.getNode());
                return new ContentTabPane.ManagedTabSpec(pane.getNode(), pane::requestClose,
                        pane::requestMandatoryClose, pane::finalizeCloseOnFx, pane::closeResources);
            } catch (Throwable failure) {
                pane.finalizeCloseOnFx();
                throw failure;
            }
        });
        if (opened == null) registry.release(fileOwner);
        return opened != null;
    }

    private void openBackgroundCleanupTab(String title, Supplier<BackgroundTab> factory) {
        contentTabs.openManagedTab(title, binding -> ManagedTabFactorySequence.create(
                factory,
                tab -> binding.bind(tab.blockingCleanup()),
                ignored -> {},
                tab -> new ContentTabPane.ManagedTabSpec(
                        tab.content(), AsyncTabCloseGuards.blocking(
                                tab.blockingCleanup(), AppShell::reportShutdownFailure),
                        tab.uiFinalizer(), tab.blockingCleanup())));
    }

    /** 连接树动作实现：将树操作转为内容标签。 */
    private final class TreeActions implements ConnectionTreePane.Actions {

        @Override
        public void openSqlEditor(ConnConfig conn, String schema) {
            if (conn != null && conn.type() == DbType.REDIS) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION,
                        "Redis 连接不适用 SQL 编辑器，请打开键浏览器或命令行控制台。", ButtonType.OK);
                alert.setHeaderText(null);
                alert.showAndWait();
                return;
            }
            if (conn != null) session.setActiveConnection(conn);
            String name = conn == null ? "SQL" : "SQL - " + conn.name();
            openSqlTab(name, () -> new SqlEditorPane(session, connMgr, treeSvc, settings,
                    this::openTableDesigner, conn, schema, sqlHistory, shortcuts, tasks));
        }

        @Override
        public void openSchemaDiff(ConnConfig source, String sourceSchema) {
            if (source == null || source.type() == DbType.REDIS) return;
            var capability = connMgr.provider(source.id()).schemaDiffCapability()
                    .orElseThrow(() -> new IllegalStateException(
                            "Schema comparison is unavailable for this database type"));
            contentTabs.openManagedTab("Schema 对比 - " + source.name(),
                    SchemaDiffManagedTabFactory.factory(
                            connectionTree::connectionConfigsSnapshot,
                            availableConnections -> new SchemaDiffPane(
                                    connMgr, availableConnections, source, sourceSchema, capability),
                            ignored -> reportShutdownFailure(new IllegalStateException(
                                    "Schema Diff construction cleanup failed"))));
        }

        @Override
        public void openDataGrid(String connId, TableRef table, boolean readOnly) {
            String connName = connMgr.config(connId).name();
            String prefix = readOnly ? "视图: " : "数据: ";
            openBackgroundCleanupTab(prefix + table.name(), () -> {
                DataGridPane pane = new DataGridPane(
                        browseSvc, editSvc, connId, connName, table, settings, readOnly, tasks);
                return new BackgroundTab(
                        pane.getNode(), pane::closeResources, pane::finalizeCloseOnFx);
            });
        }

        @Override
        public void openTableDesigner(String connId, TableRef table) {
            DbType dbType = connMgr.provider(connId).type();
            String connName = connMgr.config(connId).name();
            openBackgroundCleanupTab("设计: " + table.name(), () -> {
                TableDesignerPane pane = new TableDesignerPane(
                        designSvc, connId, connName, table, table.schema(), dbType, tasks);
                return new BackgroundTab(pane.getNode(), pane::close, () -> {});
            });
        }

        @Override
        public void newTable(String connId, String schema) {
            DbType dbType = connMgr.provider(connId).type();
            String connName = connMgr.config(connId).name();
            openBackgroundCleanupTab("新建表", () -> {
                TableDesignerPane pane = new TableDesignerPane(
                        designSvc, connId, connName, null, schema, dbType, tasks);
                return new BackgroundTab(pane.getNode(), pane::close, () -> {});
            });
        }

        @Override
        public void openRedisKeys(ConnConfig conn, int database) {
            if (conn == null) return;
            session.setActiveConnection(conn);
            openBackgroundCleanupTab(conn.name() + " · db" + database, () -> {
                RedisKeyBrowserPane pane = new RedisKeyBrowserPane(connMgr, conn, database, tasks);
                return new BackgroundTab(pane.getNode(), pane::close, () -> {});
            });
        }

        @Override
        public void openRedisConsole(ConnConfig conn) {
            if (conn == null) return;
            session.setActiveConnection(conn);
            openBackgroundCleanupTab("Redis CLI - " + conn.name(), () -> {
                RedisConsolePane pane = new RedisConsolePane(connMgr, conn, tasks);
                return new BackgroundTab(pane.getNode(), pane::close, () -> {});
            });
        }

        @Override
        public void exportTable(String connId, TableRef table) {
            ExportDialog.show(connMgr, connId, table,
                    root.getScene() == null ? null : root.getScene().getWindow(), tasks);
        }

        @Override
        public void openDdl(String connId, ConnectionTreePane.NodeData node) {
            String name = node.name();
            openBackgroundCleanupTab("DDL: " + name, () -> {
                DdlViewPane pane = new DdlViewPane("DDL: " + name, ddlFetch(connId, node), tasks);
                return new BackgroundTab(pane.getNode(), pane::close, () -> {});
            });
        }

        @Override
        public void editObject(String connId, ConnectionTreePane.NodeData node) {
            String name = node.name();
            java.util.function.Function<String, java.util.List<ScriptOutcome>> executor = ddl -> {
                try {
                    return ddlSvc.executeDdl(connId, ddl);
                } catch (Exception ex) {
                    throw new RuntimeException(ex.getMessage(), ex);
                }
            };
            openBackgroundCleanupTab("编辑: " + name, () -> {
                ObjectEditorPane pane = new ObjectEditorPane(
                        "编辑: " + name, ddlFetch(connId, node), executor, tasks);
                return new BackgroundTab(pane.getNode(), pane::close, () -> {});
            });
        }

        @Override
        public void editSequence(String connId, ConnectionTreePane.NodeData node) {
            String name = node.name();
            DbType dbType = connMgr.provider(connId).type();
            String connName = connMgr.config(connId).name();
            openBackgroundCleanupTab("编辑序列: " + name, () -> {
                SequenceDesignerPane pane = new SequenceDesignerPane(
                        ddlSvc, connId, connName, node.schema(), name, dbType, tasks);
                return new BackgroundTab(pane.getNode(), pane::close, () -> {});
            });
        }

        /** 根据节点类型选择对应的 DDL 获取逻辑。 */
        private Callable<String> ddlFetch(String connId, ConnectionTreePane.NodeData node) {
            String schema = node.schema();
            String name = node.name();
            return switch (node.kind()) {
                case TABLE -> () -> ddlSvc.tableDdl(connId, new TableRef(schema, name));
                case VIEW -> () -> ddlSvc.viewDdl(connId, new TableRef(schema, name));
                case ROUTINE -> () -> ddlSvc.routineDdl(connId, new RoutineRef(schema, name));
                case PACKAGE -> () -> ddlSvc.packageDdl(connId, schema, name);
                case TRIGGER -> () -> ddlSvc.triggerDdl(connId, schema, name);
                case TYPE -> () -> ddlSvc.typeDdl(connId, schema, name);
                case SEQUENCE -> () -> ddlSvc.sequenceDdl(connId, schema, name);
                default -> () -> "-- 不支持的对象类型";
            };
        }
    }

    private record BackgroundTab(Node content, Runnable blockingCleanup, Runnable uiFinalizer) {}
}
