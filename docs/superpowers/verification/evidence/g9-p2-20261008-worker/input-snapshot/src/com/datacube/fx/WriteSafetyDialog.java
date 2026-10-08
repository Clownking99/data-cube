package com.datacube.fx;

import com.datacube.service.WriteOperation;
import com.datacube.service.WriteTarget;
import javafx.scene.control.*;

/** Renders the same immutable request that the service will execute. */
final class WriteSafetyDialog {
    private WriteSafetyDialog() {}

    static WriteOperation.Confirmation confirm(WriteOperation<?> request, boolean always) {
        String blocked = request.blockedReason();
        if (!blocked.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, blocked, ButtonType.OK);
            alert.setHeaderText(null);
            alert.showAndWait();
            return null;
        }
        if (always || request.confirmationRequired()) {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("写入安全确认");
            dialog.setHeaderText("请核对目标、操作与范围");
            ButtonType execute = new ButtonType(request.production() ? "确认在生产环境执行" : "确认执行",
                    ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(execute, ButtonType.CANCEL);
            TextArea details = new TextArea(request.description());
            details.setEditable(false);
            details.setPrefRowCount(18);
            details.setPrefColumnCount(76);
            dialog.getDialogPane().setContent(details);
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != execute) return null;
        }
        // Configuration may have changed while the modal dialog was open.
        try { return request.confirm(); }
        catch (IllegalStateException rejected) {
            Alert alert = new Alert(Alert.AlertType.WARNING, rejected.getMessage(), ButtonType.OK);
            alert.setHeaderText(null);
            alert.showAndWait();
            return null;
        }
    }

    static void update(Button button, WriteTarget target, boolean busy) {
        String reason = target.blockedReason();
        button.setDisable(busy || !reason.isEmpty());
        button.setTooltip(new Tooltip(reason.isEmpty() ? target.description() : reason));
    }

    static Runnable watch(WriteTarget target, com.datacube.fx.task.FxTaskScope tasks, Runnable refresh) {
        Runnable unsubscribe = target.whenChanged(() -> tasks.dispatch(refresh));
        refresh.run();
        return unsubscribe;
    }

    static Label label(WriteTarget target) {
        Label label = new Label();
        update(label, target);
        return label;
    }

    static void update(Label label, WriteTarget target) {
        String reason = target.blockedReason();
        label.setText(target.safety().environment().label() + " · "
                + (reason.isEmpty() ? "可写" : reason));
        label.setTooltip(new Tooltip(target.description()));
    }
}
