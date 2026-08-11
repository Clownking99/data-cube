package com.datacube.fx;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;

import java.util.Objects;
import java.util.Optional;

/** Fixed, structured Schema Diff dialogs that never interpolate SQL, URLs or exception details. */
public final class SchemaDiffDialogs {
    private SchemaDiffDialogs() {}

    public static String confirmationSummary(SchemaDiffViewModel.Confirmation confirmation) {
        Objects.requireNonNull(confirmation, "confirmation");
        StringBuilder summary = new StringBuilder()
                .append("目标连接: ").append(confirmation.targetIdentity()).append('\n')
                .append("目标 Schema: ").append(confirmation.targetSchema()).append('\n')
                .append("已选择变更: ").append(confirmation.selectedChangeCount()).append('\n')
                .append("生产环境: ").append(confirmation.production() ? "是（生产）" : "否");
        if (confirmation.oracleImplicitCommitWarning()) {
            summary.append("\n警告: Oracle DDL 会隐式提交，不能声明事务回滚原子性。");
        }
        if (confirmation.destructive()) {
            summary.append("\n警告: 所选计划包含破坏性变更，需要第二次确认。");
        }
        return summary.toString();
    }

    public static boolean confirmDeployment(
            Window owner, SchemaDiffViewModel.Confirmation confirmation) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                confirmationSummary(confirmation), ButtonType.YES, ButtonType.NO);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("确认 Schema 部署");
        dialog.setHeaderText("请核对固定目标与变更数量");
        return dialog.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    public static Optional<String> confirmDestructive(
            Window owner, SchemaDiffViewModel.Confirmation confirmation) {
        TextInputDialog dialog = new TextInputDialog();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("确认破坏性 Schema 变更");
        dialog.setHeaderText("请输入目标 Schema 比较键以继续");
        dialog.setContentText("精确输入: " + confirmation.targetSchemaComparisonKey());
        return dialog.showAndWait();
    }

    public static boolean confirmClose(Window owner) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                "当前 Schema 对比仍有任务或已选择的变更。确定关闭吗？",
                ButtonType.YES, ButtonType.NO);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("关闭 Schema 对比");
        dialog.setHeaderText(null);
        return dialog.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }
}
