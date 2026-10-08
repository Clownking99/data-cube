package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.ResultFilterState;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlBatchViewStateTest {
    @TempDir Path directory;

    @Test void duplicateOccurrencesPreserveFiltersColumnsSortAndEqualRowSelection() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                var result = QueryResult.query(List.of("same", "same"),
                        List.of(List.of("a", 2), List.of("a", 2), List.of("b", 1)), 1);
                var occurrence = new ScriptOutcome(1, "select same, same from demo", result);
                f.show(List.of(occurrence, occurrence));
                var choices = (ComboBox<?>) f.root.lookup("#sql-batch-choice");
                var state = (ResultFilterState) field(f.pane, "resultFilterState");
                state.setSearchText("a"); invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{});
                state.appendCondition(new com.datacube.sqleditor.result.FilterCondition(1,
                        com.datacube.sqleditor.result.FilterConnector.AND,
                        com.datacube.sqleditor.result.FilterOperator.EQ, 2));
                invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{});
                var first = f.table().getColumns().get(1); var second = f.table().getColumns().get(2);
                first.setVisible(false); second.setPrefWidth(277);
                f.table().getColumns().setAll(f.table().getColumns().getFirst(), second, first);
                second.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(second));
                f.table().getSelectionModel().clearAndSelect(1, second);
                f.table().getFocusModel().focus(1, second);
                choices.getSelectionModel().select(2);
                assertEquals(3, f.table().getItems().size());
                assertEquals("", ((ResultFilterState) field(f.pane, "resultFilterState")).snapshot().searchText());
                choices.getSelectionModel().select(1);
                assertEquals("a", ((ResultFilterState) field(f.pane, "resultFilterState")).snapshot().searchText());
                assertEquals(2, f.table().getItems().size());
                assertEquals(1, f.table().getColumns().get(1).getUserData());
                assertEquals(277, f.table().getColumns().get(1).getPrefWidth());
                assertFalse(f.table().getColumns().get(2).isVisible());
                assertEquals(List.of(f.table().getColumns().get(1)), f.table().getSortOrder());
                assertEquals(1, f.table().getSelectionModel().getSelectedIndex());
                assertEquals(1, f.table().getFocusModel().getFocusedIndex());
                state.setSearchText("");
                assertEquals(List.of(0, 1), state.snapshot().visibleRowIndexes(), "raw condition survives the redacted display snapshot");
                f.show(List.of(occurrence, occurrence));
                assertEquals(3, f.table().getItems().size());
                assertTrue(f.table().getColumns().get(1).isVisible());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void scrollPositionsSurviveSwitchAndCloseReleasesCachedViews() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                var rows = java.util.stream.IntStream.range(0, 300).mapToObj(i -> List.<Object>of(i, i)).toList();
                var first = new ScriptOutcome(1, "select n,n from demo", QueryResult.query(List.of("n", "n"), rows, 1));
                f.show(List.of(first, first)); f.window();
                f.table().getColumns().get(1).setPrefWidth(1200); f.root.applyCss(); f.root.layout(); f.table().layout();
                var bars = f.table().lookupAll(".scroll-bar").stream().filter(javafx.scene.control.ScrollBar.class::isInstance)
                        .map(javafx.scene.control.ScrollBar.class::cast).toList();
                assertEquals(2, bars.size());
                for (var bar : bars) { assertTrue(bar.isVisible()); bar.setValue(bar.getMax() * 0.6); }
                var before = bars.stream().collect(java.util.stream.Collectors.toMap(javafx.scene.control.ScrollBar::getOrientation,
                        javafx.scene.control.ScrollBar::getValue));
                var choices = (ComboBox<?>) f.root.lookup("#sql-batch-choice");
                choices.getSelectionModel().select(2); choices.getSelectionModel().select(1);
                for (var node : f.table().lookupAll(".scroll-bar")) if (node instanceof javafx.scene.control.ScrollBar bar)
                    assertEquals(before.get(bar.getOrientation()), bar.getValue(), 0.01);
                assertFalse(((java.util.Map<?, ?>) field(f.pane, "batchViews")).isEmpty());
                f.pane.finalizeCloseOnFx();
                assertTrue(((java.util.Map<?, ?>) field(f.pane, "batchViews")).isEmpty());
                assertNull(field(f.pane, "displayedChoice")); assertNull(field(f.pane, "displayedResult"));
                return null;
            });
            f.assertOffline();
        }
    }
}
