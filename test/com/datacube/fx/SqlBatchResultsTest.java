package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlBatchResultsTest {
    @ParameterizedTest @ValueSource(ints = {999, 1000, 1001})
    void retentionBoundIsExplicitAndSummaryCountsEvenOmittedFailures(int count) throws Exception {
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
                outcomes.clear(); choice.getSelectionModel().selectLast();
                assertEquals(Math.min(count, 1000), chosen.get().outcome().index());
                assertSame(batch.report().entries().getLast(), chosen.get().detail(), "details reuse the bounded report entry, not raw SQL/error");
                batch.close(); assertTrue(choice.getItems().isEmpty()); assertNull(batch.report()); assertNull(batch.schema());
                batch.display(List.of(new ScriptOutcome(1, "new", QueryResult.update(1, 0))), 1, "new"); assertTrue(choice.getItems().isEmpty());
            }
            return null;
        });
    }
}
