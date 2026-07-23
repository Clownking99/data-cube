package com.datacube.provider.oracle;

import com.datacube.spi.model.SequenceDraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OracleSequenceDdlBuilder} 纯文本单测：Oracle 方言的 ALTER SEQUENCE
 * （单/多字段合并、NOCACHE/CYCLE/ORDER、RESTART START WITH），不依赖数据库。
 */
class OracleSequenceDdlBuilderTest {

    private final OracleSequenceDdlBuilder b = new OracleSequenceDdlBuilder();

    private static SequenceDraft base() {
        return new SequenceDraft("HR", "SEQ", "1", "1000000", "1", "1", 20, false, false);
    }

    @Test
    void incrementChangeSingleClause() {
        SequenceDraft e = new SequenceDraft("HR", "SEQ", "1", "1000000", "5", "1", 20, false, false);
        String s = b.alterScript(base(), e);
        assertTrue(s.contains("ALTER SEQUENCE \"HR\".\"SEQ\" INCREMENT BY 5;"), s);
    }

    @Test
    void multiFieldMergedIntoOneAlter() {
        SequenceDraft e = new SequenceDraft("HR", "SEQ", "2", "999", "1", "1", 0, true, true);
        String s = b.alterScript(base(), e);
        assertTrue(s.startsWith("ALTER SEQUENCE \"HR\".\"SEQ\" "), s);
        assertTrue(s.contains("MINVALUE 2"), s);
        assertTrue(s.contains("MAXVALUE 999"), s);
        assertTrue(s.contains("NOCACHE"), s);
        assertTrue(s.contains("CYCLE"), s);
        assertTrue(s.contains("ORDER"), s);
        assertFalse(s.contains("RESTART"), s);
    }

    @Test
    void nextValueUsesRestartStartWith() {
        SequenceDraft e = new SequenceDraft("HR", "SEQ", "1", "1000000", "1", "641", 20, false, false);
        String s = b.alterScript(base(), e);
        assertTrue(s.contains("ALTER SEQUENCE \"HR\".\"SEQ\" RESTART START WITH 641;"), s);
    }

    @Test
    void noDiffReturnsEmpty() {
        assertTrue(b.alterScript(base(), base()).isEmpty(), "无差异应返回空串");
    }
}
