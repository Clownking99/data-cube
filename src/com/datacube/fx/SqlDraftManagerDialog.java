package com.datacube.fx;

import com.datacube.config.SqlDraft;
import java.util.function.Function;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

final class SqlDraftManagerDialog {
    private SqlDraftManagerDialog() { }

    static void show(SqlDraftUi owner, Window window, ThemeManager theme, Function<SqlDraft, Boolean> restore) {
        Dialog<Void> dialog = new Dialog<>();
        if (window != null) dialog.initOwner(window);
        dialog.setTitle("SQL 草稿");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        SqlDraftManagerPane pane = new SqlDraftManagerPane(owner.runtime(), restore, dialog::close);
        dialog.getDialogPane().setContent(pane.getNode());
        if (theme != null) theme.applyTo(dialog.getDialogPane());
        try (AutoCloseable subscription = owner.observe(pane::refreshView)) {
            dialog.showAndWait();
        } catch (Exception failure) {
            throw new IllegalStateException("SQL draft manager could not close", failure);
        } finally {
            pane.close();
        }
    }
}
