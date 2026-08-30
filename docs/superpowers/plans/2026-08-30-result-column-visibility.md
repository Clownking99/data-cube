# Result Column Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给现有查询结果提供可发现、可恢复的列显示控制，并使本地筛选与导出保留同一列视图。

**Architecture:** 小型 `SqlResultColumnMenu` 封装列菜单，工具栏仅安放节点，编辑器根据活动结果身份决定是否重建列。不改变导出器和数据库执行路径。

**Tech Stack:** Java 25、JavaFX 25、JUnit Jupiter、Gradle wrapper 9.2.0。

## Global Constraints

- Worktree: `D:/Projects/朝花夕拾/.worktrees/release-acceptance`; branch `codex/release-acceptance`; feature baseline `241f5f9`.
- `.testagent/` 不读取、不修改、不暂存、不清理；测试仅用合成结果与独占临时目录。
- 不新增数据库请求、持久化、剪贴板操作、依赖或生产测试注入接口；不推送、打 tag、安装或发布。
- 序号列不参与选择或计数；最后一列不可隐藏；“显示全部列”不改变顺序、宽度、排序、筛选或值。
- 同一活动结果的本地筛选保留列对象与排序，新活动结果重置列；旧菜单操作不能作用于新结果。
- 两种导出范围仅包含当前可见数据列；用户已授权自主产品决策，不再逐项确认。

## Task 1: Implement and verify column controls

**Files:**
- Create: `src/com/datacube/fx/SqlResultColumnMenu.java`
- Modify: `src/com/datacube/fx/SqlResultToolbar.java`
- Modify: `src/com/datacube/fx/SqlEditorPane.java`
- Test: `test/com/datacube/fx/SqlEditorResultFilterContractTest.java`

**Interfaces:**
- Consumes existing `TableView<ObservableList<Object>>`, integer column `userData`, `sql-result-label`, `ResultExportSnapshot` and `QueryResultFileWriter.write`.
- Produces package-private `SqlResultColumnMenu(TableView<ObservableList<Object>> table)`, `MenuButton getNode()`, `void refresh(boolean available)`.
- Produces package-private `SqlResultToolbar(Actions actions, MenuButton columnMenu)`; existing public constructor delegates with null.
- Root owns design/plan/release documentation and desktop acceptance; implementer owns only four source/test files and its scratch report.

- [x] Step 1: Extend the existing FX contract suite with the helpers and concrete tests below, using existing `PaneFixture`, `showQuery`, `resultTable`, `invoke`, and `awaitFxDelay` helpers. No new general test harness. Three tests had pre-implementation RED; the fourth debounce/condition test was added afterward. This process deviation is retained in the [verification record](../verification/2026-08-30-result-column-visibility.md), not retroactively described as TDD.

```java
private static javafx.scene.control.MenuButton columnMenu(SqlEditorPane pane) {
    var menu = (javafx.scene.control.MenuButton) pane.getNode().lookup("#sql-result-columns");
    org.junit.jupiter.api.Assertions.assertNotNull(menu, "result column menu must be visible in the toolbar");
    return menu;
}
private static javafx.scene.control.MenuItem columnItem(SqlEditorPane pane, String id) {
    return columnMenu(pane).getItems().stream().filter(item -> id.equals(item.getId()))
            .findFirst().orElseThrow();
}

@Test void columnMenuGuardsLastColumnAndRestoresAllWithoutChangingRows() throws Exception {
    try (PaneFixture fixture = new PaneFixture(null, null)) {
        FxUiTestSupport.call(() -> {
            assertTrue(columnMenu(fixture.pane).isDisabled());
            showQuery(fixture.pane, QueryResult.query(List.of("same_name", "same_name", "last"),
                    List.of(List.of("A", "B", "C")), 1), "select demo");
            var table = resultTable(fixture.pane);
            var items = table.getItems();
            var columns = List.copyOf(table.getColumns());
            assertEquals("列（3/3）", columnMenu(fixture.pane).getText());
            assertFalse(columnItem(fixture.pane, "sql-result-column-0").isMnemonicParsing());
            assertNotEquals(columnItem(fixture.pane, "sql-result-column-0").getText(),
                    columnItem(fixture.pane, "sql-result-column-1").getText());
            columnItem(fixture.pane, "sql-result-column-0").fire();
            columnItem(fixture.pane, "sql-result-column-1").fire();
            assertEquals("列（1/3）", columnMenu(fixture.pane).getText());
            assertTrue(columnItem(fixture.pane, "sql-result-column-2").isDisable());
            columnItem(fixture.pane, "sql-result-column-2").fire();
            assertTrue(columns.get(3).isVisible());
            assertTrue(columns.get(0).isVisible());
            assertEquals(List.of("last"), fixture.pane.captureResultExportSnapshot().columns());
            columnItem(fixture.pane, "sql-result-columns-show-all").fire();
            assertEquals("列（3/3）", columnMenu(fixture.pane).getText());
            assertTrue(columns.stream().allMatch(javafx.scene.control.TableColumn::isVisible));
            assertSame(items, table.getItems());
            assertEquals(List.of(List.of("A", "B", "C")), table.getItems());
            return null;
        });
    }
}

@Test void columnMenuPersistsThroughSearchAndExportsBothScopes() throws Exception {
    try (PaneFixture fixture = new PaneFixture(null, null)) {
        ResultExportSnapshot snapshot = FxUiTestSupport.call(() -> {
            showQuery(fixture.pane, QueryResult.query(List.of("name", "score", "hidden"),
                    List.of(List.of("Ada", 1, "secret1"), List.of("Ada", 3, "secret2"),
                            List.of("Bob", 9, "secret3")), 1), "select demo");
            var table = resultTable(fixture.pane);
            var seq = table.getColumns().get(0);
            var name = table.getColumns().get(1);
            var score = table.getColumns().get(2);
            var hidden = table.getColumns().get(3);
            table.getColumns().setAll(List.of(seq, score, name, hidden));
            score.setPrefWidth(211);
            score.setSortType(javafx.scene.control.TableColumn.SortType.DESCENDING);
            table.getSortOrder().setAll(List.of(score));
            table.sort();
            columnItem(fixture.pane, "sql-result-column-2").fire();
            var search = (javafx.scene.control.TextField) fixture.pane.getNode().lookup("#sql-result-search");
            search.setText("Ada");
            search.fireEvent(new javafx.event.ActionEvent());
            assertEquals(List.of(seq, score, name, hidden), table.getColumns());
            assertFalse(hidden.isVisible());
            assertEquals(211, score.getPrefWidth());
            assertEquals(List.of(score), table.getSortOrder());
            assertEquals(List.of(3, 1), table.getItems().stream().map(row -> row.get(1)).toList());
            return fixture.pane.captureResultExportSnapshot();
        });
        Path current = directory.resolve("visible-current.csv");
        Path all = directory.resolve("visible-all.csv");
        com.datacube.export.QueryResultFileWriter.write(current,
                com.datacube.export.QueryResultFileWriter.Format.CSV, snapshot,
                ResultExportScope.CURRENT_FILTERED, false, null, new com.datacube.export.ResultExportOperation());
        com.datacube.export.QueryResultFileWriter.write(all,
                com.datacube.export.QueryResultFileWriter.Format.CSV, snapshot,
                ResultExportScope.ALL_LOADED, false, null, new com.datacube.export.ResultExportOperation());
        assertEquals(List.of("score,name", "3,Ada", "1,Ada"), Files.readAllLines(current));
        assertEquals(List.of("score,name", "1,Ada", "3,Ada", "9,Bob"), Files.readAllLines(all));
        FxUiTestSupport.call(() -> {
            ((javafx.scene.control.Button) fixture.pane.getNode().lookup("#sql-result-clear-filter")).fire();
            assertEquals("列（2/3）", columnMenu(fixture.pane).getText());
            assertEquals(List.of(9, 3, 1), resultTable(fixture.pane).getItems().stream().map(row -> row.get(1)).toList());
            return null;
        });
    }
}

@Test void columnMenuResetsForNewResultAndIgnoresOldItems() throws Exception {
    try (PaneFixture fixture = new PaneFixture(null, null)) {
        FxUiTestSupport.call(() -> {
            showQuery(fixture.pane, QueryResult.query(List.of("a", "b"), List.of(List.of(1, 2)), 1), "select old");
            var old = columnItem(fixture.pane, "sql-result-column-0");
            old.fire();
            showQuery(fixture.pane, QueryResult.query(List.of("a", "b"), List.of(), 1), "select new");
            old.fire();
            assertFalse(columnMenu(fixture.pane).isDisabled());
            assertEquals("列（2/2）", columnMenu(fixture.pane).getText());
            assertEquals(List.of("a", "b"), fixture.pane.captureResultExportSnapshot().columns());
            invoke(fixture.pane, "showError", new Class<?>[]{String.class, long.class}, "synthetic", 1L);
            assertTrue(columnMenu(fixture.pane).isDisabled());
            return null;
        });
    }
}
```

Also exercise actual debounce completion and condition add/remove using the existing suite's synthetic helpers, asserting hidden state, column ordering and sorting after those transitions. Keep test code in this same contract file; no separate test-generation fan-out or `.testagent` artifacts. Report the exact test names and assertions in a Requirement | Evidence table.

- [x] Step 2: Run the narrow class before implementation; missing menu must fail an assertion, not fail compilation. Command: `gradlew.bat test --tests com.datacube.fx.SqlEditorResultFilterContractTest --no-daemon --console=plain`, with process-scoped `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` restored in `finally`. Recorded RED: 32 tests, 3 missing-menu assertion failures; see Step 1 deviation.

- [x] Step 3: Implement the menu with this starting code; adjust only if compile or behavior evidence requires it.

```java
package com.datacube.fx;

import java.util.List;
import java.util.Objects;
import javafx.collections.ObservableList;
import javafx.scene.control.*;

final class SqlResultColumnMenu {
    private final TableView<ObservableList<Object>> table;
    private final MenuButton menu = new MenuButton("列（0/0）");
    private boolean available;

    SqlResultColumnMenu(TableView<ObservableList<Object>> table) {
        this.table = Objects.requireNonNull(table);
        menu.setId("sql-result-columns");
        menu.setAccessibleText("显示或隐藏结果列");
        menu.setTooltip(new Tooltip("仅调整当前结果的可见列；导出仅包含可见列。至少保留一列。"));
        menu.setOnShowing(event -> rebuild());
        refresh(false);
    }

    MenuButton getNode() { return menu; }

    void refresh(boolean available) {
        menu.hide();
        this.available = available;
        rebuild();
    }

    private List<TableColumn<ObservableList<Object>, ?>> columns() {
        return table.getColumns().stream()
                .filter(column -> column.getUserData() instanceof Integer position && position >= 0)
                .toList();
    }

    private void rebuild() {
        menu.getItems().clear();
        if (available) {
            for (var column : columns()) {
                int position = (Integer) column.getUserData();
                String label = Objects.toString(column.getProperties().get("sql-result-label"), column.getText());
                CheckMenuItem item = new CheckMenuItem((position + 1) + " · " + label);
                item.setMnemonicParsing(false);
                item.setId("sql-result-column-" + position);
                item.setUserData(column);
                item.setOnAction(event -> {
                    if (!available || !columns().contains(column)) return;
                    long visible = columns().stream().filter(TableColumn::isVisible).count();
                    if (!column.isVisible() || visible > 1) column.setVisible(!column.isVisible());
                    updateState();
                });
                menu.getItems().add(item);
            }
            MenuItem all = new MenuItem("显示全部列");
            all.setId("sql-result-columns-show-all");
            List<TableColumn<ObservableList<Object>, ?>> captured = columns();
            all.setOnAction(event -> {
                if (!available || !columns().equals(captured)) return;
                captured.forEach(column -> column.setVisible(true));
                updateState();
            });
            menu.getItems().addAll(new SeparatorMenuItem(), all);
        }
        updateState();
    }

    private void updateState() {
        var current = available ? columns() : List.<TableColumn<ObservableList<Object>, ?>>of();
        long visible = current.stream().filter(TableColumn::isVisible).count();
        menu.setText("列（" + visible + "/" + current.size() + "）");
        menu.setDisable(current.isEmpty());
        for (var item : menu.getItems()) {
            if (item instanceof CheckMenuItem check && item.getUserData() instanceof TableColumn<?, ?> column) {
                check.setSelected(column.isVisible());
                check.setDisable(column.isVisible() && visible <= 1);
            } else if ("sql-result-columns-show-all".equals(item.getId())) {
                item.setDisable(visible == current.size());
            }
        }
    }
}
```

- [x] Step 4: Integrate with exact existing boundaries, without moving unrelated code. Implementation additionally retains/reapplies sort columns and sort types around item replacement, as required by the regression evidence; the sketch below is not the final implementation.

`SqlResultToolbar`: retain public constructor and add package-private overload; change `composeLayout()` to accept the optional MenuButton and insert it into `actionsRow` immediately before Copy.

```java
public SqlResultToolbar(Actions actions) { this(actions, null); }
SqlResultToolbar(Actions actions, MenuButton columnMenu) {
    this.actions = Objects.requireNonNull(actions, "actions");
    configureControls();
    composeLayout(columnMenu);
}
// In composeLayout(MenuButton columnMenu), after constructing actionsRow:
if (columnMenu != null) actionsRow.getChildren().add(3, columnMenu);
```

`SqlEditorPane`: add fields, instantiate the helper immediately after creating resultTable, and pass its node as second toolbar-constructor argument.

```java
private SqlResultColumnMenu resultColumnMenu;
private QueryResult displayedResult;
// In resultContainer(), after resultTable = new TableView<>():
resultColumnMenu = new SqlResultColumnMenu(resultTable);
// End of new SqlResultToolbar(new SqlResultToolbar.Actions(...)) expression:
// this::copyResultSelection), resultColumnMenu.getNode());
```

In `renderResultFilterSnapshot(snapshot)`, replace unconditional column clear with identity-based rebuild, wrap existing sequence/data-column construction in `if (rebuildColumns)`, and reapply table sorting after setting the new row items:

```java
QueryResult active = snapshot.activeResult();
boolean rebuildColumns = active != displayedResult;
if (rebuildColumns) resultTable.getColumns().clear();
displayedResult = active;
resultTable.getItems().clear();
// Existing non-query branch retained.
// Existing buildSeqColumn / buildQueryColumn loop only runs if rebuildColumns.
// After resultTable.setItems(data):
resultTable.sort();
```

Keep existing export capture behavior; its flush path can still restore the same columns. Set `displayedResult = null` in `clearResultFilterState`. Extend toolbar render to refresh the helper with query availability, including non-query clearing paths:

```java
if (resultColumnMenu != null) {
    QueryResult active = snapshot.activeResult();
    resultColumnMenu.refresh(active != null && active.kind == QueryResult.Kind.QUERY);
}
```

- [x] Step 5: Run focused class and toolbar suite until GREEN. Actual timer, add/remove condition, clear, newer-result reset, stale-item no-op and both CSV files covered. Full nonheadless forced regression: 1212 passed, 3 named live skips, 0 failures/errors; independently checked XML.
- [x] Step 6: Self-review and commit only the four owned source/test files (`719685b`). RED/GREEN commands, full run, exact behavior mapping and deviations recorded; independent task review Approved after truthful process-evidence correction.

## Controller closeout

整分支审查修复：`fade258` 将既有 commentModeListener 改为刷新当前列表头，不走身份保留的行渲染路径。新增 `commentModeChangesRefreshExistingHeadersWithoutResettingColumnView` 在修复前失败、修复后通过；验证 OFF→INLINE→HOVER→OFF 与列对象/顺序/隐藏/211px宽度/排序保留。主代理后续完整强制回归1213 passed、3 live skipped、0 failures/errors。此项补充设计遗漏的既有表现设置兼容，不扩展新功能范围。

- [x] Inspect actual diff and XML; independent task review complete; historical TDD deviation retained, no current code defect found.
- [x] Supported computer-use synthetic UI: hiding, last-column guard, Show All, search preservation, CSV summary, compact/light layout, new-result reset and normal exit observed. No desktop file saved; denied Excel path not retried.
- [x] Update candidate notes and acceptance records, preserving historical failed/blocked attempts.
- [ ] Complete broad branch review, locally fast-forward main if clean/ancestry permits, then run merged full regression. Release gates remain open; no automatic push/tag.
