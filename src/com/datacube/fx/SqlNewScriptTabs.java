package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.spi.model.ConnConfig;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.util.List;
import java.util.function.Supplier;

/** New unbound scripts share the passive connection and managed file/draft lifecycles. */
final class SqlNewScriptTabs {
    private SqlNewScriptTabs() {}

    static boolean open(ContentTabPane tabs, Supplier<SqlEditorPane> passiveEditorFactory,
            Supplier<List<ConnConfig>> choices, SqlScriptFileStore files, RecentSqlFiles recent,
            AppShell.SqlFileDraftLifecycle drafts, SqlFileTabRegistry registry) {
        return AppShell.openSqlTab(tabs, "SQL - 新脚本", passiveEditorFactory,
                pane -> pane.installFileConnectionChooser(choices), files, recent, drafts, registry);
    }
}
