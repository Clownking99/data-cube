package com.datacube.provider.postgres;

import com.datacube.spi.model.ColumnDraft;
import com.datacube.spi.model.IndexDraft;
import com.datacube.spi.model.TableDraft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PgTableDdlBuilder} 纯文本单测：createTable / alterScript 各场景的结构化断言，
 * 不依赖数据库。断言用 contains（非整串精确），容忍空白/顺序细节。
 */
class PgTableDdlBuilderTest {

    private final PgTableDdlBuilder b = new PgTableDdlBuilder();

    private static ColumnDraft col(String name, String type, boolean nullable, String def, String comment) {
        return new ColumnDraft(name, type, nullable, def, comment);
    }

    private static TableDraft table(List<ColumnDraft> cols, List<String> pk, String pkName, List<IndexDraft> ix) {
        return new TableDraft("public", "t", null, cols, pk, pkName, ix);
    }

    @Test
    void createTableInlinesColumnsPkCommentsIndex() {
        TableDraft d = new TableDraft("public", "t", "表注释",
                List.of(col("id", "integer", false, null, "主键列"),
                        col("name", "varchar(50)", true, "'x'", null)),
                List.of("id"), null,
                List.of(new IndexDraft("idx_name", true, List.of("name"))));
        String ddl = b.createTable(d);
        assertTrue(ddl.contains("CREATE TABLE \"public\".\"t\""), ddl);
        assertTrue(ddl.contains("\"id\" integer NOT NULL"), ddl);
        assertTrue(ddl.contains("\"name\" varchar(50) DEFAULT 'x'"), ddl);
        assertTrue(ddl.contains("PRIMARY KEY (\"id\")"), ddl);
        assertTrue(ddl.contains("COMMENT ON TABLE \"public\".\"t\" IS '表注释'"), ddl);
        assertTrue(ddl.contains("COMMENT ON COLUMN \"public\".\"t\".\"id\" IS '主键列'"), ddl);
        assertTrue(ddl.contains("CREATE UNIQUE INDEX \"idx_name\" ON \"public\".\"t\" (\"name\")"), ddl);
    }

    @Test
    void addColumn() {
        TableDraft o = table(List.of(col("id", "integer", false, null, null)), List.of(), null, List.of());
        TableDraft e = table(List.of(col("id", "integer", false, null, null),
                col("age", "integer", true, null, null)), List.of(), null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("ALTER TABLE \"public\".\"t\" ADD COLUMN \"age\" integer"), s);
    }

    @Test
    void dropColumn() {
        TableDraft o = table(List.of(col("id", "integer", false, null, null),
                col("age", "integer", true, null, null)), List.of(), null, List.of());
        TableDraft e = table(List.of(col("id", "integer", false, null, null)), List.of(), null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("DROP COLUMN \"age\""), s);
    }

    @Test
    void changeTypeNullableDefault() {
        TableDraft o = table(List.of(col("c", "integer", true, null, null)), List.of(), null, List.of());
        TableDraft e = table(List.of(col("c", "bigint", false, "0", null)), List.of(), null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("ALTER COLUMN \"c\" TYPE bigint"), s);
        assertTrue(s.contains("ALTER COLUMN \"c\" SET NOT NULL"), s);
        assertTrue(s.contains("ALTER COLUMN \"c\" SET DEFAULT 0"), s);
    }

    @Test
    void primaryKeyChange() {
        TableDraft o = table(List.of(col("id", "integer", false, null, null)),
                List.of("id"), "t_pkey", List.of());
        TableDraft e = table(List.of(col("id", "integer", false, null, null),
                col("k", "integer", false, null, null)), List.of("id", "k"), "t_pkey", List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("DROP CONSTRAINT \"t_pkey\""), s);
        assertTrue(s.contains("ADD PRIMARY KEY (\"id\", \"k\")"), s);
    }

    @Test
    void indexAddDrop() {
        TableDraft o = table(List.of(col("c", "integer", true, null, null)), List.of(), null,
                List.of(new IndexDraft("old_ix", false, List.of("c"))));
        TableDraft e = table(List.of(col("c", "integer", true, null, null)), List.of(), null,
                List.of(new IndexDraft("new_ix", false, List.of("c"))));
        String s = b.alterScript(o, e);
        assertTrue(s.contains("CREATE INDEX \"new_ix\""), s);
        assertTrue(s.contains("DROP INDEX \"public\".\"old_ix\""), s);
    }

    @Test
    void commentChange() {
        TableDraft o = table(List.of(col("c", "integer", true, null, "旧")), null, null, List.of());
        TableDraft e = new TableDraft("public", "t", "新表注释",
                List.of(col("c", "integer", true, null, "新")), null, null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("COMMENT ON TABLE \"public\".\"t\" IS '新表注释'"), s);
        assertTrue(s.contains("COMMENT ON COLUMN \"public\".\"t\".\"c\" IS '新'"), s);
    }

    @Test
    void noDiffReturnsEmpty() {
        TableDraft o = table(List.of(col("id", "integer", false, null, "c")), List.of("id"), "t_pkey",
                List.of(new IndexDraft("ix", true, List.of("id"))));
        assertTrue(b.alterScript(o, o).isEmpty(), "无差异应返回空串");
    }
}
