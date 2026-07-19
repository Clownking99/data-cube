package com.datacube.export;

import com.datacube.service.ConnectionManager;
import com.datacube.spi.DataAccessor;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.TableRef;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 单表导出编排：按 {@link ExportFormat} 分派到对应 writer / 外部工具。
 *
 * <p>数据来源统一用 {@link DataAccessor} 分页构造流式 {@link RowFeed}，
 * 结构来自 {@link DatabaseProvider#ddlGenerator}，pg_dump 走外部进程。
 */
public final class TableExporter {

    private static final int PAGE = 500;

    private TableExporter() {
    }

    public static void export(ConnectionManager conns, String connId, TableRef t,
                              ExportContent content, ExportFormat format, File out) throws Exception {
        switch (format) {
            case PG_DUMP -> {
                ConnConfig cfg = conns.config(connId);
                String pw = conns.cipher().decrypt(cfg.encryptedPassword());
                PgDumpRunner.run(cfg, pw, t, content, out);
            }
            case XLSX -> {
                Connection conn = conns.acquire(connId);
                DataAccessor da = conns.provider(connId).dataAccessor(conn);
                // Excel 仅导出数据
                XlsxWriter.write(out, columnsOf(da, t), pagingFeed(da, t));
            }
            case SQL -> {
                Connection conn = conns.acquire(connId);
                DatabaseProvider provider = conns.provider(connId);
                DataAccessor da = provider.dataAccessor(conn);
                String ddl = content.includesStructure()
                        ? provider.ddlGenerator(conn).tableDdl(t) : null;
                List<String> cols = content.includesData() ? columnsOf(da, t) : List.of();
                SqlScriptExporter.write(out, t, content, ddl, cols, pagingFeed(da, t), provider.dialect());
            }
        }
    }

    /** 取一行以读回列名（空表也能拿到列）。 */
    private static List<String> columnsOf(DataAccessor da, TableRef t) throws SQLException {
        PagedResult first = da.page(t, 0, 1, null, null);
        return first.columns();
    }

    /** 分页遍历全表的流式 RowFeed。 */
    private static RowFeed pagingFeed(DataAccessor da, TableRef t) {
        return sink -> {
            long offset = 0;
            while (true) {
                PagedResult pr = da.page(t, offset, PAGE, null, null);
                for (List<Object> row : pr.rows()) {
                    sink.row(row);
                }
                if (!pr.hasMore()) break;
                offset += PAGE;
            }
        };
    }
}
