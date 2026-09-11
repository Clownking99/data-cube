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
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlResultToolbarLayoutTest {
    private static final List<String> ACTION_IDS = List.of("sql-result-add-filter",
            "sql-result-apply-database", "sql-result-columns", "sql-result-copy", "sql-result-clear-filter", "sql-result-view-cell");

    @ParameterizedTest
    @CsvSource({"880, dark", "640, dark", "480, dark", "880, light", "640, light", "480, light"})
    void resultActionsKeepFullLabelsAndWrapWithoutDispatching(double width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            List<String> events = new ArrayList<>();
            SqlResultToolbar toolbar = toolbar(events);
            Parent root = toolbar.getNode();
            Scene scene = new Scene(root, width, 400);
            scene.getStylesheets().addAll(
                    ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            // Rendering, resizing and changing the Apply label must never issue a query.
            for (var status : List.of(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                    ResultFilterState.DatabaseStatus.APPLIED)) {
                toolbar.render(snapshot(status));
                layout(root, width);
                assertReadableActions(root);
                Label summary = (Label) root.lookup("#sql-result-summary");
                assertTrue(summary.isWrapText(), "long result summaries must wrap");
                assertTrue(summary.getHeight() + 1 >= summary.prefHeight(summary.getWidth()));
                assertInside(root, summary);
                Button chip = (Button) root.lookup("#sql-result-filter-remove-0");
                assertInside(root, chip);
                assertTrue(chip.isWrapText(), "long conditions must remain readable");
                assertTrue(chip.getHeight() + 1 >= chip.prefHeight(chip.getWidth()));
            }
            assertTrue(events.isEmpty(), "presentation changes must not dispatch any actions");
            return null;
        });
    }

    @Test
    void actionsAndColumnMenuStillWorkAfterNarrowingAndWidening() throws Exception {
        FxUiTestSupport.call(() -> {
            List<String> events = new ArrayList<>();
            SqlResultToolbar toolbar = toolbar(events);
            Parent root = toolbar.getNode();
            new Scene(root, 880, 400);
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW));
            for (double width : List.of(880.0, 480.0, 880.0)) {
                layout(root, width);
                assertReadableActions(root);
            }
            assertTrue(events.isEmpty());
            ((Button) root.lookup("#sql-result-add-filter")).fire();
            ((TextField) root.lookup("#sql-result-search")).setText("current search");
            ((Button) root.lookup("#sql-result-apply-database")).fire();
            for (var item : ((MenuButton) root.lookup("#sql-result-copy")).getItems()) item.fire();
            ((Button) root.lookup("#sql-result-filter-remove-0")).fire();
            ((Button) root.lookup("#sql-result-clear-filter")).fire();
            assertEquals(List.of("add", "search:current search", "apply", "copy:CURRENT_CELL",
                    "copy:SELECTION", "copy:SELECTED_ROWS", "copy:SELECTED_ROWS_WITH_HEADERS",
                    "remove:0", "clear"), events);

            MenuButton columns = (MenuButton) root.lookup("#sql-result-columns");
            columns.getItems().getFirst().fire();
            assertEquals("列（1/2）", columns.getText(), "column visibility still updates independently");
            assertEquals(9, events.size(), "column changes must not query or copy data");
            return null;
        });
    }

    private static void layout(Parent root, double width) {
        root.resize(width, 400);
        root.applyCss();
        root.layout();
    }

    private static void assertReadableActions(Parent root) {
        List<Node> controls = new ArrayList<>();
        TextField search = (TextField) root.lookup("#sql-result-search");
        assertTrue(search.getWidth() >= 200, "search must retain a usable input width");
        controls.add(search);
        for (String id : ACTION_IDS) {
            Labeled action = (Labeled) root.lookup("#" + id);
            assertNotNull(action, id);
            assertTrue(action.isVisible() && action.isManaged(), id);
            assertTrue(action.getWidth() + 1 >= action.prefWidth(-1), id + " label is truncated");
            controls.add(action);
        }
        for (Node control : controls) assertInside(root, control);
        for (int i = 0; i < controls.size(); i++) {
            for (int j = i + 1; j < controls.size(); j++) {
                assertFalse(bounds(controls.get(i)).intersects(bounds(controls.get(j))),
                        "result controls must not overlap");
            }
        }
    }

    private static void assertInside(Parent root, Node child) {
        Bounds area = bounds(root);
        Bounds actual = bounds(child);
        assertTrue(actual.getMinX() >= area.getMinX() - 1 && actual.getMaxX() <= area.getMaxX() + 1
                        && actual.getMinY() >= area.getMinY() - 1 && actual.getMaxY() <= area.getMaxY() + 1,
                child.getId() + " must stay inside the toolbar");
    }

    private static Bounds bounds(Node node) { return node.localToScene(node.getLayoutBounds()); }

    private static SqlResultToolbar toolbar(List<String> events) {
        TableView<ObservableList<Object>> table = new TableView<>();
        for (int i = 0; i < 2; i++) {
            TableColumn<ObservableList<Object>, Object> column = new TableColumn<>("Column " + i);
            column.setUserData(i);
            table.getColumns().add(column);
        }
        SqlResultColumnMenu columns = new SqlResultColumnMenu(table);
        columns.refresh(true);
        return new SqlResultToolbar(new SqlResultToolbar.Actions(text -> events.add("search:" + text),
                () -> events.add("add"), index -> events.add("remove:" + index), () -> events.add("apply"),
                () -> events.add("clear"), mode -> events.add("copy:" + mode)), columns.getNode());
    }

    private static ResultFilterState.Snapshot snapshot(ResultFilterState.DatabaseStatus status) {
        QueryResult result = QueryResult.queryWithMetadata(
                List.of(new ResultColumn(0, "DESCRIPTION", Types.VARCHAR, "VARCHAR")),
                List.of(List.<Object>of("synthetic")), 12, false);
        FilterCondition condition = new FilterCondition(0, FilterConnector.AND,
                FilterOperator.CONTAINS, "较长的筛选值 with spaces ".repeat(5));
        return new ResultFilterState.Snapshot(result, result, "select description from example",
                null, "", List.of(condition), List.of(0), status, null,
                "数据库筛选失败，仍显示当前结果；请检查筛选条件后再试。");
    }
}
