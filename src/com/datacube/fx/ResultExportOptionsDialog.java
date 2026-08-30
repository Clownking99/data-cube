package com.datacube.fx;

import com.datacube.sqleditor.result.*;
import java.util.EnumMap;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

final class ResultExportOptionsDialog {
    record Selection(ResultExportScope scope, boolean displayConfirmed) {}
    private ResultExportOptionsDialog() {}

    static Dialog<Selection> create(Window owner, ResultExportSnapshot snapshot, boolean sql) {
        Dialog<Selection> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
            }
        }
        dialog.setTitle(sql ? "确认 INSERT 范围" : "确认导出范围");
        dialog.setResizable(true);

        ComboBox<ResultExportScope> scope = new ComboBox<>(
                FXCollections.observableArrayList(ResultExportScope.values()));
        scope.setId("result-export-scope");
        scope.setAccessibleText("导出行范围");
        scope.setMaxWidth(Double.MAX_VALUE);
        scope.setConverter(new StringConverter<>() {
            @Override public String toString(ResultExportScope value) {
                if (value == null) return "";
                return value == ResultExportScope.CURRENT_FILTERED
                        ? "当前筛选结果（当前排序）" : "全部已加载行（加载顺序）";
            }
            @Override public ResultExportScope fromString(String value) { return null; }
        });
        scope.setValue(ResultExportScope.CURRENT_FILTERED);

        Label summary = new Label();
        summary.setId("result-export-summary");
        Label boundaries = new Label("仅影响行范围；两种范围都使用当前可见列及其顺序。"
                + "\n全部已加载不代表数据库全量，不会重新查询。");
        Label truncated = new Label(snapshot.truncated()
                ? "仅包含已加载结果，数据库中可能还有更多行" : "");
        Label values = new Label();
        values.setId("result-export-values");
        CheckBox consent = new CheckBox("我理解并同意导出当前展示");
        consent.setId("result-export-display-consent");
        consent.setWrapText(true);
        for (Label label : new Label[]{summary, boundaries, truncated, values}) {
            label.setWrapText(true);
            label.setMaxWidth(Double.MAX_VALUE);
        }

        VBox content = new VBox(10, scope, summary, boundaries, truncated, values, consent);
        content.setPrefWidth(460);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(270);
        dialog.getDialogPane().setContent(scroll);

        ButtonType next = new ButtonType("继续", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(next, ButtonType.CANCEL);
        var continueButton = dialog.getDialogPane().lookupButton(next);
        continueButton.setId("result-export-continue");
        dialog.getDialogPane().applyCss();

        var assessments = new EnumMap<ResultExportScope, ResultExportValuePolicy.Assessment>(
                ResultExportScope.class);
        for (var candidate : ResultExportScope.values()) {
            assessments.put(candidate, ResultExportValuePolicy.assess(snapshot.rows(candidate)));
        }
        Runnable refresh = () -> {
            var selected = scope.getValue();
            int rowCount = snapshot.rows(selected).size();
            int columnCount = snapshot.columns().size();
            long special = assessments.get(selected).displayOnlyCells();
            summary.setText((selected == ResultExportScope.CURRENT_FILTERED && rowCount == 0
                    ? "当前筛选结果为 0 行" : rowCount + " 行") + " · " + columnCount + " 列");
            boolean needsConsent = !sql && special > 0;
            consent.setVisible(needsConsent);
            consent.setManaged(needsConsent);
            values.setText(special == 0 ? (sql ? "仅生成待审阅文本，不保证跨库兼容。" : "")
                    : special + " 个特殊值单元格。" + (sql
                    ? " 无法无损生成 INSERT，请调整结果或选择展示格式。"
                    : " 将导出当前展示，不代表完整原值"));
            continueButton.setDisable(rowCount == 0 || columnCount == 0
                    || (sql && special > 0) || (needsConsent && !consent.isSelected()));
        };
        scope.valueProperty().addListener((observable, before, after) -> {
            consent.setSelected(false);
            refresh.run();
        });
        consent.selectedProperty().addListener((observable, before, after) -> refresh.run());
        refresh.run();
        dialog.setResultConverter(button -> button == next && !continueButton.isDisabled()
                ? new Selection(scope.getValue(), consent.isSelected()) : null);
        return dialog;
    }
}
