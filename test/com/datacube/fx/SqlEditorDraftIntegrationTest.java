package com.datacube.fx;

import static org.junit.jupiter.api.Assertions.*;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlEditorDraftIntegrationTest {
  @TempDir Path directory;

  @Test
  void autoSavePreservesRawTextSchemaAndIndependentQueryStatus() throws Exception {
    try (Fixture f = new Fixture("", null, true)) {
      f.fx(
          () -> {
            f.resultStatus().setText("查询完成");
            f.pane.setSqlText("select '中文';\n");
            f.schema().setText("  raw_schema  ");
          });
      long revision = f.fxValue(() -> (long) field(f.pane, "resultStatusRevision"));
      f.tick(999);
      assertTrue(f.snapshot().drafts().isEmpty());
      f.tick(1000);
      var draft = f.snapshot().drafts().getFirst();
      assertEquals("select '中文';\n", draft.sql());
      assertEquals("  raw_schema  ", draft.schema());
      f.fx(
          () -> {
            assertEquals("查询完成", f.resultStatus().getText());
            assertEquals(revision, field(f.pane, "resultStatusRevision"));
            assertTrue(f.label("status").getText().contains("已保存"));
            assertTrue(f.label("privacy").getText().contains("关闭草稿不停止原有 SQL 历史记录"));
          });
    }
  }

  @Test
  void newEmptyEditorDoesNotCreateCheckpoint() throws Exception {
    try (Fixture f = new Fixture("", null, true)) {
      f.tick(20_000);
      assertTrue(f.snapshot().drafts().isEmpty());
      assertEquals(CloseGuardOutcome.APPROVED, f.beginClose(true).get(5, TimeUnit.SECONDS));
      assertTrue(f.snapshot().drafts().isEmpty());
    }
  }

  @Test
  void historyInitializationQualifiesButRestoredCheckpointStartsClean() throws Exception {
    try (Fixture f = new Fixture("history text", null, true)) {
      f.tick(1000);
      assertEquals("history text", f.snapshot().drafts().getFirst().sql());
    }
    try (Fixture f = new Fixture("restored text", 100_000L, true)) {
      f.tick(1000);
      assertEquals(1, f.snapshot().drafts().size());
      assertTrue(f.snapshot().drafts().stream().noneMatch(d -> d.id().equals(f.id)));
      f.fx(() -> assertTrue(f.label("status").getText().contains("已保存")));
    }
  }

  @Test
  void successfulEditorClearWarnsAboutProtectedCorruptFiles() throws Exception {
    try (Fixture f = new Fixture("select 'synthetic';", null, true)) {
      f.tick(1000);
      assertEquals(1, f.snapshot().drafts().size());
      Path corrupt = directory.resolve("drafts").resolve(UUID.randomUUID() + ".draft");
      byte[] retained = new byte[] {1, 2, 3, 4};
      java.nio.file.Files.write(corrupt, retained);
      f.fx(
          () ->
              SqlDraftManagerTest.respondToDialog(
                  () -> ((Button) f.pane.getNode().lookup("#sql-draft-clear")).fire(),
                  dialog -> {
                    ButtonType accept =
                        dialog.getButtonTypes().stream()
                            .filter(type -> type != ButtonType.CANCEL)
                            .findFirst()
                            .orElseThrow();
                    ((Button) dialog.lookupButton(accept)).fire();
                  }));
      f.drain();
      f.fx(
          () -> {
            var result = f.runtime.lastManagementResult();
            assertTrue(result.succeeded());
            assertTrue(result.snapshot().drafts().isEmpty());
            assertFalse(result.snapshot().problems().isEmpty());
            assertTrue(f.label("notice").getText().contains("仍保留"));
            assertTrue(f.label("notice").getText().contains("SQL"));
            assertTrue(f.label("notice").isVisible());
            assertEquals("select 'synthetic';", f.editor().getText());
          });
      assertArrayEquals(retained, java.nio.file.Files.readAllBytes(corrupt));
    }
  }

  @Test
  void closingWaitsForLatestSnapshotAndBlocksProgrammaticEditingActions() throws Exception {
    try (Fixture f = new Fixture("select 1", null, true)) {
      var close = f.beginClose(true);
      assertFalse(close.isDone());
      f.fx(
          () -> {
            assertFalse(f.editor().isEditable());
            assertTrue(f.schema().isDisable());
            f.pane.setSqlText("replacement");
            invoke(f.pane, "onFormat");
            invoke(f.pane, "toggleLineComment");
            invoke(f.pane, "toggleBlockComment");
            Event.fireEvent(
                f.editor(),
                new KeyEvent(
                    KeyEvent.KEY_PRESSED, "", "", KeyCode.SLASH, false, true, false, false));
            assertEquals("select 1", f.editor().getText());
            Object completion = field(f.pane, "autoComplete");
            assertNotNull(completion);
            @SuppressWarnings("unchecked")
            ListView<String> choices = (ListView<String>) field(completion, "list");
            choices.getItems().setAll("changed");
            choices.getSelectionModel().selectFirst();
            invoke(completion, "applySelection");
            assertEquals("select 1", f.editor().getText());
          });
      f.drain();
      assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
      assertEquals("select 1", f.snapshot().drafts().getFirst().sql());
    }
  }

  @Test
  void closingCapturesEditNewerThanAlreadyQueuedAutosave() throws Exception {
    try (Fixture f = new Fixture("old", null, true)) {
      f.clock.set(1000);
      f.fx(f.runtime::pulse);
      f.fx(() -> f.pane.setSqlText("newest"));
      var close = f.beginClose(true);
      f.drain();
      assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
      assertEquals("newest", f.snapshot().drafts().getFirst().sql());
    }
  }

  @Test
  void initializationRefusesMandatoryCloseAndRetainsSubscriptions() throws Exception {
    try (Fixture f = new Fixture("before init", null, false)) {
      assertEquals(CloseGuardOutcome.REJECTED, f.beginClose(true).get(5, TimeUnit.SECONDS));
      f.fx(
          () -> {
            assertTrue(f.editor().isEditable());
            assertFalse(f.schema().isDisable());
            assertFalse(((AtomicBoolean) field(f.pane, "resourcesClosed")).get());
            f.pane.setSqlText("after refusal");
          });
      f.drain();
      f.tick(1000);
      assertEquals("after refusal", f.snapshot().drafts().getFirst().sql());
    }
  }

  @Test
  void writeFailureRefusesMandatoryCloseAndRestoresExactFlagsThenSavesNewEdit() throws Exception {
    try (Fixture f = new Fixture("keep me", null, true)) {
      f.fx(
          () -> {
            f.editor().setEditable(false);
            f.schema().setDisable(true);
            f.schema().setText("s".repeat(4097));
          });
      var close = f.beginClose(true);
      f.drain();
      assertEquals(CloseGuardOutcome.REJECTED, close.get(5, TimeUnit.SECONDS));
      f.fx(
          () -> {
            assertFalse(f.editor().isEditable());
            assertTrue(f.schema().isDisable());
            assertFalse(((AtomicBoolean) field(f.pane, "resourcesClosed")).get());
            Object binding = field(f.pane, "draftBinding");
            var handle = (SqlDraftCoordinator.Handle) field(binding, "handle");
            assertEquals(SqlDraftCoordinator.FailureReason.INVALID_DRAFT, handle.status().failureReason());
            String statusText = f.label("status").getText();
            assertTrue(statusText.contains("草稿内容无法保存"), statusText);
            f.editor().setEditable(true);
            f.schema().setDisable(false);
            f.schema().setText("valid");
            f.pane.setSqlText("still editable");
          });
      f.tick(1000);
      assertEquals("still editable", f.snapshot().drafts().getFirst().sql());
    }
  }

  @Test
  void clearDoesNotResurrectUneditedTextOnClose() throws Exception {
    try (Fixture f = new Fixture("old", null, true)) {
      f.tick(1000);
      assertEquals(1, f.snapshot().drafts().size());
      var clear = f.fxValue(f.runtime::clear);
      f.drain();
      assertTrue(clear.get(5, TimeUnit.SECONDS).succeeded());
      assertEquals(CloseGuardOutcome.APPROVED, f.beginClose(true).get(5, TimeUnit.SECONDS));
      assertTrue(f.snapshot().drafts().isEmpty());
    }
  }

  @Test
  void explicitDisableAllowsCloseWithoutClaimingLatestSaved() throws Exception {
    try (Fixture f = new Fixture("not saved", null, true)) {
      var disable = f.fxValue(() -> f.runtime.setEnabled(false));
      f.drain();
      assertTrue(disable.get(5, TimeUnit.SECONDS).succeeded());
      f.tick(1000);
      f.fx(() -> assertEquals("草稿保护已关闭", f.label("status").getText()));
      assertEquals(CloseGuardOutcome.APPROVED, f.beginClose(true).get(5, TimeUnit.SECONDS));
      assertTrue(f.snapshot().drafts().isEmpty());
    }
  }

  @Test
  void constructionAbortDetachesHandleAndSubscriptions() throws Exception {
    try (Fixture f = new Fixture("before abort", null, true)) {
      f.pane.closeResources();
      f.fx(
          () -> {
            f.editor().replaceText("after abort");
            var replacement =
                f.runtime.attach(
                    f.id,
                    null,
                    new SqlDraftCoordinator.Source() {
                      public boolean hasText() {
                        return false;
                      }

                      public SqlDraft capture(UUID id, long at) {
                        throw new AssertionError("empty");
                      }
                    });
            replacement.detach();
          });
      f.tick(1000);
      assertTrue(f.snapshot().drafts().isEmpty());
      assertEquals(1, f.detachments.get());
    }
  }

  @Test
  void explicitAdmissionUpdatesStoredStableConnectionIdentity() throws Exception {
    try (Fixture f = new Fixture("select 1", null, true)) {
      f.fx(
          () -> {
            var cfg =
                new ConnConfig(
                    "stable-id",
                    "display name",
                    DbType.POSTGRESQL,
                    "example.invalid",
                    5432,
                    "db",
                    "user",
                    "",
                    Map.of());
            @SuppressWarnings("unchecked")
            Set<String> warmed = (Set<String>) field(f.pane, "prewarmed");
            warmed.add(cfg.id());
            f.context.setActiveConnection(cfg);
            invoke(f.pane, "admitCurrentConnection");
            assertNull(field(f.pane, "jdbcSession"));
          });
      f.tick(1000);
      var draft = f.snapshot().drafts().getFirst();
      assertEquals("stable-id", draft.connectionId());
      assertEquals(DbType.POSTGRESQL, draft.connectionType());
      assertEquals("display name", draft.connectionName());
    }
  }

  @Test
  void interactiveCancelKeepsEditorAndExplicitDiscardKeepsPreviousCheckpoint() throws Exception {
    try (Fixture f = new Fixture("checkpoint", null, true)) {
      f.tick(1000);
      f.fx(
          () -> {
            f.pane.setSqlText("latest");
            f.schema().setText("s".repeat(4097));
          });
      var cancelled = f.beginClose(false);
      f.drain();
      f.dismiss("取消");
      assertEquals(CloseGuardOutcome.REJECTED, cancelled.get(5, TimeUnit.SECONDS));
      f.fx(() -> assertTrue(f.editor().isEditable()));
      assertEquals("checkpoint", f.snapshot().drafts().getFirst().sql());
      var discarded = f.beginClose(false);
      f.drain();
      f.dismiss("放弃本次最新修改并关闭");
      assertEquals(CloseGuardOutcome.APPROVED, discarded.get(5, TimeUnit.SECONDS));
      assertEquals("checkpoint", f.snapshot().drafts().getFirst().sql());
      assertTrue(f.snapshot().protectionEnabled());
    }
  }

  @Test
  void mandatoryDraftRefusalDoesNotDropAnOperationCompletingDuringFlush() throws Exception {
    try (Fixture f = new Fixture("latest", null, true)) {
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicInteger callbacks = new AtomicInteger();
      CountDownLatch delivered = new CountDownLatch(1);
      var operations = (SerialSessionOperationQueue) field(f.pane, "sessionOperations");
      var operation =
          operations.submit(
              SerialSessionOperationQueue.OperationKind.EXECUTE,
              () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "query result";
              },
              result -> {
                callbacks.incrementAndGet();
                delivered.countDown();
              },
              failure -> fail(failure));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        f.fx(() -> f.schema().setText("s".repeat(4097)));
        var close = f.beginClose(true);
        assertFalse(close.isDone());
        release.countDown();
        operation.get(5, TimeUnit.SECONDS);
        assertTrue(
            delivered.await(5, TimeUnit.SECONDS),
            "a draft-pending close must not drop completed results");
        assertEquals(1, callbacks.get(), "a draft-pending close must not drop completed results");
        f.drain();
        assertEquals(CloseGuardOutcome.REJECTED, close.get(5, TimeUnit.SECONDS));
        assertEquals(1, callbacks.get());
        f.fx(() -> assertFalse(((AtomicBoolean) field(f.pane, "resourcesClosed")).get()));
      } finally {
        release.countDown();
        operation.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void failingDraftPrecedesRunningQueryCloseDecisionDialog() throws Exception {
    try (Fixture f = new Fixture("latest", null, true)) {
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicInteger prematureDialogs = new AtomicInteger();
      var operations = (SerialSessionOperationQueue) field(f.pane, "sessionOperations");
      var operation =
          operations.submit(
              SerialSessionOperationQueue.OperationKind.EXECUTE,
              () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "query result";
              },
              result -> {},
              failure -> fail(failure));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        f.fx(() -> f.schema().setText("s".repeat(4097)));
        var close =
            f.fxValue(
                () -> {
                  Platform.runLater(
                      () -> {
                        // Runs inside a premature modal loop, or after correct asynchronous return.
                        for (Window window : List.copyOf(Window.getWindows())) {
                          if (!window.isShowing()) continue;
                          var cancel =
                              window.getScene().getRoot().lookupAll(".button").stream()
                                  .filter(Button.class::isInstance)
                                  .map(Button.class::cast)
                                  .filter(Button::isCancelButton)
                                  .findFirst();
                          if (cancel.isPresent()) {
                            prematureDialogs.incrementAndGet();
                            cancel.get().fire();
                          }
                        }
                      });
                  return f.pane.requestClose().toCompletableFuture();
                });
        f.fx(() -> {});
        assertEquals(
            0,
            prematureDialogs.get(),
            "no transaction/running-query decision before draft protection");
        assertFalse(close.isDone());
        f.drain();
        f.dismiss("取消");
        assertEquals(CloseGuardOutcome.REJECTED, close.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
        operation.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closingImmediatelyHidesAnAlreadyVisibleCompletionPopup(boolean mandatory) throws Exception {
    try (Fixture f = new Fixture("select 1", null, true)) {
      javafx.stage.Stage[] stage = new javafx.stage.Stage[1];
      try {
        var close =
            f.fxValue(
                () -> {
                  stage[0] = new javafx.stage.Stage();
                  stage[0].setScene(f.pane.getNode().getScene());
                  stage[0].show();
                  Object completion = field(f.pane, "autoComplete");
                  assertNotNull(completion);
                  var popup = (javafx.stage.PopupWindow) field(completion, "popup");
                  popup.show(f.editor(), stage[0].getX() + 20, stage[0].getY() + 20);
                  assertTrue(popup.isShowing(), "fixture must begin with an actual visible popup");
                  var result =
                      (mandatory ? f.pane.requestMandatoryClose() : f.pane.requestClose())
                          .toCompletableFuture();
                  assertFalse(
                      popup.isShowing(),
                      "freeze must dismiss the already-visible popup immediately");
                  return result;
                });
        f.drain();
        assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
      } finally {
        f.fx(
            () -> {
              if (stage[0] != null) stage[0].hide();
            });
      }
    }
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void invoke(Object target, String name) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(target);
  }

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }

  private final class Fixture implements AutoCloseable {
    final Queue<Runnable> writer = new ConcurrentLinkedQueue<>();
    final AtomicLong clock = new AtomicLong();
    final AtomicInteger detachments = new AtomicInteger();
    final UUID id = UUID.randomUUID();
    final FxTaskRunner runner = new FxTaskRunner();
    final SessionContext context = new SessionContext();
    final SqlDraftCoordinator runtime;
    final SqlEditorPane pane;

    Fixture(String initial, Long savedAt, boolean initialized) throws Exception {
      runtime =
          fxValue(
              () ->
                  new SqlDraftCoordinator(
                      directory.resolve("drafts"),
                      writer::add,
                      Platform::runLater,
                      Platform::isFxApplicationThread,
                      clock::get,
                      () -> 100_000L));
      if (initialized) drain();
      pane =
          fxValue(
              () -> {
                var editor =
                    new SqlEditorPane(
                        context,
                        null,
                        null,
                        new AppSettings(directory.resolve("settings.properties")),
                        (connection, table) -> fail("No designer"),
                        null,
                        null,
                        new SqlHistoryStore(directory.resolve("history.txt")),
                        new ShortcutSettings(directory.resolve("shortcuts.properties")),
                        runner);
                editor.setSqlText(initial);
                return editor;
              });
      try {
        fx(
            () -> {
              Method bind =
                  SqlEditorPane.class.getDeclaredMethod(
                      "bindDraft",
                      SqlDraftCoordinator.class,
                      UUID.class,
                      Long.class,
                      Consumer.class);
              bind.setAccessible(true);
              bind.invoke(
                  pane,
                  runtime,
                  id,
                  savedAt,
                  (Consumer<Object>) ignored -> detachments.incrementAndGet());
              new Scene((Parent) pane.getNode(), 1200, 800);
              pane.getNode().applyCss();
            });
      } catch (Throwable failure) {
        close();
        throw failure;
      }
    }

    void fx(Action action) throws Exception {
      fxValue(
          () -> {
            action.run();
            return null;
          });
    }

    <T> T fxValue(Callable<T> action) throws Exception {
      return FxUiTestSupport.call(action);
    }

    CodeArea editor() {
      return (CodeArea) pane.getNode().lookup("#sql-editor");
    }

    TextField schema() throws Exception {
      return (TextField) field(pane, "schemaField");
    }

    Label resultStatus() throws Exception {
      return (Label) field(pane, "statusLabel");
    }

    Label label(String suffix) {
      return (Label) pane.getNode().lookup("#sql-draft-" + suffix);
    }

    void drain() throws Exception {
      assertFalse(Platform.isFxApplicationThread());
      Runnable job;
      while ((job = writer.poll()) != null) job.run();
      fx(() -> {});
    }

    void tick(long time) throws Exception {
      clock.set(time);
      fx(runtime::pulse);
      drain();
      fx(
          () -> {
            Object binding = field(pane, "draftBinding");
            invoke(binding, "refresh");
          });
    }

    SqlDraftStore.Snapshot snapshot() throws Exception {
      var future = fxValue(runtime::refresh);
      drain();
      return future.get(5, TimeUnit.SECONDS).snapshot();
    }

    CompletableFuture<CloseGuardOutcome> beginClose(boolean mandatory) throws Exception {
      return fxValue(
          () ->
              (mandatory ? pane.requestMandatoryClose() : pane.requestClose())
                  .toCompletableFuture());
    }

    void dismiss(String text) throws Exception {
      fx(
          () -> {
            var buttons =
                Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .flatMap(window -> window.getScene().getRoot().lookupAll(".button").stream())
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .toList();
            Button cancel = buttons.stream().filter(Button::isCancelButton).findFirst().orElseThrow();
            Button button =
                "取消".equals(text)
                    ? cancel
                    : buttons.stream()
                        .filter(candidate -> text.equals(candidate.getText()))
                        .findFirst()
                        .orElseThrow();
            Throwable assertionFailure = null;
            try {
              assertTrue(cancel.isDefaultButton());
              if ("放弃本次最新修改并关闭".equals(text)) {
                assertFalse(button.isDefaultButton());
              }
              button.fire();
            } catch (RuntimeException | Error failure) {
              assertionFailure = failure;
              throw failure;
            } finally {
              if (assertionFailure != null) {
                try {
                  cancel.fire();
                } catch (RuntimeException | Error cleanupFailure) {
                  assertionFailure.addSuppressed(cleanupFailure);
                }
              }
            }
          });
    }

    @Override
    public void close() throws Exception {
      try {
        pane.closeResources();
        fx(pane::finalizeCloseOnFx);
      } finally {
        var stopped = fxValue(runtime::shutdown);
        drain();
        stopped.get(5, TimeUnit.SECONDS);
        runner.close();
      }
    }
  }
}
