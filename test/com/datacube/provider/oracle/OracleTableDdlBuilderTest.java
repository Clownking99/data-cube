package com.datacube.provider.oracle;

import com.datacube.spi.model.ColumnDraft;
import com.datacube.spi.model.IndexDraft;
import com.datacube.spi.model.TableDraft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OracleTableDdlBuilder} 纯文本单测：Oracle 方言的 CREATE / ALTER（MODIFY、
 * ADD (...)、DROP PRIMARY KEY、DEFAULT 在 NOT NULL 之前），不依赖数据库。
 */
class OracleTableDdlBuilderTest {

    private final OracleTableDdlBuilder b = new OracleTableDdlBuilder();

    private static ColumnDraft col(String name, String type, boolean nullable, String def, String comment) {
        return new ColumnDraft(name, type, nullable, def, comment);
    }

    private static TableDraft table(List<ColumnDraft> cols, List<String> pk, String pkName, List<IndexDraft> ix) {
        return new TableDraft("HR", "T", null, cols, pk, pkName, ix);
    }

    @Test
    void createTableInlinesColumnsPkCommentsIndex() {
        TableDraft d = new TableDraft("HR", "T", "表注释",
                List.of(col("ID", "NUMBER", false, null, "主键列"),
                        col("NAME", "VARCHAR2(50)", true, "'x'", null)),
                List.of("ID"), null,
                List.of(new IndexDraft("IDX_NAME", true, List.of("NAME"))));
        String ddl = b.createTable(d);
        assertTrue(ddl.contains("CREATE TABLE \"HR\".\"T\""), ddl);
        assertTrue(ddl.contains("\"ID\" NUMBER NOT NULL"), ddl);
        assertTrue(ddl.contains("\"NAME\" VARCHAR2(50) DEFAULT 'x'"), ddl);
        assertTrue(ddl.contains("PRIMARY KEY (\"ID\")"), ddl);
        assertTrue(ddl.contains("COMMENT ON TABLE \"HR\".\"T\" IS '表注释'"), ddl);
        assertTrue(ddl.contains("COMMENT ON COLUMN \"HR\".\"T\".\"ID\" IS '主键列'"), ddl);
        assertTrue(ddl.contains("CREATE UNIQUE INDEX \"HR\".\"IDX_NAME\" ON \"HR\".\"T\" (\"NAME\")"), ddl);
    }

    @Test
    void addColumnWrapsInParens() {
        TableDraft o = table(List.of(col("ID", "NUMBER", false, null, null)), List.of(), null, List.of());
        TableDraft e = table(List.of(col("ID", "NUMBER", false, null, null),
                col("AGE", "NUMBER", true, null, null)), List.of(), null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("ALTER TABLE \"HR\".\"T\" ADD (\"AGE\" NUMBER)"), s);
    }

    @Test
    void dropColumn() {
        TableDraft o = table(List.of(col("ID", "NUMBER", false, null, null),
                col("AGE", "NUMBER", true, null, null)), List.of(), null, List.of());
        TableDraft e = table(List.of(col("ID", "NUMBER", false, null, null)), List.of(), null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("DROP COLUMN \"AGE\""), s);
    }

    @Test
    void changeTypeNullableDefaultUsesModify() {
        TableDraft o = table(List.of(col("C", "NUMBER", true, null, null)), List.of(), null, List.of());
        TableDraft e = table(List.of(col("C", "VARCHAR2(10)", false, "'a'", null)), List.of(), null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("MODIFY (\"C\" VARCHAR2(10))"), s);
        assertTrue(s.contains("MODIFY (\"C\" NOT NULL)"), s);
        assertTrue(s.contains("MODIFY (\"C\" DEFAULT 'a')"), s);
    }

    @Test
    void primaryKeyChangeDropsWithoutName() {
        TableDraft o = table(List.of(col("ID", "NUMBER", false, null, null)), List.of("ID"), null, List.of());
        TableDraft e = table(List.of(col("ID", "NUMBER", false, null, null),
                col("K", "NUMBER", false, null, null)), List.of("ID", "K"), null, List.of());
        String s = b.alterScript(o, e);
        assertTrue(s.contains("DROP PRIMARY KEY"), s);
        assertTrue(s.contains("ADD PRIMARY KEY (\"ID\", \"K\")"), s);
    }

    @Test
    void indexAddDrop() {
        TableDraft o = table(List.of(col("C", "NUMBER", true, null, null)), List.of(), null,
                List.of(new IndexDraft("OLD_IX", false, List.of("C"))));
        TableDraft e = table(List.of(col("C", "NUMBER", true, null, null)), List.of(), null,
                List.of(new IndexDraft("NEW_IX", false, List.of("C"))));
        String s = b.alterScript(o, e);
        assertTrue(s.contains("CREATE INDEX \"HR\".\"NEW_IX\""), s);
        assertTrue(s.contains("DROP INDEX \"HR\".\"OLD_IX\""), s);
    }

    @Test
    void noDiffReturnsEmpty() {
        TableDraft o = table(List.of(col("ID", "NUMBER", false, null, "c")), List.of("ID"), "PK_T",
                List.of(new IndexDraft("IX", true, List.of("ID"))));
        assertTrue(b.alterScript(o, o).isEmpty(), "无差异应返回空串");
    }
}
