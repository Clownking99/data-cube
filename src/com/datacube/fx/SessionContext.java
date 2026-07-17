package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * 会话上下文（fx 层）：持有当前活动连接，供各面板绑定。
 *
 * <p>仅存在于 UI 层——{@code service} 层方法一律显式接收 {@code connId}，
 * 不感知本对象，避免分层泄漏。
 */
public final class SessionContext {

    private final ObjectProperty<ConnConfig> activeConnection = new SimpleObjectProperty<>();

    public ObjectProperty<ConnConfig> activeConnectionProperty() {
        return activeConnection;
    }

    public ConnConfig getActiveConnection() {
        return activeConnection.get();
    }

    public void setActiveConnection(ConnConfig cfg) {
        activeConnection.set(cfg);
    }

    /** 当前活动连接 id，无连接时返回 {@code null}。 */
    public String activeConnId() {
        ConnConfig c = activeConnection.get();
        return c == null ? null : c.id();
    }
}
