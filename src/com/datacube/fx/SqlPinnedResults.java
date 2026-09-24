package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.PinnedResultStore;
import com.datacube.sqleditor.result.CompactResultText;
import java.util.function.BooleanSupplier;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Fixed snapshots live independently of the replaceable current batch. */
final class SqlPinnedResults implements AutoCloseable {
    private final PinnedResultStore store = new PinnedResultStore();
    private final Button pin = new Button("固定已加载结果");
    private final MenuButton saved = new MenuButton();
    private final Label status = new Label();
    private final VBox bar = new VBox(4, new FlowPane(8, 4, pin, saved), status);
    private final BooleanSupplier allowed;
    private final BooleanSupplier canPin;
    private SqlPinnedResultDialog dialog;
    private boolean closed;
    private String notice = "";

    SqlPinnedResults(BooleanSupplier allowed, BooleanSupplier canPin, Runnable pinCurrent) {
        this.allowed = allowed; this.canPin = canPin;
        pin.setId("sql-result-pin"); saved.setId("sql-pinned-results"); status.setId("sql-pinned-status");
        pin.setTooltip(new Tooltip("保留当前查询的全部已加载行（不继承本地筛选/布局）。最多 3 份，共享保留预算；仅当前标签内存，关闭即清除。"));
        status.setWrapText(true);
        pin.setOnAction(event -> { if (usable() && canPin.getAsBoolean()) pinCurrent.run(); });
        bar.disabledProperty().addListener((obs, before, disabled) -> { if (disabled) closeDialog(); });
        refresh();
    }
    VBox getNode() { return bar; }
    void pin(QueryResult result, PinnedResultStore.Source source) {
        if (!usable() || !canPin.getAsBoolean()) return;
        try { store.pin(result, source); notice = ""; }
        catch (IllegalArgumentException | IllegalStateException rejected) { notice = rejected.getMessage(); }
        refresh();
    }
    void refresh() {
        boolean usable = usable();
        pin.setDisable(!usable || !canPin.getAsBoolean());
        saved.setText("固定结果（" + store.entries().size() + "/3）");
        saved.setDisable(!usable || store.entries().isEmpty());
        saved.getItems().clear();
        for (var entry : store.entries()) {
            var item = new MenuItem("#" + entry.id() + " · " + entry.result().rows.size() + " 行 · " + CompactResultText.preview(entry.source().schema()));
            item.setId("sql-pinned-entry-" + entry.id()); item.setOnAction(event -> open(entry)); saved.getItems().add(item);
        }
        if (!store.entries().isEmpty()) {
            var clear = new MenuItem("清除所有固定结果"); clear.setId("sql-pinned-clear");
            clear.setOnAction(event -> { if (usable()) { closeDialog(); store.clear(); notice = ""; refresh(); } });
            saved.getItems().addAll(new SeparatorMenuItem(), clear);
        }
        var usage = store.usage();
        status.setText(notice.isEmpty() ? (usage.results() == 0 ? "" : "固定结果共 " + usage.rows() + "/50,000 行 · "
                + usage.cells() + "/250,000 单元格 · " + usage.textUnits() + "/4,194,304 文本单元；仅本标签内存") : notice);
        boolean visible = !closed && (canPin.getAsBoolean() || usage.results() > 0);
        bar.setVisible(visible); bar.setManaged(visible);
    }
    private boolean usable() { return !closed && !bar.isDisabled() && allowed.getAsBoolean(); }
    private void open(PinnedResultStore.Entry entry) {
        if (!usable() || !store.contains(entry)) return;
        closeDialog();
        var candidate = new SqlPinnedResultDialog(bar.getScene() == null ? null : bar.getScene().getWindow(), entry, () -> {
            if (!usable() || !store.contains(entry)) return;
            closeDialog(); store.remove(entry); notice = ""; refresh();
        });
        dialog = candidate;
        candidate.setOnHidden(event -> { candidate.dispose(); if (dialog == candidate) dialog = null; });
        try { candidate.show(); }
        catch (RuntimeException failure) { dialog = null; candidate.dispose(); throw failure; }
    }
    private void closeDialog() {
        if (dialog == null) return;
        var previous = dialog; dialog = null; previous.close(); previous.dispose();
    }
    @Override public void close() { if (closed) return; closed = true; closeDialog(); store.close(); notice = ""; refresh(); }
}
