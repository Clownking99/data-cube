package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.SqlScriptExecutionReport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Owns this batch's already loaded results, never a session or a re-execution action. */
final class SqlBatchResults implements AutoCloseable {
    /** A retained occurrence has identity even when all of its displayed values are equal. */
    static final class Choice {
        private final ScriptOutcome outcome;
        private final SqlScriptExecutionReport.Entry detail;
        private final String label;
        Choice(ScriptOutcome outcome, SqlScriptExecutionReport.Entry detail, String label) {
            this.outcome = outcome; this.detail = detail; this.label = label;
        }
        ScriptOutcome outcome() { return outcome; }
        SqlScriptExecutionReport.Entry detail() { return detail; }
        @Override public String toString() { return label; }
    }
    private final ComboBox<Choice> choices = new ComboBox<>();
    private final Label summary = new Label();
    private final Label timings = new Label();
    private final Button locateError = new Button("定位错误");
    private Consumer<Choice> errorLocator = ignored -> {};
    private final Button details = new Button("执行详情");
    private final Button previousFailure = new Button("上一异常");
    private final Button nextFailure = new Button("下一异常");
    private final VBox bar;
    private final BooleanSupplier allowed;
    private final Consumer<Choice> render;
    private Choice selected;
    private SqlScriptExecutionReport report;
    private String schema;
    private boolean updating;
    private boolean closed;
    private SqlScriptDetailsDialog dialog;
    private Runnable onClear = () -> {};

    SqlBatchResults(BooleanSupplier allowed, Consumer<Choice> render) {
        this.allowed = allowed; this.render = render;
        choices.setId("sql-batch-choice"); choices.setAccessibleText("本次执行结果");
        choices.setMaxWidth(Double.MAX_VALUE); choices.setMinWidth(100); choices.setVisibleRowCount(12);
        choices.setTooltip(new Tooltip("切换本次已返回的结果，不重新执行 SQL。同批次保留各结果的本地筛选、列布局和选区。"));
        details.setId("sql-batch-details"); details.setMinWidth(Region.USE_PREF_SIZE);
        details.setTooltip(new Tooltip("查看当前语句已返回的 SQL 和执行信息（只读，不重新执行）。执行概览请选择表格中的记录。"));
        details.setOnAction(event -> showDetails());
        configureFailureNavigation(previousFailure, "sql-batch-previous-failure", -1);
        configureFailureNavigation(nextFailure, "sql-batch-next-failure", 1);
        var label = new Label("本次结果"); label.setMinWidth(Region.USE_PREF_SIZE);
        choices.setPrefWidth(220);
        var navigation = new HBox(8, previousFailure, nextFailure, details);
        var row = new javafx.scene.layout.FlowPane(8, 4, label, choices, navigation, locateError);
        summary.setId("sql-batch-summary"); summary.setWrapText(true); summary.setMinHeight(Region.USE_PREF_SIZE);
        locateError.setId("sql-locate-error");
        locateError.setTooltip(new Tooltip("仅使用驱动确认的位置；执行后修改过正文则提示过期，不移动光标。"));
        locateError.setOnAction(event -> { if (canShowDetails() && selected.outcome().result().kind == QueryResult.Kind.ERROR) errorLocator.accept(selected); });
        timings.setId("sql-result-timings"); timings.setWrapText(true);
        bar = new VBox(4, row, summary, timings); bar.setId("sql-batch-results");
        bar.disabledProperty().addListener((obs, before, disabled) -> {
            if (disabled) closeDetails();
            refreshActions();
        });
        choices.setOnAction(event -> {
            if (updating) return;
            Choice candidate = choices.getValue();
            if (closed || !bar.isVisible() || bar.isDisabled() || !allowed.getAsBoolean()
                    || candidate == null || choices.getItems().stream().noneMatch(item -> item == candidate)) {
                selectWithoutRendering(selected); return;
            }
            if (candidate == selected) return;
            closeDetails(); selected = candidate; render.accept(candidate); refreshActions();
        });
        clear();
    }

    void onLocateError(Consumer<Choice> locator) { errorLocator = locator; }
    void rendered(long nanos) {
        timings.setText((selected != null && selected.detail() != null ? selected.detail().timingDescription() + " · " : "")
                + "FX 渲染准备 " + nanos / 1_000_000 + "ms（不含布局/绘制）");
    }

    VBox getNode() { return bar; }
    SqlScriptExecutionReport report() { return report; }
    String schema() { return schema; }
    void onClear(Runnable action) { onClear = action; }
    void cancelled() {
        if (report != null) summary.setText("已取消；仅保留取消前已显示结果；" + report.summary());
    }

    /** Overview rows hold the very same bounded entries, even after table sorting. */
    void selectResult(SqlScriptExecutionReport.Entry entry) {
        if (entry == null || closed || !bar.isVisible() || bar.isDisabled() || !allowed.getAsBoolean()
                || selected == null || selected.outcome() != null || choices.getValue() != selected
                || choices.getItems().stream().noneMatch(item -> item == selected)) return;
        for (int i = 0; i < choices.getItems().size(); i++) {
            if (choices.getItems().get(i).detail() == entry) {
                // Use the retained occurrence's position, not its displayed statement number.
                choices.getSelectionModel().select(i); return;
            }
        }
    }

    void display(List<ScriptOutcome> outcomes, long elapsed, String effectiveSchema) {
        clear(); if (closed) return;
        report = SqlScriptExecutionReport.capture(outcomes, elapsed); schema = effectiveSchema;
        var items = new ArrayList<Choice>(); items.add(new Choice(null, null, "执行概览"));
        Choice initial = items.getFirst();
        for (int i = 0; i < report.entries().size(); i++) {
            var entry = report.entries().get(i); var outcome = outcomes.get(i);
            String kind = switch (entry.kind()) { case QUERY -> "查询 · " + entry.resultDescription(); case UPDATE -> "更新 · " + entry.resultDescription(); case ERROR -> entry.status(); };
            var choice = new Choice(outcome, entry, "语句 #" + entry.index() + " · " + kind + " · " + entry.timingDescription());
            items.add(choice);
            if (initial.outcome() == null && entry.kind() == QueryResult.Kind.QUERY) initial = choice;
        }
        updating = true;
        try { choices.getItems().setAll(items); } finally { updating = false; }
        summary.setText(report.summary() + (outcomes.size() > report.entries().size()
                ? "；仅保留前 " + report.entries().size() + " 条的结果供切换，请分批执行。" : ""));
        summary.setStyle("-fx-text-fill: " + (report.hasFailures() ? "-status-error" : "-status-ok") + ";");
        bar.setVisible(true); bar.setManaged(true); selected = initial; selectWithoutRendering(initial);
        // Initial rendering is the execution completion callback, not a new user operation.
        render.accept(initial); refreshActions();
    }

    /** Append-only completed prefix: existing choices keep occurrence identity and view state. */
    void update(List<ScriptOutcome> outcomes, long elapsed, String effectiveSchema, String progress) {
        if (closed) return;
        int retained = Math.min(outcomes.size(), SqlScriptExecutionReport.MAX_ENTRIES);
        boolean compatible = choices.getItems().size() <= retained + 1;
        for (int i = 1; compatible && i < choices.getItems().size(); i++)
            compatible = choices.getItems().get(i).outcome() == outcomes.get(i - 1);
        if (!compatible) clear();
        if (choices.getItems().isEmpty()) { display(outcomes, elapsed, effectiveSchema); }
        else {
            var captured = SqlScriptExecutionReport.capture(outcomes, elapsed);
            updating = true;
            try {
                for (int i = choices.getItems().size() - 1; i < retained; i++) {
                    var entry = captured.entries().get(i);
                    choices.getItems().add(new Choice(outcomes.get(i), entry, "语句 #" + entry.index()
                            + " · " + entry.status() + " · " + entry.resultDescription()));
                }
            } finally { updating = false; }
            var entries = choices.getItems().stream().skip(1).map(Choice::detail).toList();
            report = new SqlScriptExecutionReport(entries, captured.returned(), captured.normal(),
                    captured.failed(), captured.timedOut(), captured.cancelled(), elapsed);
            if (selected != null && selected.outcome() == null) render.accept(selected);
        }
        summary.setText((progress.isEmpty() ? "" : progress + "；") + report.summary());
        if (!outcomes.isEmpty() && outcomes.getLast().result().errorMessage != null
                && outcomes.getLast().result().errorMessage.startsWith("结果预算已用尽"))
            summary.setText(summary.getText() + "；" + outcomes.getLast().result().errorMessage);
        summary.setStyle("-fx-text-fill: " + (report.hasFailures() ? "-status-error" : "-status-ok") + ";");
        refreshActions();
    }

    private boolean canShowDetails() {
        return !closed && bar.isVisible() && !bar.isDisabled() && allowed.getAsBoolean()
                && selected != null && selected.detail() != null && choices.getValue() == selected
                && choices.getItems().stream().anyMatch(item -> item == selected);
    }

    private void configureFailureNavigation(Button button, String id, int direction) {
        button.setId(id); button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip((direction < 0
                ? "按执行顺序定位上一条失败、超时或取消的已保留结果，开头回到末尾。\n"
                : "按执行顺序定位下一条失败、超时或取消的已保留结果，末尾回到开头。\n")
                + "没有其他异常时不可用；只切换结果，不重新执行 SQL。"));
        button.setOnAction(event -> {
            int target = failureIndex(direction);
            // Repeated statements may still be distinct results; preserve their positions.
            if (target >= 0) choices.getSelectionModel().select(target);
        });
    }

    private int failureIndex(int direction) {
        if (closed || !bar.isVisible() || bar.isDisabled() || !allowed.getAsBoolean()
                || selected == null || choices.getValue() != selected) return -1;
        var items = choices.getItems();
        int start = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i) == selected) { start = i; break; }
        if (start < 0) return -1;
        // Exclude the current choice: a sole current failure must not reset its view/dialog.
        for (int step = 1; step < items.size(); step++) {
            int target = Math.floorMod(start + direction * step, items.size());
            Choice candidate = items.get(target);
            if (candidate.detail() != null && candidate.detail().kind() == QueryResult.Kind.ERROR) return target;
        }
        return -1;
    }

    private void refreshActions() {
        details.setDisable(!canShowDetails());
        locateError.setDisable(!canShowDetails() || selected.outcome().result().kind != QueryResult.Kind.ERROR);
        previousFailure.setDisable(failureIndex(-1) < 0);
        nextFailure.setDisable(failureIndex(1) < 0);
    }

    private void showDetails() {
        if (!canShowDetails() || dialog != null) return;
        Choice expected = selected;
        var candidate = new SqlScriptDetailsDialog(bar.getScene() == null ? null : bar.getScene().getWindow(), expected.detail());
        if (!canShowDetails() || selected != expected) return;
        dialog = candidate;
        candidate.setOnHidden(event -> { if (dialog == candidate) dialog = null; });
        try { candidate.show(); }
        catch (RuntimeException failure) { dialog = null; throw failure; }
    }

    private void closeDetails() {
        if (dialog == null) return;
        var previous = dialog; dialog = null; previous.close();
    }

    private void selectWithoutRendering(Choice value) {
        updating = true;
        try { choices.setValue(value); } finally { updating = false; }
    }

    void clear() {
        onClear.run();
        closeDetails();
        updating = true;
        try { selected = null; choices.setValue(null); choices.getItems().clear(); }
        finally { updating = false; }
        report = null; schema = null; summary.setText(""); timings.setText(""); bar.setVisible(false); bar.setManaged(false); refreshActions();
    }

    @Override public void close() { if (closed) return; closed = true; clear(); bar.setDisable(true); }
}
