package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ConnectionStore;
import com.datacube.config.CredentialCipher;
import com.datacube.service.ConnectionManager;
import com.datacube.service.DataBrowseService;
import com.datacube.service.DdlService;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.RoutineRef;
import com.datacube.spi.model.TableRef;
import com.datacube.update.UpdateService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.concurrent.Callable;

/**
 * 应用主壳：三栏式布局（顶部工具栏 + 左连接树 + 中内容区）。
 *
 * <p>持有并装配服务层（{@link ConnectionManager} 等），实现
 * {@link ConnectionTreePane.Actions} 将树操作转为内容标签。
 * 迁移功能作为一个常驻标签保留。
 */
public final class AppShell {

    private final BorderPane root = new BorderPane();

    private final CredentialCipher cipher = new CredentialCipher();
    private final ConnectionStore store = new ConnectionStore();
    private final AppSettings settings = new AppSettings();
    private final ConnectionManager connMgr = new ConnectionManager(cipher);
    private final ObjectTreeService treeSvc = new ObjectTreeService(connMgr);
    private final DataBrowseService browseSvc = new DataBrowseService(connMgr);
    private final DdlService ddlSvc = new DdlService(connMgr);
    private final SessionContext session = new SessionContext();

    private final ContentTabPane contentTabs = new ContentTabPane();
    private final MigrationPane migrationPane = new MigrationPane();
    private final UpdateService updateService = new UpdateService();

    public AppShell() {
        build();
    }

    public BorderPane getRoot() {
        return root;
    }

    private void build() {
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");
        root.setTop(topBar());

        ConnectionTreePane treePane = new ConnectionTreePane(store, connMgr, treeSvc, session, new TreeActions());

        // 迁移功能常驻标签
        contentTabs.addPermanentTab("数据迁移", migrationPane.getNode());

        SplitPane split = new SplitPane(treePane.getNode(), contentTabs.getNode());
        split.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(treePane.getNode(), false);
        root.setCenter(split);
    }

    private HBox topBar() {
        Label title = new Label("DataCube 数据库管理工具");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #2e3440;");
        Label active = new Label();
        active.setStyle("-fx-text-fill: #666;");
        session.activeConnectionProperty().addListener((obs, o, c) ->
                active.setText(c == null ? "" : "  |  活动连接: " + c.name()));
        Button aboutBtn = new Button("ℹ 关于");
        aboutBtn.setOnAction(e ->
                AboutDialog.show(updateService, root.getScene() == null ? null : root.getScene().getWindow()));
        Button settingsBtn = new Button("⚙ 设置");
        settingsBtn.setOnAction(e ->
                SettingsDialog.show(settings, root.getScene() == null ? null : root.getScene().getWindow()));
        HBox bar = new HBox(6, title, active, aboutBtn, settingsBtn);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: #eceff4; -fx-border-color: transparent transparent #d8dee9 transparent;");
        HBox.setHgrow(active, Priority.ALWAYS);
        return bar;
    }

    /** 是否有迁移任务在运行（供窗口关闭确认）。 */
    public boolean isRunning() {
        return migrationPane.isRunning();
    }

    /** 启动后台静默自检：仅在发现新版时在 UI 线程弹出更新提示（失败静默）。 */
    public void checkForUpdatesOnStartup() {
        updateService.checkInBackground(info ->
                Platform.runLater(() -> UpdateUI.promptUpdate(updateService, info,
                        root.getScene() == null ? null : root.getScene().getWindow())));
    }

    /** 释放全部资源：迁移资源 + 关闭所有活动连接。 */
    public void shutdown() {
        try {
            migrationPane.shutdown();
        } finally {
            connMgr.closeAll();
        }
    }

    /** 连接树动作实现：将树操作转为内容标签。 */
    private final class TreeActions implements ConnectionTreePane.Actions {
        @Override
        public void openSqlEditor(ConnConfig conn) {
            if (conn != null) session.setActiveConnection(conn);
            SqlEditorPane pane = new SqlEditorPane(session, connMgr, treeSvc, settings);
            String name = conn == null ? "SQL" : "SQL - " + conn.name();
            contentTabs.openTab(name, pane.getNode());
        }

        @Override
        public void openDataGrid(String connId, TableRef table) {
            DataGridPane pane = new DataGridPane(browseSvc, connId, table);
            contentTabs.openTab("数据: " + table.name(), pane.getNode());
        }

        @Override
        public void exportTable(String connId, TableRef table) {
            ExportDialog.show(connMgr, connId, table,
                    root.getScene() == null ? null : root.getScene().getWindow());
        }

        @Override
        public void openDdl(String connId, ConnectionTreePane.NodeData node) {
            String schema = node.schema();
            String name = node.name();
            Callable<String> fetch = switch (node.kind()) {
                case TABLE -> () -> ddlSvc.tableDdl(connId, new TableRef(schema, name));
                case VIEW -> () -> ddlSvc.viewDdl(connId, new TableRef(schema, name));
                case ROUTINE -> () -> ddlSvc.routineDdl(connId, new RoutineRef(schema, name));
                case SEQUENCE -> () -> ddlSvc.sequenceDdl(connId, schema, name);
                default -> () -> "-- 不支持的对象类型";
            };
            DdlViewPane pane = new DdlViewPane("DDL: " + name, fetch);
            contentTabs.openTab("DDL: " + name, pane.getNode());
        }
    }
}
