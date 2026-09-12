package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.config.SqlHistoryStore.Entry;
import com.datacube.spi.model.ConnConfig;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Restores history text through managed script/file/draft lifecycles; names are display hints only. */
final class SqlHistoryTabs {
    private SqlHistoryTabs() {}

    static boolean open(ContentTabPane tabs, Entry entry, Function<String, SqlEditorPane> editorFactory,
            Supplier<List<ConnConfig>> choices, SqlScriptFileStore files, RecentSqlFiles recent,
            AppShell.SqlFileDraftLifecycle drafts, SqlFileTabRegistry registry) {
        String title = entry.connName() == null ? "SQL - 历史" : "SQL - 历史 - " + entry.connName();
        return AppShell.openSqlTab(tabs, title, () -> editorFactory.apply(entry.schema()),
                pane -> {
                    pane.setSqlText(entry.sql());
                    pane.installFileConnectionChooser(choices);
                }, files, recent, drafts, registry);
    }
}
