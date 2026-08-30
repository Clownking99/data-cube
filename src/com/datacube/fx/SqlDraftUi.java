package com.datacube.fx;

import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.util.Duration;

/** One application timer and writer, independent of disposable editor task scopes. */
final class SqlDraftUi {
  private final ExecutorService writer =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "sql-draft-writer");
            thread.setDaemon(true);
            return thread;
          });
  private final Set<SqlDraftEditorBinding> bindings = new LinkedHashSet<>();
  private final Map<Node, SqlDraftEditorBinding> boundContent = new LinkedHashMap<>();
  private final Map<UUID, Node> installedContent = new LinkedHashMap<>();
  private final Set<Runnable> observers = new LinkedHashSet<>();
  private final SqlDraftCoordinator runtime;
  private final Timeline timer;

  SqlDraftUi(Path directory) {
    long started = System.nanoTime();
    runtime =
        new SqlDraftCoordinator(
            directory,
            writer,
            Platform::runLater,
            Platform::isFxApplicationThread,
            () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            System::currentTimeMillis);
    timer =
        new Timeline(
            new KeyFrame(
                Duration.millis(250),
                event -> {
                  runtime.pulse();
                  List.copyOf(bindings).forEach(SqlDraftEditorBinding::refresh);
                  List.copyOf(observers).forEach(Runnable::run);
                }));
    timer.setCycleCount(Timeline.INDEFINITE);
    timer.play();
  }

  void bind(SqlEditorPane pane) {
    bind(pane, null);
  }

  SqlDraftCoordinator runtime() { return runtime; }

  void bind(SqlEditorPane pane, SqlDraft draft) {
    Node content = pane.getNode();
    SqlDraftEditorBinding binding = pane.bindDraft(runtime,
        draft == null ? UUID.randomUUID() : draft.id(),
        draft == null ? null : draft.modifiedAt(), removed -> {
          bindings.remove(removed);
          boundContent.remove(content, removed);
          installedContent.remove(removed.id(), content);
        });
    bindings.add(binding);
    boundContent.put(content, binding);
  }

  void installed(Node content) {
    SqlDraftEditorBinding binding = boundContent.get(content);
    if (binding == null) throw new IllegalStateException("Draft content is not bound");
    installedContent.put(binding.id(), content);
  }

  Node installedContent(UUID id) { return installedContent.get(id); }

  AutoCloseable observe(Runnable observer) {
    observers.add(observer);
    return () -> observers.remove(observer);
  }

  void closeFromBackground() {
    if (Platform.isFxApplicationThread())
      throw new IllegalStateException("Draft shutdown must be awaited off FX");
    CompletableFuture<Void> drained = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            timer.stop();
            observers.clear();
            runtime
                .shutdown()
                .whenComplete(
                    (unused, failure) -> {
                      if (failure == null) drained.complete(null);
                      else drained.completeExceptionally(failure);
                    });
          } catch (Throwable failure) {
            drained.completeExceptionally(failure);
          }
        });
    try {
      drained.join();
    } finally {
      writer.shutdown();
    }
  }
}
