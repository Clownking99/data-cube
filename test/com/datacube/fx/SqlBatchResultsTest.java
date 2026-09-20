package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlBatchResultsTest {
    @ParameterizedTest @CsvSource({"999,next", "1000,next", "1001,next", "999,previous", "1000,previous", "1001,previous"})
    void retentionBoundIsExplicitAndSummaryCountsEvenOmittedFailures(int count, String direction) throws Exception {
        FxUiTestSupport.call(() -> {
            var chosen = new AtomicReference<SqlBatchResults.Choice>();
            try (var batch = new SqlBatchResults(() -> true, chosen::set)) {
                new javafx.scene.Scene(batch.getNode(), 640, 160); batch.getNode().applyCss(); batch.getNode().layout();
                var outcomes = new ArrayList<ScriptOutcome>();
                for (int i = 0; i < count; i++) outcomes.add(new ScriptOutcome(i + 1, "synthetic", i == count - 1 ? QueryResult.error("last failure", 1) : QueryResult.update(1, 1)));
                batch.display(outcomes, count, "schema");
                var choice = (ComboBox<?>) batch.getNode().lookup("#sql-batch-choice");
                assertEquals(Math.min(count, 1000) + 1, choice.getItems().size()); assertNull(chosen.get().outcome());
                assertEquals(1, batch.report().failed()); assertEquals(count, batch.report().returned());
                assertEquals("schema", batch.schema());
                assertEquals(count > 1000, ((Label) batch.getNode().lookup("#sql-batch-summary")).getText().contains("仅保留前 1000"));
                var navigation = (Button) batch.getNode().lookup("#sql-batch-" + direction + "-failure");
                assertNotNull(navigation); assertEquals(count > 1000, navigation.isDisabled());
                if (count <= 1000) {
                    navigation.fire(); assertEquals(count, chosen.get().outcome().index()); assertTrue(navigation.isDisabled());
                } else {
                    var before = chosen.get(); navigation.getOnAction().handle(new javafx.event.ActionEvent());
                    assertSame(before, chosen.get(), "an omitted failure cannot be navigated to");
                }
                outcomes.clear(); choice.getSelectionModel().selectLast();
                assertEquals(Math.min(count, 1000), chosen.get().outcome().index());
                assertSame(batch.report().entries().getLast(), chosen.get().detail(), "details reuse the bounded report entry, not raw SQL/error");
                batch.close(); assertTrue(choice.getItems().isEmpty()); assertNull(batch.report()); assertNull(batch.schema());
                batch.display(List.of(new ScriptOutcome(1, "new", QueryResult.update(1, 0))), 1, "new"); assertTrue(choice.getItems().isEmpty());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"next", "previous"})
    void equalFailuresRemainDistinctNavigationTargets(String direction) throws Exception {
        FxUiTestSupport.call(() -> {
            var rendered = new ArrayList<SqlBatchResults.Choice>();
            try (var batch = new SqlBatchResults(() -> true, rendered::add)) {
                new javafx.scene.Scene(batch.getNode(), 640, 160); batch.getNode().applyCss(); batch.getNode().layout();
                var failure = new ScriptOutcome(7, "same sql", QueryResult.error("same failure", 5));
                batch.display(List.of(failure, new ScriptOutcome(8, "normal", QueryResult.update(1, 0)), failure), 10, "schema");
                var choice = (ComboBox<?>) batch.getNode().lookup("#sql-batch-choice");
                var button = (Button) batch.getNode().lookup("#sql-batch-" + direction + "-failure"); assertNotNull(button);
                assertEquals(batch.report().entries().get(0), batch.report().entries().get(2));
                assertEquals(choice.getItems().get(1).toString(), choice.getItems().get(3).toString());
                assertNotSame(choice.getItems().get(1), choice.getItems().get(3));
                int[] targets = direction.equals("next") ? new int[]{1, 3, 1} : new int[]{3, 1, 3};
                for (int i = 0; i < targets.length; i++) {
                    int target = targets[i]; assertFalse(button.isDisabled()); button.fire();
                    assertEquals(target, choice.getSelectionModel().getSelectedIndex());
                    assertSame(choice.getItems().get(target), choice.getValue());
                    assertSame(choice.getValue(), rendered.getLast());
                    assertSame(batch.report().entries().get(target - 1), rendered.getLast().detail());
                    assertEquals(i + 2, rendered.size(), "each distinct target must render exactly once");
                }
                assertEquals(2, batch.report().failed()); assertEquals("schema", batch.schema());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"query", "update", "error"})
    void equalValuesCanBeSelectedManuallyWithoutRetainingPreviousOccurrence(String kind) throws Exception {
        FxUiTestSupport.call(() -> {
            var rendered = new ArrayList<SqlBatchResults.Choice>();
            try (var batch = new SqlBatchResults(() -> true, rendered::add)) {
                new javafx.scene.Scene(batch.getNode(), 640, 160); batch.getNode().applyCss(); batch.getNode().layout();
                var result = switch (kind) {
                    case "query" -> QueryResult.query(List.of("n"), List.of(List.of(1)), 5);
                    case "update" -> QueryResult.update(1, 5);
                    default -> QueryResult.error("same failure", 5);
                };
                var repeated = new ScriptOutcome(7, "same sql", result);
                batch.display(List.of(repeated, repeated), 10, "schema");
                var choice = (ComboBox<?>) batch.getNode().lookup("#sql-batch-choice");
                assertEquals(batch.report().entries().get(0), batch.report().entries().get(1));
                choice.getSelectionModel().select(0); rendered.clear();
                int[] targets = {1, 2, 1};
                for (int i = 0; i < targets.length; i++) {
                    int target = targets[i]; choice.getSelectionModel().select(target);
                    assertEquals(target, choice.getSelectionModel().getSelectedIndex());
                    assertSame(choice.getItems().get(target), choice.getValue());
                    assertSame(choice.getValue(), rendered.getLast());
                    assertSame(batch.report().entries().get(target - 1), rendered.getLast().detail());
                    assertSame(result, rendered.getLast().outcome().result());
                    assertEquals(i + 1, rendered.size());
                }
            }
            return null;
        });
    }
}
