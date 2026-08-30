package com.datacube.fx;

import static org.junit.jupiter.api.Assertions.*;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import javafx.scene.*;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlDraftUiTest {
  @TempDir Path directory;

  @Test
  void applicationTimerSavesAndBackgroundShutdownReleasesStoreLock() throws Exception {
    FxTaskRunner runner = new FxTaskRunner();
    CountDownLatch saved = new CountDownLatch(1);
    Object[] resources =
        FxUiTestSupport.call(
            () -> {
              Class<?> type = Class.forName("com.datacube.fx.SqlDraftUi");
              Constructor<?> constructor = type.getDeclaredConstructor(Path.class);
              constructor.setAccessible(true);
              Object owner = constructor.newInstance(directory.resolve("drafts"));
              SqlEditorPane pane =
                  new SqlEditorPane(
                      new SessionContext(),
                      null,
                      null,
                      new AppSettings(directory.resolve("settings.properties")),
                      (id, table) -> fail("No designer"),
                      null,
                      null,
                      new SqlHistoryStore(directory.resolve("history.txt")),
                      new ShortcutSettings(directory.resolve("shortcuts.properties")),
                      runner);
              Method bind = type.getDeclaredMethod("bind", SqlEditorPane.class);
              bind.setAccessible(true);
              bind.invoke(owner, pane);
              new Scene((Parent) pane.getNode(), 1200, 800);
              pane.getNode().applyCss();
              ((Label) pane.getNode().lookup("#sql-draft-status"))
                  .textProperty()
                  .addListener(
                      (observable, before, after) -> {
                        if (after.contains("已保存")) saved.countDown();
                      });
              pane.setSqlText("timer checkpoint");
              return new Object[] {owner, pane};
            });
    Object owner = resources[0];
    SqlEditorPane pane = (SqlEditorPane) resources[1];
    try {
      assertTrue(
          saved.await(8, TimeUnit.SECONDS),
          "real application timer must publish without a test pulse");
      var close = FxUiTestSupport.call(pane::requestMandatoryClose);
      assertEquals(
          CloseGuardOutcome.APPROVED, close.toCompletableFuture().get(5, TimeUnit.SECONDS));
    } finally {
      try {
        pane.closeResources();
        FxUiTestSupport.call(
            () -> {
              pane.finalizeCloseOnFx();
              return null;
            });
      } finally {
        Method close = owner.getClass().getDeclaredMethod("closeFromBackground");
        close.setAccessible(true);
        close.invoke(owner);
        runner.close();
      }
    }
    try (var store = SqlDraftStore.open(directory.resolve("drafts"))) {
      assertEquals("timer checkpoint", store.snapshot().drafts().getFirst().sql());
    }
  }
}
