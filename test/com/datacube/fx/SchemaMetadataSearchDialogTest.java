package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.SchemaMetadataSearch.*;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMetadataSearchDialogTest {
    @Test void emptyQueryGuidanceRemainsVisibleInBothThemesAndFocusStatesWithoutReading() throws Exception {
        try (var runner = new FxTaskRunner()) {
            var calls = new AtomicInteger();
            var d = FxUiTestSupport.call(() -> {
                var picker = new SchemaMetadataSearchDialog(target(), "s", null, runner,
                        (r,c) -> { calls.incrementAndGet(); return result(); }, () -> true);
                picker.dialog().show(); return picker;
            });
            try {
                FxUiTestSupport.call(() -> {
                    var root = d.dialog().getDialogPane(); var input = text(d);
                    for (String theme : List.of("dark", "light")) {
                        root.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                                ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                        for (boolean focused : List.of(false, true)) {
                            input.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), focused);
                            root.applyCss(); root.layout();
                            var prompt = input.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                                    .map(javafx.scene.text.Text.class::cast).filter(t -> input.getPromptText().equals(t.getText())).findFirst().orElseThrow();
                            assertTrue(prompt.isVisible());
                            assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), prompt.getFill());
                        }
                    }
                    assertEquals(0, calls.get(), "theme and focus changes must not start metadata reads");
                    return null;
                });
            } finally { FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    private ConnConfig target() { return new ConnConfig("synthetic", "synthetic", DbType.POSTGRESQL,"invalid.example",1,"db","u","",Map.of()); }
    private Result result() { return new Result(List.of(new Hit(new TableInfo("s","orders",TableInfo.Kind.TABLE,null),Mode.COLUMN_NAME,"customer_id","customer_id")),false); }
    @Test void typingNeverReadsAndEachExplicitResultActionKeepsIdentity() throws Exception {
        for (var action:SchemaMetadataSearchDialog.Action.values()) try (var runner=new FxTaskRunner()) {
            var calls=new AtomicInteger(); var loaded=new CountDownLatch(1);
            var picker=FxUiTestSupport.call(() -> {
                var d=new SchemaMetadataSearchDialog(target(),"s",null,runner,(request,control) -> {
                    calls.incrementAndGet(); assertEquals("customer",request.term()); assertEquals("s",request.schema()); return result();
                },() -> true);
                d.dialog().show(); listen(d,loaded); text(d).setText("customer");
                assertEquals(0,calls.get()); assertTrue(button(d,"select").isDisabled()); button(d,"submit").fire(); return d;
            });
            try {
                assertTrue(loaded.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { list(picker).getSelectionModel().selectFirst(); button(picker,action.name().toLowerCase(Locale.ROOT)).fire(); return null; });
                assertEquals(action,picker.dialog().getResult().action());
                assertEquals(new TableRef("s","orders"),picker.dialog().getResult().hit().object().ref()); assertEquals(1,calls.get());
            } finally { FxUiTestSupport.call(() -> { picker.close(); return null; }); }
        }
    }
    @Test void changedConditionsInvalidateResultsAndChangedTargetBlocksStaleActions() throws Exception {
        try (var runner=new FxTaskRunner()) {
            var allowed=new AtomicBoolean(true); var loaded=new CountDownLatch(1);
            var d=FxUiTestSupport.call(() -> {
                var picker=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> result(),allowed::get);
                picker.dialog().show(); listen(picker,loaded); text(picker).setText("customer"); button(picker,"submit").fire(); return picker;
            });
            try {
                assertTrue(loaded.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    list(d).getSelectionModel().selectFirst(); allowed.set(false); button(d,"data").fire(); assertNull(d.dialog().getResult());
                    allowed.set(true); text(d).setText("new term"); assertTrue(list(d).getItems().isEmpty()); assertTrue(button(d,"select").isDisabled());
                    assertNull(d.dialog().getResult()); return null;
                });
            } finally { FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @Test void cancellationWaitsForPhysicalCompletionAndCloseDropsLateResults() throws Exception {
        try (var runner=new FxTaskRunner()) {
            var started=new CountDownLatch(1); var release=new CountDownLatch(1); var ended=new CountDownLatch(1);
            var control=new AtomicReference<SqlExecutionControl>(); var calls=new AtomicInteger();
            var d=FxUiTestSupport.call(() -> {
                var picker=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> {
                    calls.incrementAndGet(); control.set(c); started.countDown();
                    try { assertTrue(release.await(5,TimeUnit.SECONDS)); return result(); } finally { ended.countDown(); }
                },() -> true);
                picker.dialog().show(); text(picker).setText("customer"); button(picker,"submit").fire(); return picker;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    button(d,"cancel-read").fire(); assertTrue(control.get().cancellationRequested());
                    assertTrue(button(d,"submit").isDisabled()); button(d,"submit").fire(); assertEquals(1,calls.get()); d.close(); return null;
                });
                release.countDown(); assertTrue(ended.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { assertTrue(list(d).getItems().isEmpty()); assertNull(d.dialog().getResult()); return null; });
            } finally { release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @Test void findShortcutStaysInCatalogDialogAndTabReachesMatchSourceWithoutReading() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var calls=new AtomicInteger();
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> { calls.incrementAndGet(); return result(); },() -> true);
                view.dialog().show(); return view;
            });
            try { FxUiTestSupport.call(() -> {
                button(d,"submit").requestFocus();
                d.dialog().getDialogPane().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "","",javafx.scene.input.KeyCode.F,false,true,false,false));
                assertSame(text(d),text(d).getScene().getFocusOwner());
                text(d).fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "","",javafx.scene.input.KeyCode.TAB,false,false,false,false));
                assertSame(d.dialog().getDialogPane().lookup("#metadata-search-mode"),text(d).getScene().getFocusOwner());
                assertEquals(0,calls.get()); return null;
            }); } finally { FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    private static TextField text(SchemaMetadataSearchDialog d) { return (TextField)d.dialog().getDialogPane().lookup("#metadata-search-query"); }
    @Test void deadlineKeepsAdmissionClosedUntilReadReallyEndsAndDropsLateData() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var started=new CountDownLatch(1); var release=new CountDownLatch(1); var ready=new CountDownLatch(1);
            var control=new AtomicReference<SqlExecutionControl>();
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> {
                    control.set(c); started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return result();
                },() -> true);
                view.dialog().show(); text(view).setText("id"); button(view,"submit").fire(); return view;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    var field=SchemaMetadataSearchDialog.class.getDeclaredField("deadline"); field.setAccessible(true);
                    ((javafx.animation.PauseTransition)field.get(d)).getOnFinished().handle(new javafx.event.ActionEvent());
                    assertTrue(control.get().cancellationRequested()); assertTrue(button(d,"submit").isDisabled());
                    button(d,"submit").disabledProperty().addListener((o,b,a) -> { if(!a) ready.countDown(); });
                    return null;
                });
                release.countDown(); assertTrue(ready.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { assertTrue(list(d).getItems().isEmpty()); assertTrue(button(d,"data").isDisabled()); return null; });
            } finally { release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @Test void backgroundClosePublishesCancellationWithoutWaitingForTheFxQueue() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var started=new CountDownLatch(1); var release=new CountDownLatch(1); var fxBlocked=new CountDownLatch(1); var fxRelease=new CountDownLatch(1);
            var control=new AtomicReference<SqlExecutionControl>();
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> {
                    control.set(c); started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return result();
                },() -> true);
                view.dialog().show(); text(view).setText("id"); button(view,"submit").fire(); return view;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                javafx.application.Platform.runLater(() -> { fxBlocked.countDown(); try { assertTrue(fxRelease.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); } });
                assertTrue(fxBlocked.await(5,TimeUnit.SECONDS));
                d.close(); assertTrue(control.get().cancellationRequested());
            } finally { fxRelease.countDown(); release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    private static Button button(SchemaMetadataSearchDialog d,String suffix) { return (Button)d.dialog().getDialogPane().lookup("#metadata-search-"+suffix); }
    @SuppressWarnings("unchecked") private static ListView<Hit> list(SchemaMetadataSearchDialog d) { return (ListView<Hit>)d.dialog().getDialogPane().lookup("#metadata-search-results"); }
    private static void listen(SchemaMetadataSearchDialog d,CountDownLatch ready) { list(d).getItems().addListener((ListChangeListener<Hit>)c -> { if (!list(d).getItems().isEmpty()) ready.countDown(); }); }
}
