package com.datacube.provider.oracle;

import com.datacube.spi.TableDdlBuilder;
import com.datacube.spi.model.ColumnDraft;
import com.datacube.spi.model.IndexDraft;
import com.datacube.spi.model.TableDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle 表 DDL 构建器：纯文本生成 CREATE / ALTER 脚本。
 *
 * <p>标识符双引号（保留传入的真实大小写）；改列用 {@code MODIFY (...)}；
 * 删主键用 {@code DROP PRIMARY KEY}（无需约束名）；注释用独立 {@code COMMENT ON}。
 */
public final class OracleTableDdlBuilder implements TableDdlBuilder {

    @Override
    public String createTable(TableDraft d) {
        StringBuilder sb = new StringBuilder();
        String t = qualified(d.schema(), d.name());
        sb.append("CREATE TABLE ").append(t).append(" (\n");

        List<String> lines = new ArrayList<>();
        for (ColumnDraft c : d.columns()) {
            lines.add("    " + columnClause(c));
        }
        if (!d.primaryKey().isEmpty()) {
            String pk = "PRIMARY KEY (" + quoteJoin(d.primaryKey()) + ")";
            if (notBlank(d.primaryKeyName())) {
                pk = "CONSTRAINT " + quote(d.primaryKeyName()) + " " + pk;
            }
            lines.add("    " + pk);
        }
        sb.append(String.join(",\n", lines)).append("\n);");

        if (notBlank(d.tableComment())) {
            sb.append('\n').append("COMMENT ON TABLE ").append(t)
                    .append(" IS ").append(lit(d.tableComment())).append(';');
        }
        for (ColumnDraft c : d.columns()) {
            if (notBlank(c.comment())) {
                sb.append('\n').append("COMMENT ON COLUMN ").append(t).append('.').append(quote(c.name()))
                        .append(" IS ").append(lit(c.comment())).append(';');
            }
        }
        for (IndexDraft ix : d.indexes()) {
            sb.append('\n').append(createIndex(d, ix));
        }
        return sb.toString();
    }

    @Override
    public String alterScript(TableDraft original, TableDraft edited) {
        List<String> stmts = new ArrayList<>();
        String t = qualified(edited.schema(), edited.name());

        Map<String, ColumnDraft> origCols = byName(original.columns());
        Map<String, ColumnDraft> editCols = byName(edited.columns());

        // 增列 / 改列
        for (ColumnDraft c : edited.columns()) {
            ColumnDraft o = origCols.get(c.name());
            if (o == null) {
                stmts.add("ALTER TABLE " + t + " ADD (" + columnClause(c) + ");");
            } else {
                if (!sameType(o.typeText(), c.typeText())) {
                    stmts.add("ALTER TABLE " + t + " MODIFY (" + quote(c.name()) + " " + c.typeText() + ");");
                }
                if (o.nullable() != c.nullable()) {
                    stmts.add("ALTER TABLE " + t + " MODIFY (" + quote(c.name())
                            + (c.nullable() ? " NULL);" : " NOT NULL);"));
                }
                if (!sameDefault(o.defaultValue(), c.defaultValue())) {
                    stmts.add("ALTER TABLE " + t + " MODIFY (" + quote(c.name()) + " DEFAULT "
                            + (notBlank(c.defaultValue()) ? c.defaultValue().trim() : "NULL") + ");");
                }
            }
        }
        // 删列
        for (ColumnDraft o : original.columns()) {
            if (!editCols.containsKey(o.name())) {
                stmts.add("ALTER TABLE " + t + " DROP COLUMN " + quote(o.name()) + ";");
            }
        }

        // 主键变化
        if (!original.primaryKey().equals(edited.primaryKey())) {
            if (!original.primaryKey().isEmpty()) {
                stmts.add("ALTER TABLE " + t + " DROP PRIMARY KEY;");
            }
            if (!edited.primaryKey().isEmpty()) {
                stmts.add("ALTER TABLE " + t + " ADD PRIMARY KEY (" + quoteJoin(edited.primaryKey()) + ");");
            }
        }

        // 索引
        Map<String, IndexDraft> origIx = indexByName(original.indexes());
        Map<String, IndexDraft> editIx = indexByName(edited.indexes());
        for (IndexDraft ix : edited.indexes()) {
            IndexDraft o = origIx.get(ix.name());
            if (o == null) {
                stmts.add(createIndex(edited, ix));
            } else if (o.unique() != ix.unique() || !o.columns().equals(ix.columns())) {
                stmts.add("DROP INDEX " + qualified(edited.schema(), ix.name()) + ";");
                stmts.add(createIndex(edited, ix));
            }
        }
        for (IndexDraft o : original.indexes()) {
            if (!editIx.containsKey(o.name())) {
                stmts.add("DROP INDEX " + qualified(edited.schema(), o.name()) + ";");
            }
        }

        // 表注释
        if (!sameComment(original.tableComment(), edited.tableComment())) {
            stmts.add("COMMENT ON TABLE " + t + " IS "
                    + (notBlank(edited.tableComment()) ? lit(edited.tableComment()) : "''") + ";");
        }
        // 列注释（已存在或新增列）
        for (ColumnDraft c : edited.columns()) {
            ColumnDraft o = origCols.get(c.name());
            String oc = o == null ? null : o.comment();
            if (!sameComment(oc, c.comment())) {
                stmts.add("COMMENT ON COLUMN " + t + "." + quote(c.name()) + " IS "
                        + (notBlank(c.comment()) ? lit(c.comment()) : "''") + ";");
            }
        }

        return String.join("\n", stmts);
    }

    // ---------- 片段 ----------

    private String columnClause(ColumnDraft c) {
        StringBuilder sb = new StringBuilder(quote(c.name())).append(' ').append(c.typeText());
        if (notBlank(c.defaultValue())) sb.append(" DEFAULT ").append(c.defaultValue().trim());
        if (!c.nullable()) sb.append(" NOT NULL");
        return sb.toString();
    }

    private String createIndex(TableDraft d, IndexDraft ix) {
        return "CREATE " + (ix.unique() ? "UNIQUE " : "") + "INDEX " + qualified(d.schema(), ix.name())
                + " ON " + qualified(d.schema(), d.name()) + " (" + quoteJoin(ix.columns()) + ");";
    }

    private static Map<String, ColumnDraft> byName(List<ColumnDraft> cols) {
        Map<String, ColumnDraft> m = new LinkedHashMap<>();
        for (ColumnDraft c : cols) m.put(c.name(), c);
        return m;
    }

    private static Map<String, IndexDraft> indexByName(List<IndexDraft> ixs) {
        Map<String, IndexDraft> m = new LinkedHashMap<>();
        for (IndexDraft i : ixs) m.put(i.name(), i);
        return m;
    }

    private String quoteJoin(List<String> idents) {
        List<String> q = new ArrayList<>();
        for (String s : idents) q.add(quote(s));
        return String.join(", ", q);
    }

    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String qualified(String schema, String name) {
        return (schema == null || schema.isEmpty()) ? quote(name) : quote(schema) + "." + quote(name);
    }

    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean sameType(String a, String b) {
        return norm(a).equalsIgnoreCase(norm(b));
    }

    private static boolean sameDefault(String a, String b) {
        return norm(a).equalsIgnoreCase(norm(b));
    }

    private static boolean sameComment(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }
}
