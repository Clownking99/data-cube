package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.CredentialCipher;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;

/** Manual, disposable desktop fixture: no registered connections, files or database execution. */
public final class SqlLineCommentDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("line-comment-desktop-profile"))
            throw new IllegalStateException("An explicit disposable line-comment-desktop-profile is required");
        try { open(stage, profile); }
        catch (Exception failure) {
            try (var log = new java.io.PrintWriter(profile.resolve("fixture-startup-error.txt").toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
                failure.printStackTrace(log);
            }
            throw failure;
        }
    }

    private void open(Stage stage, Path profile) throws ReflectiveOperationException {
        var manager = new ConnectionManager(new CredentialCipher()); var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation in fixture"); },
                new SqlHistoryStore(profile.resolve("history.json")), new ShortcutSettings(profile.resolve("shortcuts.properties")), runner);
        pane.setSqlText("  select '中😀';\n\tselect 2;\n-- keep\nselect 3;");
        // The virtualized editor is not discoverable by CSS lookup before the scene is attached.
        var editorField = SqlEditorPane.class.getDeclaredField("editorArea"); editorField.setAccessible(true);
        var editor = (CodeArea) editorField.get(pane);
        editor.getUndoManager().forgetHistory();
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（验收）"); toggle.setOnAction(event -> theme.toggle());
        Button width = new Button("窄/宽窗口（验收）"); width.setOnAction(event -> stage.setWidth(stage.getWidth() > 700 ? 640 : 980));
        Button range = new Button("反选前两行（验收）"); range.setOnAction(event -> {
            editor.selectRange(editor.getText().indexOf("-- keep"), 2); editor.requestFocus();
        });
        VBox root = new VBox(8, new FlowPane(8, 4, toggle, width, range, new Label("合成 SQL，无连接")), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS); Scene scene = new Scene(root, 980, 780);
        theme.register(scene); theme.installWindowHook();
        stage.setTitle("DataCube - 行注释合成验收"); stage.setScene(scene);
        stage.setOnShown(event -> { editor.selectRange(editor.getText().indexOf("-- keep"), 2); editor.requestFocus(); });
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
