package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.ResultRowPreview;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javafx.application.Application;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Disposable dialog with synthetic text only; no connection manager or external result source. */
public final class ResultRowTextFindDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage unused) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("row-text-find-profile"))
            throw new IllegalStateException("An explicit disposable row-text-find-profile is required");
        String body = "{\"status\":\"READY\",\"message\":\"alpha beta ALPHA\"}\n"
                + "合成正文，中😀；只读查看。\n".repeat(150) + "alpha at the end\n" + "x".repeat(2000) + "tail_only";
        var result = QueryResult.query(List.of("id", "payload", "payload", "nullable", "empty", "literal", "hidden_only"),
                List.of(Arrays.asList("1", body, "left alpha right", null, "", "NULL", "hidden alpha")), 0);
        var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(0, 1, 2, 3, 4, 5), 0), 2);
        dialog.setTitle("行详情正文查找合成验收 · F6 明暗 · F7 窄窗");
        var theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        theme.register(dialog.getDialogPane().getScene()); theme.installWindowHook();
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) { event.consume(); theme.toggle(); }
            else if (event.getCode() == KeyCode.F7) { event.consume(); dialog.setWidth(dialog.getWidth() > 600 ? 480 : 720); dialog.setHeight(740); }
        });
        dialog.show(); dialog.setWidth(720); dialog.setHeight(740);
    }
}
