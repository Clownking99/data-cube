package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;

/** Real pane in a disposable profile, with only synthetic file/text and no registered connections. */
public final class SqlFormatDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("format-desktop-profile"))
            throw new IllegalStateException("An explicit disposable format-desktop-profile is required");
        Files.createDirectories(profile);
        var manager = new ConnectionManager(new CredentialCipher()); var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var shortcuts = new ShortcutSettings(profile.resolve("shortcuts.properties"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation in fixture"); },
                new SqlHistoryStore(profile.resolve("history.json")), shortcuts, runner);
        String sql = "-- synthetic boundary\r\nselect a,b from sample;\n  \nselect untouched;\r";
        Path file = profile.resolve("synthetic-format.sql");
        if (!Files.exists(file)) Files.writeString(file, sql);
        var fileTitle = new Label();
        pane.installSqlScriptFileController(new SqlScriptFileStore().load(file), new SqlScriptFileStore(),
                new RecentSqlFiles(profile.resolve("recent")), fileTitle::setText, "SQL");
        var editorField = SqlEditorPane.class.getDeclaredField("editorArea"); editorField.setAccessible(true);
        var editor = (CodeArea) editorField.get(pane); editor.getUndoManager().forgetHistory();
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（验收）"); toggle.setOnAction(event -> theme.toggle());
        Button width = new Button("窄/宽窗口（验收）"); width.setOnAction(event -> stage.setWidth(stage.getWidth() > 700 ? 640 : 980));
        Runnable selectSql = () -> {
            String text = editor.getText(); int start = text.indexOf('\n') + 1, end = text.indexOf("\n  \n");
            editor.selectRange(end, start); editor.requestFocus();
        };
        Button range = new Button("反选查询（验收）"); range.setOnAction(event -> selectSql.run());
        Button blank = new Button("选空白（验收）"); blank.setOnAction(event -> {
            int start = editor.getText().indexOf("\n  \n") + 1; editor.selectRange(start, start + 2); editor.requestFocus();
        });
        var lastKey = new Label("键盘事件（验收）");
        var selected = new Label();
        Runnable update = () -> selected.setText("anchor=" + editor.getAnchor() + " caret=" + editor.getCaretPosition() + " · 合成文件，无数据库连接");
        editor.selectionProperty().addListener(ignored -> update.run()); update.run();
        VBox root = new VBox(8, new FlowPane(8, 4, toggle, width, range, blank), new FlowPane(12, 4, fileTitle, selected, lastKey), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS); Scene scene = new Scene(root, 980, 780);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> lastKey.setText("key=" + event.getCode()
                + " ctrl=" + event.isControlDown() + " shift=" + event.isShiftDown() + " alt=" + event.isAltDown()));
        theme.register(scene); theme.installWindowHook(); stage.setTitle("DataCube - SQL 美化范围合成验收"); stage.setScene(scene); BrandLogo.applyIcons(stage);
        stage.setOnShown(event -> selectSql.run());
        stage.setOnCloseRequest(event -> {
            event.consume(); root.setDisable(true);
            Thread.startVirtualThread(() -> {
                pane.closeResources(); runner.close(); manager.closeAll();
                Platform.runLater(() -> { pane.finalizeCloseOnFx(); stage.setOnCloseRequest(null); stage.close(); });
            });
        });
        stage.show();
    }
}
