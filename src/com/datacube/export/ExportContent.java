package com.datacube.export;

/**
 * 导出内容：仅结构 / 仅数据 / 结构+数据。
 */
public enum ExportContent {
    /** 仅结构（DDL）。 */
    STRUCTURE,
    /** 仅数据（INSERT / 行）。 */
    DATA,
    /** 结构 + 数据。 */
    BOTH;

    public boolean includesStructure() {
        return this == STRUCTURE || this == BOTH;
    }

    public boolean includesData() {
        return this == DATA || this == BOTH;
    }
}
