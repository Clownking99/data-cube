package com.datacube.fx;

import java.nio.file.*;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Real empty AppShell with an exclusive profile; never calls the startup update check. */
public final class G8ShellDesktopFixture extends Application {
    public static final class Launcher {
        public static void main(String[] args) { Application.launch(G8ShellDesktopFixture.class, args); }
    }
    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("g8-shell-desktop-profile"))
            throw new IllegalStateException("Exclusive G8 shell profile required");
        if (Files.exists(profile.resolve(".datacube")))
            throw new IllegalStateException("Shell acceptance requires a new empty profile");
        var shell = new AppShell();
        var scene = new Scene(shell.getRoot(), 1200, 800);
        shell.getThemeManager().register(scene);
        shell.getThemeManager().installWindowHook();
        stage.setTitle("DataCube G8 空白壳验收（禁止新建连接与更新）");
        stage.setScene(scene); stage.setMinWidth(900); stage.setMinHeight(600);
        BrandLogo.applyIcons(stage);
        stage.setOnShown(e -> System.out.println("SYNTHETIC_OUTPUT_SCALE=" + stage.getOutputScaleX() + "x" + stage.getOutputScaleY()));
        stage.setOnCloseRequest(e -> {
            e.consume(); shell.getRoot().setDisable(true);
            shell.shutdownAsync().whenComplete((outcome, error) -> Platform.runLater(() -> {
                if (error != null) { System.err.println("SHELL_SHUTDOWN_FAILED"); return; }
                if (outcome == ShutdownOutcome.CANCELLED) { shell.getRoot().setDisable(false); return; }
                if (outcome != ShutdownOutcome.COMPLETED) { System.err.println("SHELL_SHUTDOWN_PARTIAL"); return; }
                stage.setOnCloseRequest(null); stage.close();
            }));
        });
        stage.show();
    }
}
