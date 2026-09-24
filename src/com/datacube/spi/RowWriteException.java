package com.datacube.spi;

import java.sql.SQLException;

/** A fixed, value-free outcome after row DML. UNKNOWN must never be automatically replayed. */
public final class RowWriteException extends SQLException {
    public enum Outcome { ROLLED_BACK, UNKNOWN, COMMITTED }
    private final Outcome outcome;
    public RowWriteException(Outcome outcome, Throwable cause) {
        super(switch (outcome) {
            case ROLLED_BACK -> "本行写入失败，已回滚；检查类型、约束或权限";
            case UNKNOWN -> "本行提交或回滚结果不确定；核对数据库前不要重试";
            case COMMITTED -> "本行已提交，但连接状态恢复失败；不要重复保存";
        }, cause);
        this.outcome = outcome;
    }
    public Outcome outcome() { return outcome; }
}
