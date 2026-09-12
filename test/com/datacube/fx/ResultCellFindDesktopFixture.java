package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.ResultCellPreview;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Synthetic snapshot in the real standalone dialog; never included in the production image. */
public final class ResultCellFindDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage unused) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("cell-find-desktop-profile"))
            throw new IllegalStateException("An explicit disposable cell-find-desktop-profile is required");
        String text = "alpha · 中😀 · .* 字面文本\n只读内容，不执行 <html> 或 SQL\n"
                + "普通合成行，无目标词。\n".repeat(90) + "ALPHA 尾部第二处\nalpha 尾部第三处\n"
                + "x".repeat(65_536) + "TAIL_ONLY";
        var result = QueryResult.query(List.of("synthetic_note"), List.of(List.of(text)), 0);
        var dialog = new ResultCellDialog(null, ResultCellPreview.capture(result, 0, 0, 0));
        dialog.setTitle("单元格查找合成验收 · F6 明暗 · F7 窄窗");
        var theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        theme.register(dialog.getDialogPane().getScene()); theme.installWindowHook();
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) { event.consume(); theme.toggle(); }
            else if (event.getCode() == KeyCode.F7) {
                event.consume(); dialog.setWidth(dialog.getWidth() > 600 ? 480 : 720); dialog.setHeight(550);
            }
        });
        dialog.show();
    }
}
