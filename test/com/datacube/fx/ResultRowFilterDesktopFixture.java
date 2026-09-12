package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.ResultRowPreview;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Real dialog as the sole window, for keyboard testing without owner-window activation ambiguity. */
public final class ResultRowFilterDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage unused) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("row-filter-desktop-profile"))
            throw new IllegalStateException("An explicit disposable row-filter-desktop-profile is required");
        var result = QueryResult.query(List.of("id", "note", "same", "same", "hidden_only"),
                List.of(List.of("1", "<html>只读内容</html>\n  第二行 😀", "left", "right", "hidden-value")), 0);
        var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(0, 1, 2, 3), 0), 2);
        dialog.setTitle("字段筛选合成验收 · F6 明暗 · F7 窄窗");
        var theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        theme.register(dialog.getDialogPane().getScene()); theme.installWindowHook();
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) { event.consume(); theme.toggle(); }
            else if (event.getCode() == KeyCode.F7) {
                event.consume(); dialog.setWidth(dialog.getWidth() > 600 ? 480 : 720); dialog.setHeight(740);
            }
        });
        dialog.show();
    }
}
