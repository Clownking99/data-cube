package com.datacube.export;

/**
 * 导出格式：SQL 脚本 / Excel(.xlsx) / pg_dump 备份。
 */
public enum ExportFormat {
    /** DDL + INSERT 组成的 .sql 脚本。 */
    SQL,
    /** 手写最简 .xlsx（仅数据）。 */
    XLSX,
    /** 调用外部 pg_dump 生成备份文件。 */
    PG_DUMP
}
