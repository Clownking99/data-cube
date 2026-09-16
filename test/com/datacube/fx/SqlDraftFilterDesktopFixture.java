package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.DraftManagementProbe;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.spi.model.DbType;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Real draft pane/coordinator; synthetic records and restore sink only, never a user store or database. */
public final class SqlDraftFilterDesktopFixture extends Application {
    private final DraftManagementProbe probe = new DraftManagementProbe();
    private SqlDraftCoordinator runtime;
    private SqlDraftManagerPane pane;
    private Timeline pump;
    private int restores;

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage unused) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("sql-draft-filter-desktop-profile"))
            throw new IllegalStateException("Explicit disposable sql-draft-filter-desktop-profile required");
        probe.records.addAll(List.of(
                new SqlDraft(new UUID(0, 1), 100_000L, "synthetic-pg", DbType.POSTGRESQL,
                        "PG Lab", "ledger", "select invoice.* from invoice;\r\n-- synthetic draft only"),
                new SqlDraft(new UUID(0, 2), 90_000L, "synthetic-oracle", DbType.ORACLE,
                        "Payroll", "Tax", "select salary from payroll;\n-- synthetic draft only"),
                new SqlDraft(new UUID(0, 3), 80_000L, null, null, null, null, "")));
        runtime = probe.create(Runnable::run, Platform::isFxApplicationThread);
        probe.drain();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("合成草稿筛选 · F6 明暗 · F7 窄窗 · 恢复仅记录内存");
        pane = new SqlDraftManagerPane(runtime, draft -> {
            dialog.setTitle("合成草稿筛选 · 内存恢复 " + (++restores) + " · " + draft.connectionName());
            return true;
        }, () -> {});
        dialog.getDialogPane().setContent(pane.getNode());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        ThemeManager theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        theme.register(dialog.getDialogPane().getScene());
        theme.installWindowHook();
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) { theme.toggle(); event.consume(); }
            else if (event.getCode() == KeyCode.F7) {
                dialog.setWidth(dialog.getWidth() > 750 ? 680 : 900); event.consume();
            }
        });
        pump = new Timeline(new KeyFrame(Duration.millis(100), event -> { probe.drain(); pane.refreshView(); }));
        pump.setCycleCount(Timeline.INDEFINITE);
        dialog.setOnHidden(event -> Platform.exit());
        pump.play(); dialog.show();
    }

    @Override public void stop() {
        if (pump != null) pump.stop();
        if (pane != null) pane.close();
        if (runtime != null) { runtime.shutdown(); probe.drain(); }
        if (probe.clears != 0 || probe.deletions != 0 || !probe.enabled || probe.records.size() != 3)
            throw new AssertionError("Desktop verification must not confirm destructive or privacy changes");
    }
}
