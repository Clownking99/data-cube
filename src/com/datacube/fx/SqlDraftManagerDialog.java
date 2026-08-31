package com.datacube.fx;

import com.datacube.config.SqlDraft;
import java.util.function.Function;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;

final class SqlDraftManagerDialog {
    private SqlDraftManagerDialog() { }

    static void show(SqlDraftUi owner, Window window, ThemeManager theme, Function<SqlDraft, Boolean> restore,
            SqlWorkspaceRecoveryTabs recovery) {
        Dialog<Void> dialog = new Dialog<>();
        if (window != null) dialog.initOwner(window);
        dialog.setTitle("SQL 草稿");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        SqlDraftManagerPane pane = new SqlDraftManagerPane(owner.runtime(), restore, dialog::close);
        SqlWorkspaceManagerPane workspace = recovery != null && owner.workspace() != null
                ? new SqlWorkspaceManagerPane(owner, recovery) : null;
        if (workspace == null) dialog.getDialogPane().setContent(pane.getNode());
        else {
            VBox content = new VBox(8, workspace.getNode(), pane.getNode());
            VBox.setVgrow(pane.getNode(), Priority.ALWAYS);
            dialog.getDialogPane().setContent(content);
        }
        if (theme != null) theme.applyTo(dialog.getDialogPane());
        try (AutoCloseable subscription = owner.observe(() -> {
            pane.refreshView();
            if (workspace != null) workspace.refreshView();
        })) {
            dialog.showAndWait();
        } catch (Exception failure) {
            throw new IllegalStateException("SQL draft manager could not close", failure);
        } finally {
            pane.close();
            if (workspace != null) workspace.close();
        }
    }

    static void show(SqlDraftUi owner, Window window, ThemeManager theme, Function<SqlDraft, Boolean> restore) {
        show(owner, window, theme, restore, null);
    }
}
