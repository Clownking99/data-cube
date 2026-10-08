package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.config.SqlFavorite;
import com.datacube.spi.model.ConnConfig;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.util.List;
import java.util.function.Supplier;

/** A favorite supplies text only. The factory uses an isolated session and the existing file chooser. */
final class SqlFavoriteTabs {
    private SqlFavoriteTabs() {}
    static boolean open(ContentTabPane tabs, SqlFavorite favorite, Supplier<SqlEditorPane> factory,
            Supplier<List<ConnConfig>> choices, SqlScriptFileStore files, RecentSqlFiles recent,
            AppShell.SqlFileDraftLifecycle drafts, SqlFileTabRegistry registry) {
        return AppShell.openSqlTab(tabs, "SQL - 收藏 - " + favorite.name(), factory, pane -> {
            pane.setSqlText(favorite.sql());
            pane.installFileConnectionChooser(choices);
        }, files, recent, drafts, registry);
    }
}
