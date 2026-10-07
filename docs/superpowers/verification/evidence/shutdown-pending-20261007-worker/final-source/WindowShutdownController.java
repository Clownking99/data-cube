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
    private VBox feedback;

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
            showPending();
            shutdown.get().whenComplete((outcome,failure) -> Platform.runLater(() -> {
                ShutdownQuarantine.Action action=quarantine.settle(outcome,failure);
                if(failure!=null) {
                    System.err.println("[DataCube] shutdown failure: "+failure);
                    failure.printStackTrace(System.err);
                }
                if(action==ShutdownQuarantine.Action.RECOVER) {clearFeedback();body.setDisable(false);return;}
                if(action==ShutdownQuarantine.Action.FATAL) {
                    System.err.println("[DataCube] shutdown partially failed; application is not retryable");
                    showPartialFailure();
                    return;
                }
                clearFeedback();window.setOnCloseRequest(null);window.close();
            }));
        });
    }

    public StackPane getRoot() {return root;}

    private void showPending() {
        showFeedback("shutdown-pending",new String[]{"title","state","caution"},new String[]{
                "正在退出，请稍候",
                "正在处理退出请求。若出现确认对话框，请先完成选择；无需重复关闭窗口。",
                "等待期间请勿强制结束进程，以免中断尚在进行的操作或丢失未保存内容。"});
    }

    private void showPartialFailure() {
        showFeedback("shutdown-failure",new String[]{"title","state","uncertain","next"},new String[]{
                "退出未完成，窗口已进入保护状态",
                "部分关闭步骤未能完成，当前窗口已停用；重复关闭不会重试。",
                "在途操作可能已部分完成，程序无法确认数据库操作结果或文件保存状态。请先通过独立工具核对实际结果，避免重复执行。",
                "如需结束程序，可通过操作系统手动结束 DataCube 后重新打开；这可能中断尚在进行的操作，未保存内容可能丢失。"});
    }

    private void showFeedback(String prefix,String[] ids,String[] messages) {
        clearFeedback();
        var notice=new VBox(8);notice.setId(prefix+"-notice");
        for(int i=0;i<messages.length;i++) {
            var label=new Label(messages[i]);label.setId(prefix+"-"+ids[i]);
            label.setWrapText(true);label.setMinHeight(Region.USE_PREF_SIZE);label.setMaxWidth(Double.MAX_VALUE);
            label.setStyle(i==0 ? "-fx-text-fill: -brand-fg; -fx-font-weight: bold; -fx-font-size: 16px;" : "-fx-text-fill: -brand-fg;");
            notice.getChildren().add(label);
        }
        notice.setPadding(new Insets(16));notice.setMinWidth(0);notice.setMaxWidth(760);notice.setMaxHeight(Region.USE_PREF_SIZE);
        notice.setStyle("-fx-background-color: -brand-surface; -fx-border-color: -brand-border; -fx-background-radius: 6; -fx-border-radius: 6;");
        StackPane.setAlignment(notice,Pos.TOP_CENTER);StackPane.setMargin(notice,new Insets(16));
        // The sole feedback node remains readable outside the quarantined body.
        feedback=notice;root.getChildren().add(notice);
    }

    private void clearFeedback() {
        if(feedback!=null) {root.getChildren().remove(feedback);feedback=null;}
    }
    private static boolean confirmRunning(Stage window) {
        var alert=new Alert(Alert.AlertType.CONFIRMATION,
                "迁移任务正在执行中，强制关闭可能导致数据不完整。\n确定关闭？",ButtonType.YES,ButtonType.NO);
        alert.initOwner(window);alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.NO)==ButtonType.YES;
    }
}
