package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 连接配置持久化：读写 {@code ~/.datacube/connections.json}。
 *
 * <p>手写极简 JSON（无第三方依赖）。损坏条目跳过并记日志，不阻断启动。
 * 存储的 {@code encryptedPassword} 为已加密密文，本类不涉及加解密。
 */
public final class ConnectionStore {

    private static final Logger LOG = Logger.getLogger(ConnectionStore.class.getName());

    private final Path file;

    public ConnectionStore() {
        this(Path.of(System.getProperty("user.home"), ".datacube", "connections.json"));
    }

    public ConnectionStore(Path file) {
        this.file = file;
    }

    /** 读取所有连接配置；文件不存在返回空列表；损坏条目跳过。 */
    public synchronized List<ConnConfig> loadAll() {
        List<ConnConfig> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("读取连接配置失败: " + e.getMessage());
            return out;
        }
        try {
            for (Map<String, String> obj : parseArrayOfObjects(text)) {
                try {
                    out.add(fromMap(obj));
                } catch (RuntimeException bad) {
                    LOG.warning("跳过损坏的连接条目: " + bad.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LOG.warning("解析连接配置失败，忽略全部: " + e.getMessage());
        }
        return out;
    }

    /** 覆盖写入全部连接配置。 */
    public synchronized void saveAll(List<ConnConfig> configs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < configs.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append(toJson(configs.get(i)));
        }
        sb.append("\n]\n");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入连接配置失败: " + e.getMessage(), e);
        }
    }

    // ---------- 序列化 ----------

    private static String toJson(ConnConfig c) {
        StringBuilder sb = new StringBuilder("  {");
        sb.append("\"id\":").append(quote(c.id())).append(',');
        sb.append("\"name\":").append(quote(c.name())).append(',');
        sb.append("\"type\":").append(quote(c.type().name())).append(',');
        sb.append("\"host\":").append(quote(c.host())).append(',');
        sb.append("\"port\":").append(c.port()).append(',');
        sb.append("\"database\":").append(quote(c.database())).append(',');
        sb.append("\"username\":").append(quote(c.username())).append(',');
        sb.append("\"encryptedPassword\":").append(quote(c.encryptedPassword()));
        sb.append('}');
        return sb.toString();
    }

    private static ConnConfig fromMap(Map<String, String> m) {
        String type = m.getOrDefault("type", DbType.POSTGRESQL.name());
        return new ConnConfig(
                require(m, "id"),
                m.getOrDefault("name", ""),
                DbType.valueOf(type),
                m.getOrDefault("host", ""),
                parseInt(m.get("port")),
                m.getOrDefault("database", ""),
                m.getOrDefault("username", ""),
                m.getOrDefault("encryptedPassword", ""),
                Map.of());
    }

    private static String require(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) throw new IllegalArgumentException("缺少必填字段: " + key);
        return v;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    // ---------- 极简解析（仅支持本类产出的平坦对象数组） ----------

    private static List<Map<String, String>> parseArrayOfObjects(String text) {
        List<Map<String, String>> result = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n && text.charAt(i) != '[') i++;
        if (i >= n) return result;
        i++; // skip [
        while (i < n) {
            while (i < n && text.charAt(i) != '{' && text.charAt(i) != ']') i++;
            if (i >= n || text.charAt(i) == ']') break;
            int end = text.indexOf('}', i);
            if (end < 0) break;
            result.add(parseObject(text.substring(i + 1, end)));
            i = end + 1;
        }
        return result;
    }

    private static Map<String, String> parseObject(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        int i = 0, n = body.length();
        while (i < n) {
            int keyStart = body.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = closingQuote(body, keyStart + 1);
            String key = unescape(body.substring(keyStart + 1, keyEnd));
            int colon = body.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            int j = colon + 1;
            while (j < n && Character.isWhitespace(body.charAt(j))) j++;
            String value;
            if (j < n && body.charAt(j) == '"') {
                int valEnd = closingQuote(body, j + 1);
                value = unescape(body.substring(j + 1, valEnd));
                i = valEnd + 1;
            } else {
                int valEnd = j;
                while (valEnd < n && body.charAt(valEnd) != ',') valEnd++;
                value = body.substring(j, valEnd).trim();
                i = valEnd;
            }
            map.put(key, value);
            int comma = body.indexOf(',', i);
            if (comma < 0) break;
            i = comma + 1;
        }
        return map;
    }

    private static int closingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return s.length();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
