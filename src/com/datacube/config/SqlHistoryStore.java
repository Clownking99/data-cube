package com.datacube.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * SQL 历史持久化：读写 {@code ~/.datacube/sql-history.txt}。
 *
 * <p>记录近期在 SQL 编辑器中执行/编辑过的语句，供“找回”。与 {@link ConnectionStore}
 * 同风格无第三方依赖；单条一行，字段用 {@code |} 分隔，SQL/连接名/schema 均以 Base64
 * 存储以规避多行、引号、竖线等转义问题。有界（最多 {@link #MAX_ENTRIES} 条），最新在前；
 * 相同 SQL 文本去重（保留最新一次并置顶）。文件缺失或损坏时回退空列表，不阻断启动。
 */
public final class SqlHistoryStore {

    private static final Logger LOG = Logger.getLogger(SqlHistoryStore.class.getName());

    /** 最多保留的历史条数（超出裁剪最旧）。 */
    public static final int MAX_ENTRIES = 200;

    /** 单条历史：执行/编辑时间戳、连接名（可空）、schema（可空）、SQL 文本（非空）。 */
    public record Entry(long timestamp, String connName, String schema, String sql) {}

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public SqlHistoryStore() {
        this(Path.of(System.getProperty("user.home"), ".datacube", "sql-history.txt"));
    }

    public SqlHistoryStore(Path file) {
        this.file = file;
        load();
    }

    /** 最新在前的历史快照副本（不影响内部列表）。 */
    public synchronized List<Entry> recent() {
        return new ArrayList<>(entries);
    }

    /**
     * 记录一条 SQL（{@code strip} 后为空则忽略）。若已存在相同 SQL 文本则先移除旧条目，
     * 再插入到最前（等效置顶并刷新时间戳）；超出上限裁剪最旧。变更后立即写回（best-effort）。
     */
    public synchronized void record(String connName, String schema, String sql) {
        if (sql == null) return;
        String trimmed = sql.strip();
        if (trimmed.isEmpty()) return;
        entries.removeIf(e -> e.sql().equals(trimmed));
        entries.add(0, new Entry(System.currentTimeMillis(),
                blankToNull(connName), blankToNull(schema), trimmed));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        save();
    }

    // ---------- 持久化 ----------

    private void load() {
        if (!Files.exists(file)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("读取 SQL 历史失败，使用空列表: " + e.getMessage());
            return;
        }
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                String[] parts = line.split("\\|", 4);
                if (parts.length < 4) continue;
                long ts = Long.parseLong(parts[0].trim());
                String conn = decode(parts[1]);
                String schema = decode(parts[2]);
                String sql = decode(parts[3]);
                if (sql == null || sql.isBlank()) continue;
                entries.add(new Entry(ts, blankToNull(conn), blankToNull(schema), sql));
            } catch (RuntimeException bad) {
                LOG.warning("跳过损坏的 SQL 历史条目: " + bad.getMessage());
            }
        }
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.timestamp()).append('|')
              .append(encode(e.connName())).append('|')
              .append(encode(e.schema())).append('|')
              .append(encode(e.sql())).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("写入 SQL 历史失败: " + e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String encode(String s) {
        if (s == null) return "";
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String s) {
        if (s == null || s.isEmpty()) return null;
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
