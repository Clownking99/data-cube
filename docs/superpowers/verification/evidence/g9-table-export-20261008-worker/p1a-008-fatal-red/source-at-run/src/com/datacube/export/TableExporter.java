package com.datacube.export;

import com.datacube.service.ConnectionManager;
import com.datacube.spi.DataAccessor;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.TableRef;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

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

    /** File identity is captured before resource acquisition, and shared by confirmation and publication. */
    public record Request(String connId, TableRef table, ExportContent content, ExportFormat format,
                          SafeResultFilePublisher.Target target) {
        public Request {
            Objects.requireNonNull(connId); Objects.requireNonNull(table); Objects.requireNonNull(content);
            Objects.requireNonNull(format); Objects.requireNonNull(target);
        }
        @Override public String toString() { return "TableExportRequest[" + format + "]"; }
    }

    @FunctionalInterface interface DumpJob {
        void run(ConnConfig cfg, String password, TableRef table, ExportContent content, File temporary) throws Exception;
    }
    @FunctionalInterface interface OutputFactory { OutputStream open(Path temporary) throws Exception; }

    public static void export(ConnectionManager conns, String connId, TableRef t,
                              ExportContent content, ExportFormat format, File out) throws Exception {
        export(conns, new Request(connId, t, content, format, SafeResultFilePublisher.capture(out.toPath())),
                new ResultExportOperation());
    }

    public static Path export(ConnectionManager conns, Request request, ResultExportOperation operation) throws Exception {
        return export(conns, request, operation, new SafeResultFilePublisher(), PgDumpRunner::run,
                Files::newOutputStream);
    }

    static Path export(ConnectionManager conns, Request request, ResultExportOperation operation,
                       SafeResultFilePublisher publisher, DumpJob dump, OutputFactory output) throws Exception {
        return publisher.publish(request.target(), operation, (temporary, token) ->
                writeTemporary(conns, request, temporary, token, dump, output));
    }

    private static void writeTemporary(ConnectionManager conns, Request request, Path temporary,
                                       ResultExportOperation operation, DumpJob dump, OutputFactory output) throws Exception {
        operation.check();
        String connId = request.connId();
        TableRef t = request.table();
        ExportContent content = request.content();
        switch (request.format()) {
            case PG_DUMP -> {
                ConnConfig cfg = conns.config(connId);
                String pw = conns.cipher().decrypt(cfg.encryptedPassword());
                operation.check();
                dump.run(cfg, pw, t, content, temporary.toFile());
            }
            case XLSX -> {
                Connection conn = conns.acquire(connId);
                DataAccessor da = conns.provider(connId).dataAccessor(conn);
                // Excel 仅导出数据
                List<String> columns = columnsOf(da, t);
                operation.check();
                XlsxWriter.write(output.open(temporary), columns, pagingFeed(da, t, operation));
            }
            case SQL -> {
                Connection conn = conns.acquire(connId);
                DatabaseProvider provider = conns.provider(connId);
                DataAccessor da = provider.dataAccessor(conn);
                String ddl = content.includesStructure()
                        ? provider.ddlGenerator(conn).tableDdl(t) : null;
                List<String> cols = content.includesData() ? columnsOf(da, t) : List.of();
                operation.check();
                SqlScriptExporter.write(output.open(temporary), t, content, ddl, cols,
                        pagingFeed(da, t, operation), provider.dialect());
            }
        }
        operation.check();
    }

    /** 取一行以读回列名（空表也能拿到列）。 */
    private static List<String> columnsOf(DataAccessor da, TableRef t) throws SQLException {
        PagedResult first = da.page(t, 0, 1, null, null);
        return first.columns();
    }

    /** 分页遍历全表的流式 RowFeed。 */
    private static RowFeed pagingFeed(DataAccessor da, TableRef t, ResultExportOperation operation) {
        return sink -> {
            long offset = 0;
            while (true) {
                operation.check();
                PagedResult pr = da.page(t, offset, PAGE, null, null);
                for (List<Object> row : pr.rows()) {
                    operation.check();
                    sink.row(row);
                    operation.check();
                }
                if (!pr.hasMore()) break;
                offset += PAGE;
            }
        };
    }
}
