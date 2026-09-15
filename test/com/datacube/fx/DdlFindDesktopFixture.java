package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Real read-only viewer with synthetic DDL and an explicitly isolated profile; no DB service. */
public final class DdlFindDesktopFixture extends Application {
    private final FxTaskRunner runner = new FxTaskRunner();
    private DdlViewPane pane;

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("ddl-find-desktop-profile"))
            throw new IllegalStateException("An explicit disposable ddl-find-desktop-profile is required");
        String ddl = "CREATE TABLE synthetic_demo (\n    id INTEGER PRIMARY KEY,\n    user_id INTEGER,\n"
                + "    note VARCHAR(200)\n);\n-- ID id · 中😀 .*\n"
                + "-- synthetic padding: no connection, query or file write\n".repeat(90)
                + "CREATE INDEX synthetic_idx ON synthetic_demo(id);\n";
        pane = new DdlViewPane("DDL: synthetic_demo_with_a_long_object_name_for_narrow_layout", () -> ddl, runner);
        Scene scene = new Scene((Parent) pane.getNode(), 720, 550);
        ThemeManager theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        theme.register(scene);
        stage.setScene(scene);
        stage.setTitle("DDL 查找合成验收 · F6 明暗 · F7 窄窗");
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) { event.consume(); theme.toggle(); }
            else if (event.getCode() == KeyCode.F7) {
                event.consume(); stage.setWidth(stage.getWidth() > 600 ? 480 : 720);
            }
        });
        stage.show();
    }

    @Override public void stop() {
        if (pane != null) { pane.close(); pane.finalizeCloseOnFx(); }
        runner.close();
    }
}
