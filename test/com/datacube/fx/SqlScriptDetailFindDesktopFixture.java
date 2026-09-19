package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.SqlScriptExecutionReport;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Synthetic, standalone real dialog; no connection manager, SQL file or clipboard access. */
public final class SqlScriptDetailFindDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage unused) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("script-detail-find-profile"))
            throw new IllegalStateException("An explicit disposable script-detail-find-profile is required");
        String sql = "select alpha from synthetic_sample;\n-- 中😀 .* literal SQL snapshot\n"
                + "-- synthetic context\n".repeat(80) + "-- ALPHA near end\n-- alpha final\n";
        String error = "Synthetic diagnostic: alpha was not found.\n"
                + "Synthetic context only; no request was sent.\n".repeat(80)
                + "SQLState=42703 · ALPHA at end\n";
        var entry = SqlScriptExecutionReport.capture(List.of(new ScriptOutcome(4, sql, QueryResult.error(error, 5))), 5).entries().getFirst();
        var dialog = new SqlScriptDetailsDialog(null, entry);
        dialog.setTitle("执行详情查找合成验收 · F6 明暗 · F7 窄窗");
        var theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        theme.register(dialog.getDialogPane().getScene()); theme.installWindowHook();
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) { event.consume(); theme.toggle(); }
            else if (event.getCode() == KeyCode.F7) {
                event.consume(); dialog.setWidth(dialog.getWidth() > 600 ? 480 : 720); dialog.setHeight(700);
            }
        });
        dialog.show(); dialog.setWidth(720); dialog.setHeight(700);
    }
}
