package com.datacube.fx;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** A view-only picker over existing Tab identities. All calls belong to the FX thread. */
final class OpenTabsPane extends VBox implements AutoCloseable {
    static final int MAX_QUERY_LENGTH = 256;
    private final TabPane tabs;
    private final BooleanSupplier navigationAllowed;
    private final TextField query = new TextField();
    private final ListView<Tab> list = new ListView<>();
    private final Label status = new Label();
    private final ReadOnlyBooleanWrapper candidateAvailable = new ReadOnlyBooleanWrapper();
    private final ObservableList<Tab> observed = FXCollections.observableArrayList(
            tab -> new Observable[] {tab.textProperty(), tab.disabledProperty()});
    private final ListChangeListener<Tab> tabChanges = change -> syncTabs();
    private final ListChangeListener<Tab> titleOrStateChanges = change -> refresh(false);
    private boolean closed;

    OpenTabsPane(TabPane tabs, BooleanSupplier navigationAllowed) {
        super(8);
        this.tabs = Objects.requireNonNull(tabs);
        this.navigationAllowed = Objects.requireNonNull(navigationAllowed);
        query.setId("open-tabs-query");
        query.setPromptText("按标签标题筛选（不搜索正文）");
        list.setId("open-tabs-list");
        list.setPlaceholder(new Label("没有匹配的已打开标签"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Tab tab, boolean empty) {
                super.updateItem(tab, empty);
                if (empty || tab == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    String title = title(tab);
                    setText((tabs.getTabs().indexOf(tab) + 1) + " · "
                            + title.replaceAll("\\p{Cntrl}", " ")
                            + (tab.isDisabled() ? "（暂不可切换）" : ""));
                    setTooltip(new Tooltip(title));
                }
            }
        });
        status.setId("open-tabs-status");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        Label hint = new Label("选择候选后按 Enter 或“切换”；Esc 取消。编号为标签栏顺序。\n仅定位已打开的页面，不新建标签或执行 SQL。");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        setPadding(new Insets(12));
        setPrefSize(600, 400);
        getChildren().addAll(query, list, status, hint);
        VBox.setVgrow(list, Priority.ALWAYS);
        query.textProperty().addListener((o, before, after) -> refresh(true));
        list.getSelectionModel().selectedItemProperty().addListener((o, before, after) -> updateCandidate());
        query.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP) {
                int next = list.getSelectionModel().getSelectedIndex()
                        + (event.getCode() == KeyCode.DOWN ? 1 : -1);
                if (!list.getItems().isEmpty()) {
                    next = Math.max(0, Math.min(next, list.getItems().size() - 1));
                    list.getSelectionModel().select(next);
                    list.scrollTo(next);
                }
                event.consume();
            }
        });
        observed.addListener(titleOrStateChanges);
        tabs.getTabs().addListener(tabChanges);
        syncTabs();
        refresh(false);
        Tab current = tabs.getSelectionModel().getSelectedItem();
        if (list.getItems().contains(current)) list.getSelectionModel().select(current);
        else if (!list.getItems().isEmpty()) list.getSelectionModel().selectFirst();
    }

    private void syncTabs() {
        if (!closed) observed.setAll(tabs.getTabs());
    }

    private void refresh(boolean queryChanged) {
        if (closed) return;
        Tab selected = list.getSelectionModel().getSelectedItem();
        String raw = query.getText() == null ? "" : query.getText();
        boolean valid = raw.length() <= MAX_QUERY_LENGTH;
        String needle = raw.strip().toLowerCase(Locale.ROOT);
        list.getItems().setAll(valid ? observed.stream()
                .filter(tab -> title(tab).toLowerCase(Locale.ROOT).contains(needle)).toList()
                : java.util.List.of());
        // Never silently retarget a removed/renamed candidate to its neighbor or a same-name tab.
        list.getSelectionModel().clearSelection();
        if (list.getItems().contains(selected)) list.getSelectionModel().select(selected);
        else if (queryChanged && !list.getItems().isEmpty()) list.getSelectionModel().selectFirst();
        list.refresh();
        status.setText(valid ? "匹配 " + list.getItems().size() + " / " + observed.size() + " 个已打开标签"
                : "筛选词过长：最多 " + MAX_QUERY_LENGTH + " 个 UTF-16 单元，请缩短后重试。");
        updateCandidate();
    }

    private void updateCandidate() {
        Tab selected = list.getSelectionModel().getSelectedItem();
        candidateAvailable.set(!closed && selected != null && !selected.isDisabled()
                && list.getItems().contains(selected) && tabs.getTabs().contains(selected));
    }

    ReadOnlyBooleanProperty candidateAvailableProperty() { return candidateAvailable.getReadOnlyProperty(); }

    void focusQuery() { query.requestFocus(); }

    void onConfirm(Runnable confirm) {
        query.setOnAction(event -> { event.consume(); confirm.run(); });
    }

    boolean activateSelected() {
        updateCandidate();
        if (!candidateAvailable.get() || tabs.isDisabled() || !navigationAllowed.getAsBoolean()) {
            status.setText("该标签已关闭或暂不可切换，请重新选择。");
            return false;
        }
        Tab selected = list.getSelectionModel().getSelectedItem();
        tabs.getSelectionModel().select(selected);
        return tabs.getSelectionModel().getSelectedItem() == selected;
    }

    private static String title(Tab tab) {
        return tab.getText() == null || tab.getText().isEmpty() ? "（未命名标签）" : tab.getText();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        tabs.getTabs().removeListener(tabChanges);
        observed.removeListener(titleOrStateChanges);
        observed.clear(); // Removes extractor listeners from each Tab, including titles/close state.
        list.getItems().clear();
        query.setOnAction(null);
        setDisable(true);
        candidateAvailable.set(false);
    }
}
