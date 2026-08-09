package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.config.CredentialMigration;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.redis.RedisSession;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.PackageInfo;
import com.datacube.spi.model.RoutineInfo;
import com.datacube.spi.model.SchemaInfo;
import com.datacube.spi.model.SequenceInfo;
import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.TableRef;
import com.datacube.spi.model.TriggerInfo;
import com.datacube.spi.model.TypeInfo;
import com.datacube.spi.model.ViewInfo;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 连接树面板：左栏 {@link TreeView}，懒加载 schema/表/视图/函数/序列。
 *
 * <p>数据来自 {@link ObjectTreeService}；右键菜单/双击的具体动作委托 {@link Actions}
 * （由 {@link AppShell} 实现）。选中任意节点会将其所属连接设为活动连接。
 */
public final class ConnectionTreePane implements AutoCloseable {

    /** 树操作回调（由 AppShell 实现，打开对应内容标签）。 */
    public interface Actions {
        void openSqlEditor(ConnConfig conn, String schema);
        void openDataGrid(String connId, TableRef table, boolean readOnly);
        void openDdl(String connId, NodeData node);
        void editObject(String connId, NodeData node);
        void editSequence(String connId, NodeData node);
        void exportTable(String connId, TableRef table);
        void openTableDesigner(String connId, TableRef table);
        void newTable(String connId, String schema);
        void openRedisKeys(ConnConfig conn, int database);
        void openRedisConsole(ConnConfig conn);
    }

    enum Kind { CONNECTION, REDIS_DB, SCHEMA, TABLES, VIEWS, ROUTINES, PACKAGES, TRIGGERS, TYPES, SEQUENCES, TABLE, VIEW, ROUTINE, PACKAGE, TRIGGER, TYPE, SEQUENCE }

    /** 树节点数据。 */
    public static final class NodeData {
        final Kind kind;
        final String label;
        final ConnConfig conn;   // 仅连接节点非空
        final String connId;
        final String schema;
        final String name;

        NodeData(Kind kind, String label, ConnConfig conn, String connId, String schema, String name) {
            this.kind = kind;
            this.label = label;
            this.conn = conn;
            this.connId = connId;
            this.schema = schema;
            this.name = name;
        }

        public Kind kind() { return kind; }
        public String connId() { return connId; }
        public String schema() { return schema; }
        public String name() { return name; }

        @Override
        public String toString() { return label; }
    }

    private final ConnectionStore store;
    private final ConnectionManager connMgr;
    private final ObjectTreeService treeSvc;
    private final SessionContext session;
    private final Actions actions;
    private final FxTaskScope tasks;

    private final VBox root = new VBox(6);
    private final TreeView<NodeData> tree = new TreeView<>();

    // 快速检索：直接键入字母即在可见行内增量定位（不含 WHERE 那种搜索框）。
    private final Label searchHint = new Label();
    private final StringBuilder searchBuffer = new StringBuilder();
    private final PauseTransition searchReset = new PauseTransition(Duration.seconds(1.2));

    public ConnectionTreePane(ConnectionStore store, ConnectionManager connMgr,
                              ObjectTreeService treeSvc, SessionContext session, Actions actions,
                              FxTaskRunner runner) {
        this.store = store;
        this.connMgr = connMgr;
        this.treeSvc = treeSvc;
        this.session = session;
        this.actions = actions;
        this.tasks = runner.scope();
        build();
    }

    public Node getNode() {
        return root;
    }

    @Override
    public void close() {
        tasks.close();
    }

    /** 新建连接（供上方应用头工具栏调用）。 */
    public void newConnection() {
        onAddConnection();
    }

    /** 刷新连接树（供上方应用头工具栏调用）。 */
    public void refresh() {
        reload();
    }

    private void build() {
        root.setPadding(new Insets(6));

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>(new NodeData(Kind.CONNECTION, "root", null, null, null, null)));
        tree.setCellFactory(tv -> new TreeCellImpl());

        // 选中节点 -> 设为活动连接
        tree.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            if (sel == null) return;
            ConnConfig c = connOf(sel);
            if (c != null) session.setActiveConnection(c);
        });

        // 双击表/视图 -> 打开数据浏览（视图只读）
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<NodeData> sel = tree.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue().kind == Kind.REDIS_DB) {
                    NodeData d = sel.getValue();
                    actions.openRedisKeys(connOf(sel), Integer.parseInt(d.name));
                } else if (sel != null && (sel.getValue().kind == Kind.TABLE || sel.getValue().kind == Kind.VIEW)) {
                    NodeData d = sel.getValue();
                    actions.openDataGrid(d.connId, new TableRef(d.schema, d.name), d.kind == Kind.VIEW);
                }
            }
        });

        root.getChildren().addAll(tree, searchHint);
        VBox.setVgrow(tree, Priority.ALWAYS);
        installQuickSearch();
        reload();
    }

    /** 重新加载连接列表（从存储读取并注册到 ConnectionManager）。 */
    public void reload() {
        tree.getRoot().getChildren().clear();
        List<ConnConfig> configs = store.loadAll();
        for (ConnConfig cfg : configs) {
            connMgr.register(cfg);
            tree.getRoot().getChildren().add(connectionItem(cfg));
        }
    }

    private void onAddConnection() {
        ConnectionDialog.show(null, connMgr.cipher(), connMgr).ifPresent(cfg -> {
            List<ConnConfig> all = new ArrayList<>(store.loadAll());
            all.add(cfg);
            saveSnapshot(all);
            connMgr.register(cfg);
            tree.getRoot().getChildren().add(connectionItem(cfg));
        });
    }

    private void onEditConnection(ConnConfig existing) {
        ConnectionDialog.show(existing, connMgr.cipher(), connMgr).ifPresent(cfg -> {
            List<ConnConfig> all = new ArrayList<>();
            for (ConnConfig c : store.loadAll()) {
                all.add(c.id().equals(cfg.id()) ? cfg : c);
            }
            saveSnapshot(all);
            connMgr.register(cfg);
            reload();
        });
    }

    private void onDeleteConnection(ConnConfig cfg) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除连接 \"" + cfg.name() + "\"？", ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText(null);
        confirm.showAndWait();
        if (confirm.getResult() != ButtonType.YES) return;
        List<ConnConfig> all = new ArrayList<>();
        for (ConnConfig c : store.loadAll()) {
            if (!c.id().equals(cfg.id())) all.add(c);
        }
        saveSnapshot(all);
        connMgr.unregister(cfg.id());
        reload();
    }

    private void saveSnapshot(List<ConnConfig> configs) {
        store.saveAll(CredentialMigration.upgradeAll(configs, connMgr.cipher()));
    }

    /** 断开连接：关闭活动连接并将该节点重置为未展开的懒加载状态（下次展开重连）。 */
    private void disconnect(ConnConfig cfg) {
        connMgr.release(cfg.id());
        List<TreeItem<NodeData>> rootChildren = tree.getRoot().getChildren();
        for (int i = 0; i < rootChildren.size(); i++) {
            NodeData d = rootChildren.get(i).getValue();
            if (d != null && cfg.id().equals(d.connId)) {
                rootChildren.set(i, connectionItem(cfg));
                break;
            }
        }
    }

    // ---------- 树节点构建 ----------

    private TreeItem<NodeData> connectionItem(ConnConfig cfg) {
        NodeData d = new NodeData(Kind.CONNECTION, cfg.name(), cfg, cfg.id(), null, null);
        if (cfg.type() == DbType.REDIS) {
            return lazyItem(d, () -> redisDatabaseItems(cfg));
        }
        return lazyItem(d, () -> {
            List<TreeItem<NodeData>> out = new ArrayList<>();
            if (treeSvc.hasSchemaLevel(cfg.id())) {
                for (SchemaInfo s : treeSvc.schemas(cfg.id(), cfg.database())) {
                    out.add(schemaItem(cfg.id(), s.name()));
                }
            } else {
                out.addAll(schemaChildren(cfg.id(), null));
            }
            return out;
        });
    }

    private List<TreeItem<NodeData>> redisDatabaseItems(ConnConfig cfg) {
        RedisSession redis = connMgr.acquireRedis(cfg.id());
        Map<Integer, Long> sizes = parseKeyspaceInfo(redis.info("keyspace"));
        List<TreeItem<NodeData>> out = new ArrayList<>(16);
        for (int database = 0; database < 16; database++) {
            long size = sizes.getOrDefault(database, 0L);
            String label = "db" + database + " (" + String.format("%,d", size) + ")";
            out.add(new TreeItem<>(new NodeData(Kind.REDIS_DB, label, null, cfg.id(), null,
                    Integer.toString(database))));
        }
        return out;
    }

    private static Map<Integer, Long> parseKeyspaceInfo(String info) {
        Map<Integer, Long> sizes = new HashMap<>();
        if (info == null) return sizes;
        for (String line : info.split("\\R")) {
            if (!line.startsWith("db")) continue;
            int colon = line.indexOf(':');
            int keys = line.indexOf("keys=", colon + 1);
            if (colon < 3 || keys < 0) continue;
            int comma = line.indexOf(',', keys);
            try {
                int database = Integer.parseInt(line.substring(2, colon));
                long count = Long.parseLong(line.substring(keys + 5, comma < 0 ? line.length() : comma));
                sizes.put(database, count);
            } catch (NumberFormatException ignored) {
                // Ignore a malformed INFO line and keep showing the remaining DBs.
            }
        }
        return sizes;
    }

    private TreeItem<NodeData> schemaItem(String connId, String schema) {
        NodeData d = new NodeData(Kind.SCHEMA, schema, null, connId, schema, schema);
        return lazyItem(d, () -> schemaChildren(connId, schema));
    }

    private List<TreeItem<NodeData>> schemaChildren(String connId, String schema) {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        out.add(lazyItem(new NodeData(Kind.TABLES, "表", null, connId, schema, null),
                () -> tableItems(connId, schema)));
        out.add(lazyItem(new NodeData(Kind.VIEWS, "视图", null, connId, schema, null),
                () -> viewItems(connId, schema)));
        out.add(lazyItem(new NodeData(Kind.ROUTINES, "函数/过程", null, connId, schema, null),
                () -> routineItems(connId, schema)));
        if (treeSvc.supportsPackages(connId)) {
            out.add(lazyItem(new NodeData(Kind.PACKAGES, "程序包", null, connId, schema, null),
                    () -> packageItems(connId, schema)));
        }
        if (treeSvc.supportsTriggers(connId)) {
            out.add(lazyItem(new NodeData(Kind.TRIGGERS, "触发器", null, connId, schema, null),
                    () -> triggerItems(connId, schema)));
        }
        if (treeSvc.supportsTypes(connId)) {
            out.add(lazyItem(new NodeData(Kind.TYPES, "类型", null, connId, schema, null),
                    () -> typeItems(connId, schema)));
        }
        out.add(lazyItem(new NodeData(Kind.SEQUENCES, "序列", null, connId, schema, null),
                () -> sequenceItems(connId, schema)));
        return out;
    }

    private List<TreeItem<NodeData>> tableItems(String connId, String schema) throws Exception {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        for (TableInfo t : treeSvc.tables(connId, schema)) {
            out.add(new TreeItem<>(new NodeData(Kind.TABLE, t.name(), null, connId, schema, t.name())));
        }
        return out;
    }

    private List<TreeItem<NodeData>> viewItems(String connId, String schema) throws Exception {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        for (ViewInfo v : treeSvc.views(connId, schema)) {
            out.add(new TreeItem<>(new NodeData(Kind.VIEW, v.name(), null, connId, schema, v.name())));
        }
        return out;
    }

    private List<TreeItem<NodeData>> routineItems(String connId, String schema) throws Exception {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        for (RoutineInfo r : treeSvc.routines(connId, schema)) {
            out.add(new TreeItem<>(new NodeData(Kind.ROUTINE, r.name(), null, connId, schema, r.name())));
        }
        return out;
    }

    private List<TreeItem<NodeData>> packageItems(String connId, String schema) throws Exception {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        for (PackageInfo p : treeSvc.packages(connId, schema)) {
            out.add(new TreeItem<>(new NodeData(Kind.PACKAGE, p.name(), null, connId, schema, p.name())));
        }
        return out;
    }

    private List<TreeItem<NodeData>> triggerItems(String connId, String schema) throws Exception {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        for (TriggerInfo t : treeSvc.triggers(connId, schema)) {
            out.add(new TreeItem<>(new NodeData(Kind.TRIGGER, t.name(), null, connId, schema, t.name())));
        }
        return out;
    }

    private List<TreeItem<NodeData>> typeItems(String connId, String schema) throws Exception {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        for (TypeInfo t : treeSvc.types(connId, schema)) {
            out.add(new TreeItem<>(new NodeData(Kind.TYPE, t.name(), null, connId, schema, t.name())));
        }
        return out;
    }

    private List<TreeItem<NodeData>> sequenceItems(String connId, String schema) throws Exception {
        List<TreeItem<NodeData>> out = new ArrayList<>();
        for (SequenceInfo s : treeSvc.sequences(connId, schema)) {
            out.add(new TreeItem<>(new NodeData(Kind.SEQUENCE, s.name(), null, connId, schema, s.name())));
        }
        return out;
    }

    /** 构造懒加载节点：首次展开时在受管虚拟线程中加载子节点。 */
    private TreeItem<NodeData> lazyItem(NodeData data, Callable<List<TreeItem<NodeData>>> loader) {
        TreeItem<NodeData> item = new TreeItem<>(data);
        TreeItem<NodeData> placeholder = new TreeItem<>(
                new NodeData(data.kind, "加载中...", null, data.connId, data.schema, null));
        item.getChildren().add(placeholder);
        final boolean[] loaded = {false};
        item.expandedProperty().addListener((obs, was, is) -> {
            if (is && !loaded[0]) {
                loaded[0] = true;
                loadInto(item, loader);
            }
        });
        return item;
    }

    private void loadInto(TreeItem<NodeData> item, Callable<List<TreeItem<NodeData>>> loader) {
        tasks.submit(loader,
                children -> item.getChildren().setAll(children),
                failure -> item.getChildren().setAll(List.of(new TreeItem<>(
                        new NodeData(item.getValue().kind, "错误: " + message(failure),
                                null, null, null, null)))));
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** 沿树向上找到所属连接的 ConnConfig。 */
    private ConnConfig connOf(TreeItem<NodeData> item) {
        TreeItem<NodeData> cur = item;
        while (cur != null) {
            if (cur.getValue() != null && cur.getValue().conn != null) {
                return cur.getValue().conn;
            }
            cur = cur.getParent();
        }
        return null;
    }

    // ---------- 快速检索（键入即定位可见行） ----------

    /** 安装键入型快速检索：直接敲字母累积成关键字，在可见行内忽略大小写做“包含”匹配。 */
    private void installQuickSearch() {
        searchHint.setManaged(false);
        searchHint.setVisible(false);
        searchHint.setPadding(new Insets(2, 6, 2, 6));
        searchReset.setOnFinished(e -> clearSearch());

        // Esc 清除当前查找串（仅在检索进行中拦截，不影响其它场景）。
        tree.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && searchBuffer.length() > 0) {
                clearSearch();
                e.consume();
            }
        });

        // 可打印字符累积；退格删字符；Enter/Tab 等控制键交默认处理（不拦截方向键导航）。
        tree.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            String s = e.getCharacter();
            if (s == null || s.isEmpty()) return;
            char c = s.charAt(0);
            if (c == '\b') {                       // 退格
                if (searchBuffer.length() > 0) {
                    searchBuffer.deleteCharAt(searchBuffer.length() - 1);
                    if (searchBuffer.length() == 0) {
                        clearSearch();
                    } else {
                        showHint(runSearch());
                        searchReset.playFromStart();
                    }
                }
                e.consume();
                return;
            }
            if (Character.isISOControl(c)) return;  // Enter/Tab/Esc 等不参与检索
            searchBuffer.append(c);
            showHint(runSearch());
            searchReset.playFromStart();
            e.consume();
        });
    }

    /**
     * 在当前可见行（展开链）内从选中项起环形查找，命中即选中并滚动定位。
     *
     * @return 是否命中
     */
    private boolean runSearch() {
        int n = tree.getExpandedItemCount();
        if (n == 0) return false;
        int sel = tree.getSelectionModel().getSelectedIndex();
        int start = sel < 0 ? 0 : sel;
        String needle = searchBuffer.toString().toLowerCase();
        for (int off = 0; off < n; off++) {
            int idx = (start + off) % n;
            TreeItem<NodeData> it = tree.getTreeItem(idx);
            NodeData d = it == null ? null : it.getValue();
            if (d != null && d.label != null && d.label.toLowerCase().contains(needle)) {
                tree.getSelectionModel().select(idx);
                tree.scrollTo(idx);
                return true;
            }
        }
        return false;
    }

    private void showHint(boolean matched) {
        searchHint.setText(matched ? "查找: " + searchBuffer : "查找: " + searchBuffer + "  (无匹配)");
        searchHint.setStyle(matched
                ? "-fx-background-color:-warn-bg; -fx-border-color:-warn-fg; -fx-text-fill:-warn-fg; -fx-background-radius:3; -fx-border-radius:3;"
                : "-fx-background-color:-danger-bg; -fx-border-color:-danger-fg; -fx-text-fill:-danger-fg; -fx-background-radius:3; -fx-border-radius:3;");
        searchHint.setManaged(true);
        searchHint.setVisible(true);
    }

    private void clearSearch() {
        searchBuffer.setLength(0);
        searchReset.stop();
        searchHint.setVisible(false);
        searchHint.setManaged(false);
    }

    // ---------- 自定义单元格（含右键菜单） ----------

    private final class TreeCellImpl extends TreeCell<NodeData> {
        @Override
        protected void updateItem(NodeData item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setContextMenu(null);
                return;
            }
            setText(item.label);
            setStyle(item.kind == Kind.REDIS_DB && item.label.endsWith("(0)")
                    ? "-fx-text-fill: -brand-fg-muted;" : "");
            setContextMenu(buildMenu(item));
        }

        private ContextMenu buildMenu(NodeData d) {
            ContextMenu menu = new ContextMenu();
            switch (d.kind) {
                case CONNECTION -> {
                    MenuItem primary = new MenuItem(d.conn.type() == DbType.REDIS ? "打开命令行控制台" : "打开 SQL 编辑器");
                    primary.setOnAction(e -> {
                        if (d.conn.type() == DbType.REDIS) actions.openRedisConsole(d.conn);
                        else actions.openSqlEditor(d.conn, null);
                    });
                    MenuItem edit = new MenuItem("编辑连接");
                    edit.setOnAction(e -> onEditConnection(d.conn));
                    MenuItem del = new MenuItem("删除连接");
                    del.setOnAction(e -> onDeleteConnection(d.conn));
                    MenuItem refresh = new MenuItem("刷新");
                    refresh.setOnAction(e -> reload());
                    menu.getItems().addAll(primary, edit, del, refresh);
                    // 仅在已连接时提供“断开连接”（连接为惰性建立，展开节点才连）。
                    if (connMgr.isConnected(d.connId)) {
                        MenuItem disconnect = new MenuItem("断开连接");
                        disconnect.setOnAction(e -> disconnect(d.conn));
                        menu.getItems().add(1, disconnect);
                    }
                }
                case REDIS_DB -> {
                    MenuItem open = new MenuItem("打开键浏览器");
                    open.setOnAction(e -> actions.openRedisKeys(connOf(getTreeItem()), Integer.parseInt(d.name)));
                    menu.getItems().add(open);
                }
                case SCHEMA -> {
                    MenuItem sql = new MenuItem("打开 SQL 编辑器");
                    sql.setOnAction(e -> actions.openSqlEditor(connOf(getTreeItem()), d.schema));
                    menu.getItems().add(sql);
                }
                case TABLES -> {
                    MenuItem create = new MenuItem("新建表");
                    create.setOnAction(e -> actions.newTable(d.connId, d.schema));
                    menu.getItems().add(create);
                }
                case TABLE -> {
                    MenuItem data = new MenuItem("查看数据");
                    data.setOnAction(e -> actions.openDataGrid(d.connId, new TableRef(d.schema, d.name), false));
                    MenuItem design = new MenuItem("设计表");
                    design.setOnAction(e -> actions.openTableDesigner(d.connId, new TableRef(d.schema, d.name)));
                    MenuItem ddl = new MenuItem("查看 DDL");
                    ddl.setOnAction(e -> actions.openDdl(d.connId, d));
                    MenuItem export = new MenuItem("导出...");
                    export.setOnAction(e -> actions.exportTable(d.connId, new TableRef(d.schema, d.name)));
                    MenuItem sql = new MenuItem("打开 SQL 编辑器");
                    sql.setOnAction(e -> actions.openSqlEditor(connOf(getTreeItem()), d.schema));
                    menu.getItems().addAll(data, design, ddl, export, sql);
                }
                case VIEW -> {
                    MenuItem data = new MenuItem("查看数据");
                    data.setOnAction(e -> actions.openDataGrid(d.connId, new TableRef(d.schema, d.name), true));
                    MenuItem ddl = new MenuItem("查看 DDL");
                    ddl.setOnAction(e -> actions.openDdl(d.connId, d));
                    MenuItem edit = new MenuItem("编辑");
                    edit.setOnAction(e -> actions.editObject(d.connId, d));
                    menu.getItems().addAll(data, ddl, edit);
                }
                case ROUTINE, PACKAGE, TRIGGER, TYPE -> {
                    MenuItem ddl = new MenuItem("查看 DDL");
                    ddl.setOnAction(e -> actions.openDdl(d.connId, d));
                    MenuItem edit = new MenuItem("编辑");
                    edit.setOnAction(e -> actions.editObject(d.connId, d));
                    menu.getItems().addAll(ddl, edit);
                }
                case SEQUENCE -> {
                    MenuItem ddl = new MenuItem("查看 DDL");
                    ddl.setOnAction(e -> actions.openDdl(d.connId, d));
                    MenuItem edit = new MenuItem("编辑");
                    edit.setOnAction(e -> actions.editSequence(d.connId, d));
                    MenuItem editSql = new MenuItem("编辑 SQL");
                    editSql.setOnAction(e -> actions.editObject(d.connId, d));
                    menu.getItems().addAll(ddl, edit, editSql);
                }
                default -> {
                    return null;
                }
            }
            return menu;
        }
    }
}
