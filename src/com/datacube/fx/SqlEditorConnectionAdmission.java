package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;

import java.util.Objects;

/** Atomic connection pin and close admission for one SQL editor. */
final class SqlEditorConnectionAdmission {
    private ConnConfig pinned;
    private boolean closing;

    SqlEditorConnectionAdmission(ConnConfig initiallyPinned) {
        if (initiallyPinned != null) requireRelational(initiallyPinned);
        this.pinned = initiallyPinned;
    }

    synchronized ConnConfig admit(ConnConfig candidate) {
        if (closing) throw new IllegalStateException("SQL 编辑器正在关闭");
        if (pinned == null) pinned = requireRelational(candidate);
        return pinned;
    }

    synchronized ConnConfig pinned() {
        return pinned;
    }

    synchronized ConnConfig requireOpenPinned() {
        if (closing) throw new IllegalStateException("SQL 编辑器正在关闭");
        if (pinned == null) throw new IllegalStateException("SQL 编辑器尚未绑定连接");
        return pinned;
    }

    synchronized void beginClosing() {
        closing = true;
    }

    synchronized void reopen() {
        closing = false;
    }

    synchronized boolean closing() {
        return closing;
    }

    private static ConnConfig requireRelational(ConnConfig connection) {
        Objects.requireNonNull(connection, "connection");
        if (connection.type() == DbType.REDIS) {
            throw new IllegalArgumentException("Redis 连接不能绑定 SQL 编辑器");
        }
        return connection;
    }
}
