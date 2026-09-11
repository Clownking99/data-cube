package com.datacube.fx;

import com.datacube.service.DraftConnectionProbe;
import com.datacube.spi.model.*;
import javafx.scene.control.Label;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionTreeClipboardTest {
    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void copiesOnlyQualifiedNameAndReportsConfirmedSuccessWithoutDatabaseAccess(DbType type) throws Exception {
        FxUiTestSupport.call(() -> {
            DraftConnectionProbe probe = new DraftConnectionProbe();
            List<String> writes = new ArrayList<>();
            ConnConfig connection = config(type);
            probe.manager.register(connection);
            ConnectionTreeClipboard clipboard = new ConnectionTreeClipboard(probe.manager, text -> {
                writes.add(text); return true;
            });
            assertTrue(writes.isEmpty(), "constructing the pane must not touch the clipboard");
            assertFalse(clipboard.getNode().isVisible()); assertFalse(clipboard.getNode().isManaged());
            clipboard.copy(connection, new TableRef(" Sales ", "Order\"Line"));
            assertEquals(List.of("\" Sales \".\"Order\"\"Line\""), writes);
            assertEquals("已复制限定名称；粘贴前请确认目标连接。", clipboard.getNode().getText());
            assertTrue(clipboard.getNode().isVisible()); assertTrue(clipboard.getNode().isManaged());
            assertTrue(clipboard.getNode().getTooltip().getText().contains("其他应用"));
            assertOffline(probe);
            clipboard.clearStatus();
            assertEquals("", clipboard.getNode().getText()); assertFalse(clipboard.getNode().isManaged());
            assertEquals(1, writes.size(), "clearing feedback is not another clipboard write");
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"false", "exception"})
    void failedWriteReplacesPreviousSuccessAndCanBeRetriedWithoutLeakingErrors(String failure) throws Exception {
        FxUiTestSupport.call(() -> {
            DraftConnectionProbe probe = new DraftConnectionProbe();
            ConnConfig connection = config(DbType.POSTGRESQL); probe.manager.register(connection);
            AtomicReference<String> mode = new AtomicReference<>("success");
            List<String> writes = new ArrayList<>();
            ConnectionTreeClipboard clipboard = new ConnectionTreeClipboard(probe.manager, text -> {
                writes.add(text);
                if (mode.get().equals("exception")) throw new IllegalArgumentException("private backend detail");
                return !mode.get().equals("false");
            });
            TableRef table = new TableRef("s", "t");
            clipboard.copy(connection, table);
            mode.set(failure); clipboard.copy(connection, table);
            Label status = clipboard.getNode();
            assertEquals("复制失败：无法写入系统剪贴板，请重试。", status.getText());
            assertTrue(status.isVisible()); assertTrue(status.getStyle().contains("-status-error"));
            mode.set("success"); clipboard.copy(connection, table);
            assertEquals("已复制限定名称；粘贴前请确认目标连接。", status.getText());
            assertFalse(status.getStyle().contains("-status-error"));
            assertEquals(List.of("\"s\".\"t\"", "\"s\".\"t\"", "\"s\".\"t\""), writes);
            assertOffline(probe);
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "null", "blank-id", "changed-type", "changed-target", "redis", "invalid-name", "missing-table"})
    void invalidOrChangedTargetIsRejectedBeforeWriting(String invalid) throws Exception {
        FxUiTestSupport.call(() -> {
            DraftConnectionProbe probe = new DraftConnectionProbe();
            ConnConfig connection = config(invalid.equals("redis") ? DbType.REDIS : DbType.POSTGRESQL);
            if (invalid.equals("null")) connection = null;
            if (invalid.equals("blank-id")) connection = new ConnConfig(" ", "demo", DbType.POSTGRESQL,
                    "example.invalid", 1, "db", "", "", Map.of());
            if (connection != null && !invalid.equals("missing")) probe.manager.register(connection);
            if (invalid.equals("changed-type")) probe.manager.register(config(DbType.ORACLE));
            if (invalid.equals("changed-target")) probe.manager.register(new ConnConfig(connection.id(), connection.name(),
                    connection.type(), "other.invalid", 1, "db", "", "", Map.of()));
            AtomicReference<String> content = new AtomicReference<>("previous clipboard");
            ConnectionTreeClipboard clipboard = new ConnectionTreeClipboard(probe.manager, text -> {
                content.set(text); fail("invalid target must not attempt any clipboard write"); return false;
            });
            TableRef table = invalid.equals("missing-table") ? null
                    : new TableRef("s", invalid.equals("invalid-name") ? "x\ny" : "t");
            clipboard.copy(connection, table);
            assertEquals("previous clipboard", content.get());
            assertTrue(clipboard.getNode().getText().startsWith("无法复制："));
            assertTrue(clipboard.getNode().isVisible()); assertTrue(clipboard.getNode().getStyle().contains("-status-error"));
            assertOffline(probe);
            return null;
        });
    }

    private static ConnConfig config(DbType type) {
        return new ConnConfig("source", "Demo", type, "example.invalid", 1, "synthetic", "user", "not-a-real-password", Map.of());
    }
    static void assertOffline(DraftConnectionProbe probe) {
        assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
        assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
    }
}
