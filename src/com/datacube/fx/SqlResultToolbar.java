package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.ResultFilterState;
import com.datacube.sqleditor.result.ResultValueFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** View-only controls for filtering and copying the currently displayed SQL result. */
public final class SqlResultToolbar {
    private static final double SEARCH_DEBOUNCE_MILLIS = 120;

    public enum CopyMode {
        CURRENT_CELL, SELECTION, SELECTED_ROWS, SELECTED_ROWS_WITH_HEADERS
    }

    public record Actions(
            Consumer<String> searchChanged,
            Runnable addCondition,
            IntConsumer removeCondition,
            Runnable applyDatabaseFilter,
            Runnable clearFilters,
            Consumer<CopyMode> copyRequested,
            Runnable viewCell) {
        public Actions(Consumer<String> searchChanged, Runnable addCondition, IntConsumer removeCondition,
                       Runnable applyDatabaseFilter, Runnable clearFilters, Consumer<CopyMode> copyRequested) {
            this(searchChanged, addCondition, removeCondition, applyDatabaseFilter, clearFilters, copyRequested, () -> { });
        }
        public Actions {
            Objects.requireNonNull(searchChanged, "searchChanged");
            Objects.requireNonNull(addCondition, "addCondition");
            Objects.requireNonNull(removeCondition, "removeCondition");
            Objects.requireNonNull(applyDatabaseFilter, "applyDatabaseFilter");
            Objects.requireNonNull(clearFilters, "clearFilters");
            Objects.requireNonNull(copyRequested, "copyRequested");
            Objects.requireNonNull(viewCell, "viewCell");
        }
    }

    private final Actions actions;
    private final VBox root = new VBox(4);
    private final TextField search = new TextField();
    private final FlowPane conditions = new FlowPane(6, 4);
    private final Button addCondition = new Button("＋ 条件");
    private final Button applyDatabase = new Button("数据库筛选");
    private final MenuButton copy = new MenuButton("复制");
    private final Button clear = new Button("清除筛选");
    private final SplitMenuButton viewCell = new SplitMenuButton();
    private final Label summary = new Label();
    private final PauseTransition searchDebounce =
            new PauseTransition(Duration.millis(SEARCH_DEBOUNCE_MILLIS));
    private boolean rendering;
    private String committedSearch = "";

    public SqlResultToolbar(Actions actions) {
        this(actions, null);
    }

    SqlResultToolbar(Actions actions, MenuButton columnMenu) {
        this(actions, columnMenu, null);
    }

    SqlResultToolbar(Actions actions, MenuButton columnMenu, javafx.scene.control.CheckBox rowDisplay) {
        this.actions = Objects.requireNonNull(actions, "actions");
        configureControls();
        composeLayout(columnMenu, rowDisplay);
    }

    public Parent getNode() {
        return root;
    }

    void configureViewMenu(List<MenuItem> items, Runnable beforeShowing) {
        viewCell.getItems().setAll(items);
        viewCell.setOnShowing(event -> beforeShowing.run());
    }

    /** Maps one immutable state snapshot to controls without causing an action callback. */
    public void render(ResultFilterState.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        viewCell.hide();
        searchDebounce.stop();
        rendering = true;
        try {
            committedSearch = snapshot.searchText() == null ? "" : snapshot.searchText();
            search.setText(committedSearch);
        } finally {
            rendering = false;
        }

        QueryResult active = snapshot.activeResult();
        boolean hasQuery = active != null && active.kind == QueryResult.Kind.QUERY;
        search.setDisable(!hasQuery);
        addCondition.setDisable(!hasQuery);
        copy.setDisable(!hasQuery);
        // The drop-down can clear sorting even when filtering leaves zero rows.
        viewCell.setDisable(!hasQuery);
        rebuildConditionChips(snapshot, hasQuery);

        String applyDisabledReason = applyDisabledReason(snapshot, hasQuery);
        setDisabledWithReason(applyDatabase, applyDisabledReason);
        boolean hasFilterState = !search.getText().isEmpty()
                || !snapshot.conditions().isEmpty()
                || snapshot.databaseStatus() != ResultFilterState.DatabaseStatus.ORIGINAL;
        clear.setDisable(!hasQuery || !hasFilterState);

        boolean reapplies = snapshot.databaseStatus() == ResultFilterState.DatabaseStatus.APPLIED
                || snapshot.databaseStatus() == ResultFilterState.DatabaseStatus.DIRTY_AFTER_APPLY;
        applyDatabase.setText(reapplies ? "重新应用" : "数据库筛选");
        applyDatabase.setAccessibleText(reapplies ? "重新应用数据库筛选" : "应用数据库筛选");
        summary.setText(summaryText(snapshot, active, hasQuery));
        summary.setAccessibleText("查询结果摘要：" + summary.getText());
    }

    private void configureControls() {
        search.setId("sql-result-search");
        search.setPromptText("搜索当前结果…");
        search.setAccessibleText("搜索当前结果");
        search.setPrefWidth(220);
        search.setMinWidth(Region.USE_PREF_SIZE);
        search.textProperty().addListener((ignored, oldValue, newValue) -> {
            if (!rendering) searchDebounce.playFromStart();
        });
        searchDebounce.setOnFinished(ignored -> commitSearchInput());
        root.sceneProperty().addListener((ignored, oldScene, newScene) -> {
            if (oldScene != null && newScene == null) searchDebounce.stop();
        });
        search.setOnAction(ignored -> {
            commitSearchInput();
        });

        addCondition.setId("sql-result-add-filter");
        addCondition.setAccessibleText("添加筛选条件");
        addCondition.setOnAction(ignored -> actions.addCondition().run());

        applyDatabase.setId("sql-result-apply-database");
        applyDatabase.setAccessibleText("应用数据库筛选");
        applyDatabase.setOnAction(ignored -> {
            commitSearchInput();
            actions.applyDatabaseFilter().run();
        });

        copy.setId("sql-result-copy");
        copy.setAccessibleText("复制查询结果");
        addCopyItem("当前单元格", CopyMode.CURRENT_CELL);
        addCopyItem("选中区域", CopyMode.SELECTION);
        addCopyItem("选中行", CopyMode.SELECTED_ROWS);
        addCopyItem("选中行（含表头）", CopyMode.SELECTED_ROWS_WITH_HEADERS);

        clear.setId("sql-result-clear-filter");
        clear.setAccessibleText("清除结果筛选");
        clear.setOnAction(ignored -> actions.clearFilters().run());

        viewCell.setId("sql-result-view-cell");
        viewCell.setText("查看单元格");
        viewCell.setAccessibleText("查看单元格及更多结果操作");
        viewCell.setAccessibleHelp("主按钮查看单元格；聚焦后按向下键打开查看整行、定位行和恢复原始行序菜单");
        viewCell.setTooltip(new Tooltip("查看选中单元格；下拉可查看整行、定位行或恢复原始行序，仅操作已加载结果"));
        viewCell.setOnAction(ignored -> actions.viewCell().run());
        root.disabledProperty().addListener((ignored, oldValue, disabled) -> {
            if (disabled) viewCell.hide();
        });

        conditions.setAlignment(Pos.CENTER_LEFT);
        conditions.setMinWidth(0);
        summary.setId("sql-result-summary");
        summary.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        summary.setWrapText(true);
        summary.setMinWidth(0);
        summary.setMinHeight(Region.USE_PREF_SIZE);
    }

    private void composeLayout(MenuButton columnMenu, javafx.scene.control.CheckBox rowDisplay) {
        FlowPane actionsRow = new FlowPane(6, 4, search, addCondition, applyDatabase, viewCell, copy, clear);
        if (columnMenu != null) actionsRow.getChildren().add(3, columnMenu);
        if (rowDisplay != null) actionsRow.getChildren().add(rowDisplay);
        actionsRow.setId("sql-result-actions");
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        actionsRow.setMinHeight(Region.USE_PREF_SIZE);
        // Keep action labels readable; move controls to another row when space runs out.
        for (var control : actionsRow.getChildren()) {
            ((Region) control).setMinWidth(Region.USE_PREF_SIZE);
        }
        root.setPadding(new Insets(4, 0, 4, 0));
        root.getStyleClass().add("sql-result-toolbar");
        root.getChildren().addAll(actionsRow, conditions, summary);
    }

    private void addCopyItem(String text, CopyMode mode) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(ignored -> actions.copyRequested().accept(mode));
        copy.getItems().add(item);
    }

    private void commitSearchInput() {
        searchDebounce.stop();
        String current = search.getText();
        if (Objects.equals(committedSearch, current)) return;
        committedSearch = current;
        actions.searchChanged().accept(current);
    }

    public boolean flushPendingSearch() {
        boolean changed = !Objects.equals(committedSearch, search.getText());
        commitSearchInput();
        return changed;
    }

    private void rebuildConditionChips(ResultFilterState.Snapshot snapshot, boolean hasQuery) {
        conditions.getChildren().clear();
        List<ResultColumn> columns = resultColumns(snapshot);
        List<FilterCondition> filters = snapshot.conditions();
        for (int index = 0; index < filters.size(); index++) {
            FilterCondition condition = filters.get(index);
            String description = conditionDescription(index, condition, columns);
            Button chip = new Button(description + "  ×");
            chip.setId("sql-result-filter-remove-" + index);
            chip.setAccessibleText("删除筛选条件：" + description);
            chip.getStyleClass().add("tag-chip");
            chip.setWrapText(true);
            chip.setMinWidth(0);
            chip.maxWidthProperty().bind(conditions.widthProperty());
            chip.setMinHeight(Region.USE_PREF_SIZE);
            chip.setDisable(!hasQuery);
            int stableIndex = index;
            chip.setOnAction(ignored -> actions.removeCondition().accept(stableIndex));
            conditions.getChildren().add(chip);
        }
        boolean visible = !filters.isEmpty();
        conditions.setVisible(visible);
        conditions.setManaged(visible);
    }

    private static List<ResultColumn> resultColumns(ResultFilterState.Snapshot snapshot) {
        QueryResult result = snapshot.originalResult() != null
                ? snapshot.originalResult() : snapshot.activeResult();
        return result == null ? List.of() : result.resultColumns;
    }

    private static String conditionDescription(
            int index, FilterCondition condition, List<ResultColumn> columns) {
        String connector = index == 0 ? "" : condition.connector().name() + " ";
        String column = condition.columnIndex() < columns.size()
                ? columns.get(condition.columnIndex()).label()
                : "列 " + (condition.columnIndex() + 1);
        String operator = operatorLabel(condition.operator());
        String value = condition.operator().valueRequired()
                ? " " + ResultValueFormatter.format(condition.value()) : "";
        return connector + column + " " + operator + value;
    }

    private static String operatorLabel(FilterOperator operator) {
        return switch (operator) {
            case EQ -> "等于";
            case NE -> "不等于";
            case CONTAINS -> "包含";
            case STARTS_WITH -> "开头是";
            case ENDS_WITH -> "结尾是";
            case GT -> "大于";
            case GTE -> "大于等于";
            case LT -> "小于";
            case LTE -> "小于等于";
            case IS_NULL -> "为空";
            case IS_NOT_NULL -> "非空";
        };
    }

    private static String applyDisabledReason(
            ResultFilterState.Snapshot snapshot, boolean hasQuery) {
        if (!hasQuery) return "当前没有查询结果";
        if (snapshot.conditions().isEmpty()) return "请先添加筛选条件";
        if (snapshot.databaseUnavailableReason() != null
                && !snapshot.databaseUnavailableReason().isBlank()) {
            return snapshot.databaseUnavailableReason();
        }
        return null;
    }

    private static void setDisabledWithReason(Button button, String reason) {
        boolean disabled = reason != null;
        button.setDisable(disabled);
        button.setTooltip(disabled ? new Tooltip(reason) : null);
        button.setAccessibleHelp(disabled ? reason : null);
    }

    private static String summaryText(
            ResultFilterState.Snapshot snapshot, QueryResult active, boolean hasQuery) {
        if (!hasQuery) return "暂无查询结果";
        int visible = snapshot.visibleRowIndexes().size();
        int loaded = active.rows.size();
        int columnCount = active.resultColumns.isEmpty()
                ? active.columns.size() : active.resultColumns.size();
        String visibleText = formatNumber(visible);
        String loadedText = formatNumber(loaded) + (active.truncated ? "+" : "");
        String elapsedText = formatNumber(active.elapsedMillis) + " ms";
        String text = switch (snapshot.databaseStatus()) {
            case ORIGINAL -> "原始结果：" + loadedText + " 行 · " + columnCount
                    + " 列 · " + elapsedText;
            case LOCAL_PREVIEW -> "本地预览：显示 " + visibleText + " / "
                    + loadedText + " 行 · " + columnCount + " 列 · " + elapsedText;
            case APPLIED -> "数据库筛选已应用：显示 " + visibleText + " / "
                    + loadedText + " 行 · " + columnCount + " 列 · " + elapsedText;
            case DIRTY_AFTER_APPLY -> "本地预览 / 有未应用更改：显示 " + visibleText + " / "
                    + loadedText + " 行 · " + columnCount + " 列 · " + elapsedText;
        };
        if (active.truncated) text += "（当前结果已截断）";
        if (snapshot.recoverableError() != null && !snapshot.recoverableError().isBlank()) {
            text += " · " + snapshot.recoverableError();
        }
        return text;
    }

    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
