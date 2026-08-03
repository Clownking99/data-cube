package com.datacube.redis;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Redis 控制台的纯解析、命令策略与响应格式化逻辑。 */
public final class RedisConsoleSupport {

    public enum CommandPolicy { NORMAL, CONFIRM, BLOCKED }

    private static final Set<String> CONFIRM = Set.of("FLUSHALL", "FLUSHDB", "SHUTDOWN", "DEBUG");
    private static final Set<String> BLOCKED = Set.of(
            "SUBSCRIBE", "PSUBSCRIBE", "MONITOR", "BLPOP", "BRPOP", "BRPOPLPUSH",
            "BLMOVE", "BZPOPMIN", "BZPOPMAX", "WAIT");

    private RedisConsoleSupport() {}

    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        boolean started = false;
        for (int i = 0; i < (line == null ? 0 : line.length()); i++) {
            char c = line.charAt(i);
            if (escaping) {
                token.append(c);
                escaping = false;
                started = true;
            } else if (c == '\\') {
                escaping = true;
                started = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(c);
                started = true;
            }
        }
        if (escaping) throw new IllegalArgumentException("命令末尾不能是转义符");
        if (quote != 0) throw new IllegalArgumentException("命令中的引号未闭合");
        if (started) tokens.add(token.toString());
        return List.copyOf(tokens);
    }

    public static CommandPolicy policy(List<String> args) {
        if (args == null || args.isEmpty()) return CommandPolicy.NORMAL;
        String command = upper(args.getFirst());
        if (BLOCKED.contains(command)) return CommandPolicy.BLOCKED;
        if ("XREAD".equals(command) && upperArgs(args).contains("BLOCK")) return CommandPolicy.BLOCKED;
        if (CONFIRM.contains(command)) return CommandPolicy.CONFIRM;
        if ("CONFIG".equals(command) && args.size() > 1 && "SET".equals(upper(args.get(1)))) {
            return CommandPolicy.CONFIRM;
        }
        return CommandPolicy.NORMAL;
    }

    public static String format(Object response) {
        return format(response, 0);
    }

    private static String format(Object response, int depth) {
        if (response == null) return "(nil)";
        if (response instanceof Long value) return "(integer) " + value;
        if (response instanceof byte[] bytes) return formatBytes(bytes);
        if (response instanceof List<?> values) {
            if (values.isEmpty()) return "(empty array)";
            StringBuilder out = new StringBuilder();
            String indent = "  ".repeat(depth);
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append('\n');
                Object value = values.get(i);
                out.append(indent).append(i + 1).append(") ");
                if (value instanceof List<?>) {
                    out.append('\n').append(format(value, depth + 1));
                } else {
                    out.append(format(value, depth + 1));
                }
            }
            return out.toString();
        }
        return String.valueOf(response);
    }

    private static String formatBytes(byte[] bytes) {
        String text = decodePrintableUtf8(bytes);
        if (text != null) return '"' + text + '"';
        StringBuilder hex = new StringBuilder("(hex)");
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, " %02x", value & 0xff));
        return hex.toString();
    }

    private static String decodePrintableUtf8(byte[] bytes) {
        final String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t') return null;
        }
        return value;
    }

    private static Set<String> upperArgs(List<String> args) {
        Set<String> result = new HashSet<>();
        for (String arg : args) result.add(upper(arg));
        return result;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
