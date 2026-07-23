package com.datacube.provider.postgres;

import com.datacube.spi.model.SequenceDraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PgSequenceDdlBuilder} 纯文本单测：PG 方言的 ALTER SEQUENCE
 * （NO CYCLE、RESTART WITH、CACHE 下限 1、忽略 ORDER），不依赖数据库。
 */
class PgSequenceDdlBuilderTest {

    private final PgSequenceDdlBuilder b = new PgSequenceDdlBuilder();

    private static SequenceDraft base() {
        return new SequenceDraft("public", "seq", "1", "9223372036854775807", "1", "1", 1, false, false);
    }

    @Test
    void incrementAndMinMergedIntoOneAlter() {
        SequenceDraft e = new SequenceDraft("public", "seq", "5", "9223372036854775807", "2", "1", 1, false, false);
        String s = b.alterScript(base(), e);
        assertTrue(s.startsWith("ALTER SEQUENCE \"public\".\"seq\" "), s);
        assertTrue(s.contains("INCREMENT BY 2"), s);
        assertTrue(s.contains("MINVALUE 5"), s);
    }

    @Test
    void cycleOffUsesNoCycle() {
        SequenceDraft o = new SequenceDraft("public", "seq", "1", "100", "1", "1", 1, true, false);
        SequenceDraft e = new SequenceDraft("public", "seq", "1", "100", "1", "1", 1, false, false);
        String s = b.alterScript(o, e);
        assertTrue(s.contains("NO CYCLE"), s);
    }

    @Test
    void nextValueUsesRestartWith() {
        SequenceDraft e = new SequenceDraft("public", "seq", "1", "9223372036854775807", "1", "641", 1, false, false);
        String s = b.alterScript(base(), e);
        assertTrue(s.contains("RESTART WITH 641"), s);
    }

    @Test
    void cacheBelowOneClampedToOne() {
        SequenceDraft e = new SequenceDraft("public", "seq", "1", "9223372036854775807", "1", "1", 0, false, false);
        String s = b.alterScript(base(), e);
        assertTrue(s.contains("CACHE 1"), s);
    }

    @Test
    void orderChangeIgnored() {
        SequenceDraft e = new SequenceDraft("public", "seq", "1", "9223372036854775807", "1", "1", 1, false, true);
        assertTrue(b.alterScript(base(), e).isEmpty(), "PG 忽略 ORDER，仅 order 变化应返回空串");
    }

    @Test
    void noDiffReturnsEmpty() {
        assertTrue(b.alterScript(base(), base()).isEmpty(), "无差异应返回空串");
        assertFalse(b.alterScript(base(), base()).contains("ALTER"));
    }
}
