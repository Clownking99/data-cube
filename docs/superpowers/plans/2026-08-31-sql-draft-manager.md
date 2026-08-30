# SQL Draft Manager Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make existing local SQL checkpoints discoverable and recoverable through the actual application, with offline connection intent and managed duplicate focus.

**Architecture:** One application-owned SqlDraftUi owns bindings and installed IDs. A disposable manager observes the coordinator's immutable management snapshots. Recovery uses the already-tested offline pane factory and the existing reserved managed-tab/abort protocol; it never uses history name lookup or unmanaged fallback.

**Tech Stack:** Existing Java25, JavaFX25, RichTextFX0.11.6, Gradle9.2, JUnit5.11.3; no new dependencies.

## Global Constraints

- Work only in `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`, branch `codex/sql-draft-recovery`; no main merge until entire P1 acceptance and broad review.
- Never read, modify, stage or delete `.testagent/` contents. Use isolated temporary fixtures and `.superpowers/sdd/` reports instead.
- No push, tag, release, installation, real database, real credentials, real history or telemetry access.
- SQL草稿仅保存于本机，可能含敏感文本；保留7天，可关闭或清空。关闭草稿不停止原有 SQL 历史记录。
- Recovery is offline: no provider, session, metadata or network access; no global connection selection change. Revalidate ID/type at explicit execution; retain immutable pinning afterward.
- All normal and restored live UUIDs select existing installed managed content. Publish only after installation; rejected close retains ownership, finalization/abort releases it. Never use openSingletonTab for recovery.
- No disk I/O or future joins on FX. Destructive confirmations default to Cancel. Partial results retain actual survivors and warnings; disabled protection does not hide records.
- Existing store bounds remain 1MiB SQL,100files,32MiB,7days. Raw untouched SQL/schema recovery must remain exact even if RichTextFX normalizes presentation.
- Existing reservation, mandatory rollback, early blocking abort binding and draft-first close guards remain intact. No per-dialog executor or shutdown of the application writer on dialog close.

---

### Task 1: Ship managed SQL draft discovery and recovery

**Files:**
- Modify: `src/com/datacube/config/SqlDraftCoordinator.java` (owner-checked busy accessor only).
- Modify: `src/com/datacube/fx/SqlDraftUi.java` (binding/install registry and observers).
- Modify: `src/com/datacube/fx/SqlDraftEditorBinding.java` (ID accessor).
- Modify: `src/com/datacube/fx/ContentTabPane.java` (selection-only operation).
- Modify: `src/com/datacube/fx/SqlEditorPane.java` (explicit recovery chooser attachment).
- Modify: `src/com/datacube/fx/AppShell.java` (toolbar, normal installed publication, recovery factory).
- Create: `src/com/datacube/fx/SqlDraftRecoveryTabs.java` (reserved recovery installation).
- Create: `src/com/datacube/fx/SqlDraftManagerPane.java` (disposable manager content).
- Create: `src/com/datacube/fx/SqlDraftManagerDialog.java` (modal ownership/theme/observer lifetime).
- Create: `src/com/datacube/fx/SqlDraftConnectionChooser.java` (safe display-only choices).
- Create: `test/com/datacube/config/DraftManagementProbe.java` (controlled coordinator backend, no real profile).
- Create: `test/com/datacube/fx/SqlDraftManagerTest.java` (actual controls, management failure and disposal).
- Create: `test/com/datacube/fx/SqlDraftRecoveryTabsTest.java` (actual reserved tabs, offline counters and lifetime).

**Interfaces:**
- Consumes existing `SqlDraftCoordinator.attach(UUID,Long,Source)`, `lastManagementResult()`, `mode()`, management futures and `shutdown()`; `SqlEditorPane.recoverDraft(...)`, `bindDraft(...)`, `chooseRecoveryConnection(ConnConfig)` and existing close phases.
- Consumes `ConnectionTreePane.connectionConfigsSnapshot():List<ConnConfig>` (in-memory only), `ContentTabPane.openManagedTab(String,ManagedTabFactory):Tab` and `ManagedTabFactorySequence.create(...)`.
- Produces `SqlDraftCoordinator.managementPending():boolean`; `SqlDraftEditorBinding.id():UUID`.
- Produces owner `runtime()`, `bind(pane,draft)`, `installed(Node)`, `installedContent(UUID):Node`, `observe(Runnable):AutoCloseable`; existing normal `bind(pane)` remains.
- Produces `SqlDraftRecoveryTabs(ContentTabPane,SqlDraftUi,Function<SqlDraft,SqlEditorPane>,Consumer<SqlEditorPane>)`, `restore(SqlDraft):boolean`.
- Produces manager `SqlDraftManagerPane(SqlDraftCoordinator,Function<SqlDraft,Boolean>,Runnable)`, `getNode():Parent`, `refreshView()`, `close()`.
- Produces `SqlDraftManagerDialog.show(SqlDraftUi,Window,ThemeManager,Function<SqlDraft,Boolean>)` and `SqlEditorPane.installRecoveryConnectionChooser(Supplier<List<ConnConfig>>)`. All UI methods are FX-owned.

- [ ] **Step 1: Add the complete behavioral tests in the test-code appendix.** Use compiling throwing stubs only for missing APIs, run RED, retain exact failure evidence before replacing stubs. Never accept a missing Scene/skin lookup as the desired feature failure. Tests use actual coordinator, controls and managed lifecycle, not source-string assertions. Root owns docs/verification while implementer owns only listed source/test files and its report.

- [ ] **Step 2: Run RED and notify controller before GREEN overwrites XML.**

```powershell
$draftPriorOptions=$env:JAVA_TOOL_OPTIONS
try {
  $env:JAVA_TOOL_OPTIONS="$draftPriorOptions -Djava.awt.headless=false".Trim()
  .\gradlew.bat test --tests '*SqlDraftManagerTest' --tests '*SqlDraftRecoveryTabsTest' --rerun-tasks --no-daemon --console=plain
  $draftExit=$LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS=$draftPriorOptions }
exit $draftExit
```

Expected: new behaviors fail on absent implementations; compile/fixture errors must be corrected and distinguished from behavioral RED. Record exact names, messages and counts, not a claimed count from an interrupted run.

- [ ] **Step 3: Implement the following complete production additions/edits.** Imports must remain explicit/readable; preserve existing surrounding code. Small corrections for compiler/API mismatch are allowed with rationale and synchronized report, never omit assertions or constraints.

`SqlDraftCoordinator` beside `lastManagementResult`:

```java
public boolean managementPending() { owner(); return busy; }
```

`SqlDraftEditorBinding` beside `getNode`:

```java
UUID id() { return handle.id(); }
```

`ContentTabPane` beside `openSingletonTab` (do not change singleton behavior):

```java
public boolean selectExistingContent(Node content) {
    for (Tab tab : tabPane.getTabs()) {
        if (tab.getContent() == content) {
            tabPane.getSelectionModel().select(tab);
            return true;
        }
    }
    return false;
}
```

`SqlDraftUi` retain existing writer/runtime/timer/shutdown. Add imports SqlDraft,Node,Map,LinkedHashMap. Add fields and methods below; replace normal bind with overload delegation. Timer calls observers after binding refresh. Shutdown FX block clears observers before runtime shutdown.

```java
private final Map<Node, SqlDraftEditorBinding> boundContent = new LinkedHashMap<>();
private final Map<UUID, Node> installedContent = new LinkedHashMap<>();
private final Set<Runnable> observers = new LinkedHashSet<>();

SqlDraftCoordinator runtime() { return runtime; }

void bind(SqlEditorPane pane) { bind(pane, null); }

void bind(SqlEditorPane pane, SqlDraft draft) {
    Node content = pane.getNode();
    SqlDraftEditorBinding binding = pane.bindDraft(runtime,
            draft == null ? UUID.randomUUID() : draft.id(),
            draft == null ? null : draft.modifiedAt(), removed -> {
                bindings.remove(removed);
                boundContent.remove(content, removed);
                installedContent.remove(removed.id(), content);
            });
    bindings.add(binding);
    boundContent.put(content, binding);
}

void installed(Node content) {
    SqlDraftEditorBinding binding = boundContent.get(content);
    if (binding == null) throw new IllegalStateException("Draft content is not bound");
    installedContent.put(binding.id(), content);
}

Node installedContent(UUID id) { return installedContent.get(id); }

AutoCloseable observe(Runnable observer) {
    observers.add(observer);
    return () -> observers.remove(observer);
}
```

Timer insertion:
```java
List.copyOf(observers).forEach(Runnable::run);
```
Shutdown insertion after `timer.stop()`:
```java
observers.clear();
```

New `SqlDraftRecoveryTabs.java`:

```java
package com.datacube.fx;

import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import java.util.function.Function;
import javafx.scene.Node;
import javafx.scene.control.Tab;

final class SqlDraftRecoveryTabs {
    private final ContentTabPane tabs;
    private final SqlDraftUi drafts;
    private final Function<SqlDraft, SqlEditorPane> factory;
    private final java.util.function.Consumer<SqlEditorPane> initialize;

    SqlDraftRecoveryTabs(ContentTabPane tabs, SqlDraftUi drafts,
            Function<SqlDraft, SqlEditorPane> factory, java.util.function.Consumer<SqlEditorPane> initialize) {
        this.tabs = tabs;
        this.drafts = drafts;
        this.factory = factory;
        this.initialize = initialize;
    }

    boolean restore(SqlDraft draft) {
        if (draft == null || drafts.runtime().managementPending()
                || drafts.runtime().mode() == SqlDraftCoordinator.Mode.CLOSED) return false;
        Node existing = drafts.installedContent(draft.id());
        if (existing != null) return tabs.selectExistingContent(existing);
        Tab opened = tabs.openManagedTab("SQL - 恢复草稿", abort -> ManagedTabFactorySequence.create(
                () -> factory.apply(draft),
                pane -> abort.bind(pane::closeResources),
                pane -> {
                    initialize.accept(pane);
                    drafts.bind(pane, draft);
                },
                pane -> new ContentTabPane.ManagedTabSpec(pane.getNode(), pane::requestClose,
                        pane::requestMandatoryClose, pane::finalizeCloseOnFx, pane::closeResources)));
        if (opened == null) return false;
        drafts.installed(opened.getContent());
        return true;
    }
}
```

New `SqlDraftConnectionChooser.java`:

```java
package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;

final class SqlDraftConnectionChooser {
    private SqlDraftConnectionChooser() { }

    record Choice(ConnConfig config) {
        @Override public String toString() {
            return SqlDraftManagerPane.preview(config.name(), 80) + " · " + config.type()
                    + " · " + SqlDraftManagerPane.preview(config.id(), 80);
        }
    }

    static List<Choice> choices(List<ConnConfig> configs) {
        return configs.stream().filter(config -> config != null && config.id() != null
                && !config.id().isBlank()
                && (config.type() == DbType.POSTGRESQL || config.type() == DbType.ORACLE))
                .map(Choice::new).toList();
    }

    static Optional<ConnConfig> show(List<ConnConfig> configs, Window owner) {
        ChoiceDialog<Choice> dialog = new ChoiceDialog<>(null, choices(configs));
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("选择草稿连接");
        dialog.setHeaderText("仅选择连接意图；执行时才连接数据库");
        dialog.setContentText("PostgreSQL / Oracle：");
        dialog.setSelectedItem(null);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(dialog.selectedItemProperty().isNull());
        return dialog.showAndWait().map(Choice::config);
    }
}
```

`SqlEditorPane`: add field `private Button recoveryConnectionButton;`. Add this method beside `chooseRecoveryConnection`. Add two lines at start of existing `renderConnectionGuidance` after its null guard to keep button disabled after pinning (closing handler additionally checks editing/admission directly).

```java
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
```

`renderConnectionGuidance` insertion:
```java
if (recoveryConnectionButton != null)
    recoveryConnectionButton.setDisable(!recoveryPassive() || draftEditingBlocked());
```

New `SqlDraftManagerPane.java`:

```java
package com.datacube.fx;

import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class SqlDraftManagerPane implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final SqlDraftCoordinator runtime;
    private final Function<SqlDraft, Boolean> restore;
    private final Runnable restored;
    private final VBox root = new VBox(8);
    private final ListView<SqlDraft> list = new ListView<>();
    private final TextArea sql = new TextArea();
    private final Label status = new Label();
    private final Label notice = new Label();
    private final Button recover = new Button("恢复");
    private final Button refresh = new Button("刷新");
    private final Button delete = new Button("删除所选");
    private final Button clear = new Button("清空草稿");
    private final Button toggle = new Button();
    private SqlDraftCoordinator.ManagementResult applied;
    private boolean initialRefresh = true, pending, closed, operationFailed;

    SqlDraftManagerPane(SqlDraftCoordinator runtime, Function<SqlDraft, Boolean> restore, Runnable restored) {
        this.runtime = runtime;
        this.restore = restore;
        this.restored = restored;
        list.setId("draft-manager-list");
        list.setPlaceholder(new Label("没有可恢复草稿"));
        list.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(SqlDraft draft, boolean empty) {
                super.updateItem(draft, empty);
                setText(empty || draft == null ? null : TIME.format(Instant.ofEpochMilli(draft.modifiedAt()))
                        + "  " + preview(draft.connectionName(), 80) + " · " + draft.connectionType()
                        + "\nSchema: " + preview(draft.schema(), 80) + "\n"
                        + (draft.sql().isEmpty() ? "空草稿" : preview(draft.sql(), 120)));
                setGraphic(null);
            }
        });
        sql.setId("draft-manager-sql");
        sql.setEditable(false);
        sql.setPromptText("选择草稿后预览完整 SQL；恢复不会自动连接数据库。");
        list.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            sql.setText(after == null ? "" : after.sql());
            renderControls();
        });
        status.setId("draft-manager-status");
        notice.setId("draft-manager-notice");
        notice.setWrapText(true);
        recover.setId("draft-manager-restore");
        refresh.setId("draft-manager-refresh");
        delete.setId("draft-manager-delete");
        clear.setId("draft-manager-clear");
        toggle.setId("draft-manager-toggle");
        recover.setOnAction(event -> {
            if (blocked() || list.getSelectionModel().getSelectedItem() == null) return;
            try {
                if (Boolean.TRUE.equals(restore.apply(list.getSelectionModel().getSelectedItem()))) restored.run();
                else notice.setText("恢复失败，现有草稿和标签保持不变。");
            } catch (RuntimeException failure) {
                notice.setText("恢复失败，现有草稿和标签保持不变。");
            }
        });
        refresh.setOnAction(event -> perform(runtime::refresh));
        delete.setOnAction(event -> {
            SqlDraft selected = list.getSelectionModel().getSelectedItem();
            if (!mutable() || selected == null) return;
            if (confirm("删除所选草稿", "仅删除本机这份恢复记录，不清空已打开的编辑器。"))
                perform(() -> runtime.delete(selected.id()));
        });
        clear.setOnAction(event -> {
            if (mutable() && confirm("清空草稿", "仅删除本机可恢复草稿，不清空编辑器；之后的新修改仍会保存。"))
                perform(runtime::clear);
        });
        toggle.setOnAction(event -> perform(() -> runtime.setEnabled(runtime.mode() != SqlDraftCoordinator.Mode.ENABLED)));
        Label privacy = new Label(SqlDraftEditorBinding.PRIVACY);
        privacy.setWrapText(true);
        SplitPane split = new SplitPane(list, sql);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);
        root.setPadding(new Insets(10));
        root.setPrefSize(860, 520);
        root.getChildren().addAll(status, new FlowPane(8, 4, recover, refresh, delete, clear, toggle),
                notice, split, privacy);
        refreshView();
    }

    Parent getNode() { return root; }

    static String preview(String text, int maximum) {
        if (text == null) return "";
        int length = Math.min(text.length(), maximum);
        StringBuilder value = new StringBuilder(length + 1);
        for (int index = 0; index < length; index++) {
            char c = text.charAt(index);
            value.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (text.length() > length) value.append('…');
        return value.toString();
    }

    void refreshView() {
        if (closed) return;
        SqlDraftCoordinator.ManagementResult current = runtime.lastManagementResult();
        if (current != applied && current != null) {
            applied = current;
            if (current.snapshot() != null) {
                SqlDraft selected = list.getSelectionModel().getSelectedItem();
                UUID selectedId = selected == null ? null : selected.id();
                list.getItems().setAll(current.snapshot().drafts().stream()
                        .sorted(Comparator.comparingLong(SqlDraft::modifiedAt).reversed()).toList());
                list.getSelectionModel().clearSelection();
                if (selectedId != null) list.getItems().stream().filter(item -> item.id().equals(selectedId))
                        .findFirst().ifPresent(list.getSelectionModel()::select);
            }
            operationFailed = !current.succeeded() || current.snapshot() == null;
        }
        renderControls();
        if (initialRefresh && !runtime.managementPending()
                && runtime.mode() != SqlDraftCoordinator.Mode.INITIALIZING) {
            initialRefresh = false;
            if (mutable()) perform(runtime::refresh);
        }
    }

    private boolean blocked() {
        return closed || pending || runtime.managementPending() || runtime.mode() == SqlDraftCoordinator.Mode.CLOSED;
    }

    private boolean mutable() {
        return !blocked() && runtime.mode() != SqlDraftCoordinator.Mode.UNAVAILABLE
                && runtime.mode() != SqlDraftCoordinator.Mode.INITIALIZING;
    }

    private void renderControls() {
        boolean blocked = blocked();
        boolean writable = mutable();
        recover.setDisable(blocked || list.getSelectionModel().getSelectedItem() == null);
        refresh.setDisable(!writable);
        delete.setDisable(!writable || list.getSelectionModel().getSelectedItem() == null);
        clear.setDisable(!writable);
        toggle.setDisable(!writable);
        toggle.setText(runtime.mode() == SqlDraftCoordinator.Mode.ENABLED ? "关闭草稿保护" : "开启草稿保护");
        String state = switch (runtime.mode()) {
            case INITIALIZING -> "草稿保护初始化中，尚未加载草稿";
            case ENABLED -> "草稿保护已开启";
            case DISABLED -> "草稿保护已关闭，已有草稿仍可恢复";
            case PAUSED -> "本次已暂停，设置未保存，下次启动可能恢复";
            case UNAVAILABLE -> "草稿保护不可用；仍可恢复已读取的草稿，请检查本地目录后重启";
            case CLOSED -> "草稿保护已停止";
        };
        status.setText(state + (pending || runtime.managementPending() ? " · 处理中" : "")
                + (applied == null || applied.snapshot() == null ? "" : " · 共 " + list.getItems().size() + " 份草稿"));
        boolean problems = applied != null && applied.snapshot() != null && !applied.snapshot().problems().isEmpty();
        if (operationFailed || problems)
            notice.setText("部分记录未能读取或清理，已保留可恢复内容及未知/损坏文件；请检查后重试。");
    }

    private void perform(Supplier<CompletableFuture<SqlDraftCoordinator.ManagementResult>> operation) {
        if (!mutable()) return;
        pending = true;
        operationFailed = false;
        notice.setText("");
        try {
            operation.get().whenComplete((result, failure) -> Platform.runLater(() -> {
                if (closed) return;
                pending = false;
                refreshView();
                operationFailed = failure != null || result == null || !result.succeeded() || result.snapshot() == null;
                renderControls();
            }));
        } catch (RuntimeException failure) {
            pending = false;
            operationFailed = true;
        }
        renderControls();
    }

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (root.getScene() != null && root.getScene().getWindow() != null)
            alert.initOwner(root.getScene().getWindow());
        alert.setTitle(title);
        alert.setHeaderText(message);
        ButtonType confirm = new ButtonType("确认删除", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(confirm, ButtonType.CANCEL);
        ((Button) alert.getDialogPane().lookupButton(confirm)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().filter(confirm::equals).isPresent();
    }

    @Override public void close() { closed = true; }
}
```

New `SqlDraftManagerDialog.java`:

```java
package com.datacube.fx;

import com.datacube.config.SqlDraft;
import java.util.function.Function;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

final class SqlDraftManagerDialog {
    private SqlDraftManagerDialog() { }

    static void show(SqlDraftUi owner, Window window, ThemeManager theme, Function<SqlDraft, Boolean> restore) {
        Dialog<Void> dialog = new Dialog<>();
        if (window != null) dialog.initOwner(window);
        dialog.setTitle("SQL 草稿");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        SqlDraftManagerPane pane = new SqlDraftManagerPane(owner.runtime(), restore, dialog::close);
        dialog.getDialogPane().setContent(pane.getNode());
        if (theme != null) theme.applyTo(dialog.getDialogPane());
        try (AutoCloseable subscription = owner.observe(pane::refreshView)) {
            dialog.showAndWait();
        } catch (Exception failure) {
            throw new IllegalStateException("SQL draft manager could not close", failure);
        } finally {
            pane.close();
        }
    }
}
```

`AppShell` toolbar: after history handler, add below, and include `draftsBtn` between `historyBtn` and `sep` in existing HBox constructor.

```java
Button draftsBtn = new Button("SQL 草稿");
draftsBtn.setId("sql-drafts");
draftsBtn.setOnAction(event -> openSqlDrafts());
```

`AppShell.openSqlTab`: change `contentTabs.openManagedTab(...)` to `javafx.scene.control.Tab opened = contentTabs.openManagedTab(...)`; after existing invocation insert:

```java
if (opened != null) sqlDrafts.get().installed(opened.getContent());
```

`AppShell` new method beside history:

```java
private void openSqlDrafts() {
    SqlDraftUi owner = sqlDrafts.get();
    SqlDraftRecoveryTabs recovery = new SqlDraftRecoveryTabs(contentTabs, owner,
            draft -> SqlEditorPane.recoverDraft(session, connMgr, treeSvc, settings,
                    treeActions::openTableDesigner, draft, sqlHistory, shortcuts, tasks),
            pane -> pane.installRecoveryConnectionChooser(connectionTree::connectionConfigsSnapshot));
    SqlDraftManagerDialog.show(owner, root.getScene() == null ? null : root.getScene().getWindow(),
            themeManager, draft -> recovery.restore(draft));
}
```

Chooser installation occurs in the initializer after early abort binding, not inside the factory before returning the pane. Only the four-argument constructor ships; it serves actual application initialization as well as the lifecycle tests.

- [ ] **Step 4: Run focused GREEN, then full non-headless regression once.** Same environment wrapper as RED; focused includes `*SqlDraftManagerTest`, `*SqlDraftRecoveryTabsTest`, `*SqlEditorDraftRecoveryTest`, `*SqlEditorDraftIntegrationTest`, `*SqlDraftUiTest`; full command removes all `--tests` filters. Expected exit0, no new skips. Baseline147suites1331tests1328passed0failures/errors3 existing live skips. Record actual XML totals and skip names, disclose existing unchecked compiler note.

- [ ] **Step 5: Self-review all requirements, commit exact listed source/test files, report.**

```powershell
git diff --check
git add src/com/datacube/config/SqlDraftCoordinator.java src/com/datacube/fx/SqlDraftUi.java src/com/datacube/fx/SqlDraftEditorBinding.java src/com/datacube/fx/ContentTabPane.java src/com/datacube/fx/SqlEditorPane.java src/com/datacube/fx/AppShell.java src/com/datacube/fx/SqlDraftRecoveryTabs.java src/com/datacube/fx/SqlDraftManagerPane.java src/com/datacube/fx/SqlDraftManagerDialog.java src/com/datacube/fx/SqlDraftConnectionChooser.java test/com/datacube/config/DraftManagementProbe.java test/com/datacube/fx/SqlDraftManagerTest.java test/com/datacube/fx/SqlDraftRecoveryTabsTest.java
git commit -m "feat: add managed offline SQL draft recovery UI"
```

Report `.superpowers/sdd/draft-manager-task-1-report.md` with RED/GREEN commands/output, actual XML counts, exact requirement-to-test matrix, files/commits and remaining concerns. Implementation is not task-complete until independent review; P1 restart/desktop/broad review/local merge remain after this task.

## Test-code appendix

The following test bodies and fixture code belong to Task1 and are included in its brief. No test-file scope exists outside the listed paths. Tests may use reflection for existing private lifecycle seams; never add production-only fault injection. Report fixture corrections without pretending they were product RED.

`test/com/datacube/config/DraftManagementProbe.java`:

```java
package com.datacube.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Controlled storage boundary for real coordinator/UI tests; never opens a profile. */
public final class DraftManagementProbe implements SqlDraftCoordinator.Backend {
    public final List<SqlDraft> records = new ArrayList<>();
    public final Queue<Runnable> writes = new ConcurrentLinkedQueue<>();
    public boolean enabled = true, writable = true, failPreference, partialClear;
    public int deletions, clears, prunes;

    public SqlDraftCoordinator create(Executor ui, BooleanSupplier isUi) {
        return new SqlDraftCoordinator(() -> this, writes::add, ui, isUi, () -> 0, () -> 100_000L);
    }
    public void drain() { Runnable work; while ((work = writes.poll()) != null) work.run(); }
    public void save(SqlDraft draft) { records.removeIf(item -> item.id().equals(draft.id())); records.add(draft); }
    public SqlDraftStore.Snapshot snapshot() {
        return new SqlDraftStore.Snapshot(records, partialClear
                ? List.of(new SqlDraftStore.Problem(null, SqlDraftStore.ProblemCode.CORRUPT_DRAFT))
                : List.of(), enabled, writable);
    }
    public void setEnabled(boolean value) throws IOException {
        if (failPreference) throw new IOException("synthetic preference failure");
        enabled = value;
    }
    public void clear() throws IOException {
        clears++;
        if (partialClear) {
            if (!records.isEmpty()) records.removeFirst();
            throw new IOException("synthetic partial deletion");
        }
        records.clear();
    }
    public void delete(UUID id) { deletions++; records.removeIf(item -> item.id().equals(id)); }
    public void prune(long now, Set<UUID> openIds) { prunes++; }
    public void close() { }
}
```

`test/com/datacube/fx/SqlDraftManagerTest.java`:

```java
package com.datacube.fx;

import com.datacube.config.DraftManagementProbe;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftManagerTest {
    static SqlDraft draft(long time, String sql) {
        return new SqlDraft(UUID.randomUUID(), time, "saved", DbType.POSTGRESQL, "Saved", "  schema  ", sql);
    }

    @Test void initializingDisablesRestoreAndRefreshesOnceWhenReady() throws Exception {
        try (Fixture f = new Fixture(false, true, true)) {
            f.fx(() -> {
                assertTrue(f.button("restore").isDisabled());
                assertTrue(f.label("status").getText().contains("初始化"));
            });
            f.ready();
            assertEquals(2, f.probe.prunes, "startup prune plus one manager refresh");
            f.fx(() -> { f.pane.refreshView(); f.pane.refreshView(); });
            assertEquals(2, f.probe.prunes);
        }
    }

    @Test void rowsAreNewestFirstAndFullSqlRequiresExplicitSelection() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                assertEquals(List.of(f.newer, f.older), f.list().getItems());
                assertNull(f.list().getSelectionModel().getSelectedItem());
                assertEquals("", f.sql().getText());
                assertFalse(f.sql().isEditable());
                f.list().getSelectionModel().select(f.newer);
                assertEquals(f.newer.sql(), f.sql().getText());
                assertFalse(f.button("restore").isDisabled());
            });
        }
    }

    @Test void emptyDraftRestoresAndFalseRestoreKeepsManagerOpen() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.older);
                assertEquals("", f.sql().getText());
                f.acceptRestore = false;
                f.button("restore").fire();
                assertEquals(1, f.restores.get());
                assertEquals(0, f.closed.get());
                assertTrue(f.label("notice").getText().contains("恢复失败"));
                f.acceptRestore = true;
                f.button("restore").fire();
                assertEquals(2, f.restores.get());
                assertEquals(1, f.closed.get());
            });
        }
    }

    @Test void disabledProtectionRetainsReadableRecoverableRecords() throws Exception {
        try (Fixture f = new Fixture(true, false, true)) {
            f.fx(() -> {
                assertTrue(f.label("status").getText().contains("已关闭"));
                assertEquals(2, f.list().getItems().size());
                f.list().getSelectionModel().selectFirst();
                f.button("restore").fire();
                assertEquals(1, f.closed.get());
            });
        }
    }

    @Test void unavailableStorageStillAllowsAlreadyReadRecordsToRestore() throws Exception {
        try (Fixture f = new Fixture(true, true, false)) {
            f.fx(() -> {
                assertTrue(f.label("status").getText().contains("不可用"));
                assertTrue(f.button("clear").isDisabled());
                assertTrue(f.button("toggle").isDisabled());
                f.list().getSelectionModel().selectFirst();
                assertFalse(f.button("restore").isDisabled());
                f.button("restore").fire();
                assertEquals(1, f.closed.get());
            });
        }
    }

    @Test void externalManagementBlocksRestoreAndControlsUntilSnapshotSettles() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.newer);
                f.runtime.refresh();
                assertTrue(f.runtime.managementPending());
                // Event must be guarded even before the timer disables its button.
                f.button("restore").fire();
                assertEquals(0, f.restores.get());
                f.pane.refreshView();
                for (String id : List.of("restore", "refresh", "clear", "delete", "toggle"))
                    assertTrue(f.button(id).isDisabled(), id);
            });
            f.settle();
            f.fx(() -> {
                assertFalse(f.runtime.managementPending());
                assertFalse(f.button("restore").isDisabled());
                assertEquals(f.newer.id(), f.list().getSelectionModel().getSelectedItem().id());
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"delete", "clear"})
    void destructiveCancelIsDefaultAndDoesNotMutate(String action) throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().selectFirst();
                respondToDialog(() -> f.button(action).fire(), dialog -> {
                    assertTrue(((Button) dialog.lookupButton(ButtonType.CANCEL)).isDefaultButton());
                    dialog.getButtonTypes().stream().filter(type -> type != ButtonType.CANCEL)
                            .forEach(type -> assertFalse(((Button) dialog.lookupButton(type)).isDefaultButton()));
                });
            });
            assertEquals(0, f.probe.clears);
            assertEquals(0, f.probe.deletions);
            assertEquals(2, f.probe.records.size());
        }
    }

    @Test void confirmedDeleteRemovesOnlySelectedRecord() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.newer);
                respondToDialog(() -> f.button("delete").fire(), SqlDraftManagerTest::confirmDialog);
            });
            f.settle();
            f.fx(() -> assertEquals(List.of(f.older), f.list().getItems()));
            assertEquals(1, f.probe.deletions);
            assertEquals(0, f.probe.clears);
        }
    }

    @Test void partialClearShowsActualSurvivorAndWarning() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.probe.partialClear = true;
            f.fx(() -> respondToDialog(() -> f.button("clear").fire(), SqlDraftManagerTest::confirmDialog));
            f.settle();
            f.fx(() -> {
                assertEquals(List.of(f.newer), f.list().getItems());
                assertTrue(f.label("notice").getText().contains("部分"));
            });
            assertEquals(1, f.probe.clears);
        }
    }

    @Test void successfulClearEmptiesOnlyRecoveryList() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.newer);
                respondToDialog(() -> f.button("clear").fire(), SqlDraftManagerTest::confirmDialog);
            });
            f.settle();
            f.fx(() -> {
                assertTrue(f.list().getItems().isEmpty());
                assertEquals("", f.sql().getText());
                assertTrue(f.button("restore").isDisabled());
                assertTrue(f.label("status").getText().contains("共 0 份"));
            });
            assertEquals(1, f.probe.clears);
            assertEquals(0, f.probe.deletions);
        }
    }

    @Test void explicitDisableThenEnableUpdatesPreferenceAndRetainsRecords() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> f.button("toggle").fire());
            f.settle();
            assertFalse(f.probe.enabled);
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.Mode.DISABLED, f.runtime.mode());
                assertEquals(2, f.list().getItems().size());
                f.button("toggle").fire();
            });
            f.settle();
            assertTrue(f.probe.enabled);
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
                assertEquals(2, f.list().getItems().size());
            });
        }
    }

    @Test void failedDisableSaysPausedNotPersistedDisabled() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.probe.failPreference = true;
            f.fx(() -> f.button("toggle").fire());
            f.settle();
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.Mode.PAUSED, f.runtime.mode());
                assertTrue(f.label("status").getText().contains("设置未保存"));
                assertFalse(f.label("status").getText().contains("已关闭"));
                assertEquals(2, f.list().getItems().size());
            });
        }
    }

    @Test void closedViewIgnoresLateManagementCompletion() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            String before = f.call(() -> {
                f.button("refresh").fire();
                f.pane.close();
                return f.label("status").getText();
            });
            f.probe.records.clear();
            f.settle();
            f.fx(() -> {
                assertEquals(2, f.list().getItems().size());
                assertEquals(before, f.label("status").getText());
            });
        }
    }

    @Test void previewAndChoiceLabelsAreBoundedAndDoNotRenderCredentialFields() {
        assertEquals("a b c…", SqlDraftManagerPane.preview("a\nb\tc" + "x".repeat(1_048_576), 5));
        ConnConfig pg = new ConnConfig("id", "Name", DbType.POSTGRESQL,
                "SECRET_HOST", 99, "SECRET_DB", "SECRET_USER", "SECRET_PASSWORD", Map.of("secret", "SECRET_PROP"));
        ConnConfig redis = new ConnConfig("r", "Redis", DbType.REDIS, "host", 1, "db", "u", "p", Map.of());
        var choices = SqlDraftConnectionChooser.choices(List.of(pg, redis));
        assertEquals(1, choices.size());
        assertEquals("Name · POSTGRESQL · id", choices.getFirst().toString());
        assertFalse(choices.toString().contains("SECRET"));
    }

    static void confirmDialog(DialogPane dialog) {
        ButtonType confirm = dialog.getButtonTypes().stream().filter(type -> type != ButtonType.CANCEL).findFirst().orElseThrow();
        ((Button) dialog.lookupButton(confirm)).fire();
    }

    /** FX nested event loop, with unconditional Cancel cleanup even when an assertion fails. */
    static void respondToDialog(Runnable open, Consumer<DialogPane> response) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            DialogPane dialog = null;
            try {
                dialog = Window.getWindows().stream().filter(Window::isShowing)
                        .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance)
                        .map(DialogPane.class::cast).findFirst().orElseThrow();
                response.accept(dialog);
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                if (dialog != null && dialog.getScene().getWindow().isShowing()) {
                    var cancel = dialog.lookupButton(ButtonType.CANCEL);
                    if (cancel == null) cancel = dialog.lookupButton(ButtonType.CLOSE);
                    if (cancel instanceof Button button) button.fire();
                    else dialog.getScene().getWindow().hide();
                }
            }
        });
        open.run();
        if (failure.get() != null) throw new AssertionError("Dialog assertion failed", failure.get());
    }

    private static final class Fixture implements AutoCloseable {
        final DraftManagementProbe probe = new DraftManagementProbe();
        final SqlDraft older = draft(90_000L, "");
        final SqlDraft newer = draft(100_000L, "select 1;\r\n-- raw\n");
        final AtomicInteger restores = new AtomicInteger(), closed = new AtomicInteger();
        boolean acceptRestore = true;
        final SqlDraftCoordinator runtime;
        final SqlDraftManagerPane pane;

        Fixture(boolean ready, boolean enabled, boolean writable) throws Exception {
            probe.enabled = enabled;
            probe.writable = writable;
            probe.records.addAll(List.of(older, newer));
            runtime = call(() -> probe.create(Platform::runLater, Platform::isFxApplicationThread));
            pane = call(() -> {
                SqlDraftManagerPane created = new SqlDraftManagerPane(runtime, draft -> {
                    restores.incrementAndGet();
                    return acceptRestore;
                }, closed::incrementAndGet);
                new Scene(created.getNode());
                created.getNode().applyCss();
                created.getNode().layout();
                return created;
            });
            if (ready) ready();
        }
        void ready() throws Exception { settle(); settle(); }
        void settle() throws Exception {
            probe.drain();
            fx(pane::refreshView);
            fx(() -> {});
        }
        @SuppressWarnings("unchecked") ListView<SqlDraft> list() {
            return (ListView<SqlDraft>) pane.getNode().lookup("#draft-manager-list");
        }
        Button button(String id) { return (Button) pane.getNode().lookup("#draft-manager-" + id); }
        Label label(String id) { return (Label) pane.getNode().lookup("#draft-manager-" + id); }
        TextArea sql() { return (TextArea) pane.getNode().lookup("#draft-manager-sql"); }
        <T> T call(Callable<T> work) throws Exception { return FxUiTestSupport.call(work); }
        void fx(Runnable work) throws Exception { call(() -> { work.run(); return null; }); }
        public void close() throws Exception {
            fx(pane::close);
            var close = call(runtime::shutdown);
            probe.drain();
            close.get(5, TimeUnit.SECONDS);
        }
    }
}
```

`test/com/datacube/fx/SqlDraftRecoveryTabsTest.java`:

```java
package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftRecoveryTabsTest {
    @TempDir Path directory;

    @Test void recoveredDuplicateFocusesInstalledTabWithoutSecondFactoryOrConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                Node expected = f.owner.installedContent(f.draft.id());
                f.tabs.openTab("other", new Label("other"));
                assertTrue(f.recovery.restore(f.draft));
                assertSame(expected, f.tabPane().getSelectionModel().getSelectedItem().getContent());
                assertEquals(2, f.tabPane().getTabs().size());
                assertEquals(1, f.created.size());
                assertNull(f.context.getActiveConnection());
            });
            f.offline();
        }
    }

    @Test void normallyOpenedAutosavedDraftAlsoFocusesExistingTab() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlEditorPane normal = f.call(() -> {
                AtomicReference<SqlEditorPane> pane = new AtomicReference<>();
                Tab tab = f.tabs.openManagedTab("normal", abort -> ManagedTabFactorySequence.create(
                        () -> {
                            SqlEditorPane created = new SqlEditorPane(f.context, f.probe.manager,
                                    new ObjectTreeService(f.probe.manager), f.settings, null, null, null,
                                    f.history, f.shortcuts, f.runner);
                            pane.set(created);
                            f.created.add(created);
                            return created;
                        }, value -> abort.bind(value::closeResources), value -> {
                            value.setSqlText("normal checkpoint");
                            f.owner.bind(value);
                        }, value -> new ContentTabPane.ManagedTabSpec(value.getNode(), value::requestClose,
                                value::requestMandatoryClose, value::finalizeCloseOnFx, value::closeResources)));
                assertNotNull(tab);
                f.owner.installed(tab.getContent());
                return pane.get();
            });
            f.flush(normal);
            var records = f.call(() -> f.owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot().drafts();
            f.fx(() -> {
                assertEquals(1, records.size());
                assertEquals("normal checkpoint", records.getFirst().sql());
                assertTrue(f.recovery.restore(records.getFirst()));
                assertEquals(1, f.created.size());
                assertEquals(1, f.tabPane().getTabs().size());
                assertSame(normal.getNode(), f.tabPane().getSelectionModel().getSelectedItem().getContent());
            });
            f.offline();
        }
    }

    @Test void refusedCloseRetainsMappingAndSuccessfulFinalizationRemovesIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                ((TextField) fieldUnchecked(f.created.getFirst(), "schemaField")).setText("\ud800");
            });
            assertEquals(TabCloseOutcome.CANCELLED,
                    f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNotNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.recovery.restore(f.draft));
                assertEquals(1, f.created.size());
                ((TextField) fieldUnchecked(f.created.getFirst(), "schemaField")).setText("valid");
            });
            assertEquals(TabCloseOutcome.COMPLETED,
                    f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.tabPane().getTabs().isEmpty());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner, "boundContent")).size());
            });
            f.offline();
        }
    }

    @Test void staleMappedContentFailsWithoutUnmanagedFallbackOrSecondHandle() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                Tab tab = f.tabPane().getTabs().getFirst();
                Node content = tab.getContent();
                try {
                    tab.setContent(new Label("temporarily different content"));
                    assertFalse(f.recovery.restore(f.draft));
                    assertEquals(1, f.created.size());
                    assertEquals(1, f.tabPane().getTabs().size());
                    assertFalse(f.tabs.selectExistingContent(new Label("absent")));
                    assertEquals(1, f.tabPane().getTabs().size());
                } finally { tab.setContent(content); }
            });
        }
    }

    @Test void refusedInstallationAbortsBoundPaneAndReleasesDraftSubscription() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<CompletionStage<TabCloseOutcome>> closing = new AtomicReference<>();
            SqlDraftRecoveryTabs recovery = f.call(() -> new SqlDraftRecoveryTabs(f.tabs, f.owner, draft -> {
                SqlEditorPane pane = f.create(draft);
                closing.set(f.tabs.closeAllManagedTabsMandatory());
                return pane;
            }, ignored -> {}));
            assertFalse(f.call(() -> recovery.restore(f.draft)));
            assertEquals(TabCloseOutcome.COMPLETED, closing.get().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.tabPane().getTabs().isEmpty());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner, "boundContent")).size());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner.runtime(), "handles")).size());
            });
            f.offline();
        }
    }

    @Test void initializerFailureHasEarlyAbortOwnershipAndNoInstalledMapping() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlDraftRecoveryTabs recovery = f.call(() -> new SqlDraftRecoveryTabs(f.tabs, f.owner,
                    f::create, ignored -> { throw new IllegalStateException("synthetic initializer failure"); }));
            assertFalse(f.call(() -> recovery.restore(f.draft)));
            assertEquals(TabCloseOutcome.COMPLETED,
                    f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.tabPane().getTabs().isEmpty());
                assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                        fieldUnchecked(f.created.getFirst(), "resourcesClosed")).get());
            });
            f.offline();
        }
    }

    @Test void explicitChooserIsUnselectedSafeAndChangesOnlyRecoveryIntent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                f.tabPane().applyCss();
                f.tabPane().layout();
                SqlEditorPane pane = f.created.getFirst();
                Button choose = (Button) pane.getNode().lookup("#sql-draft-connection");
                assertNotNull(choose);
                SqlDraftManagerTest.respondToDialog(choose::fire, dialog -> {
                    ComboBox<?> combo = (ComboBox<?>) dialog.lookup(".combo-box");
                    assertNull(combo.getSelectionModel().getSelectedItem());
                    assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                    assertFalse(combo.getItems().toString().contains("SECRET"));
                    combo.getSelectionModel().selectFirst();
                    ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                assertNull(f.context.getActiveConnection());
                SqlDraftRecoveryIntent intent = (SqlDraftRecoveryIntent) fieldUnchecked(pane, "recoveryIntent");
                assertEquals("replacement", intent.connectionId());
                assertEquals(DbType.ORACLE, intent.connectionType());
                assertNull(((SqlEditorConnectionAdmission) fieldUnchecked(pane, "admission")).pinned());
            });
            f.flush(f.created.getFirst());
            var snapshot = f.call(() -> f.owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot();
            assertEquals(f.draft.sql(), snapshot.drafts().getFirst().sql(), "connection-only edit preserves raw CRLF/CR");
            f.offline();
        }
    }

    @Test void dialogUnsubscribesWithoutStoppingApplicationWriter() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> SqlDraftManagerTest.respondToDialog(
                    () -> SqlDraftManagerDialog.show(f.owner, null, null, ignored -> false), dialog -> {
                        assertEquals(1, ((java.util.Set<?>) fieldUnchecked(f.owner, "observers")).size());
                    }));
            f.fx(() -> {
                assertEquals(0, ((java.util.Set<?>) fieldUnchecked(f.owner, "observers")).size());
                assertNotEquals(SqlDraftCoordinator.Mode.CLOSED, f.owner.runtime().mode());
            });
            f.ready();
            assertTrue(f.call(() -> f.recovery.restore(f.draft)));
            f.fx(() -> f.created.getFirst().setSqlText("still writable"));
            f.flush(f.created.getFirst());
            assertEquals("still writable", f.call(() -> f.owner.runtime().refresh())
                    .get(5, TimeUnit.SECONDS).snapshot().drafts().getFirst().sql());
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final List<SqlEditorPane> created = new ArrayList<>();
        final AppSettings settings = new AppSettings(directory.resolve("settings"));
        final SqlHistoryStore history = new SqlHistoryStore(directory.resolve("history"));
        final ShortcutSettings shortcuts = new ShortcutSettings(directory.resolve("shortcuts"));
        final ConnConfig replacement = new ConnConfig("replacement", "Oracle", DbType.ORACLE,
                "SECRET_HOST", 1, "SECRET_DB", "SECRET_USER", "SECRET_PASSWORD", Map.of());
        final SqlDraft draft = new SqlDraft(UUID.randomUUID(), System.currentTimeMillis(), "missing",
                DbType.POSTGRESQL, "Saved", "  schema  ", "select a.\r\nfrom t a;\r-- raw");
        final ContentTabPane tabs;
        final SqlDraftUi owner;
        final SqlDraftRecoveryTabs recovery;

        Fixture() throws Exception {
            probe.manager.register(replacement);
            tabs = call(ContentTabPane::new);
            owner = call(() -> new SqlDraftUi(directory.resolve("drafts")));
            recovery = call(() -> {
                new Scene((TabPane) tabs.getNode(), 1000, 700);
                return new SqlDraftRecoveryTabs(tabs, owner, this::create,
                        pane -> pane.installRecoveryConnectionChooser(() -> List.of(replacement)));
            });
            ready();
        }
        SqlEditorPane create(SqlDraft record) {
            SqlEditorPane pane = SqlEditorPane.recoverDraft(context, probe.manager,
                    new ObjectTreeService(probe.manager), settings, null, record, history, shortcuts, runner);
            created.add(pane);
            return pane;
        }
        TabPane tabPane() { return (TabPane) tabs.getNode(); }
        void ready() throws Exception {
            CountDownLatch ready = new CountDownLatch(1);
            AutoCloseable observer = call(() -> {
                Runnable check = () -> {
                    if (!owner.runtime().managementPending()) ready.countDown();
                };
                var registered = owner.observe(check);
                check.run();
                return registered;
            });
            try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
            finally { call(() -> { observer.close(); return null; }); }
        }
        void flush(SqlEditorPane pane) throws Exception {
            var future = call(() -> {
                SqlDraftEditorBinding binding = (SqlDraftEditorBinding) fieldUnchecked(pane, "draftBinding");
                return ((SqlDraftCoordinator.Handle) fieldUnchecked(binding, "handle")).flush();
            });
            future.get(5, TimeUnit.SECONDS);
            fx(() -> {});
        }
        void offline() {
            assertEquals(0, probe.providers.get(), "provider resolution");
            assertEquals(0, probe.sessions.get(), "session construction");
            assertEquals(0, probe.metadata.get(), "metadata access");
            assertEquals(0, probe.network.get(), "network access");
        }
        <T> T call(Callable<T> work) throws Exception { return FxUiTestSupport.call(work); }
        void fx(Runnable work) throws Exception { call(() -> { work.run(); return null; }); }
        public void close() throws Exception {
            try {
                for (SqlEditorPane pane : created) {
                    pane.closeResources();
                    fx(pane::finalizeCloseOnFx);
                }
            } finally {
                try { owner.closeFromBackground(); }
                finally { runner.close(); probe.manager.closeAll(); }
            }
        }
    }

    private static Object fieldUnchecked(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
```

## Controller self-review

Bindings and ownership match the runtime contract: normal and restored publication, no fallback, early abort and no dialog-owned writer. Manager state and actual control tests cover initializing, disabled, unavailable, pending, partial deletion, cancelled confirmation, failed preference, late completion, and restore refusal. Existing offline tests continue to cover identity deletion/type change and explicit admission; this task adds actual chooser/managed installation and connection-only raw CR/CRLF preservation. P1 restart/desktop/broad review remain separately gated. No placeholder test steps or unbound new API names remain.
