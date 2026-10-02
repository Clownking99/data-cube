import com.datacube.fx.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.config.*;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import javafx.application.*;
import javafx.collections.ListChangeListener;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.fxmisc.richtext.CodeArea;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** External fixture only. Initialization is programmatic; production decisions are never fired. */
public final class WorkspaceNativeExitProbe extends Application {
    public static final class Launcher {
        public static void main(String[] args) { Application.launch(WorkspaceNativeExitProbe.class, args); }
    }
    Path profile, workspacePath, marker, afterFile;
    AppShell shell; Stage stage; VBox host; Label status;
    TabPane tabs; Object drafts, registry, dispatcher;
    SqlDraftCoordinator runtime;
    final AtomicBoolean fault = new AtomicBoolean(true);
    final AtomicInteger providers = new AtomicInteger(), dispatcherCloses = new AtomicInteger(), draftMoves = new AtomicInteger();
    final List<String> attemptHashes = new CopyOnWriteArrayList<>();
    volatile CompletableFuture<Void> nextRead;
    String oldSha, beforeCloseSha;
    SqlWorkspace initial, closingLayout;
    SqlWorkspace cancelledFrozen;
    int cancelledActivityCount, closeAttemptStart;
    String lastDecision = "none";
    boolean ready, closing, terminal;
    final ShutdownQuarantine quarantine = new ShutdownQuarantine();
    final AtomicInteger summaries = new AtomicInteger();
    int cancellations, explicitActivities;
    java.io.PrintWriter events;

    @Override public void start(Stage ownStage) throws Exception {
        profile = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        require(profile.getFileName().toString().startsWith("datacube-workspace-native-profile-"), "exclusive namespace required");
        require(!Files.exists(profile.resolve(".datacube")), "fresh profile required");
        marker = profile.resolve("release-workspace-fault.marker");
        workspacePath = profile.resolve(".datacube/sql-drafts/workspace.bin");
        afterFile = Files.writeString(profile.resolve("after-cancel.sql"), "select 303;\n", StandardOpenOption.CREATE_NEW);
        events = new java.io.PrintWriter(Files.newBufferedWriter(profile.resolve("events.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW), true);
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> fail(failure));
        stage = ownStage; shell = new AppShell();
        set(get(shell, "connMgr"), "providerResolver", (Function<DbType, DatabaseProvider>) type -> {
            providers.incrementAndGet(); throw new AssertionError("all database provider requests forbidden");
        });
        tabs = (TabPane) ((ContentTabPane) get(shell, "contentTabs")).getNode();
        registry = get(shell, "sqlFileTabs");
        installTrackingDispatcher();
        drafts = invoke(get(shell, "sqlDrafts"), "get");
        runtime = (SqlDraftCoordinator) invoke(drafts, "runtime");
        Button release = new Button("夹具：释放工作区故障"); release.setId("fixture-release-workspace");
        release.setOnAction(event -> fixture(() -> { Files.writeString(marker, "fixture release\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); fault.set(false); log("FIXTURE_RELEASE", "button; not a production/native decision"); refreshStatus(); }));
        Button restore = new Button("夹具：重新开启故障"); restore.setId("fixture-restore-workspace");
        restore.setOnAction(event -> fixture(() -> { Files.deleteIfExists(marker); fault.set(true); log("FIXTURE_RESTORE", "button; workspace-only atomic fault"); refreshStatus(); }));
        Button open = new Button("夹具：打开 after-cancel.sql"); open.setId("fixture-open-after-cancel");
        open.setOnAction(event -> {
            if (!ready || closing) return;
            explicitActivities++;
            Thread.startVirtualThread(() -> {
                try {
                    Tab tab = open(afterFile);
                    log("AFTER_FILE_OPEN", "real shell admission; tabs=" + fx(() -> tabs.getTabs().size()) + "; selected=" + tab.getText() + "; fileSHA=" + sha(Files.readAllBytes(afterFile)));
                    fx(() -> { refreshStatus(); return null; });
                } catch (Throwable failure) { fail(failure); }
            });
        });
        status = new Label("夹具初始化中；请等待 READY。生产退出请用此窗口标题栏。");
        host = new VBox(5, new FlowPane(8, 4, release, restore, open), status, shell.getRoot());
        VBox.setVgrow(shell.getRoot(), Priority.ALWAYS);
        Scene scene = new Scene(host, 1120, 760);
        shell.getThemeManager().register(scene); shell.getThemeManager().installWindowHook();
        stage.setTitle(System.getProperty("probe.title")); stage.setScene(scene);
        observeScene(scene, "SHELL");
        stage.widthProperty().addListener((o,a,b) -> geometry("shell")); stage.heightProperty().addListener((o,a,b) -> geometry("shell"));
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) for (Window window : change.getAddedSubList()) if (window instanceof Stage dialog && dialog.getOwner() == stage)
                Platform.runLater(() -> observeDialog(dialog));
        });
        stage.setOnCloseRequest(event -> { event.consume(); requestShutdown(); });
        stage.show(); host.setDisable(true);
        log("EXTERNAL_HOOK", "own Stage titlebar close -> actual AppShell.shutdownAsync; not DataCubeFx launcher; no automatic decision");
        Thread.startVirtualThread(() -> {
            try { initialize(); fx(() -> { ready = true; host.setDisable(false); refreshStatus(); return null; }); log("READY", "two real file reads/checkpoints; oldWorkspaceSHA=" + oldSha + "; layout=" + initial); }
            catch (Throwable failure) { fail(failure); }
        });
    }
    void initialize() throws Exception {
        CompletableFuture<Void> initialized = new CompletableFuture<>();
        AutoCloseable observer = fx(() -> {
            Runnable check = () -> { if (runtime.mode() != SqlDraftCoordinator.Mode.INITIALIZING) initialized.complete(null); };
            AutoCloseable handle = (AutoCloseable) invoke(drafts, "observe", new Class<?>[]{Runnable.class}, check); check.run(); return handle;
        });
        try {
            initialized.get(5, TimeUnit.SECONDS);
            var refreshed = fx(runtime::refresh).get(5, TimeUnit.SECONDS);
            require(refreshed.succeeded() && refreshed.snapshot() != null && refreshed.snapshot().writable(), "actual refresh must succeed");
            fx(() -> { require(runtime.mode() == SqlDraftCoordinator.Mode.ENABLED && !runtime.managementPending(), "runtime ready"); return null; });
        } finally { fx(() -> { observer.close(); return null; }); }
        installAtomicFault();
        // Initialization has real writes and selections, explicitly not native user evidence.
        Path a = Files.writeString(profile.resolve("initial-a.sql"), "select 101;\n", StandardOpenOption.CREATE_NEW);
        Path b = Files.writeString(profile.resolve("initial-b.sql"), "select 202;\n", StandardOpenOption.CREATE_NEW);
        Tab one = open(a), two = open(b);
        fx(() -> { editor(one).selectRange(1, 7); editor(two).selectRange(2, 8); tabs.getSelectionModel().select(one); return null; });
        checkpoint(one); checkpoint(two);
        initial = fx(this::capture);
        List<SqlWorkspace.Entry> reverse = new ArrayList<>(initial.entries()); Collections.reverse(reverse);
        SqlWorkspace old = new SqlWorkspace(1, reverse, reverse.get(0).draftId());
        // Only fixture seed temporarily bypasses its fault; actual production store still writes.
        fault.set(false); fx(() -> runtime.saveWorkspace(old)).get(5, TimeUnit.SECONDS);
        oldSha = sha(Files.readAllBytes(workspacePath)); attemptHashes.clear(); fault.set(true);
        require(draftMoves.get() >= 2, "two actual successful draft publications required");
        log("PROGRAMMATIC_INITIALIZATION", "real reads+checkpoint+position+old layout seed; not native evidence");
    }
    void installAtomicFault() throws Exception {
        Object directory = get(get(get(runtime, "backend"), "store"), "directory");
        Object original = get(directory, "mover"); Class<?> type = original.getClass().getInterfaces()[0];
        set(directory, "mover", Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Path target = (Path) args[1]; require(target.getParent().equals(workspacePath.getParent()), "own directory only");
            if (target.getFileName().toString().equals("workspace.bin")) {
                if (Files.exists(marker) && fault.compareAndSet(true, false)) log("FIXTURE_RELEASE", "own release marker observed at atomic publication; not native action");
                String digest = sha(Files.readAllBytes((Path) args[0])); attemptHashes.add(digest);
                log("WORKSPACE_ATTEMPT", "sha=" + digest + "; fault=" + fault.get());
                if (fault.get()) throw new java.io.IOException("synthetic owned workspace atomic publication failure");
            }
            method.setAccessible(true);
            try { Object result = method.invoke(original, args); if (target.getFileName().toString().endsWith(".draft")) { draftMoves.incrementAndGet(); log("DRAFT_ATOMIC_SUCCESS", "count=" + draftMoves.get()); } return result; }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }));
    }
    void installTrackingDispatcher() throws Exception {
        Object entry = get(shell, "sqlFileEntry"), original = get(entry, "tasks");
        Class<?> type = original.getClass().getInterfaces()[0];
        dispatcher = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("close")) dispatcherCloses.incrementAndGet();
            if (method.getName().equals("submit")) {
                CompletableFuture<Void> read = nextRead; nextRead = null;
                if (read != null) {
                    Consumer<Object> success = (Consumer<Object>) args[1]; Consumer<Throwable> error = (Consumer<Throwable>) args[2];
                    args[1] = (Consumer<Object>) value -> { try { success.accept(value); read.complete(null); } catch (Throwable failure) { read.completeExceptionally(failure); throw failure; } };
                    args[2] = (Consumer<Throwable>) failure -> { read.completeExceptionally(failure); error.accept(failure); };
                }
            }
            method.setAccessible(true);
            try { return method.invoke(original, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        set(entry, "tasks", dispatcher);
    }
    Tab open(Path path) throws Exception {
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        fx(() -> { nextRead = loaded; invoke(shell, "openSqlFile", new Class<?>[]{Path.class}, path); return null; }); loaded.get(5, TimeUnit.SECONDS);
        return fx(() -> { require((Boolean) invoke(registry, "select", new Class<?>[]{Path.class}, path), "file registry admitted/reused"); return tabs.getSelectionModel().getSelectedItem(); });
    }
    SqlEditorPane pane(Tab tab) { return tab.getContent().getProperties().values().stream().filter(SqlEditorPane.class::isInstance).map(SqlEditorPane.class::cast).findFirst().orElseThrow(); }
    CodeArea editor(Tab tab) throws Exception { return (CodeArea) get(pane(tab), "editorArea"); }
    void checkpoint(Tab tab) throws Exception {
        Object binding = fx(() -> get(pane(tab), "draftBinding"));
        fx(() -> ((SqlDraftCoordinator.Handle) get(binding, "handle")).flush()).get(5, TimeUnit.SECONDS);
        fx(() -> { require((Boolean) invoke(binding, "checkpointed"), "actual checkpoint acknowledgement"); return null; });
    }
    SqlWorkspace capture() throws Exception { return (SqlWorkspace) invoke(invoke(drafts, "workspace"), "capture"); }
    void requestShutdown() {
        if (!ready || closing || terminal) { log("CLOSE_REQUEST_NOT_ADMITTED", "initializing/pending/terminal"); return; }
        try {
            if (!quarantine.begin()) return;
            beforeCloseSha = sha(Files.readAllBytes(workspacePath)); closingLayout = capture();
            lastDecision = "none"; closeAttemptStart = attemptHashes.size();
            closing = true; host.setDisable(true); shell.getRoot().setDisable(true);
            log("EXTERNAL_CLOSE_REQUEST", "real shell; currentLayout=" + closingLayout + "; beforeSHA=" + beforeCloseSha);
            shell.shutdownAsync().whenComplete((outcome, failure) -> Platform.runLater(() -> {
                ShutdownQuarantine.Action action = quarantine.settle(outcome, failure);
                log("QUARANTINE_SETTLED", action + ";outcome=" + outcome);
                if (action == ShutdownQuarantine.Action.FATAL) {
                    try { summary("FAILED_PARTIAL", false, false); } catch (Throwable ignored) { }
                    log("FATAL_ISOLATED", "root remains disabled; no retry/automatic cleanup/product success"); return;
                }
                if (action == ShutdownQuarantine.Action.RECOVER) shell.getRoot().setDisable(false);
                if (failure != null) { fail(failure); return; }
                try {
                    require(providers.get() == 0 && draftMoves.get() >= 2, "database isolation and real drafts");
                    SqlWorkspaceActivity.Frozen actualFrozen = (SqlWorkspaceActivity.Frozen) get(invoke(drafts, "workspace"), "frozen");
                    if (outcome == ShutdownOutcome.CANCELLED) {
                        require(dispatcherCloses.get() == 0 && runtime.mode() == SqlDraftCoordinator.Mode.ENABLED, "cancel retains global/file resources");
                        require(tabs.getTabs().isEmpty(), "original mandatory guards finalized tabs before decision");
                        require(sha(Files.readAllBytes(workspacePath)).equals(beforeCloseSha), "cancel preserves prior workspace bytes");
                        ((FxTaskRunner) get(shell, "tasks")).submit(() -> log("CANCEL_TASK_PULSE", "actual global runner accepted"));
                        require(!(Boolean) get(get(shell, "sqlFileEntry"), "suspended"), "actual file admission resumed");
                        cancellations++; closing = false; host.setDisable(false); refreshStatus();
                        Object frozen = get(invoke(drafts, "workspace"), "frozen");
                        if (cancelledFrozen != null && cancelledActivityCount == explicitActivities)
                            require(cancelledFrozen.equals(actualFrozen.workspace()), "repeat cancel without activity retains frozen layout");
                        cancelledFrozen = actualFrozen.workspace(); cancelledActivityCount = explicitActivities;
                        log("CANCELLED", "tabs=removed; fileAdmission=resumed; registry=retained; frozen=" + frozen + "; workspaceSHA=" + beforeCloseSha + "; cancellations=" + cancellations);
                        summary("CANCELLED", true, false); return;
                    }
                    require(outcome == ShutdownOutcome.COMPLETED, "nonrecoverable result must not be success");
                    require(dispatcherCloses.get() == 1 && runtime.mode() == SqlDraftCoordinator.Mode.CLOSED && tabs.getTabs().isEmpty(), "actual completed resource boundaries");
                    try { ((FxTaskRunner) get(shell, "tasks")).submit(() -> {}); throw new AssertionError("global runner still admitted"); } catch (RejectedExecutionException expected) { }
                    try { invoke(registry, "createOwner", new Class<?>[]{Runnable.class}, (Runnable) () -> {}); throw new AssertionError("registry still open"); } catch (InvocationTargetException expected) { require(expected.getCause() instanceof IllegalStateException, "registry closed exception"); }
                    Thread.startVirtualThread(() -> {
                        try {
                            try (SqlDraftStore store = SqlDraftStore.open(workspacePath.getParent())) {
                                SqlWorkspace stored = store.workspaceSnapshot().workspace();
                                if (lastDecision.equals("忽略本次工作区更新并退出")) {
                                    require(fault.get(), "ignore leaves fault enabled");
                                    require(sha(Files.readAllBytes(workspacePath)).equals(beforeCloseSha), "ignore preserves prior bytes");
                                } else {
                                    require(stored != null && stored.entries().equals(actualFrozen.workspace().entries())
                                        && Objects.equals(stored.selectedDraftId(), actualFrozen.workspace().selectedDraftId()), "stored layout matches real frozen entries/selection/positions");
                                    Set<UUID> ids = new HashSet<>(); store.snapshot().drafts().forEach(d -> ids.add(d.id()));
                                    require(ids.containsAll(stored.entries().stream().map(SqlWorkspace.Entry::draftId).toList()), "persisted layout ids have real drafts");
                                    if (lastDecision.equals("重试")) {
                                        int count = attemptHashes.size();
                                        require(count - closeAttemptStart >= 2 && attemptHashes.get(count - 1).equals(attemptHashes.get(count - 2)), "retry republishes identical frozen bytes");
                                    }
                                }
                                log("STORE_LOCK_REOPENED", "actual store and layout assertions succeeded; persistedLayout=" + stored + "; decision=" + lastDecision);
                            }
                            log("COMPLETED", "runtime=CLOSED registry=CLOSED fileDispatcherClose=1 providerRequests=0 afterFileSHA=" + sha(Files.readAllBytes(afterFile)));
                            summary("COMPLETED", true, true);
                            fx(() -> { terminal = true; stage.setOnCloseRequest(null); stage.close(); Platform.exit(); return null; });
                        } catch (Throwable error) { fail(error); }
                    });
                } catch (Throwable error) { fail(error); }
            }));
        } catch (Throwable failure) { fail(failure); }
    }
    void observeDialog(Stage dialog) {
        if (!dialog.isShowing() || !"工作区记录未保存".equals(dialog.getTitle())) return;
        try {
            DialogPane pane = (DialogPane) dialog.getScene().getRoot();
            require(dialog.getOwner() == stage, "production alert owner");
            require(pane.getButtonTypes().stream().map(ButtonType::getText).toList().equals(List.of("重试", "取消退出", "忽略本次工作区更新并退出")), "production buttons");
            for (ButtonType type : pane.getButtonTypes()) {
                Button button = (Button) pane.lookupButton(type); require(button.isDefaultButton() == type.getText().equals("取消退出"), "default cancel");
                button.addEventHandler(javafx.event.ActionEvent.ACTION, event -> { lastDecision = type.getText(); log("PRODUCTION_BUTTON_ACTION", lastDecision + "; source origin requires root tool evidence"); });
            }
            observeScene(dialog.getScene(), "DIALOG");
            log("PRODUCTION_ALERT", "owner=ownShell title=" + dialog.getTitle() + "; default=cancel; message=" + pane.getContentText() + "; buttons=retry,cancel,ignore");
            log("GEOMETRY", "dialog=" + dialog.getWidth() + "x" + dialog.getHeight() + ";scale=" + dialog.getOutputScaleX());
        } catch (Throwable failure) { fail(failure); }
    }
    void observeScene(Scene scene, String owner) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> log(owner + "_KEY", event.getCode() + ";ctrl=" + event.isControlDown() + ";shift=" + event.isShiftDown()));
        scene.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> log(owner + "_CLICK", "button=" + event.getButton() + ";target=" + event.getTarget().getClass().getSimpleName()));
        scene.focusOwnerProperty().addListener((o,a,b) -> log(owner + "_FOCUS", b == null ? "none" : b.getClass().getSimpleName() + ":" + b.getId()));
    }
    void geometry(String owner) { if (stage != null) log("GEOMETRY", owner + ";shell=" + stage.getWidth() + "x" + stage.getHeight() + ";scale=" + stage.getOutputScaleX()); }
    void refreshStatus() { status.setText("READY | 工作区故障=" + fault.get() + " | 取消次数=" + cancellations + " | 夹具文件活动=" + explicitActivities + " | 标题栏关闭触发实际 shell 退出"); }
    synchronized void log(String event, String details) {
        String line = "{\"utc\":" + quote(Instant.now().toString()) + ",\"event\":" + quote(event) + ",\"details\":" + quote(details) + "}";
        System.out.println(line); if (events != null) events.println(line);
    }
    synchronized void summary(String outcome, boolean passed, boolean lockReopened) throws Exception {
        String current = Files.exists(workspacePath) ? sha(Files.readAllBytes(workspacePath)) : "absent";
        String hashes = "[" + String.join(",", attemptHashes.stream().map(WorkspaceNativeExitProbe::quote).toList()) + "]";
        String json = "{\"outcome\":" + quote(outcome) + ",\"passed\":" + passed + ",\"evidenceLevel\":\"external fixture; native source requires root evidence\",\"oldWorkspaceSHA\":" + quote(oldSha) + ",\"currentWorkspaceSHA\":" + quote(current) + ",\"workspaceAttemptSHAs\":" + hashes + ",\"draftAtomicMoves\":" + draftMoves.get() + ",\"providerRequests\":" + providers.get() + ",\"dispatcherCloses\":" + dispatcherCloses.get() + ",\"cancellations\":" + cancellations + ",\"lastDecision\":" + quote(lastDecision) + ",\"storeLockReopened\":" + lockReopened + "}";
        Files.writeString(profile.resolve("summary.json"), json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(profile.resolve("summary-" + summaries.incrementAndGet() + "-" + outcome + ".json"), json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    final AtomicBoolean failing = new AtomicBoolean();
    void fail(Throwable failure) {
        if (!failing.compareAndSet(false, true)) return;
        log("FAIL", failure.getClass().getName() + "; product success not asserted");
        try { summary("FAIL", false, false); } catch (Throwable ignored) { }
        Thread.startVirtualThread(() -> {
            try {
                CompletableFuture<Void> released = new CompletableFuture<>();
                Thread.startVirtualThread(() -> {
                    try { Method cleanup = AppShell.class.getDeclaredMethod("shutdownRemaining"); cleanup.setAccessible(true); if (shell != null) cleanup.invoke(shell); released.complete(null); }
                    catch (Throwable cleanup) { released.completeExceptionally(cleanup); }
                });
                released.get(10, TimeUnit.SECONDS); log("FIXTURE_SAFETY_CLEANUP", "bounded10s; not product shutdown evidence");
            }
            catch (Throwable cleanup) { log("FIXTURE_CLEANUP_FAILURE", cleanup.getClass().getName()); }
            Platform.runLater(() -> { if (stage != null) { stage.setOnCloseRequest(null); stage.close(); } Platform.exit(); });
        });
    }
    interface Checked { void run() throws Exception; }
    void fixture(Checked action) { try { action.run(); } catch (Throwable failure) { fail(failure); } }
    static <T> T fx(Callable<T> action) throws Exception { if (Platform.isFxApplicationThread()) return action.call(); FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(5, TimeUnit.SECONDS); }
    static Object get(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    static void set(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    static Object invoke(Object target, String name) throws Exception { return invoke(target, name, new Class<?>[0]); }
    static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String quote(String text) { if (text == null) return "null"; return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""; }
}
