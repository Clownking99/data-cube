package com.datacube.provider.jdbc;

import java.sql.SQLException;

/**
 * 行护栏异常：UPDATE/DELETE 影响行数不为 1 时抛出（已回滚）。
 *
 * <p>{@code n==0} 表示旧值未匹配到任何行（可能已被他人修改/删除）；
 * {@code n>1} 表示无主键全列匹配撞到多行。两种情况都已 rollback，
 * 保证不误伤数据。
 */
public final class RowGuardException extends SQLException {

    private final int affectedRows;

    public RowGuardException(int affectedRows) {
        super("该行可能已被他人修改/删除，或匹配到多行（" + affectedRows + "），已回滚");
        this.affectedRows = affectedRows;
    }

    public int affectedRows() {
        return affectedRows;
    }
}
