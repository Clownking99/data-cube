package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.TableRef;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.sqleditor.TableSelectSql;
import java.util.List;
import java.util.function.Supplier;

/** Installs a generated SELECT through the existing passive script and managed-tab lifecycles. */
final class TableSelectSqlTabs {
    private TableSelectSqlTabs() {}

    static boolean open(ContentTabPane tabs, ConnConfig connection, TableRef table,
            ConnectionManager connections, Supplier<SqlEditorPane> passiveEditorFactory,
            Supplier<List<ConnConfig>> connectionChoices, SqlScriptFileStore files, RecentSqlFiles recent,
            AppShell.SqlFileDraftLifecycle drafts, SqlFileTabRegistry registry) {
        if (connection == null || connection.id() == null || connection.id().isBlank()
                || !connection.equals(connections.config(connection.id())))
            throw new IllegalArgumentException("所选连接已变更或不可用，请刷新连接树后重试。");
        String sql = TableSelectSql.generate(connection.type(), table);
        String title = "SQL - " + connection.name() + " - " + table.name();
        return AppShell.openSqlTab(tabs, title, passiveEditorFactory, pane -> {
            pane.installFileConnectionChooser(connectionChoices);
            if (!connection.equals(connections.config(connection.id())) || !pane.chooseFileConnection(connection))
                throw new IllegalArgumentException("所选连接已变更或不可用，请刷新连接树后重试。");
            pane.setSqlText(sql);
        }, files, recent, drafts, registry);
    }
}
