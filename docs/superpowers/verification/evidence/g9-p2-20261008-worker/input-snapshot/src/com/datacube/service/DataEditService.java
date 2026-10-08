package com.datacube.service;

import com.datacube.spi.DataEditor;
import com.datacube.spi.RowEditSql;
import com.datacube.spi.RowWriteException;
import com.datacube.spi.model.EditableColumn;
import com.datacube.spi.model.RowKey;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 表数据编辑服务：编排 {@link DataEditor} 提供行级 INSERT/UPDATE/DELETE。
 *
 * <p>与只读的 {@link DataBrowseService} 对称。所有方法显式接收 {@code connId}，
 * 零 JavaFX 依赖。
 */
public final class DataEditService {

    private final ConnectionManager connections;

    public DataEditService(ConnectionManager connections) {
        this.connections = connections;
    }

    private DataEditor editor(String connId) throws SQLException {
        Connection c = connections.acquire(connId);
        return connections.provider(connId).dataEditor(c);
    }

    public List<EditableColumn> columns(String connId, TableRef t) throws SQLException {
        return editor(connId).columns(t);
    }

    public int insert(String connId, TableRef t, LinkedHashMap<String, String> values) throws SQLException {
        return prepareInsert(target(connId), t, values).execute(null);
    }

    public int update(String connId, TableRef t, LinkedHashMap<String, String> newValues, RowKey key) throws SQLException {
        return prepareUpdate(target(connId), t, newValues, key).execute(null);
    }

    public int delete(String connId, TableRef t, RowKey key) throws SQLException {
        return prepareDelete(target(connId), t, key).execute(null);
    }

    public WriteTarget target(String connId) { return connections.writeTarget(connId); }

    public enum ChangeKind { INSERT, UPDATE, DELETE }
    public enum SaveStatus { COMMITTED, COMMITTED_WARNING, FAILED, CONFLICT, UNKNOWN, NOT_EXECUTED }
    public record Change(long rowId, ChangeKind kind, Map<String, String> values, RowKey key) {
        public Change {
            Objects.requireNonNull(kind);
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            if (kind != ChangeKind.DELETE && values.isEmpty()) throw new IllegalArgumentException("新增或修改行至少填写一个列值");
            if (kind != ChangeKind.INSERT) {
                RowEditSql.where(key, value -> value); // shape validation without provider or connection
                key = freezeKey(key);
            } else key = null;
        }
        @Override public RowKey key() { return key == null ? null : freezeKey(key); }
        @Override public String toString() { return "RowChange[redacted]"; }
    }
    public record RowResult(long rowId, SaveStatus status, String message) {
        public boolean committed() { return status == SaveStatus.COMMITTED || status == SaveStatus.COMMITTED_WARNING; }
    }
    public record SaveResult(List<RowResult> rows) {
        public SaveResult { rows = List.copyOf(rows); }
        public long committedCount() { return rows.stream().filter(RowResult::committed).count(); }
    }

    /** One immutable batch confirmation, with one separately committed transaction per row. */
    public WriteOperation<SaveResult> prepareSave(WriteTarget target, TableRef table,
                                                 List<Change> changes, BooleanSupplier cancelled) {
        if (!target.belongsTo(connections)) throw new IllegalArgumentException("写入目标不属于当前连接管理器");
        List<Change> frozen = List.copyOf(changes);
        if (frozen.isEmpty() || frozen.size() > 1000
                || frozen.stream().map(Change::rowId).distinct().count() != frozen.size())
            throw new IllegalArgumentException("本次保存需要 1–1000 个不同的行修改");
        Objects.requireNonNull(cancelled);
        String kinds = java.util.Arrays.stream(ChangeKind.values()).map(kind -> switch (kind) {
            case INSERT -> "新增 "; case UPDATE -> "修改 "; case DELETE -> "删除 ";
        } + frozen.stream().filter(change -> change.kind() == kind).count()).collect(java.util.stream.Collectors.joining("，"));
        return new WriteOperation<>(target, "保存页面修改到数据库", table.qualified() + "，" + frozen.size() + " 行（" + kinds + "）"
                + "；按原始加载/新增顺序逐行独立提交，失败即停止，已提交行不会整体回滚。", revalidate -> {
            List<RowResult> results = new ArrayList<>();
            boolean stopped = false;
            for (Change change : frozen) {
                if (stopped || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    stopped = true;
                    results.add(new RowResult(change.rowId(), SaveStatus.NOT_EXECUTED, "未执行；可检查后重新保存"));
                    continue;
                }
                boolean[] reachedEditor = {false}, committed = {false};
                try {
                    revalidate.run();
                    WriteOperation<Integer> row = connections.prepareWrite(target, "保存行", table.qualified(), (c, p) -> {
                        if (cancelled.getAsBoolean()) throw new IllegalStateException("剩余保存已取消");
                        DataEditor editor = p.dataEditor(c);
                        reachedEditor[0] = true;
                        int count = switch (change.kind()) {
                            case INSERT -> editor.insert(table, new LinkedHashMap<>(change.values()));
                            case UPDATE -> editor.update(table, new LinkedHashMap<>(change.values()), change.key());
                            case DELETE -> editor.delete(table, change.key());
                        };
                        if (count != 1) throw new RowWriteException(RowWriteException.Outcome.UNKNOWN, null);
                        committed[0] = true;
                        return count;
                    });
                    row.execute(row.confirm()); // authorized only inside this already-admitted immutable batch
                    results.add(new RowResult(change.rowId(), SaveStatus.COMMITTED, "已提交；刷新后可继续编辑"));
                } catch (Exception failure) {
                    SaveStatus status;
                    String message;
                    if (committed[0] || failure instanceof RowWriteException e && e.outcome() == RowWriteException.Outcome.COMMITTED) {
                        status = SaveStatus.COMMITTED_WARNING; message = "已提交，但连接清理失败；不要重复保存";
                    } else if (failure instanceof com.datacube.provider.jdbc.RowGuardException guard) {
                        status = SaveStatus.CONFLICT;
                        message = guard.affectedRows() == 0 ? "旧值冲突或行已删除：影响 0 行，已回滚"
                                : "行定位不唯一：影响多行，已回滚";
                    } else if (failure instanceof RowWriteException e && e.outcome() == RowWriteException.Outcome.ROLLED_BACK) {
                        status = SaveStatus.FAILED; message = "本行写入失败，已回滚；检查类型、约束或权限";
                    } else if (reachedEditor[0]) {
                        status = SaveStatus.UNKNOWN; message = "结果不确定，已停止后续写入；核对数据库前不要重试";
                    } else {
                        status = SaveStatus.NOT_EXECUTED; message = "未执行：已取消、连接失败或安全配置变化";
                    }
                    results.add(new RowResult(change.rowId(), status, message));
                    stopped = true;
                }
            }
            return new SaveResult(results);
        });
    }

    /** Explicit, bounded UI preview. Never writes SQL or parameter values to logs or disk. */
    public String preview(WriteTarget target, TableRef table, List<Change> changes) {
        if (!target.belongsTo(connections)) throw new IllegalArgumentException("写入目标不属于当前连接管理器");
        var quote = (java.util.function.UnaryOperator<String>) connections.provider(target.config()).dialect()::quoteIdentifier;
        String qualified = RowEditSql.qualify(table, quote);
        StringBuilder text = new StringBuilder(target.description()).append("\n按原始加载/新增顺序逐行独立提交；列排序不改变执行顺序。失败停止，已提交行不会整体回滚。\n")
                .append("新值按执行时列类型转换；NULL 与空串分别显示。Oracle 字符空串按数据库 NULL 语义处理。\n")
                .append("旧值检查覆盖可比较列；LOB、二进制、时间戳及不支持比较的类型不保证冲突检测。\n\n");
        int index = 0;
        previewRows: for (Change change : List.copyOf(changes)) {
            if (trimPreview(text)) break;
            if (++index > 1000) { text.append("\n预览达到 1000 行上限，后续条目省略；请缩小本次修改范围。\n"); break; }
            var columns = new ArrayList<>(change.values().keySet());
            String sql = switch (change.kind()) {
                case INSERT -> RowEditSql.insert(qualified, columns, quote);
                case UPDATE -> RowEditSql.update(qualified, columns, change.key(), quote);
                case DELETE -> RowEditSql.delete(qualified, change.key(), quote);
            };
            text.append("#").append(index).append(" ").append(change.kind()).append("\n").append(sql).append(";\n");
            if (trimPreview(text)) break;
            int parameter = 0;
            if (change.kind() != ChangeKind.DELETE) for (var value : change.values().entrySet()) {
                text.append("  ?").append(++parameter).append(" 新值 ").append(value.getKey()).append(" = ").append(display(value.getValue())).append('\n');
                if (trimPreview(text)) break previewRows;
            }
            if (change.key() != null) {
                RowKey key = change.key();
                for (int i = 0; i < key.columns().size(); i++) {
                    Object value = key.values().get(i);
                    text.append(value == null ? "  IS NULL" : "  ?" + (++parameter)).append(" 旧值 ")
                            .append(key.columns().get(i)).append(" = ").append(display(value)).append('\n');
                    if (trimPreview(text)) break previewRows;
                }
            }
            text.append('\n');
        }
        trimPreview(text);
        return text.toString();
    }
    private static boolean trimPreview(StringBuilder text) {
        if (text.length() < 256_000) return false;
        String omitted = "\n预览达到字符上限，后续内容省略；请缩小本次修改范围。\n";
        text.setLength(256_000 - omitted.length()); text.append(omitted);
        return true;
    }
    private static String display(Object value) {
        if (value == null) return "NULL";
        String text = value.toString();
        String shown = text.length() > 256 ? text.substring(0, 256) + "…（共 " + text.length() + " 字符，已省略）" : text;
        return "\"" + shown.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\"";
    }

    public WriteOperation<Integer> prepareInsert(WriteTarget target, TableRef table,
                                                LinkedHashMap<String, String> values) {
        var copy = new LinkedHashMap<>(values);
        return connections.prepareWrite(target, "新增行", table.qualified() + "，1 行",
                (c, p) -> p.dataEditor(c).insert(table, new LinkedHashMap<>(copy)));
    }

    public WriteOperation<Integer> prepareUpdate(WriteTarget target, TableRef table,
                                                LinkedHashMap<String, String> values, RowKey key) {
        var copy = new LinkedHashMap<>(values);
        RowKey frozen = freezeKey(key);
        return connections.prepareWrite(target, "修改行", table.qualified() + "，1 行",
                (c, p) -> p.dataEditor(c).update(table, new LinkedHashMap<>(copy), frozen));
    }

    public WriteOperation<Integer> prepareDelete(WriteTarget target, TableRef table, RowKey key) {
        RowKey frozen = freezeKey(key);
        return connections.prepareWrite(target, "删除行", table.qualified() + "，1 行",
                (c, p) -> p.dataEditor(c).delete(table, frozen));
    }

    private static RowKey freezeKey(RowKey key) {
        return new RowKey(key.columns(), key.values().stream().map(DataEditService::freezeValue).toList());
    }

    static Object freezeValue(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            java.sql.Timestamp copy = new java.sql.Timestamp(timestamp.getTime());
            copy.setNanos(timestamp.getNanos());
            return copy;
        }
        if (value instanceof java.sql.Date date) return new java.sql.Date(date.getTime());
        if (value instanceof java.sql.Time time) return new java.sql.Time(time.getTime());
        if (value instanceof byte[] bytes) return bytes.clone();
        if (value == null || value instanceof String || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof java.math.BigDecimal
                || value instanceof java.math.BigInteger
                || value instanceof Boolean || value instanceof java.time.Instant
                || value instanceof java.time.LocalDate || value instanceof java.time.LocalTime
                || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetTime
                || value instanceof java.time.OffsetDateTime || value instanceof java.time.ZonedDateTime
                || value instanceof java.util.UUID) return value;
        throw new IllegalArgumentException("不支持的可变写入参数类型");
    }
}
