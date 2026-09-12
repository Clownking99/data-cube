package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.config.AppSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlConnectionPickerTest {
    @TempDir Path directory;
    private static final ConnConfig PG = config("pg-one", DbType.POSTGRESQL, "REPORTING 中😀");
    private static final ConnConfig ORACLE = config("oracle-id", DbType.ORACLE, "same-name");
    private static final ConnConfig OTHER = config("other-[.*]", DbType.POSTGRESQL, "same-name");
    private static final List<ConnConfig> CONFIGS = List.of(PG, ORACLE, OTHER);
    @Test void sharedChooserExposesSearchInsteadOfAnUnsearchableDropdown() throws Exception {
        FxUiTestSupport.call(() -> {
            SqlDraftManagerTest.respondToDialog(() -> SqlDraftConnectionChooser.show(List.of(
                    config("one", DbType.ORACLE, "演示")), null), pane -> {
                assertNotNull(pane.lookup("#sql-connection-query"), "connection selection needs a searchable entry");
                assertNotNull(pane.lookup("#sql-connection-list"));
            });
            return null;
        });
    }

    @Test void snapshotExcludesUnsupportedTargetsAndHasNoImplicitSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            var input = new ArrayList<>(Arrays.asList(PG, null, config("redis", DbType.REDIS, "redis"),
                    config(" ", DbType.ORACLE, "invalid"), config(null, DbType.ORACLE, "null-id"),
                    config("invalid-type", null, "invalid"), ORACLE, OTHER));
            var d = create(input); input.clear();
            assertEquals(CONFIGS, configs(d)); assertEquals("显示 3 / 3 个连接", count(d));
            assertNull(selected(d)); assertTrue(ok(d).isDisabled()); assertFalse(ok(d).isDefaultButton());
            assertTrue(clear(d).isDisabled()); assertTrue(d.isResizable()); assertNull(d.getResult());
            assertNotEquals(list(d).getItems().get(1).toString(), list(d).getItems().get(2).toString());
            assertFalse(list(d).getItems().toString().contains("password-secret"));
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"' reporting ',0", "中😀,0", "PG-ONE,0", "oracle,1", "oracle-id,1", "[.*],2"})
    void literalSearchFindsNameTypeOrIdWithoutChoosing(String term, int index) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(CONFIGS); query(d).setText(term);
            assertEquals(List.of(CONFIGS.get(index)), configs(d)); assertEquals("显示 1 / 3 个连接", count(d));
            assertNull(selected(d)); assertTrue(ok(d).isDisabled()); assertNull(d.getResult());
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings={"example.invalid", "synthetic", "user-secret", "password-secret", "property-secret"})
    void searchNeverUsesPrivateConnectionFields(String term) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(CONFIGS); query(d).setText(term);
            assertTrue(configs(d).isEmpty()); assertEquals("显示 0 / 3 个连接", count(d));
            assertNull(selected(d)); assertTrue(ok(d).isDisabled());
            return null;
        });
    }

    @Test void localeNullNameAndFullUntruncatedIdRemainSearchable() throws Exception {
        FxUiTestSupport.call(() -> {
            Locale before = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                var longId = config("I".repeat(100) + "needle", DbType.ORACLE, null);
                var d = create(List.of(PG, longId));
                query(d).setText("reporting"); assertEquals(List.of(PG), configs(d));
                query(d).setText("NEEDLE"); assertEquals(List.of(longId), configs(d));
                assertFalse(list(d).getItems().getFirst().toString().contains("needle"), "search uses full ID, not the display preview");
                query(d).setText("\u2003 \t"); assertEquals(List.of(PG, longId), configs(d));
                assertNull(selected(d));
            } finally { Locale.setDefault(before); }
            return null;
        });
    }

    @Test void filterKeepsTheCandidateNotItsIndexAndNeverFallsBackOrResurrects() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(CONFIGS); list(d).getSelectionModel().selectLast();
            query(d).setText("same-name"); assertEquals(List.of(ORACLE, OTHER), configs(d));
            assertSame(OTHER, selected(d).config()); assertEquals(1, list(d).getSelectionModel().getSelectedIndex());
            assertFalse(ok(d).isDisabled());
            query(d).setText("oracle-id"); assertEquals(List.of(ORACLE), configs(d));
            assertNull(selected(d)); assertTrue(ok(d).isDisabled());
            clear(d).fire(); assertEquals(CONFIGS, configs(d)); assertNull(selected(d)); assertTrue(ok(d).isDisabled());
            list(d).getSelectionModel().select(1); query(d).setText("oracle"); clear(d).fire();
            assertSame(ORACLE, selected(d).config()); assertFalse(ok(d).isDisabled());
            return null;
        });
    }

    @Test void manyConnectionsCanFindLastIdWithoutChangingOrderOrTarget() throws Exception {
        FxUiTestSupport.call(() -> {
            var many = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(i -> config("connection-" + i, DbType.ORACLE, "同名连接")).toList();
            var d = create(many); query(d).setText("connection-199");
            assertEquals(List.of(many.getLast()), configs(d)); assertNull(selected(d));
            assertEquals("显示 1 / 200 个连接", count(d));
            list(d).getSelectionModel().selectFirst(); clear(d).fire();
            assertEquals(many, configs(d)); assertSame(many.getLast(), selected(d).config());
            assertEquals(199, list(d).getSelectionModel().getSelectedIndex()); assertNull(d.getResult());
            return null;
        });
    }

    @Test void emptyAndNoMatchesHaveDifferentGuidanceAndCannotConfirm() throws Exception {
        FxUiTestSupport.call(() -> {
            var empty = create(List.of()); query(empty).setText("anything");
            assertEquals("暂无可用 PostgreSQL / Oracle 连接", placeholder(empty));
            assertTrue(empty.getHeaderText().contains("没有可用连接")); assertEquals("显示 0 / 0 个连接", count(empty));
            var d = create(CONFIGS);
            try {
                d.show(); query(d).setText("absent");
                assertEquals("没有匹配的连接，请修改或清除筛选", placeholder(d));
                assertEquals("显示 0 / 3 个连接", count(d));
                query(d).fireEvent(key(KeyCode.DOWN)); query(d).fireEvent(key(KeyCode.ENTER)); ok(d).fire();
                assertTrue(d.isShowing()); assertNull(d.getResult()); assertNull(selected(d));
                clear(d).fire(); assertEquals(CONFIGS, configs(d)); assertNull(selected(d));
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings={"query", "list", "button"})
    void explicitConfirmationReturnsExactFilteredObject(String source) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(CONFIGS);
            try {
                d.show(); query(d).setText("other-[.*]");
                assertNull(selected(d)); query(d).fireEvent(key(KeyCode.ENTER)); assertTrue(d.isShowing());
                list(d).getSelectionModel().selectFirst(); assertNull(d.getResult());
                if (source.equals("button")) ok(d).fire();
                else {
                    var target = source.equals("query") ? query(d) : list(d);
                    target.requestFocus(); target.fireEvent(key(KeyCode.ENTER));
                }
                assertFalse(d.isShowing()); assertSame(OTHER, d.getResult());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings={"query", "list", "button", "close"})
    void cancellationDiscardsEvenAnExplicitCandidate(String source) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(CONFIGS);
            try {
                d.show(); query(d).setText("oracle"); list(d).getSelectionModel().selectFirst();
                if (source.equals("close")) d.close();
                else if (source.equals("button")) ((Button) d.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
                else (source.equals("query") ? query(d) : list(d)).fireEvent(key(KeyCode.ESCAPE));
                assertFalse(d.isShowing()); assertNull(d.getResult());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void keyboardRequiresNavigationAndSupportsReturningToSearch() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(CONFIGS);
            try {
                d.show(); assertSame(query(d), d.getDialogPane().getScene().getFocusOwner());
                query(d).setText("same-name"); query(d).fireEvent(key(KeyCode.DOWN));
                assertSame(list(d), d.getDialogPane().getScene().getFocusOwner());
                assertSame(ORACLE, selected(d).config()); assertTrue(d.isShowing());
                list(d).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.F,false,true,false,false));
                assertSame(query(d), d.getDialogPane().getScene().getFocusOwner()); assertEquals("same-name", query(d).getSelectedText());
                query(d).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,true,false,false));
                assertTrue(d.isShowing()); assertNull(d.getResult());
                list(d).fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,5,5,5,5,MouseButton.PRIMARY,2,
                        false,false,false,false,true,false,false,false,false,true,null));
                assertTrue(d.isShowing()); assertNull(d.getResult());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void searchLimitRejectsWholeEditAndKeepsPriorCandidate() throws Exception {
        FxUiTestSupport.call(() -> {
            var config = config("x".repeat(256), DbType.ORACLE, "long"); var d = create(List.of(config));
            query(d).setText("x".repeat(255)); assertEquals(List.of(config), configs(d));
            query(d).appendText("x"); assertEquals(256, query(d).getLength());
            list(d).getSelectionModel().selectFirst(); query(d).appendText("x");
            assertEquals("x".repeat(256), query(d).getText()); assertSame(config, selected(d).config());
            query(d).replaceText(0,256,"z".repeat(257)); assertEquals("x".repeat(256), query(d).getText());
            assertSame(config, selected(d).config()); assertFalse(ok(d).isDisabled());
            clear(d).fire(); query(d).setText("😀".repeat(128)); assertEquals(256, query(d).getLength());
            query(d).appendText("😀"); assertEquals(256, query(d).getLength());
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"480,DARK", "680,DARK", "480,LIGHT", "680,LIGHT"})
    void themesAndNarrowLayoutKeepSearchAndGuidanceVisible(double width, AppSettings.Theme mode) throws Exception {
        FxUiTestSupport.call(() -> {
            var settings = new AppSettings(directory.resolve("settings")); settings.setTheme(mode);
            var d = create(CONFIGS); new ThemeManager(settings).applyTo(d.getDialogPane());
            try {
                d.show(); d.setWidth(width); d.setHeight(490); d.getDialogPane().applyCss(); d.getDialogPane().layout();
                assertTrue(query(d).getWidth() > 170); assertTrue(list(d).getHeight() > 90);
                assertTrue(clear(d).getWidth() + 1 >= clear(d).prefWidth(-1));
                var hint = (Label) d.getDialogPane().lookup("#sql-connection-hint");
                String rendered = hint.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                        .map(Text::getText).findFirst().orElseThrow();
                assertEquals(hint.getText(), rendered);
                assertTrue(hint.getHeight()+1 >= hint.prefHeight(hint.getWidth()));
                Text prompt = query(d).lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                        .filter(t -> t.getText().equals(query(d).getPromptText())).findFirst().orElseThrow();
                assertEquals(1.0, ((javafx.scene.paint.Color) prompt.getFill()).getOpacity());
                assertTrue(ok(d).isDisabled());
            } finally { d.close(); }
            return null;
        });
    }

    private static Dialog<ConnConfig> create(List<ConnConfig> configs) { return SqlDraftConnectionChooser.create(configs, null, "选择脚本连接"); }
    @SuppressWarnings("unchecked") private static ListView<SqlDraftConnectionChooser.Choice> list(Dialog<ConnConfig> d) {
        return (ListView<SqlDraftConnectionChooser.Choice>) d.getDialogPane().lookup("#sql-connection-list");
    }
    private static TextField query(Dialog<ConnConfig> d) { return (TextField) d.getDialogPane().lookup("#sql-connection-query"); }
    private static Button ok(Dialog<ConnConfig> d) { return (Button) d.getDialogPane().lookupButton(ButtonType.OK); }
    private static Button clear(Dialog<ConnConfig> d) { return (Button) d.getDialogPane().lookup("#sql-connection-clear"); }
    private static String count(Dialog<ConnConfig> d) { return ((Label) d.getDialogPane().lookup("#sql-connection-count")).getText(); }
    private static String placeholder(Dialog<ConnConfig> d) { return ((Label) list(d).getPlaceholder()).getText(); }
    private static List<ConnConfig> configs(Dialog<ConnConfig> d) { return list(d).getItems().stream().map(SqlDraftConnectionChooser.Choice::config).toList(); }
    private static SqlDraftConnectionChooser.Choice selected(Dialog<ConnConfig> d) { return list(d).getSelectionModel().getSelectedItem(); }
    private static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED,"","",code,false,false,false,false); }

    private static ConnConfig config(String id, DbType type, String name) {
        return new ConnConfig(id, name, type, "example.invalid", 1, "synthetic", "user-secret", "password-secret", Map.of("note", "property-secret"));
    }
}
