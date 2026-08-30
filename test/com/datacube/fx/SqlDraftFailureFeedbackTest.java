package com.datacube.fx;

import static org.junit.jupiter.api.Assertions.*;

import com.datacube.config.DraftManagementProbe;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import java.lang.reflect.Field;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SqlDraftFailureFeedbackTest {
  @ParameterizedTest
  @EnumSource(
      value = DraftManagementProbe.SaveFault.class,
      names = {"WRITE", "CAPACITY", "INVALID_DRAFT", "CLEANUP"})
  void failureKindsReachRealEditorControlsWithoutPrivateDiagnostics(
      DraftManagementProbe.SaveFault fault) throws Exception {
    try (Fixture f = new Fixture()) {
      f.probe.saveFault = fault;
      CompletableFuture<Void> failed = f.call(f.handle::flush);
      f.probe.drain();
      ExecutionException error =
          assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
      assertInstanceOf(SqlDraftCoordinator.Failure.class, error.getCause());
      assertNull(error.getCause().getCause());
      f.settle();
      f.fx(
          () -> {
            assertEquals(
                SqlDraftCoordinator.FailureReason.valueOf(fault.name()),
                f.handle.status().failureReason());
            String text = f.label("status").getText();
            assertFalse(text.contains("synthetic private"));
            assertEquals("select 'synthetic';", f.editor.getText());
            if (fault == DraftManagementProbe.SaveFault.CAPACITY) {
              assertTrue(text.contains("100"));
              assertTrue(text.contains("32 MiB"));
              assertTrue(text.contains("复制"));
            } else if (fault == DraftManagementProbe.SaveFault.INVALID_DRAFT) {
              assertTrue(text.contains("1 MiB"));
              assertTrue(text.contains("4096"));
              assertTrue(text.contains("复制"));
            } else if (fault == DraftManagementProbe.SaveFault.CLEANUP) {
              assertTrue(text.contains("临时文件"));
              assertTrue(text.contains("SQL"));
              assertTrue(text.contains("重启"));
              assertFalse(f.button("retry").isVisible());
              assertTrue(f.button("clear").isDisabled());
              SqlDraftManagerPane manager =
                  new SqlDraftManagerPane(f.runtime, ignored -> false, () -> {});
              try {
                String managerText =
                    ((Label) manager.getNode().lookup("#draft-manager-status")).getText();
                assertTrue(managerText.contains("临时文件"));
                assertTrue(managerText.contains("SQL"));
              } finally {
                manager.close();
              }
            } else {
              assertTrue(text.contains("尚未保存"));
              assertTrue(f.button("retry").isVisible());
            }
          });
      assertTrue(f.probe.records.isEmpty());
    }
  }

  @Test
  void successfulExplicitRetryClearsThePreviousFailureClassification() throws Exception {
    try (Fixture f = new Fixture()) {
      f.probe.saveFault = DraftManagementProbe.SaveFault.CAPACITY;
      f.call(f.handle::flush);
      f.probe.drain();
      f.settle();
      f.probe.saveFault = DraftManagementProbe.SaveFault.NONE;
      f.fx(() -> f.button("retry").fire());
      f.probe.drain();
      f.settle();
      f.fx(
          () -> {
            assertEquals(SqlDraftCoordinator.SaveStatus.SAVED, f.handle.status().saveStatus());
            assertNull(f.handle.status().failureReason());
            assertTrue(f.label("status").getText().contains("已保存"));
          });
      assertEquals("select 'synthetic';", f.probe.records.getFirst().sql());
    }
  }

  @Test
  void staleFailureCannotAnnotateANewerEditorRevision() throws Exception {
    try (Fixture f = new Fixture()) {
      f.probe.saveFault = DraftManagementProbe.SaveFault.CAPACITY;
      f.call(f.handle::flush);
      f.probe.drain();
      f.fx(() -> f.editor.replaceText("select 'new revision';"));
      f.settle();
      f.fx(
          () -> {
            assertEquals(SqlDraftCoordinator.SaveStatus.WAITING, f.handle.status().saveStatus());
            assertNull(f.handle.status().failureReason());
            assertFalse(f.label("status").getText().contains("32 MiB"));
          });
    }
  }

  private static final class Fixture implements AutoCloseable {
    final DraftManagementProbe probe = new DraftManagementProbe();
    final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    final SqlDraftCoordinator runtime;
    final CodeArea editor;
    final SqlDraftEditorBinding binding;
    final SqlDraftCoordinator.Handle handle;

    Fixture() throws Exception {
      runtime = call(() -> probe.create(callbacks::add, Platform::isFxApplicationThread));
      probe.drain();
      fx(this::drainCallbacks);
      editor = call(() -> new CodeArea("select 'synthetic';"));
      SqlDraftEditorBinding created = null;
      try {
        created =
            call(
                () -> {
                  TextField schema = new TextField(" raw ");
                  SqlDraftEditorBinding candidate =
                      new SqlDraftEditorBinding(
                          runtime,
                          UUID.randomUUID(),
                          null,
                          editor,
                          schema,
                          new SqlDraftCoordinator.Source() {
                            public boolean hasText() {
                              return !editor.getText().isEmpty();
                            }

                            public SqlDraft capture(UUID id, long at) {
                              return new SqlDraft(
                                  id, at, null, null, null, schema.getText(), editor.getText());
                            }
                          },
                          ignored -> {});
                  new Scene(new VBox(editor, schema, candidate.getNode()));
                  return candidate;
                });
        binding = created;
        Field field = SqlDraftEditorBinding.class.getDeclaredField("handle");
        field.setAccessible(true);
        handle = (SqlDraftCoordinator.Handle) field.get(binding);
      } catch (Throwable failure) {
        try {
          if (created != null) fx(created::close);
        } finally {
          CompletableFuture<Void> closed = call(runtime::shutdown);
          probe.drain();
          closed.get(5, TimeUnit.SECONDS);
        }
        throw failure;
      }
    }

    void drainCallbacks() {
      Runnable next;
      while ((next = callbacks.poll()) != null) next.run();
    }

    void settle() throws Exception {
      fx(
          () -> {
            drainCallbacks();
            binding.refresh();
          });
      fx(() -> {});
    }

    Label label(String id) {
      return (Label) binding.getNode().lookup("#sql-draft-" + id);
    }

    Button button(String id) {
      return (Button) binding.getNode().lookup("#sql-draft-" + id);
    }

    <T> T call(Callable<T> work) throws Exception {
      return FxUiTestSupport.call(work);
    }

    void fx(Runnable work) throws Exception {
      call(
          () -> {
            work.run();
            return null;
          });
    }

    @Override
    public void close() throws Exception {
      fx(binding::close);
      CompletableFuture<Void> closed = call(runtime::shutdown);
      probe.drain();
      closed.get(5, TimeUnit.SECONDS);
    }
  }
}
