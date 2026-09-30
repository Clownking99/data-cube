package com.datacube.fx;

import com.datacube.spi.model.EditableColumn;
import com.datacube.spi.model.RowKey;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 可编辑网格的行/单元格状态模型：保留原始类型值与 NULL，跟踪脏标记，
 * 并据主键/可匹配列构建 {@link RowKey} 与被改列集合。
 *
 * <p>纯数据模型，不含任何 JavaFX 依赖，便于 {@link DataGridPane} 复用与推理。
 */
public final class EditableGridModel {

    /** All pending changes stay in memory until an explicit page save. */
    public enum RowState { CLEAN, MODIFIED, NEW, DELETED, SAVED, UNKNOWN }

    /** 单元格：保留加载时的原始值（供 WHERE 定位）与当前编辑态。 */
    public static final class Cell {
        private Object original;   // 加载时原始值（NEW 行为 null）
        private String text;       // 当前文本（非 NULL 时）
        private boolean isNull;    // 当前值是否 SQL NULL
        private boolean touched;   // 是否被用户改过

        private Cell(Object original) {
            this.original = com.datacube.spi.model.ImmutableResultValue.freeze(original);
            this.isNull = (this.original == null);
            this.text = this.original == null ? "" : this.original.toString();
        }

        static Cell of(Object original) {
            return new Cell(original);
        }

        public boolean isNull() {
            return isNull;
        }

        public boolean touched() {
            return touched;
        }

        public String text() {
            return text;
        }

        public Object original() {
            return original;
        }

        void setText(String t) {
            this.text = t == null ? "" : t;
            this.isNull = false;
            this.touched = true;
        }

        void setNull() {
            this.text = "";
            this.isNull = true;
            this.touched = true;
        }

        void clearDirty() {
            this.touched = false;
        }

        /** 提交给数据库的值：SQL NULL 返回 {@code null}。 */
        String committedText() {
            return isNull ? null : text;
        }
    }

    /** 行：单元格集合 + 状态。 */
    public static final class Row {
        private static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong();
        private final long id = NEXT_ID.incrementAndGet();
        private final List<Cell> cells;
        private final boolean created;
        private RowState state;
        private String result = "";

        Row(List<Cell> cells, RowState state) {
            this.cells = cells;
            this.state = state;
            this.created = state == RowState.NEW;
        }

        public Cell cell(int i) {
            return cells.get(i);
        }

        public RowState state() {
            return state;
        }
        public long id() { return id; }
        public boolean created() { return created; }
        public String result() { return result; }
        void result(String value) { result = value; }
        public boolean editable() { return state == RowState.CLEAN || state == RowState.MODIFIED || state == RowState.NEW; }

        void setState(RowState s) {
            this.state = s;
        }

        public boolean dirty() {
            return state == RowState.MODIFIED || state == RowState.NEW || state == RowState.DELETED || state == RowState.UNKNOWN;
        }
    }

    private final List<EditableColumn> columns;
    private final List<String> keyColumns;
    private final boolean hasPrimaryKey;
    private final boolean canLocate;
    private final String readOnlyReason;

    public EditableGridModel(List<EditableColumn> columns) {
        this(columns, false);
    }

    /**
     * @param forceReadOnly 为 {@code true} 时整对象强制只读（如视图）：忽略主键/可匹配列的推断，
     *                      直接禁用新增/删除/单元格编辑。
     */
    public EditableGridModel(List<EditableColumn> columns, boolean forceReadOnly) {
        this.columns = List.copyOf(columns);
        List<String> pk = new ArrayList<>();
        for (EditableColumn c : columns) {
            if (c.primaryKey()) pk.add(c.name());
        }
        this.hasPrimaryKey = !pk.isEmpty();
        if (hasPrimaryKey) {
            this.keyColumns = List.copyOf(pk);
        } else {
            List<String> matchable = new ArrayList<>();
            for (EditableColumn c : columns) {
                if (isKeyMatchable(c.jdbcType())) matchable.add(c.name());
            }
            this.keyColumns = List.copyOf(matchable);
        }
        if (forceReadOnly) {
            this.canLocate = false;
            this.readOnlyReason = "当前数据页为只读，仅支持查看数据，不支持编辑";
        } else {
            this.canLocate = !keyColumns.isEmpty();
            this.readOnlyReason = canLocate ? null
                    : "无法定位行：该表无主键，且所有列均为大字段/二进制/时间戳，不能安全匹配，故只读";
        }
    }

    public List<EditableColumn> columns() {
        return columns;
    }

    public boolean hasPrimaryKey() {
        return hasPrimaryKey;
    }

    /** 是否能定位行（有主键或存在可匹配列）；否则整表只读。 */
    public boolean canLocateRow() {
        return canLocate;
    }

    public String readOnlyReason() {
        return readOnlyReason;
    }

    public boolean columnEditable(int i) {
        return columns.get(i).editable();
    }

    public int jdbcType(int i) {
        return columns.get(i).jdbcType();
    }

    /** 参与 WHERE 定位的列（主键列或全部可匹配列）。 */
    public List<String> keyColumns() {
        return keyColumns;
    }

    /** 从一行原始值构建 CLEAN 行。 */
    public Row toRow(List<Object> values) {
        List<Cell> cells = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            Object v = i < values.size() ? values.get(i) : null;
            cells.add(Cell.of(v));
        }
        return new Row(cells, RowState.CLEAN);
    }

    /** 新增一个 NEW 行（所有单元格未 touched，INSERT 时省略）。 */
    public Row newRow() {
        List<Cell> cells = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            cells.add(Cell.of(null));
        }
        return new Row(cells, RowState.NEW);
    }

    /** 被修改列 → 文本（SQL NULL 为 null 值）；保持列顺序。 */
    public LinkedHashMap<String, String> changedValues(Row row) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            Cell c = row.cell(i);
            boolean same = c.isNull() ? c.original() == null : c.original() != null && c.text().equals(c.original().toString());
            if (c.touched() && (row.created() || !same)) m.put(columns.get(i).name(), c.committedText());
        }
        return m;
    }

    /** 被修改列中是否含定位列（含则更新后需重载以刷新 WHERE 基线）。 */
    public boolean changedAnyKeyColumn(Row row) {
        for (int i = 0; i < columns.size(); i++) {
            if (row.cell(i).touched() && keyColumns.contains(columns.get(i).name())) return true;
        }
        return false;
    }

    /** 行定位符：主键列或全部可匹配列，用原始值。 */
    public RowKey keyOf(Row row) {
        List<Object> vals = new ArrayList<>(keyColumns.size());
        for (String col : keyColumns) {
            int idx = indexOf(col);
            vals.add(idx < 0 ? null : row.cell(idx).original());
        }
        return new RowKey(keyColumns, vals);
    }

    /** Optimistic matching adds comparable original columns, never truncated result wrappers. */
    public RowKey optimisticKeyOf(Row row) {
        List<String> names = new ArrayList<>(keyColumns);
        for (EditableColumn column : columns) {
            if (isKeyMatchable(column.jdbcType()) && !names.contains(column.name())) names.add(column.name());
        }
        List<Object> values = new ArrayList<>();
        for (String name : names) {
            Object value = row.cell(indexOf(name)).original();
            if (value instanceof com.datacube.spi.model.ImmutableResultValue)
                throw new IllegalArgumentException("该行定位值为裁剪或不支持的类型，不能安全保存");
            values.add(value);
        }
        return new RowKey(names, values);
    }

    void reconcile(Row row) {
        if (row.state() == RowState.CLEAN || row.state() == RowState.MODIFIED)
            row.setState(changedValues(row).isEmpty() ? RowState.CLEAN : RowState.MODIFIED);
        row.result("");
    }

    void discard(Row row) {
        for (Cell cell : row.cells) {
            cell.isNull = cell.original == null;
            cell.text = cell.original == null ? "" : cell.original.toString();
            cell.touched = false;
        }
        if (row.state() == RowState.UNKNOWN) {
            row.setState(RowState.SAVED);
            row.result("已清除本地标记；此前结果不确定，请刷新核对");
        } else {
            row.setState(RowState.CLEAN); row.result("");
        }
    }

    /** 提交成功后清理脏标记（原地，仅当未改动定位列时使用）。 */
    public void markClean(Row row) {
        for (int i = 0; i < columns.size(); i++) {
            row.cell(i).clearDirty();
        }
        row.setState(RowState.CLEAN);
    }

    private int indexOf(String col) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(col)) return i;
        }
        return -1;
    }

    /** 是否为字符类型（清空文本视作空串而非 NULL）。 */
    public static boolean isCharType(int jdbcType) {
        switch (jdbcType) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return true;
            default:
                return false;
        }
    }

    /** 显示值被裁剪或不可靠比较的类型不参与无主键匹配。 */
    private static boolean isKeyMatchable(int jdbcType) {
        switch (jdbcType) {
            case Types.BLOB:
            case Types.CLOB:
            case Types.NCLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.ROWID:
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return false;
            case Types.CHAR: case Types.VARCHAR: case Types.LONGVARCHAR:
            case Types.NCHAR: case Types.NVARCHAR: case Types.LONGNVARCHAR:
            case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT:
            case Types.NUMERIC: case Types.DECIMAL: case Types.FLOAT: case Types.REAL: case Types.DOUBLE:
            case Types.BOOLEAN: case Types.BIT: case Types.DATE: case Types.TIME:
                return true;
            default: return false;
        }
    }
}
