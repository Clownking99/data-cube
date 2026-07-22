package com.datacube.spi;

import com.datacube.spi.model.TableDraft;

/**
 * 表 DDL 构建能力：从结构草稿生成 CREATE / ALTER 脚本。
 *
 * <p>纯文本生成、无状态、不绑定 {@link java.sql.Connection}；方言差异
 * （标识符引用、类型渲染、{@code MODIFY} vs {@code ALTER COLUMN}、{@code COMMENT ON}
 * 语法等）全部封闭在各 provider 实现内，业务层不出现数据库专属语法。
 */
public interface TableDdlBuilder {

    /**
     * 从草稿生成建表脚本：{@code CREATE TABLE}（列与主键内联）
     * + 表/列 {@code COMMENT} + {@code CREATE [UNIQUE] INDEX}。
     */
    String createTable(TableDraft draft);

    /**
     * diff 原始态与编辑态，生成变更脚本（多语句，以分号分隔）：
     * 增列 / 改列（类型、可空、默认）/ 删列 / 主键增删改 / 增删索引 / 表列注释变更。
     *
     * @return 变更脚本；无差异时返回空串
     */
    String alterScript(TableDraft original, TableDraft edited);
}
