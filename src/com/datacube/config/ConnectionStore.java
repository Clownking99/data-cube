package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final PathMover mover;
    private final SnapshotWriter writer;

    public ConnectionStore() {
        this(Path.of(System.getProperty("user.home"), ".datacube", "connections.json"));
    }

    public ConnectionStore(Path file) {
        this(file,
                (source, target, options) -> Files.move(source, target, options),
                (target, json) -> Files.writeString(target, json, StandardCharsets.UTF_8));
    }

    ConnectionStore(Path file, PathMover mover) {
        this(file, mover,
                (target, json) -> Files.writeString(target, json, StandardCharsets.UTF_8));
    }

    ConnectionStore(Path file, PathMover mover, SnapshotWriter writer) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.mover = Objects.requireNonNull(mover, "mover");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /** 读取所有连接配置；主文件损坏时尝试备份，但不覆盖损坏主文件。 */
    public synchronized List<ConnConfig> loadAll() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            return load(file);
        } catch (IOException | RuntimeException primaryFailure) {
            LOG.warning("读取主连接配置失败，尝试备份: " + primaryFailure.getMessage());
        }
        Path backup = backupFile();
        if (!Files.exists(backup)) return new ArrayList<>();
        try {
            return load(backup);
        } catch (IOException | RuntimeException backupFailure) {
            LOG.warning("读取连接配置备份失败: " + backupFailure.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<ConnConfig> load(Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        List<ConnConfig> out = new ArrayList<>();
        for (Map<String, String> obj : parseArrayOfObjects(text)) {
            try {
                out.add(fromMap(obj));
            } catch (RuntimeException badEntry) {
                LOG.warning("跳过损坏的连接条目: " + badEntry.getMessage());
            }
        }
        return out;
    }

    /** 在同目录写入临时快照并原子替换，替换前复制有效旧文件为 {@code .bak}。 */
    public synchronized void saveAll(List<ConnConfig> configs) {
        String json = serialize(List.copyOf(Objects.requireNonNull(configs, "configs")));
        Path parent = file.getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, file.getFileName() + ".", ".tmp");
            writer.write(temp, json);
            if (Files.exists(file) && isStructurallyValid(file)) {
                Files.copy(file, backupFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            replace(temp);
            temp = null;
        } catch (IOException e) {
            throw new IllegalStateException("写入连接配置失败: " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanup) {
                    LOG.fine("清理连接配置临时文件失败: " + cleanup.getMessage());
                }
            }
        }
    }

    private static String serialize(List<ConnConfig> configs) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < configs.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append(toJson(configs.get(i)));
        }
        return sb.append("\n]\n").toString();
    }

    private void replace(Path temp) throws IOException {
        try {
            mover.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            mover.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private static boolean isStructurallyValid(Path candidate) {
        try {
            parseArrayOfObjects(Files.readString(candidate, StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException invalid) {
            LOG.warning("旧连接配置损坏，不覆盖现有备份: " + invalid.getMessage());
            return false;
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
        if (c.type() != DbType.REDIS) {
            ConnectionSafetyOptions safety = ConnectionSafetyOptions.from(c);
            sb.append(',').append("\"environment\":").append(quote(safety.environment().name()));
            sb.append(',').append("\"readOnly\":").append(safety.readOnly());
            sb.append(',').append("\"queryTimeoutSeconds\":").append(safety.queryTimeoutSeconds());
        }
        sb.append('}');
        return sb.toString();
    }

    private static ConnConfig fromMap(Map<String, String> m) {
        DbType dbType = DbType.valueOf(m.getOrDefault("type", DbType.POSTGRESQL.name()));
        Map<String, String> props;
        if (dbType == DbType.REDIS) {
            props = Map.of();
        } else {
            warnInvalidSafetyValues(m);
            props = Map.of(
                    "environment", ConnectionEnvironment.parse(m.get("environment")).name(),
                    "readOnly", Boolean.toString(Boolean.parseBoolean(m.getOrDefault("readOnly", "false"))),
                    "queryTimeoutSeconds", Integer.toString(new ConnectionSafetyOptions(
                            ConnectionEnvironment.parse(m.get("environment")),
                            Boolean.parseBoolean(m.getOrDefault("readOnly", "false")),
                            parseIntOrDefault(m.get("queryTimeoutSeconds"),
                                    ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS))
                            .queryTimeoutSeconds()));
        }
        return new ConnConfig(
                require(m, "id"),
                m.getOrDefault("name", ""),
                dbType,
                m.getOrDefault("host", ""),
                parseInt(m.get("port")),
                m.getOrDefault("database", ""),
                m.getOrDefault("username", ""),
                m.getOrDefault("encryptedPassword", ""),
                props);
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
        String json = text.strip();
        if (json.length() < 2 || json.charAt(0) != '[' || json.charAt(json.length() - 1) != ']') {
            throw new IllegalArgumentException("连接配置必须是完整 JSON 数组");
        }
        List<Map<String, String>> result = new ArrayList<>();
        int i = skipWhitespace(json, 1);
        if (json.charAt(i) == ']') {
            if (i == json.length() - 1) return result;
            throw new IllegalArgumentException("连接配置数组后存在多余内容");
        }
        while (i < json.length() - 1) {
            if (json.charAt(i) != '{') {
                throw new IllegalArgumentException("连接条目必须是 JSON 对象");
            }
            int end = closingBrace(json, i + 1);
            result.add(parseObject(json.substring(i + 1, end)));
            i = skipWhitespace(json, end + 1);
            if (json.charAt(i) == ']') {
                if (i == json.length() - 1) return result;
                throw new IllegalArgumentException("连接配置数组后存在多余内容");
            }
            if (json.charAt(i) != ',') {
                throw new IllegalArgumentException("连接条目之间缺少逗号");
            }
            i = skipWhitespace(json, i + 1);
        }
        throw new IllegalArgumentException("连接配置数组未闭合");
    }

    private static int parseIntOrDefault(String value, int fallback) {
        try {
            int parsed = value == null ? fallback : Integer.parseInt(value.trim());
            return parsed >= 0 && parsed <= ConnectionSafetyOptions.MAX_QUERY_TIMEOUT_SECONDS
                    ? parsed : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static void warnInvalidSafetyValues(Map<String, String> values) {
        String environment = values.get("environment");
        if (environment != null && java.util.Arrays.stream(ConnectionEnvironment.values())
                .noneMatch(candidate -> candidate.name().equalsIgnoreCase(environment.trim()))) {
            LOG.warning("连接安全环境值无效，已回退到 DEVELOPMENT");
        }
        String readOnly = values.get("readOnly");
        if (readOnly != null && !readOnly.equalsIgnoreCase("true")
                && !readOnly.equalsIgnoreCase("false")) {
            LOG.warning("连接只读值无效，已回退到 false");
        }
        String timeout = values.get("queryTimeoutSeconds");
        if (timeout != null && parseIntOrDefault(
                timeout, ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS)
                == ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS
                && !timeout.trim().equals(Integer.toString(
                        ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS))) {
            LOG.warning("连接查询超时值无效，已回退到 60 秒");
        }
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    private static int closingBrace(String text, int from) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (quoted && ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && ch == '}') {
                return i;
            }
        }
        throw new IllegalArgumentException("连接条目对象未闭合");
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
        throw new IllegalArgumentException("字符串未闭合");
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

    @FunctionalInterface
    interface PathMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    @FunctionalInterface
    interface SnapshotWriter {
        void write(Path target, String json) throws IOException;
    }
}
