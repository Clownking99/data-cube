package com.datacube.spi;

import com.datacube.spi.model.SequenceDraft;

/**
 * 序列 DDL 构建能力：从序列草稿 diff 生成 {@code ALTER SEQUENCE} 脚本。
 *
 * <p>纯文本生成、无状态、不绑定 {@link java.sql.Connection}；方言差异
 * （标识符引用、{@code NOCACHE} vs {@code CACHE 1}、{@code NOCYCLE} vs {@code NO CYCLE}、
 * {@code RESTART START WITH} vs {@code RESTART WITH}、ORDER 支持与否）全部封闭在各
 * provider 实现内。
 */
public interface SequenceDdlBuilder {

    /**
     * diff 原始态与编辑态，生成变更脚本（可含多条语句，以换行分隔）。
     *
     * @return 变更脚本；无差异时返回空串
     */
    String alterScript(SequenceDraft original, SequenceDraft edited);
}
