package com.datacube.export;

import com.datacube.sqleditor.InsertSqlGenerator;
import com.datacube.sqleditor.result.ResultExportScope;
import com.datacube.sqleditor.result.ResultExportSnapshot;
import com.datacube.sqleditor.result.ResultExportValuePolicy;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;

/** Adapts a captured query result to the application's supported file formats. */
public final class QueryResultFileWriter {
    private QueryResultFileWriter() {
    }

    public enum Format {
        XLSX("Excel (.xlsx)", "Excel 文件", "*.xlsx", "query_result.xlsx"),
        CSV("CSV (.csv)", "CSV 文件", "*.csv", "query_result.csv"),
        SQL("SQL 插入脚本 (.sql)", "SQL 脚本", "*.sql", "query_result.sql"),
        HTML("HTML (.html)", "HTML 文件", "*.html", "query_result.html"),
        XML("XML (.xml)", "XML 文件", "*.xml", "query_result.xml");

        public final String label;
        public final String filterDesc;
        public final String filterExt;
        public final String defaultName;

        Format(String label, String filterDesc, String filterExt, String defaultName) {
            this.label = label;
            this.filterDesc = filterDesc;
            this.filterExt = filterExt;
            this.defaultName = defaultName;
        }
    }

    private static List<List<Object>> guardedRows(ResultExportSnapshot snapshot,
            ResultExportScope scope, ResultExportOperation operation, boolean display) {
        List<List<Object>> source = snapshot.rows(scope);
        return new AbstractList<>() {
            @Override
            public int size() {
                return source.size();
            }

            @Override
            public List<Object> get(int index) {
                operation.check();
                List<Object> row = source.get(index);
                if (!display) return row;
                return new AbstractList<>() {
                    @Override
                    public int size() {
                        return row.size();
                    }

                    @Override
                    public Object get(int column) {
                        operation.check();
                        return ResultExportValuePolicy.displayValue(row.get(column));
                    }
                };
            }
        };
    }

    private static void validate(ResultExportSnapshot snapshot, List<List<Object>> rows,
            boolean sql, boolean displayConfirmed, String table) {
        if (snapshot.columns().isEmpty() || rows.isEmpty()) {
            throw new IllegalArgumentException("No exportable result");
        }
        if (sql && (table == null || table.isBlank())) {
            throw new IllegalArgumentException("Missing INSERT target");
        }
        var assessment = ResultExportValuePolicy.assess(rows);
        if (!assessment.sqlAllowed() && (sql || !displayConfirmed)) {
            throw new IllegalArgumentException("Export value policy rejected");
        }
    }

    public static String insert(ResultExportSnapshot snapshot, ResultExportScope scope, String table) {
        var operation = new ResultExportOperation();
        var rows = guardedRows(snapshot, scope, operation, false);
        validate(snapshot, rows, true, false, table);
        return InsertSqlGenerator.generate(table, snapshot.columns(), rows);
    }

    public static void write(Path temporary, Format format, ResultExportSnapshot snapshot,
            ResultExportScope scope, boolean displayConfirmed, String table,
            ResultExportOperation operation) throws Exception {
        operation.check();
        var originalRows = guardedRows(snapshot, scope, operation, false);
        validate(snapshot, originalRows, format == Format.SQL, displayConfirmed, table);
        var rows = guardedRows(snapshot, scope, operation, format != Format.SQL);
        List<String> columns = snapshot.columns();
        switch (format) {
            case XLSX -> {
                XlsxLayout layout = QueryXlsxLayoutEstimator.estimate(columns, originalRows, operation::check);
                operation.check();
                XlsxWriter.write(temporary.toFile(), columns, sink -> {
                    for (List<Object> row : rows) {
                        operation.check();
                        sink.row(row);
                    }
                }, layout);
            }
            case SQL -> Files.writeString(temporary,
                    InsertSqlGenerator.generate(table, columns, rows), StandardCharsets.UTF_8);
            default -> {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    switch (format) {
                        case CSV -> ResultExporter.writeCsv(writer, columns, rows);
                        case HTML -> ResultExporter.writeHtml(writer, "查询结果", columns, rows);
                        case XML -> ResultExporter.writeXml(writer, columns, rows);
                        default -> throw new IllegalArgumentException("Unsupported format");
                    }
                }
            }
        }
        operation.check();
    }
}
