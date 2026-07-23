package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ConnectionStore;
import com.datacube.config.CredentialCipher;
import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
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
import com.datacube.update.UpdateService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.concurrent.Callable;

/**
 * 应用主壳：三栏式布局（顶部工具栏 + 左连接树 + 中内容区）。
 *
 * <p>持有并装配服务层（{@link ConnectionManager} 等），实现
 * {@link ConnectionTreePane.Actions} 将树操作转为内容标签。
 * 迁移功能作为一个常驻标签保留。
 */
public final class AppShell {

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

    private final ContentTabPane contentTabs = new ContentTabPane();
    private final MigrationPane migrationPane = new MigrationPane();
    private final UpdateService updateService = new UpdateService();
    private final SqlHistoryStore sqlHistory = new SqlHistoryStore();
    private final ShortcutSettings shortcuts = new ShortcutSettings();
    private final TreeActions treeActions = new TreeActions();

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

        ConnectionTreePane treePane = new ConnectionTreePane(store, connMgr, treeSvc, session, treeActions);
        root.setTop(topBar(treePane));

        SplitPane split = new SplitPane(treePane.getNode(), contentTabs.getNode());
        split.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(treePane.getNode(), false);
        root.setCenter(split);

        // 快捷键（默认 Ctrl+Shift+H）：找回近期使用的 SQL。用事件过滤器实时匹配
        // 当前绑定值，而非静态 accelerator，便于在设置里改绑后即时生效。
        root.sceneProperty().addListener((o, old, scene) -> {
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                    if (shortcuts.get(ShortcutAction.SQL_HISTORY).match(e)) {
                        e.consume();
                        openSqlHistory();
                    }
                });
            }
        });
    }

    private HBox topBar(ConnectionTreePane treePane) {
        // 品牌以小立方体图标呈现（标题文字与系统标题栏重复，故省略）
        Node logo = BrandLogo.cube(20);

        Button addConnBtn = new Button("＋ 新建连接");
        addConnBtn.setOnAction(e -> treePane.newConnection());
        Button refreshBtn = new Button("⟳ 刷新");
        refreshBtn.setOnAction(e -> treePane.refresh());
        Button newSqlBtn = new Button("🗒 新建 SQL");
        newSqlBtn.setOnAction(e -> treeActions.openSqlEditor(session.getActiveConnection(), null));
        Button historyBtn = new Button("🕘 SQL 历史");
        historyBtn.setOnAction(e -> openSqlHistory());
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
        migrationBtn.setOnAction(e -> contentTabs.openSingletonTab("数据迁移", migrationPane.getNode()));
        Button aboutBtn = new Button("ℹ 关于");
        aboutBtn.setOnAction(e ->
                AboutDialog.show(updateService, root.getScene() == null ? null : root.getScene().getWindow(), themeManager));
        Button settingsBtn = new Button("⚙ 设置");
        settingsBtn.setOnAction(e ->
                SettingsDialog.show(settings, shortcuts, root.getScene() == null ? null : root.getScene().getWindow(), themeManager));

        HBox bar = new HBox(6, logo, addConnBtn, refreshBtn, newSqlBtn, historyBtn, sep, spacer,
                migrationBtn, themeBtn, aboutBtn, settingsBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.getStyleClass().add("top-bar");
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return bar;
    }

    /** 是否有迁移任务在运行（供窗口关闭确认）。 */
    public boolean isRunning() {
        return migrationPane.isRunning();
    }

    /** 启动后台静默自检：仅在发现新版时在 UI 线程弹出更新提示（失败静默）。 */
    public void checkForUpdatesOnStartup() {
        updateService.checkInBackground(info ->
                Platform.runLater(() -> UpdateUI.promptUpdate(updateService, info,
                        root.getScene() == null ? null : root.getScene().getWindow())));
    }

    /** 释放全部资源：迁移资源 + 关闭所有活动连接。 */
    public void shutdown() {
        try {
            migrationPane.shutdown();
        } finally {
            connMgr.closeAll();
        }
    }

    /**
     * 打开 SQL 历史找回对话框：选中一条则在新的 SQL 编辑标签中载入其 SQL，
     * 并按连接名解析回原连接（解析不到则不绑定）、回填其 schema。
     */
    private void openSqlHistory() {
        javafx.stage.Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        SqlHistoryDialog.show(sqlHistory, owner, themeManager).ifPresent(entry -> {
            ConnConfig conn = resolveConnByName(entry.connName());
            if (conn != null) session.setActiveConnection(conn);
            SqlEditorPane pane = new SqlEditorPane(session, connMgr, treeSvc, settings,
                    treeActions::openTableDesigner, conn, entry.schema(), sqlHistory, shortcuts);
            pane.setSqlText(entry.sql());
            String name = conn == null ? "SQL" : "SQL - " + conn.name();
            Tab tab = contentTabs.openTab(name, pane.getNode());
            tab.setOnClosed(e -> pane.snapshotToHistory());
        });
    }

    /** 按连接名解析连接配置（历史仅存名字）；找不到返回 {@code null}。 */
    private ConnConfig resolveConnByName(String name) {
        if (name == null) return null;
        for (ConnConfig c : store.loadAll()) {
            if (name.equals(c.name())) return c;
        }
        return null;
    }

    /** 连接树动作实现：将树操作转为内容标签。 */
    private final class TreeActions implements ConnectionTreePane.Actions {

        @Override
        public void openSqlEditor(ConnConfig conn, String schema) {
            if (conn != null) session.setActiveConnection(conn);
            SqlEditorPane pane = new SqlEditorPane(session, connMgr, treeSvc, settings,
                    this::openTableDesigner, conn, schema, sqlHistory, shortcuts);
            String name = conn == null ? "SQL" : "SQL - " + conn.name();
            Tab tab = contentTabs.openTab(name, pane.getNode());
            tab.setOnClosed(e -> pane.snapshotToHistory());
        }

        @Override
        public void openDataGrid(String connId, TableRef table, boolean readOnly) {
            String connName = connMgr.config(connId).name();
            DataGridPane pane = new DataGridPane(browseSvc, editSvc, connId, connName, table, settings, readOnly);
            String prefix = readOnly ? "视图: " : "数据: ";
            contentTabs.openTab(prefix + table.name(), pane.getNode());
        }

        @Override
        public void openTableDesigner(String connId, TableRef table) {
            DbType dbType = connMgr.provider(connId).type();
            String connName = connMgr.config(connId).name();
            TableDesignerPane pane = new TableDesignerPane(designSvc, connId, connName, table, table.schema(), dbType);
            contentTabs.openTab("设计: " + table.name(), pane.getNode());
        }

        @Override
        public void newTable(String connId, String schema) {
            DbType dbType = connMgr.provider(connId).type();
            String connName = connMgr.config(connId).name();
            TableDesignerPane pane = new TableDesignerPane(designSvc, connId, connName, null, schema, dbType);
            contentTabs.openTab("新建表", pane.getNode());
        }

        @Override
        public void exportTable(String connId, TableRef table) {
            ExportDialog.show(connMgr, connId, table,
                    root.getScene() == null ? null : root.getScene().getWindow());
        }

        @Override
        public void openDdl(String connId, ConnectionTreePane.NodeData node) {
            String name = node.name();
            DdlViewPane pane = new DdlViewPane("DDL: " + name, ddlFetch(connId, node));
            contentTabs.openTab("DDL: " + name, pane.getNode());
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
            ObjectEditorPane pane = new ObjectEditorPane(
                    "编辑: " + name, ddlFetch(connId, node), executor);
            contentTabs.openTab("编辑: " + name, pane.getNode());
        }

        @Override
        public void editSequence(String connId, ConnectionTreePane.NodeData node) {
            String name = node.name();
            DbType dbType = connMgr.provider(connId).type();
            String connName = connMgr.config(connId).name();
            SequenceDesignerPane pane = new SequenceDesignerPane(
                    ddlSvc, connId, connName, node.schema(), name, dbType);
            contentTabs.openTab("编辑序列: " + name, pane.getNode());
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
}
