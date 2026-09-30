import com.datacube.fx.*;
import java.nio.file.*;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Exclusive empty-profile AppShell; no startup update check or connection configuration. */
public final class SqlSmallWindowDesktopProbe extends Application {
    public static final class Launcher {
        public static void main(String[] args) { Application.launch(SqlSmallWindowDesktopProbe.class, args); }
    }
    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("sql-small-window-profile")
                || Files.exists(profile.resolve(".datacube")))
            throw new IllegalStateException("New isolated small-window profile required");
        var shell = new AppShell();
        var toggle = new Button("切换明暗");
        var size = new Button("小/大窗口");
        toggle.setOnAction(e -> shell.getThemeManager().toggle());
        size.setOnAction(e -> {
            boolean small = stage.getWidth() > 1000;
            stage.setWidth(small ? 950 : 1250); stage.setHeight(small ? 630 : 900);
        });
        VBox host = new VBox(4, new FlowPane(8, 4, toggle, size), shell.getRoot());
        VBox.setVgrow(shell.getRoot(), Priority.ALWAYS);
        var scene = new Scene(host, 950, 630);
        shell.getThemeManager().register(scene); shell.getThemeManager().installWindowHook();
        stage.setTitle("DataCube SQL 小窗口合成验收");
        stage.setScene(scene); BrandLogo.applyIcons(stage);
        stage.setOnShown(e -> System.out.println("OUTPUT_SCALE=" + stage.getOutputScaleX()));
        stage.setOnCloseRequest(e -> {
            e.consume(); host.setDisable(true);
            shell.shutdownAsync().whenComplete((outcome, error) -> Platform.runLater(() -> {
                if (error != null) { System.err.println("SHUTDOWN_FAILED"); return; }
                if (outcome != ShutdownOutcome.COMPLETED) { host.setDisable(false); System.out.println("SHUTDOWN_" + outcome); return; }
                System.out.println("SHUTDOWN_COMPLETED");
                stage.setOnCloseRequest(null); stage.close();
            }));
        });
        stage.show();
    }
}
