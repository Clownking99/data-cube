package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Owns window shutdown feedback only; the supplied application still owns resource cleanup. */
public final class WindowShutdownController {
    private final StackPane root;
    private final ShutdownQuarantine quarantine=new ShutdownQuarantine();

    public WindowShutdownController(Stage window,Parent body,BooleanSupplier running,
            Supplier<CompletionStage<ShutdownOutcome>> shutdown) {
        this(window,body,running,shutdown,() -> confirmRunning(window));
    }

    WindowShutdownController(Stage window,Parent body,BooleanSupplier running,
            Supplier<CompletionStage<ShutdownOutcome>> shutdown,BooleanSupplier confirm) {
        Objects.requireNonNull(window);Objects.requireNonNull(body);Objects.requireNonNull(running);
        Objects.requireNonNull(shutdown);Objects.requireNonNull(confirm);
        root=new StackPane(body);
        window.setOnCloseRequest(event -> {
            event.consume();
            if(quarantine.isQuarantined()) return;
            if(running.getAsBoolean() && !confirm.getAsBoolean()) return;
            if(!quarantine.begin()) return;
            body.setDisable(true);
            shutdown.get().whenComplete((outcome,failure) -> Platform.runLater(() -> {
                ShutdownQuarantine.Action action=quarantine.settle(outcome,failure);
                if(failure!=null) {
                    System.err.println("[DataCube] shutdown failure: "+failure);
                    failure.printStackTrace(System.err);
                }
                if(action==ShutdownQuarantine.Action.RECOVER) {body.setDisable(false);return;}
                if(action==ShutdownQuarantine.Action.FATAL) {
                    System.err.println("[DataCube] shutdown partially failed; application is not retryable");
                    showPartialFailure();
                    return;
                }
                window.setOnCloseRequest(null);window.close();
            }));
        });
    }

    public StackPane getRoot() {return root;}

    private void showPartialFailure() {
        var title=new Label("退出未完成，窗口已进入保护状态");title.setId("shutdown-failure-title");
        title.setStyle("-fx-text-fill: -brand-fg; -fx-font-weight: bold; -fx-font-size: 16px;");
        var state=new Label("部分关闭步骤未能完成，当前窗口已停用；重复关闭不会重试。");state.setId("shutdown-failure-state");
        var uncertain=new Label("在途操作可能已部分完成，程序无法确认数据库操作结果或文件保存状态。请先通过独立工具核对实际结果，避免重复执行。");uncertain.setId("shutdown-failure-uncertain");
        var next=new Label("如需结束程序，可通过操作系统手动结束 DataCube 后重新打开；这可能中断尚在进行的操作，未保存内容可能丢失。");next.setId("shutdown-failure-next");
        for(var label:new Label[]{title,state,uncertain,next}) {
            label.setWrapText(true);label.setMinHeight(Region.USE_PREF_SIZE);label.setMaxWidth(Double.MAX_VALUE);
            if(label!=title)label.setStyle("-fx-text-fill: -brand-fg;");
        }
        var notice=new VBox(8,title,state,uncertain,next);notice.setId("shutdown-failure-notice");
        notice.setPadding(new Insets(16));notice.setMinWidth(0);notice.setMaxWidth(760);notice.setMaxHeight(Region.USE_PREF_SIZE);
        notice.setStyle("-fx-background-color: -brand-surface; -fx-border-color: -brand-border; -fx-background-radius: 6; -fx-border-radius: 6;");
        StackPane.setAlignment(notice,Pos.TOP_CENTER);StackPane.setMargin(notice,new Insets(16));
        // A sibling of the quarantined body remains fully readable; it offers no retry or exit action.
        root.getChildren().add(notice);
    }

    private static boolean confirmRunning(Stage window) {
        var alert=new Alert(Alert.AlertType.CONFIRMATION,
                "迁移任务正在执行中，强制关闭可能导致数据不完整。\n确定关闭？",ButtonType.YES,ButtonType.NO);
        alert.initOwner(window);alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.NO)==ButtonType.YES;
    }
}