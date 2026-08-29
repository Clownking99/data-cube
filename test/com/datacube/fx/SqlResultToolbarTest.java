package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.ResultFilterState;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlResultToolbarTest {
    private static final List<ResultColumn> COLUMNS = List.of(
            new ResultColumn(0, "NAME", Types.VARCHAR, "VARCHAR"),
            new ResultColumn(1, "SCORE", Types.INTEGER, "INTEGER"),
            new ResultColumn(2, "CREATED_AT", Types.TIMESTAMP, "TIMESTAMP"),
            new ResultColumn(3, "ACTIVE", Types.BOOLEAN, "BOOLEAN"),
            new ResultColumn(4, "NOTE", Types.VARCHAR, "VARCHAR"),
            new ResultColumn(5, "ID", Types.BIGINT, "BIGINT"));

    @Test
    void compactToolbarReflectsLocalPreviewAndDoesNotApplyDatabaseImplicitly() throws Exception {
        AtomicInteger searchRequests = new AtomicInteger();
        AtomicInteger databaseRequests = new AtomicInteger();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(searchRequests, databaseRequests);
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                    "ada", List.of(condition(0, FilterConnector.AND, FilterOperator.CONTAINS, "Ada")),
                    12, null, null));

            Parent root = toolbar.getNode();
            assertEquals("本地预览：显示 12 / 186 行 · 6 列",
                    ((Label) root.lookup("#sql-result-summary")).getText());
            assertEquals("ada", ((TextField) root.lookup("#sql-result-search")).getText());
            assertEquals(0, searchRequests.get(), "render must not replay search callbacks");
            assertEquals(0, databaseRequests.get());
            assertFalse(root.lookup("#sql-result-apply-database").isDisabled());
            assertNull(((Button) root.lookup("#sql-result-apply-database")).getTooltip());
            return null;
        });
    }

    @Test
    void stateMappingKeepsLabelsAndDisabledReasonsInSync() throws Exception {
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(new AtomicInteger(), new AtomicInteger());
            Parent root = toolbar.getNode();

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            Button apply = (Button) root.lookup("#sql-result-apply-database");
            assertTrue(apply.isDisabled());
            assertEquals("请先添加筛选条件", apply.getTooltip().getText());
            assertEquals("应用数据库筛选", apply.getAccessibleText());
            assertEquals("请先添加筛选条件", apply.getAccessibleHelp());
            assertEquals("原始结果：186 行 · 6 列",
                    ((Label) root.lookup("#sql-result-summary")).getText());
            assertTrue(root.lookup("#sql-result-clear-filter").isDisabled());

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                    "", List.of(condition(1, FilterConnector.AND, FilterOperator.GT, 60)),
                    12, "当前 SQL 不能安全包装", null));
            assertTrue(apply.isDisabled());
            assertEquals("当前 SQL 不能安全包装", apply.getTooltip().getText());
            assertEquals("当前 SQL 不能安全包装", apply.getAccessibleHelp());
            assertFalse(root.lookup("#sql-result-clear-filter").isDisabled());

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.APPLIED,
                    "", List.of(condition(1, FilterConnector.AND, FilterOperator.GT, 60)),
                    72, null, null));
            assertEquals("重新应用", apply.getText());
            assertEquals("重新应用数据库筛选", apply.getAccessibleText());
            assertNull(apply.getAccessibleHelp());
            assertEquals("数据库筛选已应用：显示 72 / 186 行 · 6 列",
                    ((Label) root.lookup("#sql-result-summary")).getText());

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.DIRTY_AFTER_APPLY,
                    "Ada", List.of(condition(1, FilterConnector.AND, FilterOperator.GT, 60)),
                    4, null, "数据库筛选失败，仍显示当前结果"));
            assertEquals("重新应用", apply.getText());
            assertEquals("本地预览 / 有未应用更改：显示 4 / 186 行 · 6 列 · 数据库筛选失败，仍显示当前结果",
                    ((Label) root.lookup("#sql-result-summary")).getText());
            return null;
        });
    }

    @Test
    void buttonsMenusAndConditionChipsDispatchOnlyTheirExplicitCallbacks() throws Exception {
        AtomicInteger add = new AtomicInteger();
        AtomicInteger remove = new AtomicInteger(-1);
        AtomicInteger apply = new AtomicInteger();
        AtomicInteger clear = new AtomicInteger();
        AtomicReference<SqlResultToolbar.CopyMode> copy = new AtomicReference<>();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = new SqlResultToolbar(new SqlResultToolbar.Actions(
                    ignored -> {}, add::incrementAndGet, remove::set,
                    apply::incrementAndGet, clear::incrementAndGet, copy::set));
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW, "",
                    List.of(condition(0, FilterConnector.AND, FilterOperator.EQ, "Ada"),
                            condition(1, FilterConnector.OR, FilterOperator.GTE, 90)),
                    7, null, null));
            Parent root = toolbar.getNode();

            ((Button) root.lookup("#sql-result-add-filter")).fire();
            ((Button) root.lookup("#sql-result-filter-remove-1")).fire();
            ((Button) root.lookup("#sql-result-apply-database")).fire();
            ((Button) root.lookup("#sql-result-clear-filter")).fire();
            assertEquals(1, add.get());
            assertEquals(1, remove.get());
            assertEquals(1, apply.get());
            assertEquals(1, clear.get());

            MenuButton menu = (MenuButton) root.lookup("#sql-result-copy");
            assertEquals(List.of("当前单元格", "选中区域", "选中行", "选中行（含表头）"),
                    menu.getItems().stream().map(item -> item.getText()).toList());
            for (int index = 0; index < menu.getItems().size(); index++) {
                menu.getItems().get(index).fire();
                assertEquals(SqlResultToolbar.CopyMode.values()[index], copy.get());
            }

            Button chip = (Button) root.lookup("#sql-result-filter-remove-1");
            assertTrue(chip.getText().contains("OR"));
            assertTrue(chip.getText().contains("SCORE"));
            assertTrue(chip.getAccessibleText().contains("删除筛选条件"));
            assertEquals("搜索当前结果", root.lookup("#sql-result-search").getAccessibleText());
            assertEquals("添加筛选条件", root.lookup("#sql-result-add-filter").getAccessibleText());
            assertEquals("应用数据库筛选", root.lookup("#sql-result-apply-database").getAccessibleText());
            assertEquals("复制查询结果", root.lookup("#sql-result-copy").getAccessibleText());
            assertEquals("清除结果筛选", root.lookup("#sql-result-clear-filter").getAccessibleText());
            assertEquals(1, apply.get(), "non-database actions must never apply a database filter");
            return null;
        });
    }

    @Test
    void searchUsesAResettableLocalDebounceWithoutDatabaseExecution() throws Exception {
        AtomicReference<String> search = new AtomicReference<>();
        AtomicInteger searchCount = new AtomicInteger();
        AtomicInteger database = new AtomicInteger();
        CountDownLatch searched = new CountDownLatch(1);
        AtomicReference<SqlResultToolbar> toolbar = new AtomicReference<>();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar created = new SqlResultToolbar(new SqlResultToolbar.Actions(
                    text -> {
                        search.set(text);
                        searchCount.incrementAndGet();
                        searched.countDown();
                    }, () -> {}, ignored -> {}, database::incrementAndGet,
                    () -> {}, ignored -> {}));
            created.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            toolbar.set(created);
            return null;
        });
        FxUiTestSupport.call(() -> {
            TextField field = (TextField) toolbar.get().getNode().lookup("#sql-result-search");
            field.setText("a");
            field.setText("ada");
            assertNull(search.get(), "search must not run synchronously for every keystroke");
            assertEquals(0, database.get());
            return null;
        });
        assertTrue(searched.await(2, TimeUnit.SECONDS), "debounced search timed out");
        FxUiTestSupport.call(() -> null); // drain every FX event queued before this barrier
        assertEquals("ada", search.get());
        assertEquals(1, searchCount.get());
        assertEquals(0, database.get());
    }

    @Test
    void conditionDialogRestrictsOperatorsValidatesValuesAndForcesFirstConnectorToAnd() throws Exception {
        FxUiTestSupport.call(() -> {
            AtomicReference<List<FilterOperator>> allowed = new AtomicReference<>();
            AtomicReference<String> invalidMessage = new AtomicReference<>();
            AtomicReference<Boolean> invalidDisabled = new AtomicReference<>();
            AtomicReference<Boolean> nullValueDisabled = new AtomicReference<>();
            AtomicReference<String> invalidAccessibleText = new AtomicReference<>();
            AtomicReference<String> clearedAccessibleText = new AtomicReference<>();
            AtomicReference<Boolean> connectorLabelPresent = new AtomicReference<>();
            AtomicReference<Boolean> connectorLabelVisible = new AtomicReference<>();
            AtomicReference<Boolean> connectorLabelManaged = new AtomicReference<>();
            AtomicReference<Throwable> interactionFailure = new AtomicReference<>();

            Platform.runLater(() -> {
                try {
                    DialogPane pane = showingDialogPane();
                    @SuppressWarnings("unchecked")
                    ComboBox<ResultColumn> column = (ComboBox<ResultColumn>) pane.lookup("#filter-condition-column");
                    @SuppressWarnings("unchecked")
                    ComboBox<FilterOperator> operator = (ComboBox<FilterOperator>) pane.lookup("#filter-condition-operator");
                    TextField value = (TextField) pane.lookup("#filter-condition-value");
                    Label error = (Label) pane.lookup("#filter-condition-error");
                    Node connectorLabel = pane.lookup("#filter-condition-connector-label");
                    ButtonBase ok = (ButtonBase) pane.lookupButton(pane.getButtonTypes().get(0));

                    connectorLabelPresent.set(connectorLabel != null);
                    connectorLabelVisible.set(connectorLabel != null && connectorLabel.isVisible());
                    connectorLabelManaged.set(connectorLabel != null && connectorLabel.isManaged());

                    column.getSelectionModel().select(1);
                    allowed.set(List.copyOf(operator.getItems()));
                    value.setText("not-a-number");
                    invalidMessage.set(error.getText());
                    invalidAccessibleText.set(error.getAccessibleText());
                    invalidDisabled.set(ok.isDisabled());
                    operator.setValue(FilterOperator.IS_NULL);
                    nullValueDisabled.set(value.isDisabled());
                    clearedAccessibleText.set(error.getAccessibleText());
                    ok.fire();
                } catch (Throwable failure) {
                    interactionFailure.set(failure);
                    closeShowingDialog();
                }
            });

            Optional<FilterCondition> result = FilterConditionDialog.show(
                    null, COLUMNS, 0, FilterConnector.OR);
            if (interactionFailure.get() != null) throw new AssertionError(interactionFailure.get());
            assertEquals(FilterOperator.allowedFor(COLUMNS.get(1)), allowed.get());
            assertTrue(invalidMessage.get().matches(".*[\\u4e00-\\u9fff].*"));
            assertEquals(invalidMessage.get(), invalidAccessibleText.get());
            assertTrue(invalidDisabled.get());
            assertTrue(nullValueDisabled.get());
            assertEquals("", clearedAccessibleText.get());
            assertTrue(connectorLabelPresent.get());
            assertFalse(connectorLabelVisible.get());
            assertFalse(connectorLabelManaged.get());
            assertTrue(result.isPresent());
            assertEquals(new FilterCondition(1, FilterConnector.AND,
                    FilterOperator.IS_NULL, null), result.get());
            return null;
        });
    }

    private static SqlResultToolbar toolbar(
            AtomicInteger searchRequests, AtomicInteger databaseRequests) {
        return new SqlResultToolbar(new SqlResultToolbar.Actions(
                ignored -> searchRequests.incrementAndGet(), () -> {}, ignored -> {},
                databaseRequests::incrementAndGet, () -> {}, ignored -> {}));
    }

    private static FilterCondition condition(
            int columnIndex, FilterConnector connector, FilterOperator operator, Object value) {
        return new FilterCondition(columnIndex, connector, operator, value);
    }

    private static ResultFilterState.Snapshot snapshot(
            ResultFilterState.DatabaseStatus status, String search,
            List<FilterCondition> conditions, int visibleRows,
            String unavailableReason, String error) {
        QueryResult result = result(186);
        List<Integer> visible = new ArrayList<>(visibleRows);
        for (int index = 0; index < visibleRows; index++) visible.add(index);
        return new ResultFilterState.Snapshot(result, result, "select * from people",
                search, conditions, visible, status, unavailableReason, error);
    }

    private static QueryResult result(int rowCount) {
        List<List<Object>> rows = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            rows.add(List.of("name-" + index, index, "2026-08-29 10:00:00",
                    true, "note", (long) index));
        }
        return QueryResult.queryWithMetadata(COLUMNS, rows, 37, false);
    }

    private static DialogPane showingDialogPane() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .map(Window::getScene)
                .filter(scene -> scene != null)
                .map(scene -> scene.getRoot())
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("condition dialog is not showing"));
    }

    private static void closeShowingDialog() {
        Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> window.getScene() != null)
                .map(window -> window.getScene().getRoot())
                .filter(DialogPane.class::isInstance)
                .map(Node::getScene)
                .map(scene -> scene.getWindow())
                .forEach(Window::hide);
    }
}
