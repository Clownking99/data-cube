package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.provider.postgres.PostgresProvider;
import com.datacube.service.ConnectionManager;
import com.datacube.service.SchemaMetadataSearch;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.fxmisc.richtext.CodeArea;
import static org.junit.jupiter.api.Assertions.*;

/** FX integration through the real picker, metadata query, tree routing and AppShell handlers.
 * Programmatic control events are deliberately not native desktop acceptance evidence. */
class MetadataSearchShellRoutingTest {
    @TempDir Path directory;

    @BeforeAll static void loadNativeFontsBeforeChangingProfile() throws Exception {
        // Windows keeps native DLLs open until JVM exit; use the isolated worker profile for their cache,
        // not a per-case @TempDir that JUnit must delete while the worker is still alive.
        FxUiTestSupport.call(() -> { new Scene(new Label("synthetic font initialization"), 100, 50).getRoot().applyCss(); return null; });
    }

    @ParameterizedTest
    @CsvSource({"TABLE,SELECT", "VIEW,SELECT", "TABLE,DATA", "VIEW,DATA", "TABLE,DDL", "VIEW,DDL"})
    void metadataResultOpensBoundPassiveOrReadOnlyShellTab(TableInfo.Kind kind,
                                                         SchemaMetadataSearchDialog.Action action) throws Exception {
        try (var f = new Fixture(directory, kind)) {
            f.searchAndChoose(action);
            FxUiTestSupport.call(() -> {
                f.shell.getRoot().applyCss(); f.shell.getRoot().layout();
                assertEquals(1, f.tabs.getTabs().size(), "one result action must open exactly one tab");
                var tab = f.tabs.getTabs().getFirst();
                assertFalse(f.ownedDialogs().stream().anyMatch(Window::isShowing), "both pickers close after selection");
                switch (action) {
                    case SELECT -> {
                        var text = (CodeArea) tab.getContent().lookup("#sql-editor");
                        assertNotNull(text);
                        assertEquals("SELECT *\nFROM \"demo\".\"orders\";", text.getText());
                        assertTrue(((Label) tab.getContent().lookup("#sql-connection")).getText().contains(f.target.name()));
                        assertEquals(2, f.opens.get(), "SELECT generation adds no connection beyond catalog and search");
                        assertEquals(0, f.pages.get() + f.ddls.get());
                    }
                    case DATA -> assertEquals("数据（只读）: orders", tab.getText(),
                            "read-only is a page mode and must not relabel a table as a view");
                    case DDL -> assertEquals("DDL: orders", tab.getText());
                }
                assertEquals(1, f.searches.get());
                assertEquals(0, f.writes.get() + f.executions.get());
                return null;
            });
            if (action == SchemaMetadataSearchDialog.Action.DATA) f.verifyData();
            if (action == SchemaMetadataSearchDialog.Action.DDL) f.verifyDdl();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "change"})
    void cancelledOrChangedTargetCannotOpenDownstreamShellTab(String outcome) throws Exception {
        try (var f = new Fixture(directory, TableInfo.Kind.TABLE)) {
            f.searchAndChoose(SchemaMetadataSearchDialog.Action.DATA, outcome);
            FxUiTestSupport.call(() -> {
                assertTrue(f.tabs.getTabs().isEmpty());
                assertTrue(f.ownedDialogs().isEmpty());
                assertEquals(1, f.searches.get()); assertEquals(2, f.opens.get());
                assertEquals(0, f.pages.get() + f.ddls.get() + f.writes.get() + f.executions.get());
                return null;
            });
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger(), searches = new AtomicInteger(),
                pages = new AtomicInteger(), ddls = new AtomicInteger(), writes = new AtomicInteger(), executions = new AtomicInteger();
        final ConnConfig target = new ConnConfig("synthetic-shell", "合成检索连接", DbType.POSTGRESQL,
                "example.invalid", 1, "synthetic", "synthetic", "", Map.of());
        final TableInfo object;
        final AppShell shell;
        final Stage stage;
        final TabPane tabs;
        final TreeItem<ConnectionTreePane.NodeData> schema;
        final TreeView<ConnectionTreePane.NodeData> tree;
        final String originalHome;
        final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        final CountDownLatch pageRead = new CountDownLatch(1), ddlRead = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        Fixture(Path directory, TableInfo.Kind kind) throws Exception {
            object = new TableInfo("demo", "orders", kind, "合成订单");
            originalHome = System.getProperty("user.home");
            // The exclusive @TempDir replaces user.home BEFORE constructing any AppShell-owned stores.
            shell = FxUiTestSupport.call(() -> {
                System.setProperty("user.home", directory.toString());
                var s = new AppShell();
                var manager = (ConnectionManager) field(s, "connMgr");
                var resolver = ConnectionManager.class.getDeclaredField("providerResolver"); resolver.setAccessible(true);
                var provider = provider();
                resolver.set(manager, (Function<DbType, DatabaseProvider>) type -> {
                    assertEquals(DbType.POSTGRESQL, type); return provider;
                });
                ((ConnectionStore) field(s, "store")).saveAll(List.of(target));
                ((ConnectionTreePane) field(s, "connectionTree")).refresh();
                // Persistence expands default safety props; the synthetic node and manager must share one snapshot.
                manager.register(target);
                return s;
            });
            tabs = FxUiTestSupport.call(() -> (TabPane) ((ContentTabPane) field(shell, "contentTabs")).getNode());
            tree = FxUiTestSupport.call(() -> (TreeView<ConnectionTreePane.NodeData>) field(field(shell, "connectionTree"), "tree"));
            schema = FxUiTestSupport.call(() -> {
                var connection = new TreeItem<>(new ConnectionTreePane.NodeData(ConnectionTreePane.Kind.CONNECTION,
                        target.name(), target, target.id(), null, null));
                // Attach a synthetic schema without expanding lazy nodes or adding unrelated reads.
                var item = new TreeItem<>(new ConnectionTreePane.NodeData(ConnectionTreePane.Kind.SCHEMA,
                        "demo", null, target.id(), "demo", "demo"));
                connection.getChildren().setAll(item);
                tree.getRoot().getChildren().setAll(connection);
                // This synthetic node has no lazy-load listener; the picker and query remain the real implementation.
                connection.setExpanded(true);
                return item;
            });
            stage = FxUiTestSupport.call(() -> {
                var s = new Stage(); s.setTitle("Synthetic metadata routing test");
                s.setScene(new Scene(shell.getRoot(), 1100, 720)); shell.getThemeManager().register(s.getScene()); s.show();
                shell.getRoot().applyCss(); shell.getRoot().layout(); return s;
            });
        }

        List<Window> ownedDialogs() {
            return Window.getWindows().stream().filter(w -> w instanceof Stage s && s.getOwner() != null)
                    .filter(w -> { for (Window p = w; p instanceof Stage s; p = s.getOwner()) if (p == stage) return true; return false; })
                    .toList();
        }

        void searchAndChoose(SchemaMetadataSearchDialog.Action action) throws Exception {
            searchAndChoose(action, "choose");
        }
        void searchAndChoose(SchemaMetadataSearchDialog.Action action, String outcome) throws Exception {
            var done = new CompletableFuture<Void>();
            ListChangeListener<Window> windows = change -> {
                while (change.next()) if (change.wasAdded()) for (Window window : change.getAddedSubList()) {
                    Platform.runLater(() -> driveDialog(window, action, outcome));
                }
            };
            FxUiTestSupport.call(() -> {
                Window.getWindows().addListener(windows);
                var cell = tree.lookupAll(".tree-cell").stream().filter(n -> n instanceof TreeCell<?> c && c.getTreeItem() == schema)
                        .map(n -> (TreeCell<?>) n).findFirst().orElseThrow();
                var menu = cell.getContextMenu().getItems().stream()
                        .filter(m -> "tree-find-schema-objects".equals(m.getId())).findFirst().orElseThrow();
                Platform.runLater(() -> {
                    try { menu.fire(); done.complete(null); } catch (Throwable failure) { done.completeExceptionally(failure); }
                }); return null;
            });
            try { done.get(12, TimeUnit.SECONDS); }
            finally { FxUiTestSupport.call(() -> {
                Window.getWindows().removeListener(windows);
                System.out.println("ROUTING " + object.kind() + " " + action + " " + outcome
                        + " opens=" + opens + " closes=" + closes + " searches=" + searches);
                for (var w : ownedDialogs()) System.out.println("DIALOG " + ((Stage) w).getTitle() + " "
                        + w.getScene().getRoot().lookupAll(".label").stream().filter(Label.class::isInstance)
                        .map(n -> ((Label) n).getText()).toList());
                return null;
            }); }
            if (callbackFailure.get() != null) throw new AssertionError("Dialog driver failed", callbackFailure.get());
        }

        @SuppressWarnings("unchecked")
        void driveDialog(Window window, SchemaMetadataSearchDialog.Action action, String outcome) {
            if (!ownedDialogs().contains(window)) return;
            try {
                var root = window.getScene().getRoot();
                root.applyCss(); root.layout();
                var metadataButton = root.lookup("#schema-object-metadata-search");
                if (metadataButton instanceof Button button) {
                    var list = (ListView<TableInfo>) root.lookup("#schema-object-list");
                    if (list.getItems().isEmpty()) list.getItems().addListener((ListChangeListener<TableInfo>) c -> {
                        if (!list.getItems().isEmpty()) Platform.runLater(button::fire);
                    }); else Platform.runLater(button::fire);
                } else if (root.lookup("#metadata-search-query") instanceof TextField query) {
                    var list = (ListView<SchemaMetadataSearch.Hit>) root.lookup("#metadata-search-results");
                    list.getItems().addListener((ListChangeListener<SchemaMetadataSearch.Hit>) c -> {
                        if (!list.getItems().isEmpty()) Platform.runLater(() -> {
                            try {
                                assertEquals(object.ref(), list.getItems().getFirst().object().ref());
                                assertEquals(object.kind(), list.getItems().getFirst().object().kind());
                                list.getSelectionModel().selectFirst();
                                var button = (Button) root.lookup("#metadata-search-" + action.name().toLowerCase(Locale.ROOT));
                                assertFalse(button.isDisabled());
                                if (outcome.equals("change")) {
                                    var manager = (ConnectionManager) field(shell, "connMgr");
                                    manager.register(new ConnConfig(target.id(), "changed synthetic target", target.type(), target.host(),
                                            target.port(), target.database(), target.username(), "", target.props()));
                                    button.fire();
                                    assertTrue(window.isShowing(), "stale selection is rejected before closing/routing");
                                    assertTrue(tabs.getTabs().isEmpty());
                                }
                                if (outcome.equals("choose")) button.fire();
                                else ownedDialogs().reversed().forEach(w -> ((Stage) w).close());
                            } catch (Throwable failure) { failDriver(failure); }
                        });
                    });
                    query.setText("customer");
                    assertEquals(0, searches.get(), "typing must not read remote metadata");
                    ((Button) root.lookup("#metadata-search-submit")).fire();
                } else throw new AssertionError("Expected picker controls are missing: " + root.getClass());
            } catch (Throwable failure) { failDriver(failure); }
        }
        void failDriver(Throwable failure) {
            callbackFailure.compareAndSet(null, failure);
            ownedDialogs().reversed().forEach(w -> ((Stage) w).close());
        }

        @SuppressWarnings("unchecked")
        void verifyData() throws Exception {
            assertTrue(pageRead.await(5, TimeUnit.SECONDS));
            // The service can return before its FX success callback; observe rows via a listener.
            var rendered = new CountDownLatch(1);
            FxUiTestSupport.call(() -> {
                var grid = (TableView<Object>) tabs.getTabs().getFirst().getContent().lookup(".table-view");
                if (grid.getItems().size() == 2) rendered.countDown();
                else grid.getItems().addListener((ListChangeListener<Object>) c -> { if (grid.getItems().size() == 2) rendered.countDown(); });
                return null;
            });
            assertTrue(rendered.await(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                var content = tabs.getTabs().getFirst().getContent();
                var grid = (TableView<?>) content.lookup(".table-view");
                assertEquals(2, grid.getItems().size()); assertFalse(grid.isEditable());
                assertEquals(101, ((EditableGridModel.Row) grid.getItems().get(0)).cell(0).original());
                assertEquals(202, ((EditableGridModel.Row) grid.getItems().get(1)).cell(0).original());
                for (String label : List.of("＋ 新增行", "标记删除", "保存到数据库")) {
                    var button = content.lookupAll(".button").stream().filter(n -> n instanceof Button b && label.equals(b.getText()))
                            .map(n -> (Button) n).findFirst().orElseThrow();
                    assertTrue(button.isDisabled(), label);
                }
                assertTrue(content.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                        .anyMatch(l -> l.getText().contains("当前数据页为只读")), "explain the page mode accurately");
                assertEquals(1, pages.get()); assertEquals(0, writes.get() + executions.get()); return null;
            });
        }
        void verifyDdl() throws Exception {
            assertTrue(ddlRead.await(5, TimeUnit.SECONDS));
            var rendered = new CountDownLatch(1);
            FxUiTestSupport.call(() -> {
                var text = (CodeArea) tabs.getTabs().getFirst().getContent().lookup("#ddl-text");
                assertFalse(text.isEditable());
                if (ddl().equals(text.getText())) rendered.countDown();
                else text.textProperty().addListener((o, before, value) -> { if (ddl().equals(value)) rendered.countDown(); });
                return null;
            });
            assertTrue(rendered.await(5, TimeUnit.SECONDS));
            assertEquals(1, ddls.get()); assertEquals(0, pages.get() + writes.get() + executions.get());
        }
        String ddl() { return "-- synthetic " + object.kind() + " demo.orders"; }
        void table(Object ref) { assertEquals(object.ref(), ref); }

        DatabaseProvider provider() {
            var factory = new ConnectionFactory() {
                public void ensureDriverLoaded() { }
                public Connection open(ConnConfig cfg) {
                    assertEquals(target.id(), cfg.id()); assertEquals("example.invalid", cfg.host());
                    assertEquals(1, cfg.port()); assertEquals("", cfg.encryptedPassword());
                    opens.incrementAndGet(); var closed = new AtomicBoolean();
                    return proxy(Connection.class, (p,m,a) -> switch (m.getName()) {
                        case "isClosed" -> closed.get();
                        case "isValid" -> !closed.get();
                        case "close" -> { if (closed.compareAndSet(false, true)) closes.incrementAndGet(); yield null; }
                        case "prepareStatement" -> statement((String) a[0]);
                        default -> throw new AssertionError("Unexpected connection operation " + m.getName());
                    });
                }
                public String test(ConnConfig cfg) { throw new AssertionError("Network testing forbidden"); }
            };
            var metadata = proxy(MetadataReader.class, (p,m,a) -> switch (m.getName()) {
                case "tableAndViewNames" -> { assertEquals("demo", a[0]); yield List.of(object); }
                default -> throw new AssertionError("Unexpected metadata operation " + m.getName());
            });
            var editor = proxy(DataEditor.class, (p,m,a) -> {
                if (m.getName().equals("columns")) { table(a[0]); return List.of(new EditableColumn("customer_id", Types.INTEGER, "integer", false, true, false, true, "合成客户")); }
                writes.incrementAndGet(); throw new AssertionError("Writes forbidden");
            });
            var data = proxy(DataAccessor.class, (p,m,a) -> {
                assertEquals("page", m.getName()); table(a[0]); assertEquals(0L, a[1]); assertNull(a[4]);
                pages.incrementAndGet(); pageRead.countDown();
                return new PagedResult(List.of("customer_id"), List.of(List.of(101), List.of(202)), false);
            });
            var ddl = proxy(DdlGenerator.class, (p,m,a) -> {
                assertEquals(object.kind() == TableInfo.Kind.VIEW ? "viewDdl" : "tableDdl", m.getName()); table(a[0]);
                ddls.incrementAndGet(); ddlRead.countDown(); return ddl();
            });
            var sql = proxy(SqlRunner.class, (p,m,a) -> { executions.incrementAndGet(); throw new AssertionError("Execution forbidden"); });
            return proxy(DatabaseProvider.class, (p,m,a) -> switch (m.getName()) {
                case "type" -> DbType.POSTGRESQL;
                case "dialect" -> new PostgresProvider().dialect();
                case "connectionFactory" -> factory;
                case "metadataReader" -> metadata;
                case "dataEditor" -> editor;
                case "dataAccessor" -> data;
                case "ddlGenerator" -> ddl;
                case "sqlRunner" -> sql;
                case "schemaDiffCapability", "resultFilterSqlRenderer" -> Optional.empty();
                default -> throw new AssertionError("Unexpected capability " + m.getName());
            });
        }
        PreparedStatement statement(String sql) {
            assertTrue(sql.startsWith("SELECT t.table_name, t.table_type, ") && sql.contains("FROM information_schema.tables t"));
            var parameters = new HashMap<Integer, String>();
            return proxy(PreparedStatement.class, (p,m,a) -> switch (m.getName()) {
                case "setString" -> { parameters.put((Integer) a[0], (String) a[1]); yield null; }
                case "setQueryTimeout" -> { assertEquals(10, a[0]); yield null; }
                case "setMaxRows" -> { assertEquals(201, a[0]); yield null; }
                case "close", "cancel" -> null;
                case "executeQuery" -> {
                    assertEquals("demo", parameters.get(1)); assertEquals("customer", parameters.get(2)); searches.incrementAndGet();
                    var row = new AtomicInteger();
                    yield proxy(ResultSet.class, (rp,rm,ra) -> switch (rm.getName()) {
                        case "next" -> row.incrementAndGet() == 1;
                        case "getString" -> switch ((Integer) ra[0]) {
                            case 1 -> "orders"; case 2 -> object.kind() == TableInfo.Kind.VIEW ? "VIEW" : "BASE TABLE";
                            case 3 -> "customer_id"; case 4 -> "customer_id"; default -> throw new AssertionError();
                        };
                        case "close" -> null;
                        default -> throw new AssertionError("Unexpected result method " + rm.getName());
                    });
                }
                default -> throw new AssertionError("Unexpected statement method " + m.getName());
            });
        }
        @Override public void close() throws Exception {
            try {
                var shutdown = FxUiTestSupport.call(() -> { ownedDialogs().reversed().forEach(w -> ((Stage) w).close()); return shell.shutdownAsync(); });
                assertEquals(ShutdownOutcome.COMPLETED, shutdown.toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertEquals(opens.get(), closes.get(), "every dedicated and cached mock connection closes");
                assertEquals(0, writes.get() + executions.get());
                System.out.println("CLOSED " + object.kind() + " opens=" + opens + " closes=" + closes
                        + " searches=" + searches + " pages=" + pages + " ddls=" + ddls + " writes=" + writes + " executions=" + executions);
            } finally {
                FxUiTestSupport.call(() -> { stage.close(); System.setProperty("user.home", originalHome); return null; });
            }
        }
    }
}
