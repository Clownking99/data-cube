package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.AppSettings.CommentMode;
import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.SqlHistoryStore;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.export.QueryResultFileWriter.Format;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.service.ConnectionManager;
import com.datacube.service.JdbcEditorSession;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlFormatter;
import com.datacube.sqleditor.SqlSafetyAnalyzer;
import com.datacube.sqleditor.SqlSafetyPolicy;
import com.datacube.sqleditor.SqlScriptSplitter;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.RenderedFilterQuery;
import com.datacube.sqleditor.result.ResultFilterSqlRenderer;
import com.datacube.sqleditor.result.ResultFilterState;
import com.datacube.sqleditor.result.ResultExportSnapshot;
import com.datacube.sqleditor.result.ResultValueFormatter;
import com.datacube.sqleditor.result.SafeSelectEligibility;
import com.datacube.sqleditor.result.TsvClipboardFormatter;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.model.ColumnInfo;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.spi.model.SchemaInfo;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.TableRef;
import com.datacube.spi.model.ViewInfo;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.TwoDimensional;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 编辑器面板：绑定 {@link SessionContext} 活动连接，经 {@link SqlRunner} 执行。
 *
 * <p>升级自原 {@code SqlEditorController}：连接不再手工注入，而是取自活动连接；
 * 执行委托 provider 的 {@link SqlRunner}，方言差异（schema 切换）由 provider 处理。
 */
public final class SqlEditorPane implements AutoCloseable {
    @FunctionalInterface
    interface ClipboardWriter {
        boolean write(String text);
    }


    /** 常见 SQL 关键字（大写形式，补全时展示）。 */
    private static final List<String> SQL_KEYWORDS = Arrays.asList(
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP",
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "OUTER", "CROSS", "ON", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS",
            "BETWEEN", "LIKE", "ILIKE", "DISTINCT", "UNION", "ALL", "CASE", "WHEN", "THEN",
            "ELSE", "END", "ASC", "DESC", "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE",
            "CAST", "WITH", "RETURNING", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT",
            "CONSTRAINT", "UNIQUE", "CHECK", "TRUE", "FALSE", "BEGIN", "COMMIT", "ROLLBACK");
     
     /** 关键字集合（大写），用于别名解析时排除关键字被误判为别名。 */
     private static final Set<String> KEYWORDS_UPPER = new java.util.HashSet<>(SQL_KEYWORDS);
     
     /** 匹配 FROM 子句区域（至下一个子句边界或语句结束），忽略大小写与换行。 */
     private static final Pattern FROM_REGION = Pattern.compile(
             "(?is)\\bfrom\\b(.*?)(?:\\bwhere\\b|\\bgroup\\b|\\border\\b|\\bhaving\\b|\\blimit\\b|\\bunion\\b|;|$)");
    private static final Pattern SAFE_DATABASE_FILTER_FAILURE = Pattern.compile(
            "数据库查询(?:失败|超时|已取消)(?: \\((?:SQLState=[A-Za-z0-9]{5}"
                    + "(?:, vendorCode=-?\\d+)?|vendorCode=-?\\d+)\\))?");
    private static final String UNCONFIRMED_DATABASE_FILTER_CAPABILITY =
            "当前数据库筛选能力无法安全确认；本地筛选仍可使用";

    private final SessionContext session;
    private final ConnectionManager connections;
    private final ObjectTreeService treeSvc;
    private final AppSettings settings;
    /** Ctrl+点击表名时打开表设计器（connId, 表引用）；由 AppShell 注入。 */
    private final java.util.function.BiConsumer<String, TableRef> openDesigner;
    /** Once selected, an editor connection is pinned for the lifetime of this tab. */
    private volatile ConnConfig editorConnection;
    private final SqlEditorConnectionAdmission admission;
    /** One caller-owned JDBC session; its physical connection remains lazy. */
    private volatile JdbcEditorSession jdbcSession;

    /** 近期 SQL 历史存储（可空）：执行/执行计划时记录，关闭标签时快照，供“找回”。 */
    private final SqlHistoryStore history;

    /** 可配置快捷键：执行/补全/注释等在按键事件里用 {@code match} 实时判定，改绑即时生效。 */
    private final ShortcutSettings shortcuts;
    private final FxTaskScope tasks;
    private final FxSerialTaskQueue metadataTasks;
    private final SerialSessionOperationQueue sessionOperations;
    private final StrictCleanupRetryChannel sessionCleanup;
    private final ChangeListener<CommentMode> commentModeListener;
    private final ChangeListener<ConnConfig> activeConnectionListener;

    /** 预热的元数据名称（表/视图/schema），线程安全。 */
    private final Set<String> metaNames = ConcurrentHashMap.newKeySet();
    /** 已预热的 connId（每连接只预热一次）。 */
    private final Set<String> prewarmed = ConcurrentHashMap.newKeySet();
    /** 列名缓存：key = folded(schema).folded(table)，value = 列名列表。 */
    private final Map<String, List<String>> columnCache = new ConcurrentHashMap<>();
    /** 正在后台加载列的 key，避免并发重复拉取。 */
    private final Set<String> columnLoading = ConcurrentHashMap.newKeySet();
    private final VBox root = new VBox(8);
    private CodeArea editorArea;
    private SqlAutoComplete autoComplete;
    private final ResultFilterState resultFilterState = new ResultFilterState();
    private final Map<ObservableList<Object>, Integer> resultRowIndexes = new IdentityHashMap<>();
    private ClipboardWriter clipboardWriter = SqlEditorPane::writeSystemClipboard;
    private SqlResultExportCoordinator resultExports;
    private long resultStatusRevision;
    private SqlResultToolbar resultToolbar;
    private SqlResultColumnMenu resultColumnMenu;
    private TableView<ObservableList<Object>> resultTable;
    private QueryResult displayedResult;
    private TextArea planArea;
    private TitledPane resultPane;
    private Label statusLabel;
    private TextField schemaField;
    private Button executeBtn, explainBtn, formatBtn, clearBtn;
    private Button saveSqlFileBtn, saveAsSqlFileBtn;
    private Button recoveryConnectionButton;
    private MenuButton exportResultBtn;
    private Button copyInsertBtn;
    private CheckBox analyzeCheck;
    private ComboBox<JdbcEditorSession.TransactionMode> transactionModeBox;
    private Button commitBtn;
    private Button rollbackBtn;
    private Button cancelBtn;
    private Label environmentBadge;
    private Label readOnlyBadge;
    private Label connectionBadge;
    private Label connectionGuidance;
    private Label transactionStatus;
    private boolean updatingTransactionMode;

    private volatile boolean running = false;
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final AtomicBoolean uiFinalized = new AtomicBoolean();
    private final AsyncTabCloseGuard closeGuard;
    private final AsyncTabCloseGuard mandatoryCloseGuard;
    private SqlScriptFileController fileController;
    /** 最近一次单条查询的原 SQL（用于安全重查与「复制 INSERT」解析目标表）。 */
    private String lastQuerySql;
    private SqlDraftEditorBinding draftBinding;
    private SqlDraftRecoveryIntent recoveryIntent;
    private String recoveredUneditedSql;

    SqlDraftEditorBinding bindDraft(SqlDraftCoordinator runtime, java.util.UUID id, Long savedAt,
            java.util.function.Consumer<SqlDraftEditorBinding> detached) {
        if (draftBinding != null) throw new IllegalStateException("Draft already bound");
        draftBinding = new SqlDraftEditorBinding(runtime, id, savedAt, editorArea, schemaField,
                new SqlDraftCoordinator.Source() {
                    public boolean hasText() { return editorArea.getLength() != 0; }
                    public SqlDraft capture(java.util.UUID draftId, long at) {
                        SqlDraftRecoveryIntent identity = recoveryPassive()
                                ? recoveryIntent : SqlDraftRecoveryIntent.from(currentConn());
                        return new SqlDraft(draftId, at, identity.connectionId(), identity.connectionType(),
                                identity.connectionName(), schemaField.getText(),
                                recoveredUneditedSql == null ? editorArea.getText() : recoveredUneditedSql);
                    }
                }, detached);
        try { root.getChildren().add(draftBinding.getNode()); }
        catch (RuntimeException failure) { draftBinding.close(); throw failure; }
        return draftBinding;
    }
    private boolean draftEditingBlocked() {
        return draftBinding != null && draftBinding.closing();
    }

    private void draftEdited() {
        if (draftBinding != null) draftBinding.edited();
    }

    public SqlEditorPane(SessionContext session, ConnectionManager connections, ObjectTreeService treeSvc,
                         AppSettings settings, java.util.function.BiConsumer<String, TableRef> openDesigner,
                         ConnConfig boundConn, String initialSchema, SqlHistoryStore history,
                         ShortcutSettings shortcuts, FxTaskRunner runner) {
        this(session, connections, treeSvc, settings, openDesigner, boundConn, initialSchema,
                history, shortcuts, runner, null);
    }

    static SqlEditorPane recoverDraft(SessionContext session, ConnectionManager connections,
            ObjectTreeService treeSvc, AppSettings settings,
            java.util.function.BiConsumer<String, TableRef> openDesigner, SqlDraft draft,
            SqlHistoryStore history, ShortcutSettings shortcuts, FxTaskRunner runner) {
        java.util.Objects.requireNonNull(draft, "draft");
        return new SqlEditorPane(session, connections, treeSvc, settings, openDesigner, null,
                draft.schema(), history, shortcuts, runner, draft);
    }

    private SqlEditorPane(SessionContext session, ConnectionManager connections, ObjectTreeService treeSvc,
                         AppSettings settings, java.util.function.BiConsumer<String, TableRef> openDesigner,
                         ConnConfig boundConn, String initialSchema, SqlHistoryStore history,
                         ShortcutSettings shortcuts, FxTaskRunner runner, SqlDraft recoveredDraft) {
        this.session = session;
        this.connections = connections;
        this.treeSvc = treeSvc;
        this.settings = settings;
        this.openDesigner = openDesigner;
        this.admission = new SqlEditorConnectionAdmission(boundConn);
        this.editorConnection = boundConn;
        this.history = history;
        this.shortcuts = shortcuts;
        this.recoveryIntent = recoveredDraft == null ? null : new SqlDraftRecoveryIntent(
                recoveredDraft.connectionId(), recoveredDraft.connectionType(), recoveredDraft.connectionName());
        ConstructionOwner construction = new ConstructionOwner();
        try {
            this.tasks = runner.scope();
            construction.own(tasks::close);
            this.metadataTasks = new FxSerialTaskQueue(runner);
            construction.own(metadataTasks::close);
            this.sessionOperations = new SerialSessionOperationQueue(runner);
            construction.own(sessionOperations::close);
            this.sessionCleanup = new StrictCleanupRetryChannel(
                    this::closeCurrentSessionStrict, SqlEditorPane::reportStrictCleanupFailure);
            if (editorConnection != null) {
                JdbcEditorSession jdbcSession = connections.openEditorSession(editorConnection);
                construction.ownBlocking(this::awaitStrictSessionCleanup);
                this.jdbcSession = jdbcSession;
            }
            this.closeGuard = AsyncTabCloseGuards.retryable(this::startCloseAttempt);
            this.mandatoryCloseGuard = AsyncTabCloseGuards.retryable(this::startMandatoryCloseAttempt);
            this.commentModeListener = (obs, oldMode, newMode) -> {
                ResultFilterState.Snapshot snapshot = resultFilterState.snapshot();
                refreshResultColumnHeaders(snapshot.activeResult());
            };
            this.activeConnectionListener = (obs, oldConnection, connection) -> {
                if (recoveryIntent != null) return;
                if (admission.pinned() == null) {
                    if (connection != null && connection.type() != DbType.REDIS) prewarm(connection);
                    renderDisconnectedCandidate(connection);
                    draftEdited();
                }
            };
            construction.own(() -> settings.commentModeProperty().removeListener(commentModeListener));
            construction.own(() -> session.activeConnectionProperty().removeListener(activeConnectionListener));
            build();
            resultExports = new SqlResultExportCoordinator(tasks, this::captureResultExportSnapshot,
                    () -> resultStatusRevision, (text, error) -> {
                        statusLabel.setText(text);
                        statusLabel.setStyle(error
                                ? "-fx-text-fill: -status-error; -fx-font-size: 12px;"
                                : "-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
                    }, this::writeClipboard,
                    () -> root.getScene() == null ? null : root.getScene().getWindow());
            construction.own(resultExports::close);
            if (recoveredDraft != null) {
                schemaField.setText(initialSchema == null ? "" : initialSchema);
                setSqlText(recoveredDraft.sql());
                if (!recoveredDraft.sql().equals(editorArea.getText())) {
                    recoveredUneditedSql = recoveredDraft.sql();
                }
            } else if (initialSchema != null && !initialSchema.isBlank()) {
                schemaField.setText(initialSchema.trim());
            }
            settings.commentModeProperty().addListener(commentModeListener);
            renderInitialSessionState();
            construction.commit();
        } catch (Throwable failure) {
            throw construction.close(failure).failure();
        }
    }

    /** 载入指定 SQL 文本到编辑区（用于历史“找回”）。 */
    public void setSqlText(String sql) {
        if (draftEditingBlocked() || sql == null || editorArea == null) return;
        editorArea.replaceText(sql);
        applyHighlighting(editorArea.getText());
    }

    /** Installs the one per-editor SQL file lifecycle after its managed tab has a title owner. */
    public void installSqlScriptFileController(SqlScriptFileStore.Loaded initial,
            SqlScriptFileStore store, RecentSqlFiles recentFiles,
            Consumer<String> titleConsumer, String fallbackTitle) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "SqlEditorPane.installSqlScriptFileController must run on the FX Application Thread");
        }
        if (fileController != null) throw new IllegalStateException("SQL file controller already installed");
        SqlScriptFileController controller = new SqlScriptFileController(
                editorArea, store, recentFiles, tasks,
                () -> root.getScene() == null ? null : root.getScene().getWindow(),
                titleConsumer, fallbackTitle, this::chooseSqlSavePath, this::confirmSqlOverwrite,
                this::requestSqlFileCloseDecision, this::showAlert);
        controller.install(initial);
        fileController = controller;
        saveSqlFileBtn.disableProperty().bind(fileController.busyProperty());
        saveAsSqlFileBtn.disableProperty().bind(fileController.busyProperty());
    }

    /** Captures history data on FX; persistence itself always runs on a virtual thread. */
    private HistorySnapshot captureHistory(String sql, ConnConfig connection, String schema) {
        return new HistorySnapshot(connection == null ? null : connection.name(), schema, sql);
    }

    private void recordHistory(HistorySnapshot snapshot) {
        if (history == null || snapshot == null || snapshot.sql() == null) return;
        history.record(snapshot.connectionName(), snapshot.schema(), snapshot.sql());
    }

    /** 将当前编辑区内容快照到历史（供关闭标签时调用，留存未执行的草稿）。 */
    public void snapshotToHistory() {
        if (history == null || editorArea == null) return;
        HistorySnapshot snapshot = captureHistory(
                editorArea.getText(), currentConn(), schemaField.getText().trim());
        tasks.submit(() -> {
            recordHistory(snapshot);
            return null;
        }, ignored -> {}, failure -> failure.printStackTrace(System.err));
    }

    /** Uses the pinned editor connection, otherwise the current relational candidate. */
    private ConnConfig currentConn() {
        ConnConfig pinned = admission.pinned();
        if (pinned != null) return pinned;
        if (recoveryIntent != null) return recoveryIntent.resolve(connections::config);
        ConnConfig candidate = session.getActiveConnection();
        return candidate == null || candidate.type() == DbType.REDIS ? null : candidate;
    }

    private boolean recoveryPassive() {
        return recoveryIntent != null && admission.pinned() == null;
    }

    boolean chooseRecoveryConnection(ConnConfig choice) {
        if (!recoveryPassive() || draftEditingBlocked() || !sessionOperations.snapshot().accepting()
                || choice == null || choice.id() == null || choice.id().isBlank()
                || (choice.type() != DbType.POSTGRESQL && choice.type() != DbType.ORACLE)) return false;
        recoveryIntent = SqlDraftRecoveryIntent.from(choice);
        renderDisconnectedCandidate(currentConn());
        draftEdited();
        return true;
    }

    void installRecoveryConnectionChooser(java.util.function.Supplier<List<ConnConfig>> configs) {
        if (recoveryIntent == null || recoveryConnectionButton != null) return;
        recoveryConnectionButton = new Button("重新选择草稿连接");
        recoveryConnectionButton.setId("sql-draft-connection");
        recoveryConnectionButton.setOnAction(event -> {
            if (!recoveryPassive() || draftEditingBlocked() || !sessionOperations.snapshot().accepting()) return;
            SqlDraftConnectionChooser.show(configs.get(),
                    root.getScene() == null ? null : root.getScene().getWindow()).ifPresent(choice -> {
                ConnConfig current = connections.config(choice.id());
                if (current == null || current.type() != choice.type() || !chooseRecoveryConnection(current))
                    showAlert("所选连接已不可用，请重新选择。草稿内容未改变。");
            });
        });
        root.getChildren().add(1, recoveryConnectionButton);
        renderConnectionGuidance();
    }

    /** FX admission point: pin before safety/schema/oracle decisions or worker submission. */
    private ConnConfig admitCurrentConnection() {
        ConnConfig candidate = currentConn();
        if (recoveryPassive() && candidate == null) {
            throw new IllegalStateException("草稿连接不可用，请重新选择连接");
        }
        ConnConfig pinned = admission.admit(candidate);
        editorConnection = pinned;
        if (jdbcSession == null) connectionBadge.setText("🔗 " + pinned.name() + " · 未连接");
        renderConnectionGuidance();
        draftEdited();
        if (recoveryIntent != null) prewarm(pinned);
        return pinned;
    }

    public Node getNode() {
        return root;
    }

    @Override
    @Deprecated(forRemoval = false)
    public void close() {
        Runnable beginClose = () -> requestClose().whenComplete((outcome, failure) -> {
            if (failure != null || outcome != CloseGuardOutcome.APPROVED) {
                if (failure != null) failure.printStackTrace(System.err);
                return;
            }
            if (Platform.isFxApplicationThread()) finalizeCloseOnFx();
            else Platform.runLater(this::finalizeCloseOnFx);
        });
        if (Platform.isFxApplicationThread()) beginClose.run();
        else Platform.runLater(beginClose);
    }

    /**
     * Captures JavaFX state immediately, then persists history and closes task/JDBC resources on
     * one virtual thread. The returned stage reaches {@link CloseGuardOutcome#APPROVED} only after
     * that blocking phase is safe. Repeated calls share only in-flight, approved, or fatal attempts.
     */
    public CompletionStage<CloseGuardOutcome> requestClose() {
        if (!Platform.isFxApplicationThread()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("SqlEditorPane.requestClose must start on the FX Application Thread"));
        }
        if (fileController != null) {
            return fileController.guardClose(closeGuard::requestClose);
        }
        return closeGuard.requestClose();
    }

    /** Starts the non-interactive application-exit guard; pending work is rolled back, never committed. */
    public CompletionStage<CloseGuardOutcome> requestMandatoryClose() {
        if (!Platform.isFxApplicationThread()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "SqlEditorPane.requestMandatoryClose must start on the FX Application Thread"));
        }
        return mandatoryCloseGuard.requestClose();
    }

    /** Thread-safe resource phase; callers run this from a virtual-thread close guard. */
    void closeResources() {
        if (fileController != null) fileController.close();
        detachDraftFromAnyThread();
        if (resourcesClosed.get()) return;
        if (resultExports != null) resultExports.close();
        admission.beginClosing();
        sessionOperations.stopAcceptingAndCancelQueued();
        sessionOperations.suppressCallbacks();
        BestEffortCloseSequence.run(
                this::cancelCancellableCurrentSession,
                this::awaitSessionOperationsIdle,
                metadataTasks::close,
                sessionOperations::close,
                tasks::close,
                this::awaitStrictSessionCleanup);
        resourcesClosed.set(true);
    }

    /** Lightweight JavaFX phase; callers invoke this only on the FX Application Thread. */
    void finalizeCloseOnFx() {
        if (!uiFinalized.compareAndSet(false, true)) return;
        if (fileController != null) fileController.detachUi();
        if (draftBinding != null) draftBinding.close();
        resultRowIndexes.clear();
        resultFilterState.clearAll();
        renderResultFilterToolbar();
        if (resultToolbar != null) resultToolbar.getNode().setDisable(true);
        settings.commentModeProperty().removeListener(commentModeListener);
        session.activeConnectionProperty().removeListener(activeConnectionListener);
        if (autoComplete != null) autoComplete.hide();
    }

    private ClosePlan captureClosePlan(SerialSessionOperationQueue.Snapshot operationSnapshot) {
        ConnConfig connection = currentConn();
        JdbcEditorSession editorSession = currentEditorSession();
        JdbcEditorSession.Snapshot sessionSnapshot =
                editorSession == null ? null : editorSession.snapshot();
        boolean pendingTransaction = sessionSnapshot != null
                && sessionSnapshot.hasPendingTransaction();
        CloseDecision decision = switch (SqlEditorClosePolicy.decide(
                operationSnapshot, pendingTransaction)) {
            case CANCEL_RUNNING_SQL -> requestCancelRollbackClose();
            case RESOLVE_TRANSACTION -> requestTransactionClose();
            case CLOSE -> CloseDecision.CLOSE;
            case WAIT_FOR_NON_CANCELLABLE ->
                    throw new IllegalStateException("不可取消的会话操作尚未结束");
        };
        return new ClosePlan(
                connection == null ? null : connection.name(),
                schemaField == null ? null : schemaField.getText().trim(),
                editorArea == null ? null : editorArea.getText(),
                decision);
    }

    private CompletionStage<CloseGuardOutcome> startCloseAttempt() {
        CompletableFuture<CloseGuardOutcome> result = new CompletableFuture<>();
        if (draftBinding != null) draftBinding.freeze();
        if (autoComplete != null) autoComplete.hide();
        admission.beginClosing();
        SerialSessionOperationQueue.Snapshot operationSnapshot = sessionOperations.snapshot();
        CompletionStage<Void> idle = sessionOperations.stopAcceptingAndCancelQueued();
        if (operationSnapshot.running() && !operationSnapshot.currentCancellable()) {
            idle.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    reopenAdmissionWithoutUi();
                    result.completeExceptionally(failure);
                    return;
                }
                try {
                    Platform.runLater(() -> continueCloseDecisionOnFx(result));
                } catch (Throwable dispatchFailure) {
                    reopenAdmissionWithoutUi();
                    result.completeExceptionally(dispatchFailure);
                }
            });
        } else {
            continueCloseDecisionOnFx(result);
        }
        return result;
    }

    private CompletionStage<CloseGuardOutcome> startMandatoryCloseAttempt() {
        CompletableFuture<CloseGuardOutcome> result = new CompletableFuture<>();
        if (draftBinding != null) draftBinding.freeze();
        if (autoComplete != null) autoComplete.hide();
        ClosePlan plan;
        try {
            ConnConfig connection = currentConn();
            plan = new ClosePlan(
                    connection == null ? null : connection.name(),
                    schemaField == null ? null : schemaField.getText().trim(),
                    editorArea == null ? null : editorArea.getText(),
                    CloseDecision.CANCEL_ROLLBACK);
            admission.beginClosing();
            sessionOperations.stopAcceptingAndCancelQueued();
            continueAfterDraftFlush(true, result, () -> {
                sessionOperations.suppressCallbacks();
                Thread.startVirtualThread(() -> result.complete(closeMandatoryInBackground(plan)));
            });
        } catch (Throwable failure) {
            reportMandatoryCloseFailure(failure);
            result.complete(CloseGuardOutcome.FAILED_PARTIAL);
        }
        return result;
    }

    private void continueCloseDecisionOnFx(CompletableFuture<CloseGuardOutcome> result) {
        if (result.isDone()) return;
        continueAfterDraftFlush(false, result, () -> continueTransactionCloseDecisionOnFx(result));
    }

    private void detachDraftFromAnyThread() {
        if (draftBinding == null) return;
        if (Platform.isFxApplicationThread()) { draftBinding.close(); return; }
        CompletableFuture<Void> detached = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { draftBinding.close(); detached.complete(null); }
            catch (Throwable failure) { detached.completeExceptionally(failure); }
        });
        detached.join();
    }

    private void continueTransactionCloseDecisionOnFx(CompletableFuture<CloseGuardOutcome> result) {
        ClosePlan plan;
        try {
            if (!Platform.isFxApplicationThread()) {
                throw new IllegalStateException("close decision must run on the FX Application Thread");
            }
            plan = captureClosePlan(sessionOperations.snapshot());
        } catch (Throwable preCleanupFailure) {
            reopenAfterRejectedClose();
            result.completeExceptionally(preCleanupFailure);
            return;
        }
        if (plan.decision() == CloseDecision.CANCEL_CLOSE) {
            reopenAfterRejectedClose();
            result.complete(CloseGuardOutcome.REJECTED);
            return;
        }
        sessionOperations.suppressCallbacks();
        try {
            Thread.startVirtualThread(() -> {
                try {
                    closeInBackground(plan);
                    result.complete(CloseGuardOutcome.APPROVED);
                } catch (RetryableTransactionCloseFailure gateFailure) {
                    finishRetryableCloseFailure(result, gateFailure.getCause());
                } catch (Throwable partialFailure) {
                    partialFailure.printStackTrace(System.err);
                    result.complete(CloseGuardOutcome.FAILED_PARTIAL);
                }
            });
        } catch (Throwable startupFailure) {
            reopenAfterRejectedClose();
            result.completeExceptionally(startupFailure);
        }
    }

    private void continueAfterDraftFlush(boolean mandatory, CompletableFuture<CloseGuardOutcome> result,
            Runnable continuation) {
        if (draftBinding == null) { continuation.run(); return; }
        draftBinding.prepareClose(mandatory).whenComplete((allowed, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(allowed)) {
                reopenAfterRejectedClose();
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(CloseGuardOutcome.REJECTED);
                return;
            }
            try { continuation.run(); }
            catch (Throwable startupFailure) { reopenAfterRejectedClose(); result.completeExceptionally(startupFailure); }
        });
    }

    private void finishRetryableCloseFailure(
            CompletableFuture<CloseGuardOutcome> result, Throwable failure) {
        try {
            Platform.runLater(() -> SqlEditorCloseSequence.finishRetryableFailure(
                    failure,
                    this::reopenAfterRejectedClose,
                    this::showCloseTransactionFailure,
                    result::completeExceptionally));
        } catch (Throwable dispatchFailure) {
            SqlEditorCloseSequence.finishRetryableFailure(
                    failure,
                    this::reopenAdmissionWithoutUi,
                    () -> {
                        if (failure != dispatchFailure) failure.addSuppressed(dispatchFailure);
                    },
                    result::completeExceptionally);
        }
    }

    private void showCloseTransactionFailure() {
        showAlert("提交或回滚失败，编辑器和事务已保留，请检查连接后重试。");
    }

    private CloseDecision requestCancelRollbackClose() {
        ButtonType close = new ButtonType("取消执行、回滚并关闭", ButtonBar.ButtonData.OK_DONE);
        ButtonType reject = new ButtonType("取消关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "SQL 仍在执行。关闭将取消执行，并回滚未提交事务。", close, reject);
        alert.setTitle("关闭 SQL 编辑器");
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(reject) == close
                ? CloseDecision.CANCEL_ROLLBACK : CloseDecision.CANCEL_CLOSE;
    }

    private CloseDecision requestTransactionClose() {
        ButtonType commit = new ButtonType("提交并关闭", ButtonBar.ButtonData.YES);
        ButtonType rollback = new ButtonType("回滚并关闭", ButtonBar.ButtonData.NO);
        ButtonType reject = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "当前编辑器有未提交事务，请选择关闭方式。", commit, rollback, reject);
        alert.setTitle("未提交事务");
        alert.setHeaderText(null);
        ButtonType chosen = alert.showAndWait().orElse(reject);
        if (chosen == commit) return CloseDecision.COMMIT;
        if (chosen == rollback) return CloseDecision.ROLLBACK;
        return CloseDecision.CANCEL_CLOSE;
    }

    private void persistCloseSnapshot(ClosePlan snapshot) {
        if (history == null || snapshot.sql() == null) return;
        try {
            history.recordStrict(snapshot.connectionName(), snapshot.schema(), snapshot.sql());
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private void reopenAfterRejectedClose() {
        if (draftBinding != null) draftBinding.reopen();
        admission.reopen();
        sessionOperations.reopen();
        running = sessionOperations.snapshot().pending();
        JdbcEditorSession editorSession = currentEditorSession();
        if (editorSession != null) {
            JdbcEditorSession.Snapshot snapshot = editorSession.snapshot();
            running = running || snapshot.running() || snapshot.cancelling();
        }
        refreshOperationControls();
    }

    private void reopenAdmissionWithoutUi() {
        admission.reopen();
        sessionOperations.reopen();
    }

    private JdbcEditorSession currentEditorSession() {
        synchronized (admission) {
            return jdbcSession;
        }
    }

    private void cancelCurrentSession() {
        JdbcEditorSession editorSession = currentEditorSession();
        if (editorSession != null) editorSession.cancel();
    }

    private void cancelCancellableCurrentSession() {
        if (sessionOperations.snapshot().currentCancellable()) cancelCurrentSession();
    }

    private void awaitSessionOperationsIdle() {
        sessionOperations.idle().toCompletableFuture().join();
    }

    private void closeCurrentSessionStrict() throws java.sql.SQLException {
        JdbcEditorSession editorSession = currentEditorSession();
        if (editorSession == null) return;
        editorSession.closeStrict();
    }

    private void awaitStrictSessionCleanup() {
        sessionCleanup.start().toCompletableFuture().join();
    }

    private static void reportStrictCleanupFailure(Throwable failure) {
        System.err.println("[DataCube] SQL editor strict cleanup retry: " + failure);
    }

    private static void reportMandatoryCloseFailure(Throwable failure) {
        System.err.println("[DataCube] SQL editor mandatory close failure: " + failure);
    }

    /** Virtual-thread-only close chain; every required step is attempted. */
    private void closeInBackground(ClosePlan snapshot) {
        if (resourcesClosed.get()) return;
        cancelCancellableCurrentSession();
        awaitSessionOperationsIdle();
        SqlEditorCloseSequence.run(
                () -> {
                    try {
                        resolveCloseTransaction(currentEditorSession(), snapshot.decision());
                    } catch (Throwable failure) {
                        throw new RetryableTransactionCloseFailure(failure);
                    }
                },
                () -> runDestructiveClose(snapshot));
    }

    private CloseGuardOutcome closeMandatoryInBackground(ClosePlan snapshot) {
        if (resourcesClosed.get()) return CloseGuardOutcome.APPROVED;
        try {
            cancelCancellableCurrentSession();
            awaitSessionOperationsIdle();
        } catch (Throwable failure) {
            reportMandatoryCloseFailure(failure);
            return CloseGuardOutcome.FAILED_PARTIAL;
        }
        return SqlEditorCloseSequence.runMandatory(
                () -> resolveCloseTransaction(
                        currentEditorSession(), CloseDecision.CANCEL_ROLLBACK),
                () -> runDestructiveClose(snapshot),
                SqlEditorPane::reportMandatoryCloseFailure);
    }

    private void runDestructiveClose(ClosePlan snapshot) {
        sessionOperations.suppressCallbacks();
        if (fileController != null) fileController.close();
        BestEffortCloseSequence.run(
                () -> persistCloseSnapshot(snapshot),
                metadataTasks::close,
                sessionOperations::close,
                tasks::close,
                this::awaitStrictSessionCleanup);
        resourcesClosed.set(true);
    }

    private static void resolveCloseTransaction(
            JdbcEditorSession editorSession, CloseDecision decision) {
        if (editorSession == null) return;
        try {
            if (decision == CloseDecision.COMMIT) editorSession.commit();
            else if (decision == CloseDecision.ROLLBACK) editorSession.rollback();
            else if (decision == CloseDecision.CANCEL_ROLLBACK) {
                JdbcEditorSession.Snapshot snapshot = editorSession.snapshot();
                if (snapshot.transactionMode() == JdbcEditorSession.TransactionMode.MANUAL
                        && snapshot.hasPendingTransaction()) {
                    editorSession.rollback();
                }
            }
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static final class RetryableTransactionCloseFailure extends RuntimeException {
        private RetryableTransactionCloseFailure(Throwable cause) {
            super(cause);
        }
    }

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");
        // 垂直可拖拽分隔：上为 SQL 编辑区，下为结果展示区，分隔条可手动上下拖拽。
        SplitPane split = new SplitPane();
        split.setOrientation(Orientation.VERTICAL);
        split.getItems().addAll(editor(), resultContainer());
        split.setDividerPositions(0.38);
        root.getChildren().addAll(toolbar(), split, statusBar());
        VBox.setVgrow(split, Priority.ALWAYS);
    }

    private Node toolbar() {
        HBox primary = new HBox(8);
        primary.setAlignment(Pos.CENTER_LEFT);

        schemaField = new TextField();
        schemaField.setPromptText("schema（可选）");
        schemaField.setPrefWidth(160);

        saveSqlFileBtn = new Button("保存 SQL");
        saveSqlFileBtn.setId("sql-file-save");
        saveSqlFileBtn.setDisable(true);
        saveSqlFileBtn.setOnAction(event -> {
            if (fileController != null) fileController.save();
        });

        saveAsSqlFileBtn = new Button("SQL 另存为");
        saveAsSqlFileBtn.setId("sql-file-save-as");
        saveAsSqlFileBtn.setDisable(true);
        saveAsSqlFileBtn.setOnAction(event -> {
            if (fileController != null) fileController.saveAs();
        });

        executeBtn = new Button("执行 (" + shortcuts.get(ShortcutAction.SQL_EXECUTE).getDisplayText() + ")");
        executeBtn.setId("sql-execute");
        executeBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        executeBtn.setOnAction(e -> onExecute());

        explainBtn = new Button("执行计划");
        explainBtn.setId("sql-explain");
        explainBtn.setOnAction(e -> onExplain());
        analyzeCheck = new CheckBox("ANALYZE(实际执行)");

        formatBtn = new Button("美化 SQL");
        formatBtn.setId("sql-format");
        formatBtn.setOnAction(e -> onFormat());

        clearBtn = new Button("清空");
        clearBtn.setOnAction(e -> {
            if (draftEditingBlocked()) return;
            editorArea.clear();
            resultTable.getItems().clear();
            resultTable.getColumns().clear();
            planArea.clear();
            useTable();
            clearResultFilterState();
            exportResultBtn.setDisable(true);
            copyInsertBtn.setDisable(true);
            statusLabel.setText("就绪");
        });

        exportResultBtn = new MenuButton("导出结果");
        exportResultBtn.setDisable(true);
        for (Format fmt : Format.values()) {
            MenuItem item = new MenuItem(fmt.label);
            item.setOnAction(e -> exportAs(fmt));
            exportResultBtn.getItems().add(item);
        }

        copyInsertBtn = new Button("复制INSERT");
        copyInsertBtn.setDisable(true);
        copyInsertBtn.setOnAction(e -> onCopyInsert());

        primary.getChildren().addAll(new Label("Schema:"), schemaField,
                saveSqlFileBtn, saveAsSqlFileBtn, executeBtn, explainBtn,
                analyzeCheck, formatBtn, exportResultBtn, copyInsertBtn, clearBtn);

        environmentBadge = new Label();
        environmentBadge.setId("sql-environment");
        readOnlyBadge = new Label();
        connectionBadge = new Label();
        connectionBadge.setId("sql-connection");
        transactionModeBox = new ComboBox<>(FXCollections.observableArrayList(
                JdbcEditorSession.TransactionMode.values()));
        transactionModeBox.setPrefWidth(125);
        transactionModeBox.setOnAction(e -> onTransactionModeChanged());
        commitBtn = new Button("提交");
        commitBtn.setOnAction(e -> submitTransactionAction(true));
        rollbackBtn = new Button("回滚");
        rollbackBtn.setOnAction(e -> submitTransactionAction(false));
        cancelBtn = new Button("取消执行");
        cancelBtn.setOnAction(e -> onCancelExecution());
        transactionStatus = new Label();
        transactionStatus.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");

        HBox safety = new HBox(8,
                connectionBadge, environmentBadge, readOnlyBadge,
                new Label("事务:"), transactionModeBox,
                commitBtn, rollbackBtn, cancelBtn, transactionStatus);
        safety.setAlignment(Pos.CENTER_LEFT);
        safety.setPadding(new Insets(2, 0, 0, 0));
        connectionGuidance = new Label();
        connectionGuidance.setId("sql-connection-guidance");
        connectionGuidance.setWrapText(true);
        connectionGuidance.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return new VBox(4, primary, safety, connectionGuidance);
    }

    private java.nio.file.Path chooseSqlSavePath(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存 SQL 文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("SQL 文件 (*.sql)", "*.sql"));
        java.io.File chosen = chooser.showSaveDialog(owner);
        return chosen == null ? null : chosen.toPath();
    }

    private boolean confirmSqlOverwrite(Window owner, java.nio.file.Path ignoredTarget) {
        ButtonType overwrite = new ButtonType("覆盖", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "目标文件已存在，是否覆盖？", overwrite, cancel);
        alert.setTitle("覆盖 SQL 文件");
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
        return alert.showAndWait().orElse(cancel) == overwrite;
    }

    private SqlScriptFileController.CloseDecision requestSqlFileCloseDecision(Window owner) {
        ButtonType save = new ButtonType("保存并关闭", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("不保存", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "SQL 文件有未保存更改，请选择关闭方式。", save, discard, cancel);
        alert.setTitle("关闭 SQL 文件");
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
        ButtonType chosen = alert.showAndWait().orElse(cancel);
        if (chosen == save) return SqlScriptFileController.CloseDecision.SAVE;
        if (chosen == discard) return SqlScriptFileController.CloseDecision.DISCARD;
        return SqlScriptFileController.CloseDecision.CANCEL;
    }

    private SqlConnectionGuidance guidance() {
        return SqlConnectionGuidance.from(admission.pinned(),
                recoveryIntent == null ? session.getActiveConnection() : currentConn());
    }

    private void renderConnectionGuidance() {
        if (connectionGuidance == null) return;
        if (recoveryConnectionButton != null)
            recoveryConnectionButton.setDisable(!recoveryPassive() || draftEditingBlocked());
        SqlConnectionGuidance state = guidance();
        String text = recoveryPassive()
                ? (state.hasConnection() ? "草稿已恢复，尚未连接；执行时将绑定原连接。"
                    : "草稿连接不可用，请为此草稿重新选择连接后执行。")
                : state.text();
        connectionGuidance.setText(text);
        connectionGuidance.setVisible(!text.isEmpty());
        connectionGuidance.setManaged(!text.isEmpty());
        environmentBadge.setVisible(state.hasConnection());
        environmentBadge.setManaged(state.hasConnection());
        readOnlyBadge.setVisible(state.hasConnection());
        readOnlyBadge.setManaged(state.hasConnection());
    }

    private boolean rejectMissingConnection() {
        if (guidance().hasConnection()) return false;
        renderConnectionGuidance();
        setButtonsRunning(false);
        return true;
    }

    private void renderInitialSessionState() {
        JdbcEditorSession editorSession = jdbcSession;
        if (editorSession != null) renderSessionSnapshot(editorSession.snapshot());
        else renderDisconnectedCandidate(currentConn());
    }

    private void renderDisconnectedCandidate(ConnConfig candidate) {
        if (connectionBadge == null) return;
        if (candidate == null || candidate.type() == DbType.REDIS) {
            connectionBadge.setText("🔌 未绑定连接");
            environmentBadge.setText("");
            environmentBadge.setStyle("-fx-text-fill: -brand-fg-muted;");
            readOnlyBadge.setText("");
        } else {
            ConnectionSafetyOptions safety = ConnectionSafetyOptions.from(candidate);
            connectionBadge.setText(admission.pinned() == null
                    ? "🔗 待绑定: " + candidate.name()
                    : "🔗 " + candidate.name() + " · 未连接");
            renderSafetyBadges(safety);
        }
        updatingTransactionMode = true;
        transactionModeBox.setValue(JdbcEditorSession.TransactionMode.AUTO_COMMIT);
        updatingTransactionMode = false;
        var operation = sessionOperations.snapshot();
        transactionModeBox.setDisable(!guidance().hasConnection() || running
                || !operation.accepting() || operation.pending());
        commitBtn.setDisable(true);
        rollbackBtn.setDisable(true);
        cancelBtn.setDisable(true);
        transactionStatus.setText("尚未创建专用会话");
        renderConnectionGuidance();
        setButtonsRunning(false);
    }

    private void renderSafetyBadges(ConnectionSafetyOptions safety) {
        environmentBadge.setText("环境: " + safety.environment().label());
        String style = switch (safety.environment()) {
            case PRODUCTION -> "-fx-text-fill: #d32f2f; -fx-font-weight: bold;";
            case TEST -> "-fx-text-fill: #d58a00; -fx-font-weight: bold;";
            case DEVELOPMENT -> "-fx-text-fill: -brand-fg-muted;";
        };
        environmentBadge.setStyle(style);
        readOnlyBadge.setText(safety.readOnly() ? "只读" : "可写");
        readOnlyBadge.setStyle(safety.readOnly()
                ? "-fx-text-fill: #1976d2; -fx-font-weight: bold;"
                : "-fx-text-fill: -brand-fg-muted;");
    }

    private void renderSessionSnapshot(JdbcEditorSession.Snapshot snapshot) {
        if (snapshot == null || connectionBadge == null) return;
        ConnConfig connection = admission.pinned();
        String connectionName = connection == null ? snapshot.connectionId() : connection.name();
        connectionBadge.setText("🔗 " + connectionName + " · " + connectionStateText(snapshot));
        renderSafetyBadges(snapshot.safety());
        updatingTransactionMode = true;
        transactionModeBox.setValue(snapshot.transactionMode());
        updatingTransactionMode = false;
        SerialSessionOperationQueue.Snapshot operationSnapshot = sessionOperations.snapshot();
        boolean busy = !operationSnapshot.accepting()
                || snapshot.running() || snapshot.cancelling() || operationSnapshot.pending();
        transactionModeBox.setDisable(busy || snapshot.connectionState()
                == JdbcEditorSession.ConnectionState.CLOSED);
        boolean pendingManual = snapshot.transactionMode() == JdbcEditorSession.TransactionMode.MANUAL
                && snapshot.hasPendingTransaction();
        commitBtn.setDisable(busy || !pendingManual);
        rollbackBtn.setDisable(busy || !pendingManual);
        cancelBtn.setDisable(!operationSnapshot.currentCancellable() || snapshot.cancelling());
        String timeout = snapshot.safety().queryTimeoutSeconds() == 0
                ? "无超时限制" : snapshot.safety().queryTimeoutSeconds() + " 秒超时";
        if (!snapshot.timeoutSupported()) timeout += "（驱动不支持）";
        transactionStatus.setText(transactionStateText(snapshot) + " · " + timeout);
        renderConnectionGuidance();
        setButtonsRunning(busy);
    }

    private static String connectionStateText(JdbcEditorSession.Snapshot snapshot) {
        return switch (snapshot.connectionState()) {
            case DISCONNECTED -> "未连接";
            case CONNECTED -> "已连接";
            case BROKEN -> "连接异常";
            case CLOSED -> "已关闭";
        };
    }

    private static String transactionStateText(JdbcEditorSession.Snapshot snapshot) {
        if (snapshot.transactionMode() == JdbcEditorSession.TransactionMode.AUTO_COMMIT) {
            return "自动提交";
        }
        return switch (snapshot.transactionState()) {
            case IDLE -> "手动事务 · 空闲";
            case ACTIVE -> "手动事务 · 待提交";
            case ERROR_PENDING -> "手动事务 · 错误待处理";
        };
    }

    private Node editor() {
        editorArea = new CodeArea();
        editorArea.setId("sql-editor");
        editorArea.getStyleClass().add("code-area");
        editorArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 14px;");
        // 行号栏
        editorArea.setParagraphGraphicFactory(LineNumberFactory.get(editorArea));
        // 语法高亮：文本变化后单遍正则重算样式区间并应用到富文本
        editorArea.textProperty().addListener((obs, oldText, newText) -> {
            recoveredUneditedSql = null;
            applyHighlighting(newText);
        });
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (draftEditingBlocked()) { e.consume(); return; }
            if (shortcuts.get(ShortcutAction.SQL_EXECUTE).match(e)) {
                e.consume();
                onExecute();
            } else if (shortcuts.get(ShortcutAction.SQL_BLOCK_COMMENT).match(e)) {
                // 块注释切换（先于行注释判定：两者组合键精确匹配，Shift 状态互斥）
                e.consume();
                toggleBlockComment();
            } else if (shortcuts.get(ShortcutAction.SQL_LINE_COMMENT).match(e)) {
                e.consume();
                toggleLineComment();
            }
        });
        // 高亮样式表（类名与 SqlHighlighter 输出一致）
        var css = getClass().getResource("/com/datacube/fx/sql-highlight.css");
        if (css != null) editorArea.getStylesheets().add(css.toExternalForm());
        // Ctrl+点击标识符 -> 若为存在的表则打开表设计器（后台校验存在性）
        editorArea.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.isControlDown() && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                onCtrlClick(e);
            }
        });
        // 自动补全：关键字 + 预热的元数据名称（Ctrl+Space 强制触发）；
        // 并为「别名./表名.」提供列名成员补全。
        autoComplete = new SqlAutoComplete(editorArea, this::completionCandidates, shortcuts);
        autoComplete.setMemberProvider(this::membersFor);
        installMetadataPrewarm();
        // 虚拟化滚动容器：为 CodeArea 提供垂直/水平滚动条（宽/长 SQL 友好）。
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(editorArea);
        TitledPane pane = new TitledPane("SQL 编辑器", scroll);
        // SplitPane 中不可折叠，改用分隔条调整高度；去除固定 prefHeight 以尊重用户拖拽。
        pane.setCollapsible(false);
        pane.setExpanded(true);
        // TitledPane 默认 maxHeight 受 prefHeight 限制，Vgrow/分隔条无法将其拉高超过
        // 首选高度；解除上限后分隔条才能自由分配上下两区高度。
        pane.setMaxHeight(Double.MAX_VALUE);
        return pane;
    }

    private VBox resultContainer() {
        resultTable = new TableView<>();
        resultColumnMenu = new SqlResultColumnMenu(resultTable);
        resultTable.setPlaceholder(new Label("（无结果）"));
        // UNCONSTRAINED：保留列自然宽度与底部横向滚动条（宽表友好）。
        resultTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // 单元格级选择（而非整行）+ 多选；支持 Ctrl+C / 右键复制选中内容。
        resultTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        resultTable.getSelectionModel().setCellSelectionEnabled(true);
        resultTable.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.C) {
                copyResultSelection(SqlResultToolbar.CopyMode.SELECTION);
                e.consume();
            }
        });
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> copyResultSelection(SqlResultToolbar.CopyMode.SELECTION));
        MenuItem insertItem = new MenuItem("复制为 INSERT 语句");
        insertItem.setOnAction(e -> onCopyInsert());
        resultTable.setContextMenu(new ContextMenu(copyItem, insertItem));
        // 执行计划文本区（等宽、只读、不换行）；与结果表格共用同一 TitledPane，按需切换。
        planArea = new TextArea();
        planArea.setEditable(false);
        planArea.setWrapText(false);
        planArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        resultPane = new TitledPane("结果", resultTable);
        resultPane.setCollapsible(false);
        // 同 editor()：解除 TitledPane 的 prefHeight 上限，使其在 VBox 中随 Vgrow 填满可用
        // 空间（否则结果表只占 TableView 首选高度，下方留大片空白）。
        resultPane.setMaxHeight(Double.MAX_VALUE);
        resultToolbar = new SqlResultToolbar(new SqlResultToolbar.Actions(
                text -> {
                    resultFilterState.setSearchText(text);
                    renderResultFilterSnapshot();
                },
                this::onAddResultFilterCondition,
                this::onRemoveResultFilterCondition,
                this::onApplyDatabaseFilter,
                this::onClearResultFilters,
                this::copyResultSelection), resultColumnMenu.getNode());
        renderResultFilterToolbar();
        VBox box = new VBox(resultToolbar.getNode(), resultPane);
        VBox.setVgrow(resultPane, Priority.ALWAYS);
        return box;
    }

    private Node statusBar() {
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        statusLabel.textProperty().addListener((observable, before, after) -> resultStatusRevision++);
        HBox box = new HBox(statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    private void onFormat() {
        if (draftEditingBlocked()) return;
        String sql = editorArea.getText();
        if (sql.trim().isEmpty()) return;
        try {
            String formatted = SqlFormatter.format(sql);
            editorArea.replaceText(formatted);
            // replaceText 为一次性整体替换，textProperty 监听在挂起更新周期内
            // 应用的样式会被替换收尾重置为默认样式；此处在替换返回后再次
            // 应用高亮，确保美化后色彩不丢失。
            applyHighlighting(formatted);
            statusLabel.setText("已美化");
        } catch (Exception e) {
            showAlert("美化失败：" + e.getMessage());
        }
    }

    /** 依据当前文本重算并应用语法高亮样式区间（供文本监听与美化后复用）。 */
    private void applyHighlighting(String text) {
        editorArea.setStyleSpans(0, SqlHighlighter.compute(text));
    }

    /**
     * 待执行 SQL 文本：有非空文本选区时只取选区（“执行选中”），否则取全部内容。
     */
    private String selectedOrAllSql() {
        String selected = editorArea.getSelectedText();
        if (selected != null && !selected.trim().isEmpty()) return selected;
        return editorArea.getText();
    }

    private void onExecute() {
        if (running || sessionOperations.snapshot().pending()) return;
        if (rejectMissingConnection()) return;
        String sql = selectedOrAllSql();
        if (sql.trim().isEmpty()) {
            showAlert("请输入 SQL");
            return;
        }
        ConnConfig active;
        try {
            active = admitCurrentConnection();
        } catch (RuntimeException rejected) {
            showAlert("请先在左侧选择一个活动连接");
            return;
        }
        if (!allowBySafetyPolicy(sql, active)) return;
        final String schema = schemaField.getText().trim();
        final String effectiveSchema = schema.isEmpty() ? null : schema;
        final boolean oracle = active.type() == DbType.ORACLE;
        HistorySnapshot historySnapshot = captureHistory(sql, active, schema);

        running = true;
        setButtonsRunning(true);
        statusLabel.setText("执行中...");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");

        submitSessionOperation(SerialSessionOperationQueue.OperationKind.EXECUTE, () -> {
            recordHistory(historySnapshot);
            JdbcEditorSession editorSession = ensureEditorSession();
            return editorSession.executeScript(
                    sql,
                    effectiveSchema,
                    settings.getMaxResultRows(),
                    this::askScriptError,
                    oracle);
        }, batch -> {
            running = false;
            JdbcEditorSession editorSession = jdbcSession;
            if (editorSession != null) renderSessionSnapshot(editorSession.snapshot());
            else setButtonsRunning(false);
            showScriptResults(batch.outcomes(), batch.elapsedMillis(), effectiveSchema);
        }, failure -> {
            running = false;
            JdbcEditorSession editorSession = jdbcSession;
            if (editorSession != null) renderSessionSnapshot(editorSession.snapshot());
            else setButtonsRunning(false);
            showError(message(failure), 0);
        });
    }

    private JdbcEditorSession ensureEditorSession() {
        synchronized (admission) {
            ConnConfig connection = admission.requireOpenPinned();
            JdbcEditorSession existing = jdbcSession;
            if (existing != null) {
                if (!existing.snapshot().connectionId().equals(connection.id())) {
                    throw new IllegalStateException("SQL 编辑器会话连接与固定连接不一致");
                }
                return existing;
            }
            editorConnection = connection;
            ConstructionOwner construction = new ConstructionOwner();
            try {
                JdbcEditorSession jdbcSession = connections.openEditorSession(editorConnection);
                construction.ownBlocking(this::awaitStrictSessionCleanup);
                this.jdbcSession = jdbcSession;
                construction.commit();
                return jdbcSession;
            } catch (Throwable failure) {
                throw construction.close(failure).failure();
            }
        }
    }

    private <T> void submitSessionOperation(
            SerialSessionOperationQueue.OperationKind kind,
            Callable<T> operation,
            Consumer<? super T> success,
            Consumer<? super Throwable> failure) {
        setButtonsRunning(true);
        setTransactionControlsDisabled(true);
        cancelBtn.setDisable(!kind.cancellable());
        try {
            sessionOperations.submit(kind, operation, value -> {
                success.accept(value);
                refreshOperationControls();
            }, error -> {
                failure.accept(error);
                refreshOperationControls();
            });
        } catch (RuntimeException rejected) {
            refreshOperationControls();
            throw rejected;
        }
    }

    private void refreshOperationControls() {
        JdbcEditorSession editorSession = currentEditorSession();
        if (editorSession != null) renderSessionSnapshot(editorSession.snapshot());
        else {
            SerialSessionOperationQueue.Snapshot snapshot = sessionOperations.snapshot();
            boolean pending = !snapshot.accepting() || snapshot.pending();
            setButtonsRunning(pending);
            setTransactionControlsDisabled(pending);
            cancelBtn.setDisable(!snapshot.currentCancellable());
        }
    }

    private boolean allowBySafetyPolicy(String sql, ConnConfig active) {
        boolean oracle = active.type() == DbType.ORACLE;
        SqlSafetyAnalyzer.ScriptAnalysis analysis = SqlSafetyAnalyzer.analyze(sql, oracle);
        ConnectionSafetyOptions safety = ConnectionSafetyOptions.from(active);
        SqlSafetyPolicy.Decision decision = SqlSafetyPolicy.decide(analysis, safety);
        if (decision.blocked()) {
            showAlert(decision.message());
            return false;
        }
        return !decision.confirmationRequired() || confirmSafety(decision, active);
    }

    private boolean confirmSafety(SqlSafetyPolicy.Decision decision, ConnConfig active) {
        ConnectionSafetyOptions safety = ConnectionSafetyOptions.from(active);
        StringBuilder details = new StringBuilder()
                .append("环境: ").append(safety.environment().label()).append('\n')
                .append("连接: ").append(active.name()).append('\n')
                .append("风险语句:\n");
        for (SqlSafetyAnalyzer.StatementAnalysis statement : decision.relevantStatements()) {
            details.append("  #").append(statement.index())
                    .append("  ").append(riskSummary(statement.risks()))
                    .append("\n  ").append(truncate(statement.sql().replaceAll("\\s+", " "), 180))
                    .append('\n');
        }
        ButtonType confirm = new ButtonType(
                safety.environment() == ConnectionEnvironment.PRODUCTION
                        ? "确认在生产环境执行" : "确认执行",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, details.toString(), confirm, cancel);
        alert.setTitle("SQL 安全确认");
        alert.setHeaderText(decision.message());
        return alert.showAndWait().orElse(cancel) == confirm;
    }

    private static String riskSummary(Set<SqlSafetyAnalyzer.Risk> risks) {
        if (risks.isEmpty()) return "生产环境写入确认";
        List<String> labels = new ArrayList<>();
        if (risks.contains(SqlSafetyAnalyzer.Risk.MISSING_WHERE)) labels.add("缺少 WHERE");
        if (risks.contains(SqlSafetyAnalyzer.Risk.DESTRUCTIVE_DDL)) labels.add("破坏性 DDL");
        if (risks.contains(SqlSafetyAnalyzer.Risk.UNKNOWN_STATEMENT)) labels.add("未知语句");
        if (risks.contains(SqlSafetyAnalyzer.Risk.SESSION_STATE_CONFLICT)) labels.add("会话状态冲突");
        return String.join("、", labels);
    }

    /**
     * 脚本遇错处置回调：在 worker 线程被 runner 调用，切到 FX 线程弹三按钮框
     * （继续 / 全部继续 / 取消）并以 {@link CountDownLatch} 阻塞等待用户选择。
     */
    private ScriptErrorPolicy.Decision askScriptError(int index, String sql, String message) {
        if (tasks.isClosed()) return ScriptErrorPolicy.Decision.ABORT;
        final java.util.concurrent.atomic.AtomicReference<ScriptErrorPolicy.Decision> ref =
                new java.util.concurrent.atomic.AtomicReference<>(ScriptErrorPolicy.Decision.ABORT);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                if (tasks.isClosed()) return;
                ButtonType cont = new ButtonType("继续");
                ButtonType contAll = new ButtonType("全部继续");
                ButtonType abort = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert a = new Alert(Alert.AlertType.ERROR,
                        "第 " + index + " 条语句失败：\n" + truncate(message, 300)
                                + "\n\n是否继续执行剩余语句？",
                        cont, contAll, abort);
                a.setHeaderText(null);
                a.setTitle("执行遇错");
                ButtonType chosen = a.showAndWait().orElse(abort);
                if (chosen == cont) ref.set(ScriptErrorPolicy.Decision.CONTINUE);
                else if (chosen == contAll) ref.set(ScriptErrorPolicy.Decision.CONTINUE_ALL);
                else ref.set(ScriptErrorPolicy.Decision.ABORT);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScriptErrorPolicy.Decision.ABORT;
        }
        return ref.get();
    }

    private void onTransactionModeChanged() {
        if (updatingTransactionMode) return;
        JdbcEditorSession.TransactionMode selected = transactionModeBox.getValue();
        if (selected == null) return;
        try {
            admitCurrentConnection();
        } catch (RuntimeException rejected) {
            showAlert("请先在左侧选择一个活动连接");
            renderDisconnectedCandidate(null);
            return;
        }
        JdbcEditorSession editorSession = jdbcSession;
        JdbcEditorSession.Snapshot snapshot = editorSession == null ? null : editorSession.snapshot();
        TransactionModeDecision pendingDecision = TransactionModeDecision.NONE;
        if (snapshot != null
                && snapshot.transactionMode() == JdbcEditorSession.TransactionMode.MANUAL
                && selected == JdbcEditorSession.TransactionMode.AUTO_COMMIT
                && snapshot.hasPendingTransaction()) {
            pendingDecision = requestPendingModeChange();
            if (pendingDecision == TransactionModeDecision.CANCEL) {
                renderSessionSnapshot(snapshot);
                return;
            }
        }
        TransactionModeDecision decision = pendingDecision;
        transactionModeBox.setDisable(true);
        submitSessionOperation(SerialSessionOperationQueue.OperationKind.SET_MODE, () -> {
            JdbcEditorSession session = ensureEditorSession();
            if (decision == TransactionModeDecision.COMMIT) session.commit();
            else if (decision == TransactionModeDecision.ROLLBACK) session.rollback();
            session.setTransactionMode(selected);
            return session.snapshot();
        }, this::renderSessionSnapshot, failure -> {
            JdbcEditorSession session = jdbcSession;
            if (session != null) renderSessionSnapshot(session.snapshot());
            showError(message(failure), 0);
        });
    }

    private TransactionModeDecision requestPendingModeChange() {
        ButtonType commit = new ButtonType("提交", ButtonBar.ButtonData.YES);
        ButtonType rollback = new ButtonType("回滚", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "切换为自动提交前，必须先处理当前未提交事务。", commit, rollback, cancel);
        alert.setTitle("处理未提交事务");
        alert.setHeaderText(null);
        ButtonType selected = alert.showAndWait().orElse(cancel);
        if (selected == commit) return TransactionModeDecision.COMMIT;
        if (selected == rollback) return TransactionModeDecision.ROLLBACK;
        return TransactionModeDecision.CANCEL;
    }

    private void submitTransactionAction(boolean commit) {
        JdbcEditorSession editorSession = currentEditorSession();
        if (editorSession == null) return;
        setTransactionControlsDisabled(true);
        submitSessionOperation(commit
                ? SerialSessionOperationQueue.OperationKind.COMMIT
                : SerialSessionOperationQueue.OperationKind.ROLLBACK, () -> {
            JdbcEditorSession current = ensureEditorSession();
            if (commit) current.commit();
            else current.rollback();
            return current.snapshot();
        }, this::renderSessionSnapshot, failure -> {
            renderSessionSnapshot(currentEditorSession().snapshot());
            showError(message(failure), 0);
        });
    }

    private void onCancelExecution() {
        JdbcEditorSession editorSession = currentEditorSession();
        if (editorSession == null) return;
        cancelBtn.setDisable(true);
        transactionStatus.setText("正在取消...");
        tasks.submit(editorSession::cancel,
                outcome -> renderCancelled(outcome, editorSession.snapshot()),
                failure -> {
                    renderSessionSnapshot(editorSession.snapshot());
                    showError(message(failure), 0);
                });
    }

    private void renderCancelled(
            JdbcEditorSession.CancelOutcome outcome, JdbcEditorSession.Snapshot snapshot) {
        renderSessionSnapshot(snapshot);
        if (outcome == JdbcEditorSession.CancelOutcome.NOTHING_RUNNING) {
            statusLabel.setText("没有正在执行的 SQL");
        } else {
            statusLabel.setText("已请求取消");
            statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        }
    }

    private void setTransactionControlsDisabled(boolean disabled) {
        transactionModeBox.setDisable(disabled);
        commitBtn.setDisable(disabled);
        rollbackBtn.setDisable(disabled);
        cancelBtn.setDisable(disabled);
    }

    // ---------- Ctrl+点击跳转表设计器 ----------

    /** 允许出现在标识符中的字符（含 {@code .} 与双引号，以支持 schema.table 与引用名）。 */
    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '"';
    }

    /**
     * Ctrl+点击：定位点击处标识符，解析 {@code schema.table} 或裸名，
     * 后台校验表是否存在，存在则回主线程打开表设计器。
     */
    private void onCtrlClick(javafx.scene.input.MouseEvent e) {
        if (recoveryPassive()) return;
        if (openDesigner == null) return;
        ConnConfig active = currentConn();
        if (active == null) return;
        var hit = editorArea.hit(e.getX(), e.getY()).getCharacterIndex();
        if (hit.isEmpty()) return;
        String token = identifierAt(editorArea.getText(), hit.getAsInt());
        if (token == null || token.isEmpty()) return;
        e.consume();

        final String connId = active.id();
        var dialect = connections.provider(connId).dialect();
        // 解析 schema.table / 裸名（取最后两段作为 schema.table）
        String[] parts = token.split("\\.");
        String rawName = parts[parts.length - 1];
        String rawSchema = parts.length >= 2 ? parts[parts.length - 2] : null;
        final String name = foldIdentifier(dialect, rawName);
        if (name.isEmpty()) return;
        final String schema = resolveSchema(active, dialect, rawSchema);

        tasks.submit(() -> {
            for (TableInfo t : treeSvc.tables(connId, schema)) {
                if (t.name().equalsIgnoreCase(name)) return true;
            }
            return false;
        }, exists -> {
            if (exists) {
                openDesigner.accept(connId, new TableRef(schema, name));
            } else {
                statusLabel.setText("未找到表: " + (schema == null ? "" : schema + ".") + name);
            }
        }, failure -> {
            // 校验失败静默：不跳转
        });
    }

    /** 从 text 的 pos 处向两侧扩展取标识符（含 {@code .} 与引号）。 */
    private static String identifierAt(String text, int pos) {
        if (text == null || text.isEmpty() || pos < 0 || pos >= text.length()) return null;
        int start = pos;
        while (start > 0 && isIdentChar(text.charAt(start - 1))) start--;
        int end = pos;
        while (end < text.length() && isIdentChar(text.charAt(end))) end++;
        String s = text.substring(start, end).trim();
        // 修剪首尾多余的点
        while (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 去引号后折叠未引用标识符（Oracle → 大写）；已引用则保留原大小写。 */
    private static String foldIdentifier(com.datacube.spi.SqlDialect dialect, String ident) {
        if (ident == null) return "";
        String s = ident.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return dialect.foldUnquotedIdentifier(s);
    }

    /** 解析 schema：限定名用其 schema 段；裸名用 schemaField（空则 Oracle 取登录名）。 */
    private String resolveSchema(ConnConfig active, com.datacube.spi.SqlDialect dialect, String rawSchema) {
        if (rawSchema != null && !rawSchema.isEmpty()) return foldIdentifier(dialect, rawSchema);
        String fieldSchema = schemaField.getText().trim();
        if (!fieldSchema.isEmpty()) return dialect.foldUnquotedIdentifier(fieldSchema);
        if (active.type() == DbType.ORACLE && active.username() != null && !active.username().isEmpty()) {
            return dialect.foldUnquotedIdentifier(active.username());
        }
        return null;
    }

    // ---------- 执行计划（EXPLAIN / EXPLAIN ANALYZE） ----------

    private void onExplain() {
        if (running || sessionOperations.snapshot().pending()) return;
        if (rejectMissingConnection()) return;
        String text = selectedOrAllSql();
        if (text.trim().isEmpty()) {
            showAlert("请输入 SQL");
            return;
        }
        ConnConfig active;
        try {
            active = admitCurrentConnection();
        } catch (RuntimeException rejected) {
            showAlert("请先在左侧选择一个活动连接");
            return;
        }
        List<String> stmts = SqlScriptSplitter.split(text, active.type() == DbType.ORACLE);
        if (stmts.isEmpty()) {
            showAlert("请输入 SQL");
            return;
        }
        final String sql = stmts.get(0);
        final int total = stmts.size();
        final boolean analyze = analyzeCheck.isSelected();
        // ANALYZE is executable; ordinary EXPLAIN is still classified through the shared analyzer.
        if (!allowBySafetyPolicy(sql, active)) return;
        final String schema = schemaField.getText().trim();
        HistorySnapshot historySnapshot = captureHistory(text, active, schema);

        running = true;
        setButtonsRunning(true);
        statusLabel.setText(analyze ? "执行计划(ANALYZE)中..." : "生成执行计划中...");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");

        submitSessionOperation(SerialSessionOperationQueue.OperationKind.EXPLAIN, () -> {
            recordHistory(historySnapshot);
            JdbcEditorSession editorSession = ensureEditorSession();
            return editorSession.explain(sql, schema.isEmpty() ? null : schema, analyze);
        }, result -> {
            running = false;
            JdbcEditorSession editorSession = jdbcSession;
            if (editorSession != null) renderSessionSnapshot(editorSession.snapshot());
            else setButtonsRunning(false);
            if (result.kind == QueryResult.Kind.ERROR) {
                showFailure(result);
            } else if (result.kind == QueryResult.Kind.QUERY) {
                StringBuilder plan = new StringBuilder();
                for (List<Object> row : result.rows) {
                    if (!row.isEmpty() && row.get(0) != null) plan.append(row.get(0));
                    plan.append('\n');
                }
                showPlan(plan.toString(), result.elapsedMillis, total);
            } else {
                showError("未返回执行计划", result.elapsedMillis);
            }
        }, failure -> {
            running = false;
            JdbcEditorSession editorSession = jdbcSession;
            if (editorSession != null) renderSessionSnapshot(editorSession.snapshot());
            else setButtonsRunning(false);
            showError(message(failure), 0);
        });
    }

    private void showPlan(String planText, long elapsed, int totalStmts) {
        clearResultFilterState();
        exportResultBtn.setDisable(true);
        copyInsertBtn.setDisable(true);
        planArea.setText(planText);
        usePlan();
        String status = "执行计划 - " + elapsed + "ms";
        if (totalStmts > 1) status += "（已对第 1 条语句，共 " + totalStmts + " 条）";
        statusLabel.setText(status);
        statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
    }

    /** 将结果区切回表格视图。 */
    private void useTable() {
        if (resultPane.getContent() != resultTable) resultPane.setContent(resultTable);
        resultPane.setText("结果");
    }

    private void onAddResultFilterCondition() {
        ResultFilterState.Snapshot snapshot = resultFilterState.snapshot();
        QueryResult original = snapshot.originalResult();
        if (original == null || original.kind != QueryResult.Kind.QUERY) return;
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        FilterConditionDialog.show(owner, original.resultColumns,
                snapshot.conditions().size(), FilterConnector.AND)
                .ifPresent(condition -> {
                    resultFilterState.appendCondition(condition);
                    renderResultFilterSnapshot();
                });
    }

    private void onRemoveResultFilterCondition(int index) {
        if (index < 0 || index >= resultFilterState.snapshot().conditions().size()) return;
        resultFilterState.removeCondition(index);
        renderResultFilterSnapshot();
    }

    private void onClearResultFilters() {
        resultFilterState.clearFilters();
        renderResultFilterSnapshot();
        ResultFilterState.Snapshot snapshot = resultFilterState.snapshot();
        QueryResult restored = snapshot.activeResult();
        if (restored == null) return;
        statusLabel.setText("已清除筛选 - " + formatResultRowCount(restored));
        statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
    }

    private void onApplyDatabaseFilter() {
        ResultFilterState.DatabaseFilterRequest request;
        try {
            refreshDatabaseFilterAvailability();
            request = resultFilterState.databaseRequest();
        } catch (RuntimeException unavailable) {
            statusLabel.setText(message(unavailable));
            statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
            return;
        }

        final ConnConfig connection;
        final RenderedFilterQuery query;
        try {
            connection = admission.requireOpenPinned();
            SafeSelectEligibility.Result eligibility = SafeSelectEligibility.check(
                    request.originalSql(), connection.type() == DbType.ORACLE,
                    request.originalResult());
            if (!eligibility.eligible()) {
                onDatabaseFilterFailed(request.generation(), eligibility.reason());
                return;
            }
            ResultFilterSqlRenderer resultFilterRenderer = connections.provider(connection.id())
                    .resultFilterSqlRenderer()
                    .orElseThrow(() -> new IllegalStateException("当前数据库不支持结果筛选"));
            query = resultFilterRenderer.render(
                    eligibility.normalizedSql(), request.originalResult().resultColumns,
                    request.conditions());
        } catch (RuntimeException failure) {
            onDatabaseFilterFailed(
                    request.generation(), UNCONFIRMED_DATABASE_FILTER_CAPABILITY);
            return;
        }

        String effectiveSchema = request.effectiveSchema();
        statusLabel.setText("正在应用数据库筛选（" + schemaContext(effectiveSchema) + "）...");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        try {
            submitSessionOperation(SerialSessionOperationQueue.OperationKind.EXECUTE,
                    () -> ensureEditorSession().executePrepared(
                            query.sql(), query.parameters(),
                            effectiveSchema,
                            settings.getMaxResultRows()),
                    result -> onDatabaseFilterSucceeded(request, result),
                    failure -> onDatabaseFilterFailed(
                            request.generation(), "数据库筛选执行失败"));
        } catch (RuntimeException rejected) {
            onDatabaseFilterFailed(request.generation(), "数据库筛选执行失败");
        }
    }

    private void onDatabaseFilterSucceeded(
            ResultFilterState.DatabaseFilterRequest request, QueryResult result) {
        if (result == null || result.kind != QueryResult.Kind.QUERY) {
            String failure = result == null
                    ? "数据库筛选未返回结果" : databaseFilterResultFailureMessage(result);
            onDatabaseFilterFailed(request.generation(), failure);
            return;
        }
        if (!orderedLabels(request.originalResult()).equals(orderedLabels(result))) {
            onDatabaseFilterFailed(request.generation(), "数据库筛选返回了不一致的列结构");
            return;
        }
        QueryResult candidate = request.originalResult().columnComments.isEmpty()
                ? result : result.withColumnComments(request.originalResult().columnComments);
        if (!resultFilterState.databaseApplied(request.generation(), candidate)) return;
        renderResultFilterSnapshot();
        statusLabel.setText("数据库筛选已应用（" + schemaContext(request.effectiveSchema())
                + "） - " + formatResultRowCount(candidate));
        statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
    }

    private void onDatabaseFilterFailed(long generation, String failure) {
        String detail = failure == null || failure.isBlank() ? "数据库筛选失败" : failure;
        if (!resultFilterState.databaseFailed(generation, detail)) return;
        renderResultFilterToolbar(resultFilterState.snapshot());
        statusLabel.setText("数据库筛选失败，仍显示当前结果：" + detail);
        statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
    }

    private static List<String> orderedLabels(QueryResult result) {
        if (!result.resultColumns.isEmpty()) {
            return result.resultColumns.stream().map(ResultColumn::label).toList();
        }
        return result.columns;
    }

    private static String databaseFilterResultFailureMessage(QueryResult result) {
        if (result == null || result.kind != QueryResult.Kind.ERROR) {
            return "数据库筛选执行失败";
        }
        String diagnostic = result.errorMessage;
        if (diagnostic != null && SAFE_DATABASE_FILTER_FAILURE.matcher(diagnostic).matches()) {
            return diagnostic;
        }
        return switch (result.failureKind) {
            case CANCELLED -> "数据库筛选已取消";
            case TIMEOUT -> "数据库筛选超时";
            case SQL_ERROR -> "数据库筛选执行失败";
        };
    }

    /** Copies only formatted values in the table's current visible order. */
    private void copyResultSelection(SqlResultToolbar.CopyMode mode) {
        ResultFilterState.Snapshot snapshot = resultFilterState.snapshot();
        QueryResult active = snapshot.activeResult();
        if (active == null || active.kind != QueryResult.Kind.QUERY) return;
        List<TableColumn> copyColumns = visibleResultColumns();
        List<String> headers = copyColumns.stream()
                .map(column -> String.valueOf(column.getProperties().get("sql-result-label")))
                .toList();
        List<List<String>> rows = formattedVisibleRows(copyColumns);
        List<TablePosition> positions =
                new ArrayList<>(resultTable.getSelectionModel().getSelectedCells());
        Set<TsvClipboardFormatter.CellRef> cells = new HashSet<>();
        for (TablePosition position : positions) {
            int column = copyColumns.indexOf(position.getTableColumn());
            if (position.getRow() >= 0 && position.getRow() < rows.size()
                    && column >= 0 && column < headers.size()) {
                cells.add(new TsvClipboardFormatter.CellRef(position.getRow(), column));
            }
        }

        String value;
        int copied;
        String unit;
        switch (mode) {
            case CURRENT_CELL -> {
                TsvClipboardFormatter.CellRef focused = focusedResultCell(headers.size(), rows.size());
                if (focused == null) {
                    focused = cells.stream().min(Comparator
                            .comparingInt(TsvClipboardFormatter.CellRef::row)
                            .thenComparingInt(TsvClipboardFormatter.CellRef::column)).orElse(null);
                }
                if (focused == null) return;
                value = TsvClipboardFormatter.rectangle(headers, rows, Set.of(focused), false);
                copied = 1;
                unit = "个单元格";
            }
            case SELECTION -> {
                if (cells.isEmpty()) return;
                value = TsvClipboardFormatter.rectangle(headers, rows, cells, false);
                copied = cells.size();
                unit = "个单元格";
            }
            case SELECTED_ROWS, SELECTED_ROWS_WITH_HEADERS -> {
                Set<Integer> selectedRows = new HashSet<>();
                for (TsvClipboardFormatter.CellRef cell : cells) selectedRows.add(cell.row());
                if (selectedRows.isEmpty()) return;
                value = TsvClipboardFormatter.rows(headers, rows, selectedRows,
                        mode == SqlResultToolbar.CopyMode.SELECTED_ROWS_WITH_HEADERS);
                copied = selectedRows.size();
                unit = "行";
            }
            default -> throw new IllegalStateException("未知复制模式: " + mode);
        }
        if (!writeClipboard(value)) {
            showClipboardWriteFailure();
            return;
        }
        statusLabel.setText("已复制 " + copied + " " + unit);
        statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
    }

    private TsvClipboardFormatter.CellRef focusedResultCell(int columns, int rows) {
        TablePosition focused = resultTable.getFocusModel().getFocusedCell();
        int column = visibleResultColumns().indexOf(focused.getTableColumn());
        if (focused.getRow() < 0 || focused.getRow() >= rows || column < 0 || column >= columns) {
            return null;
        }
        return new TsvClipboardFormatter.CellRef(focused.getRow(), column);
    }

    private List<TableColumn> visibleResultColumns() {
        List<TableColumn> columns = new ArrayList<>();
        for (TableColumn column : resultTable.getVisibleLeafColumns()) {
            if (column.getUserData() instanceof Integer index && index >= 0) columns.add(column);
        }
        return columns;
    }

    private List<List<String>> formattedVisibleRows(List<TableColumn> columns) {
        List<List<String>> formatted = new ArrayList<>(resultTable.getItems().size());
        for (int rowIndex = 0; rowIndex < resultTable.getItems().size(); rowIndex++) {
            List<String> row = new ArrayList<>(columns.size());
            for (TableColumn column : columns) {
                row.add(ResultValueFormatter.format(column.getCellData(rowIndex)));
            }
            formatted.add(row);
        }
        return formatted;
    }

    /** 将结果区切到执行计划文本视图。 */
    private void usePlan() {
        if (resultPane.getContent() != planArea) resultPane.setContent(planArea);
        resultPane.setText("结果（执行计划）");
    }

    private void showError(String msg, long elapsed) {
        clearResultFilterState();
        useTable();
        exportResultBtn.setDisable(true);
        copyInsertBtn.setDisable(true);
        resultTable.getColumns().clear();
        resultTable.getItems().clear();
        TableColumn<ObservableList<Object>, Object> col = new TableColumn<>("错误");
        col.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(d.getValue().get(0)));
        resultTable.getColumns().add(col);
        ObservableList<ObservableList<Object>> rows = FXCollections.observableArrayList();
        rows.add(FXCollections.observableArrayList(msg));
        resultTable.setItems(rows);
        statusLabel.setText("ERROR - " + elapsed + "ms");
        statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
    }

    private void showFailure(QueryResult result) {
        if (result.failureKind == QueryResult.FailureKind.CANCELLED) {
            showError(result.errorMessage, result.elapsedMillis);
            statusLabel.setText("已取消");
        } else if (result.failureKind == QueryResult.FailureKind.TIMEOUT) {
            showError(result.errorMessage, result.elapsedMillis);
            statusLabel.setText("执行超时");
        } else {
            showError(result.errorMessage, result.elapsedMillis);
        }
    }

    private void showScriptResults(List<ScriptOutcome> outcomes, long totalElapsed) {
        showScriptResults(outcomes, totalElapsed, null);
    }

    private void showScriptResults(
            List<ScriptOutcome> outcomes, long totalElapsed, String effectiveSchema) {
        if (outcomes != null && outcomes.size() == 1) {
            ScriptOutcome outcome = outcomes.getFirst();
            QueryResult result = outcome.result();
            if (result.kind == QueryResult.Kind.QUERY) {
                if (showQueryResult(result, outcome.sql(), effectiveSchema)) {
                    statusLabel.setText("OK - " + formatResultRowCount(result)
                            + " - " + result.elapsedMillis + "ms");
                    statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
                }
                return;
            }
        }
        clearResultFilterState();
        useTable();
        exportResultBtn.setDisable(true);
        copyInsertBtn.setDisable(true);
        resultTable.getColumns().clear();
        resultTable.getItems().clear();
        if (outcomes == null || outcomes.isEmpty()) {
            statusLabel.setText("无结果");
            return;
        }
        if (outcomes.size() > 1) {
            addColumn("#", 0);
            addColumn("类型", 1);
            addColumn("耗时", 2);
            addColumn("结果", 3);
            ObservableList<ObservableList<Object>> data = FXCollections.observableArrayList();
            for (ScriptOutcome o : outcomes) {
                QueryResult r = o.result();
                data.add(FXCollections.observableArrayList(
                        String.valueOf(o.index()), r.kind.name(), r.elapsedMillis + "ms", summarize(r)));
            }
            resultTable.setItems(data);
            statusLabel.setText("共 " + outcomes.size() + " 条语句 - " + totalElapsed + "ms");
            statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
        } else {
            QueryResult r = outcomes.get(0).result();
            switch (r.kind) {
                case QUERY -> throw new IllegalStateException("single query handled before reset");
                case UPDATE -> {
                    statusLabel.setText("OK - " + r.updateCount + " rows affected - " + r.elapsedMillis + "ms");
                    statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
                }
                case ERROR -> showFailure(r);
            }
        }
    }

    private static String summarize(QueryResult r) {
        return switch (r.kind) {
            case QUERY -> r.rows.size() + " rows";
            case UPDATE -> r.updateCount + " affected";
            case ERROR -> switch (r.failureKind) {
                case CANCELLED -> "已取消";
                case TIMEOUT -> "执行超时";
                case SQL_ERROR -> "ERR: " + truncate(r.errorMessage, 80);
            };
        };
    }

    private void addColumn(String title, int idx) {
        TableColumn<ObservableList<Object>, Object> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(
                idx < d.getValue().size() ? d.getValue().get(idx) : ""));
        resultTable.getColumns().add(c);
    }

    private void showQueryResult(QueryResult r) {
        String sql = lastQuerySql == null ? "" : lastQuerySql;
        showQueryResult(r, sql);
    }

    private boolean showQueryResult(QueryResult result, String sql) {
        return showQueryResult(result, sql, null);
    }

    private boolean showQueryResult(QueryResult result, String sql, String effectiveSchema) {
        String candidateSql = sql == null ? "" : sql;
        try {
            resultFilterState.showOriginal(result, candidateSql, effectiveSchema,
                    databaseFilterUnavailableReason(candidateSql, result));
        } catch (RuntimeException failure) {
            statusLabel.setText("无法显示新查询结果，仍显示当前结果：" + message(failure));
            statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
            return false;
        }
        lastQuerySql = candidateSql;
        renderResultFilterSnapshot();
        return true;
    }

    private void renderResultFilterSnapshot() {
        renderResultFilterSnapshot(refreshDatabaseFilterAvailability());
    }

    private ResultFilterState.Snapshot refreshDatabaseFilterAvailability() {
        ResultFilterState.DatabaseAvailabilityContext context =
                resultFilterState.databaseAvailabilityContext();
        QueryResult original = context.originalResult();
        if (original != null) {
            resultFilterState.setDatabaseUnavailableReason(
                    databaseFilterUnavailableReason(
                            context.originalSql(), original, context.conditions()));
        }
        return resultFilterState.snapshot();
    }

    private void renderResultFilterSnapshot(ResultFilterState.Snapshot snapshot) {
        resultStatusRevision++;
        resultRowIndexes.clear();
        QueryResult active = snapshot.activeResult();
        boolean rebuildColumns = active != displayedResult;
        List<TableColumn<ObservableList<Object>, ?>> sorting =
                rebuildColumns ? List.of() : new ArrayList<>(resultTable.getSortOrder());
        Map<TableColumn<ObservableList<Object>, ?>, TableColumn.SortType> sortTypes =
                new IdentityHashMap<>();
        for (var column : sorting) sortTypes.put(column, column.getSortType());
        if (rebuildColumns) resultTable.getColumns().clear();
        displayedResult = active;
        resultTable.getItems().clear();
        if (active == null || active.kind != QueryResult.Kind.QUERY) {
            exportResultBtn.setDisable(true);
            copyInsertBtn.setDisable(true);
            renderResultFilterToolbar(snapshot);
            return;
        }
        useTable();
        if (rebuildColumns) {
            resultTable.getColumns().add(buildSeqColumn());
            List<String> labels = orderedLabels(active);
            List<String> comments = active.columnComments;
            for (int i = 0; i < labels.size(); i++) {
                String name = labels.get(i);
                String comment = (comments != null && i < comments.size()) ? comments.get(i) : null;
                TableColumn<ObservableList<Object>, Object> col = buildQueryColumn(name, comment, i);
                col.setPrefWidth(estimateColumnWidth(name, active.rows, i));
                resultTable.getColumns().add(col);
            }
        }
        ObservableList<ObservableList<Object>> data = FXCollections.observableArrayList();
        for (int rowIndex : snapshot.visibleRowIndexes()) {
            if (rowIndex < 0 || rowIndex >= active.rows.size()) continue;
            ObservableList<Object> row = FXCollections.observableArrayList(active.rows.get(rowIndex));
            resultRowIndexes.put(row, rowIndex);
            data.add(row);
        }
        resultTable.setItems(data);
        if (!sorting.isEmpty()) {
            sortTypes.forEach(TableColumn::setSortType);
            resultTable.getSortOrder().setAll(sorting);
        }
        resultTable.sort();
        exportResultBtn.setDisable(active.rows.isEmpty());
        copyInsertBtn.setDisable(active.rows.isEmpty());
        renderResultFilterToolbar(snapshot);
    }

    private void renderResultFilterToolbar() {
        renderResultFilterToolbar(resultFilterState.snapshot());
    }

    private void renderResultFilterToolbar(ResultFilterState.Snapshot snapshot) {
        if (resultToolbar != null) resultToolbar.render(snapshot);
        if (resultColumnMenu != null) {
            QueryResult active = snapshot.activeResult();
            resultColumnMenu.refresh(active != null && active.kind == QueryResult.Kind.QUERY);
        }
    }

    private void clearResultFilterState() {
        resultStatusRevision++;
        resultRowIndexes.clear();
        resultFilterState.clearAll();
        lastQuerySql = null;
        displayedResult = null;
        renderResultFilterToolbar();
    }

    ResultExportSnapshot captureResultExportSnapshot() {
        if (!Platform.isFxApplicationThread())
            throw new IllegalStateException("Export capture requires FX thread");
        var before = resultFilterState.snapshot();
        QueryResult active = before.activeResult();
        if (active == null || active.kind != QueryResult.Kind.QUERY) return null;
        List<TableColumn<ObservableList<Object>, ?>> columns =
                new ArrayList<>(resultTable.getColumns());
        List<TableColumn<ObservableList<Object>, ?>> sorting =
                new ArrayList<>(resultTable.getSortOrder());
        Map<TableColumn<ObservableList<Object>, ?>, TableColumn.SortType> sortTypes =
                new IdentityHashMap<>();
        for (var column : sorting) sortTypes.put(column, column.getSortType());
        boolean flushed = resultToolbar.flushPendingSearch();
        var state = resultFilterState.snapshot();
        if (state.activeResult() != active)
            throw new IllegalStateException("Result changed during export capture");
        if (flushed) {
            resultTable.getColumns().setAll(columns);
            sortTypes.forEach(TableColumn::setSortType);
            resultTable.getSortOrder().setAll(sorting);
            resultTable.sort();
        }
        List<Integer> rowPositions = new ArrayList<>();
        for (var row : resultTable.getItems()) {
            Integer position = resultRowIndexes.get(row);
            if (position == null) throw new IllegalStateException("Missing result row identity");
            rowPositions.add(position);
        }
        List<ResultExportSnapshot.Column> projection = new ArrayList<>();
        for (var column : resultTable.getVisibleLeafColumns()) {
            if (column.getUserData() instanceof Integer position && position >= 0)
                projection.add(new ResultExportSnapshot.Column(position,
                        Objects.toString(column.getProperties().get("sql-result-label"), "")));
        }
        return ResultExportSnapshot.capture(active, state.originalSql(), rowPositions, projection);
    }

    private String databaseFilterUnavailableReason(String sql, QueryResult result) {
        return databaseFilterUnavailableReason(sql, result, List.of());
    }

    private String databaseFilterUnavailableReason(
            String sql, QueryResult result, List<FilterCondition> conditions) {
        ConnConfig connection = currentConn();
        SafeSelectEligibility.Result eligibility = SafeSelectEligibility.check(
                sql, connection != null && connection.type() == DbType.ORACLE, result);
        if (!eligibility.eligible()) return eligibility.reason();
        if (connection == null || connections == null) return "当前编辑器未绑定数据库连接";
        try {
            ResultFilterSqlRenderer renderer = connections.provider(connection.id())
                    .resultFilterSqlRenderer().orElse(null);
            if (renderer == null) return "当前数据库不支持结果筛选";
            return renderer.firstUnsupportedReason(result.resultColumns, conditions);
        } catch (RuntimeException unavailable) {
            return UNCONFIRMED_DATABASE_FILTER_CAPABILITY;
        }
    }

    private String formatResultRowCount(QueryResult result) {
        int count = result.rows.size();
        String formatted = String.format(Locale.ROOT, "%,d", count);
        return result.truncated ? formatted + "+，当前结果已截断" : formatted + " rows";
    }

    private static String schemaContext(String effectiveSchema) {
        return effectiveSchema == null ? "原查询默认 Schema" : "原查询 Schema: " + effectiveSchema;
    }

    private void exportAs(Format format) {
        if (resultExports != null) resultExports.export(format);
    }

    private void onCopyInsert() {
        if (resultExports != null) resultExports.copyInsert();
    }

    void setClipboardWriterForTesting(ClipboardWriter writer) {
        clipboardWriter = Objects.requireNonNull(writer, "writer");
    }

    private boolean writeClipboard(String text) {
        try {
            return clipboardWriter.write(text);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean writeSystemClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        return Clipboard.getSystemClipboard().setContent(content);
    }

    private void showClipboardWriteFailure() {
        statusLabel.setText("复制失败：无法写入系统剪贴板");
        statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
    }

    /** 行号列（序号）：显示 1..N，不参与排序，不映射数据。 */
    private TableColumn<ObservableList<Object>, Object> buildSeqColumn() {
        TableColumn<ObservableList<Object>, Object> seq = new TableColumn<>("#");
        seq.setSortable(false);
        seq.setResizable(false);
        seq.setPrefWidth(56);
        seq.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(String.valueOf(getIndex() + 1));
                    setStyle("-fx-alignment: CENTER_RIGHT; -fx-text-fill: -brand-fg-muted;");
                }
            }
        });
        return seq;
    }

    /** 构建带注释表头的查询列，表头展现方式由当前 {@link CommentMode} 决定。 */
    private TableColumn<ObservableList<Object>, Object> buildQueryColumn(
            String name, String comment, int idx) {
        TableColumn<ObservableList<Object>, Object> c = new TableColumn<>();
        c.setUserData(idx);
        c.getProperties().put("sql-result-label", name);
        c.setCellValueFactory(d -> new javafx.beans.property.SimpleObjectProperty<>(
                idx < d.getValue().size() ? d.getValue().get(idx) : null));
        c.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : ResultValueFormatter.format(item));
            }
        });
        applyColumnHeader(c, name, comment);
        return c;
    }

    /** Applies the comment-display preference to existing result columns without rebuilding their view state. */
    private void refreshResultColumnHeaders(QueryResult result) {
        if (result == null || result.kind != QueryResult.Kind.QUERY || resultTable == null) return;
        List<String> labels = orderedLabels(result);
        List<String> comments = result.columnComments;
        for (TableColumn<ObservableList<Object>, ?> column : resultTable.getColumns()) {
            if (!(column.getUserData() instanceof Integer index)
                    || index < 0 || index >= labels.size()) continue;
            String comment = comments != null && index < comments.size() ? comments.get(index) : null;
            applyColumnHeader(column, labels.get(index), comment);
        }
    }

    /** 根据当前注释显示模式设置列头（纯文本 / 悬停 Tooltip / 固定两行）。 */
    private void applyColumnHeader(TableColumn<?, ?> c, String name, String comment) {
        boolean hasComment = comment != null && !comment.isEmpty();
        CommentMode mode = settings.getCommentMode();
        if (!hasComment || mode == CommentMode.OFF) {
            c.setGraphic(null);
            c.setText(name);
            return;
        }
        if (mode == CommentMode.INLINE) {
            Label nameLabel = new Label(name);
            Label commentLabel = new Label(comment);
            commentLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 11px;");
            VBox box = new VBox(1, nameLabel, commentLabel);
            c.setText("");
            c.setGraphic(box);
        } else { // HOVER
            Label nameLabel = new Label(name);
            // 让标题 Label 撜满整个表头宽度，悬停表头任意处均可触发 Tooltip
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            nameLabel.prefWidthProperty().bind(c.widthProperty());
            Tooltip tip = new Tooltip(name + "\n" + comment);
            tip.setWrapText(true);
            tip.setMaxWidth(360);
            tip.setShowDelay(Duration.millis(300));
            nameLabel.setTooltip(tip);
            c.setText("");
            c.setGraphic(nameLabel);
        }
    }

    /** 估算列宽：取表头与前若干行内容的最大字符数，换算像素并裁剪到 [60, 360]。 */
    private static double estimateColumnWidth(String header, List<List<Object>> rows, int idx) {
        int maxLen = header == null ? 0 : header.length();
        int sample = Math.min(rows.size(), 100);
        for (int r = 0; r < sample; r++) {
            List<Object> row = rows.get(r);
            if (idx < row.size() && row.get(idx) != null) {
                int len = ResultValueFormatter.format(row.get(idx)).length();
                if (len > maxLen) maxLen = len;
            }
        }
        double px = maxLen * 8.0 + 24;
        return Math.max(60, Math.min(360, px));
    }

    private void setButtonsRunning(boolean isRunning) {
        var operation = sessionOperations.snapshot();
        boolean busy = isRunning || running || !operation.accepting() || operation.pending();
        boolean disabled = guidance().blocksExecution(busy);
        executeBtn.setDisable(disabled);
        explainBtn.setDisable(disabled);
        formatBtn.setDisable(busy);
        clearBtn.setDisable(busy);
        if (resultToolbar != null) resultToolbar.getNode().setDisable(busy);
    }

    // ---------- 自动补全：候选词 + 元数据预热 ----------

    /** 补全候选：SQL 关键字 + 已预热的元数据名称。 */
    private Collection<String> completionCandidates() {
        if (tasks.isClosed()) return List.of();
        List<String> all = new ArrayList<>(SQL_KEYWORDS.size() + metaNames.size());
        all.addAll(SQL_KEYWORDS);
        all.addAll(metaNames);
        return all;
    }

    /** 预热元数据名称（每连接一次）：绑定连接只预热它；未绑定时监听全局活动连接变化。 */
    private void installMetadataPrewarm() {
        if (recoveryPassive()) return;
        if (editorConnection != null) {
            prewarm(editorConnection);
            return;
        }
        session.activeConnectionProperty().addListener(activeConnectionListener);
        ConnConfig cur = session.getActiveConnection();
        if (cur != null) prewarm(cur);
    }

    /**
     * 后台加载 schema/表/视图名称并入库。best-effort：与连接树共享同一 JDBC 连接，
     * 若并发冲突或失败则静默跳过并允许下次重试，不影响关键字补全。
     */
    private void prewarm(ConnConfig cfg) {
        if (recoveryPassive()) return;
        if (tasks.isClosed() || cfg == null || cfg.type() == DbType.REDIS) return;
        final String connId = cfg.id();
        final String database = cfg.database();
        if (!prewarmed.add(connId)) return;
        metadataTasks.submit(() -> {
            List<String> schemas = new ArrayList<>();
            if (treeSvc.hasSchemaLevel(connId)) {
                for (SchemaInfo s : treeSvc.schemas(connId, database)) schemas.add(s.name());
            } else {
                schemas.add(null);
            }
            List<String> collected = new ArrayList<>();
            for (String schema : schemas) {
                if (schema != null) collected.add(schema);
                try {
                    for (TableInfo t : treeSvc.tables(connId, schema)) collected.add(t.name());
                    for (ViewInfo v : treeSvc.views(connId, schema)) collected.add(v.name());
                } catch (Exception ignore) {
                    // 单个 schema 读取失败不阻断其余
                }
                if (collected.size() > 5000) break;
            }
            return collected;
        }, metaNames::addAll, failure -> prewarmed.remove(connId));
    }

    // ---------- 列名成员补全（别名./表名. 上下文） ----------

    /**
     * 为限定符（别名或表名）提供列名候选：解析编辑器中 FROM/JOIN 的别名映射，
     * 折叠标识符大小写（Oracle→大写）后按 schema.table 命中列缓存；未命中则触发
     * 后台加载并先返回空，加载完成后回调 {@link SqlAutoComplete#refresh()}。
     */
    private Collection<String> membersFor(String qualifier) {
        if (recoveryPassive()) return List.of();
        if (tasks.isClosed()) return List.of();
        ConnConfig active = currentConn();
        if (active == null) return List.of();
        String connId = active.id();
        var dialect = connections.provider(connId).dialect();
        String table = resolveAlias(qualifier);
        if (table == null) table = qualifier; // 未命中别名则当作表名直接查
        String tableName = dialect.foldUnquotedIdentifier(table);
        if (tableName == null || tableName.isEmpty()) return List.of();

        String rawSchema = schemaField.getText().trim();
        String schema;
        if (!rawSchema.isEmpty()) {
            schema = dialect.foldUnquotedIdentifier(rawSchema);
        } else if (active.type() == DbType.ORACLE
                && active.username() != null && !active.username().isEmpty()) {
            // Oracle 默认 schema 即登录用户名
            schema = dialect.foldUnquotedIdentifier(active.username());
        } else {
            schema = null;
        }

        String key = (schema == null ? "" : schema + ".") + tableName;
        List<String> cached = columnCache.get(key);
        if (cached != null) return cached;
        loadColumnsAsync(connId, schema, tableName, key);
        return List.of();
    }

    /** 后台加载指定表的列名并入缓存，成功后触发补全刷新。 */
    private void loadColumnsAsync(String connId, String schema, String tableName, String key) {
        if (recoveryPassive()) return;
        if (tasks.isClosed()) return;
        if (!columnLoading.add(key)) return;
        metadataTasks.submit(() -> {
            List<String> cols = new ArrayList<>();
            for (ColumnInfo c : treeSvc.columns(connId, new TableRef(schema, tableName))) {
                cols.add(c.name());
            }
            return cols;
        }, cols -> {
            columnLoading.remove(key);
            if (!cols.isEmpty()) {
                columnCache.put(key, cols);
                if (autoComplete != null) autoComplete.refresh();
            }
        }, failure -> {
            // 读取失败静默：允许下次重试
            columnLoading.remove(key);
        });
    }

    /** 解析编辑器中 FROM/JOIN 的别名→表名映射，返回 qualifier 对应的表名（大小写不敏感）。 */
    private String resolveAlias(String qualifier) {
        if (qualifier == null || qualifier.isEmpty()) return null;
        Map<String, String> map = parseAliases(editorArea.getText());
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(qualifier)) return e.getValue();
        }
        return null;
    }

    /** 扫描 FROM 子句区域，构建 别名/表名 → 表名 映射。 */
    private Map<String, String> parseAliases(String sql) {
        Map<String, String> map = new HashMap<>();
        if (sql == null || sql.isBlank()) return map;
        Matcher m = FROM_REGION.matcher(sql);
        while (m.find()) {
            String region = m.group(1);
            if (region == null) continue;
            // 将 JOIN 关键字规整为逗号分隔的引用段，并剔除 ON 条件
            String normalized = region
                    .replaceAll("(?is)\\b(inner|left|right|full|outer|cross)\\b", " ")
                    .replaceAll("(?is)\\bjoin\\b", ",")
                    .replaceAll("(?is)\\bon\\b[^,]*", "");
            for (String seg : normalized.split(",")) {
                addRef(map, seg);
            }
        }
        return map;
    }

    private static final Pattern TABLE_TOKEN =
            Pattern.compile("[A-Za-z_][\\w$]*(\\.[A-Za-z_][\\w$]*)?");
    private static final Pattern IDENT_TOKEN =
            Pattern.compile("[A-Za-z_][\\w$]*");

    /** 解析单个引用段「表 [AS] 别名」，写入 alias→table 与 table→table。 */
    private void addRef(Map<String, String> map, String seg) {
        String s = seg.trim();
        if (s.isEmpty()) return;
        String[] parts = s.split("\\s+");
        if (parts.length == 0) return;
        String table = parts[0];
        if (!TABLE_TOKEN.matcher(table).matches()) return;
        String tableName = table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
        // 别名：表名之后的下一个标识符（可含 AS）
        if (parts.length >= 2) {
            String cand = ("AS".equalsIgnoreCase(parts[1]) && parts.length >= 3) ? parts[2] : parts[1];
            if (IDENT_TOKEN.matcher(cand).matches() && !KEYWORDS_UPPER.contains(cand.toUpperCase())) {
                map.put(cand, tableName);
            }
        }
        // 允许以表名本身作为限定符
        map.put(tableName, tableName);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---------- 注释切换（Ctrl+/ 行注释；Ctrl+Shift+/ 块注释） ----------

    /**
     * 行注释切换：选区跨越的整行（无选区取光标行）若非空行全部以 {@code --} 开头则去注释，
     * 否则每行行首加 {@code -- }。空行在添加时跳过，判定时忽略。
     */
    private void toggleLineComment() {
        if (draftEditingBlocked()) return;
        IndexRange sel = editorArea.getSelection();
        int startPar = editorArea.offsetToPosition(sel.getStart(), TwoDimensional.Bias.Forward).getMajor();
        int endPar = editorArea.offsetToPosition(sel.getEnd(), TwoDimensional.Bias.Backward).getMajor();
        // 选区跨行且末尾恰在行首时，末行不计入
        if (endPar > startPar
                && editorArea.offsetToPosition(sel.getEnd(), TwoDimensional.Bias.Forward).getMinor() == 0) {
            endPar--;
        }
        List<String> lines = new ArrayList<>();
        for (int p = startPar; p <= endPar; p++) lines.add(editorArea.getParagraph(p).getText());

        boolean allCommented = true;
        for (String ln : lines) {
            if (ln.trim().isEmpty()) continue;
            if (!ln.stripLeading().startsWith("--")) { allCommented = false; break; }
        }

        List<String> out = new ArrayList<>(lines.size());
        for (String ln : lines) {
            if (ln.trim().isEmpty()) { out.add(ln); continue; }
            if (allCommented) {
                int idx = ln.indexOf("--");
                String after = ln.substring(idx + 2);
                if (after.startsWith(" ")) after = after.substring(1);
                out.add(ln.substring(0, idx) + after);
            } else {
                out.add("-- " + ln);
            }
        }

        int repStart = editorArea.getAbsolutePosition(startPar, 0);
        int repEnd = editorArea.getAbsolutePosition(endPar, editorArea.getParagraph(endPar).length());
        String joined = String.join("\n", out);
        editorArea.replaceText(repStart, repEnd, joined);
        editorArea.selectRange(repStart, repStart + joined.length());
        applyHighlighting(editorArea.getText());
    }

    /**
     * 块注释切换：有选区且被 {@code /*}{@code *}{@code /} 包裹则去壳，否则包裹；
     * 无选区在光标处插入 {@code /*  *}{@code /} 并将光标置于中间。
     */
    private void toggleBlockComment() {
        if (draftEditingBlocked()) return;
        IndexRange sel = editorArea.getSelection();
        if (sel.getLength() > 0) {
            String text = editorArea.getSelectedText();
            String trimmed = text.strip();
            String rep;
            if (trimmed.length() >= 4 && trimmed.startsWith("/*") && trimmed.endsWith("*/")) {
                int s = text.indexOf("/*");
                int e = text.lastIndexOf("*/");
                String inner = text.substring(s + 2, e);
                if (inner.startsWith(" ")) inner = inner.substring(1);
                if (inner.endsWith(" ")) inner = inner.substring(0, inner.length() - 1);
                rep = text.substring(0, s) + inner + text.substring(e + 2);
            } else {
                rep = "/* " + text + " */";
            }
            int start = sel.getStart();
            editorArea.replaceText(start, sel.getEnd(), rep);
            editorArea.selectRange(start, start + rep.length());
        } else {
            int pos = editorArea.getCaretPosition();
            editorArea.replaceText(pos, pos, "/*  */");
            editorArea.moveTo(pos + 3);
        }
        applyHighlighting(editorArea.getText());
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private enum CloseDecision { CLOSE, COMMIT, ROLLBACK, CANCEL_ROLLBACK, CANCEL_CLOSE }

    private enum TransactionModeDecision { NONE, COMMIT, ROLLBACK, CANCEL }

    private record HistorySnapshot(String connectionName, String schema, String sql) {}

    private record ClosePlan(
            String connectionName,
            String schema,
            String sql,
            CloseDecision decision) {}

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
