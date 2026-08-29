package com.datacube.sqleditor.result;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative scanner for unquoted, depth-zero SQL tokens. */
public final class TopLevelSqlTokens {
    private static final java.util.Set<String> SIDE_EFFECT_FUNCTIONS =
            java.util.Set.of("NEXTVAL", "SETVAL");

    private TopLevelSqlTokens() {
    }

    public static List<String> scan(String sql) {
        return scan(sql, false);
    }

    static List<String> scan(String sql, boolean oracleMode) {
        if (sql == null) throw new IllegalArgumentException("SQL 不能为空");
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;

            if (postgresUnicodeQuotedIdentifierAt(sql, index, oracleMode)) throw invalid();
            OracleQuote oracleQuote = oracleQuoteAt(sql, index, oracleMode);
            if (oracleQuote != null) {
                flush(tokens, token, depth);
                index = skipOracleQuote(sql, index + oracleQuote.prefixLength(), oracleQuote.close());
            } else if (current == '\'') {
                flush(tokens, token, depth);
                index = skipSingleQuote(sql, index, postgresEscapeStringAt(sql, index, oracleMode));
            } else if (current == '"') {
                flush(tokens, token, depth);
                index = skipDoubleQuote(sql, index);
            } else if (current == '-' && next == '-') {
                flush(tokens, token, depth);
                index = skipLineComment(sql, index + 2);
            } else if (current == '/' && next == '*') {
                flush(tokens, token, depth);
                index = skipBlockComment(sql, index + 2, oracleMode);
            } else if (current == '*' && next == '/') {
                throw invalid();
            } else if (!oracleMode && current == '$') {
                String delimiter = dollarDelimiterAt(sql, index);
                if (delimiter == null) {
                    appendOrFlush(tokens, token, depth, current);
                    index++;
                } else {
                    flush(tokens, token, depth);
                    index = skipDollarQuote(sql, index + delimiter.length(), delimiter);
                }
            } else if (current == '(') {
                flush(tokens, token, depth);
                depth++;
                index++;
            } else if (current == ')') {
                flush(tokens, token, depth);
                if (depth == 0) throw invalid();
                depth--;
                index++;
            } else {
                appendOrFlush(tokens, token, depth, current);
                index++;
            }
        }
        flush(tokens, token, depth);
        if (depth != 0) throw invalid();
        return List.copyOf(tokens);
    }

    static boolean containsKnownSideEffectInvocation(String sql, boolean oracleMode) {
        List<String> tokens = scanAll(sql, oracleMode);
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if ((SIDE_EFFECT_FUNCTIONS.contains(token) || advisoryFunction(token))
                    && index + 1 < tokens.size() && tokens.get(index + 1).equals("(")) {
                return true;
            }
            if (oracleMode && token.equals("NEXTVAL")
                    && index > 0 && tokens.get(index - 1).equals(".")) {
                return true;
            }
            if (token.equals("DBMS_LOCK") && index + 3 < tokens.size()
                    && tokens.get(index + 1).equals(".") && tokens.get(index + 3).equals("(")) {
                return true;
            }
        }
        return false;
    }

    private static boolean advisoryFunction(String token) {
        return token.startsWith("PG_ADVISORY_") || token.startsWith("PG_TRY_ADVISORY_");
    }

    private static List<String> scanAll(String sql, boolean oracleMode) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (postgresUnicodeQuotedIdentifierAt(sql, index, oracleMode)) throw invalid();
            OracleQuote oracleQuote = oracleQuoteAt(sql, index, oracleMode);
            if (oracleQuote != null) {
                flushAll(tokens, token);
                index = skipOracleQuote(sql, index + oracleQuote.prefixLength(), oracleQuote.close());
            } else if (current == '\'') {
                flushAll(tokens, token);
                index = skipSingleQuote(sql, index, postgresEscapeStringAt(sql, index, oracleMode));
            } else if (current == '"') {
                flushAll(tokens, token);
                QuotedIdentifier identifier = quotedIdentifier(sql, index);
                tokens.add(identifier.value().toUpperCase(Locale.ROOT));
                index = identifier.end();
            } else if (current == '-' && next == '-') {
                flushAll(tokens, token);
                index = skipLineComment(sql, index + 2);
            } else if (current == '/' && next == '*') {
                flushAll(tokens, token);
                index = skipBlockComment(sql, index + 2, oracleMode);
            } else if (current == '*' && next == '/') {
                throw invalid();
            } else if (!oracleMode && current == '$') {
                String delimiter = dollarDelimiterAt(sql, index);
                if (delimiter == null) {
                    appendOrFlushAll(tokens, token, current);
                    index++;
                } else {
                    flushAll(tokens, token);
                    index = skipDollarQuote(sql, index + delimiter.length(), delimiter);
                }
            } else if (current == '(') {
                flushAll(tokens, token);
                tokens.add("(");
                depth++;
                index++;
            } else if (current == ')') {
                flushAll(tokens, token);
                if (depth == 0) throw invalid();
                tokens.add(")");
                depth--;
                index++;
            } else if (current == '.') {
                flushAll(tokens, token);
                tokens.add(".");
                index++;
            } else {
                appendOrFlushAll(tokens, token, current);
                index++;
            }
        }
        flushAll(tokens, token);
        if (depth != 0) throw invalid();
        return tokens;
    }

    private static void appendOrFlushAll(
            List<String> tokens, StringBuilder token, char current) {
        if (wordPart(current)) token.append(current);
        else flushAll(tokens, token);
    }

    private static void flushAll(List<String> tokens, StringBuilder token) {
        if (token.isEmpty()) return;
        tokens.add(token.toString().toUpperCase(Locale.ROOT));
        token.setLength(0);
    }

    private static void appendOrFlush(
            List<String> tokens, StringBuilder token, int depth, char current) {
        if (wordPart(current)) {
            token.append(current);
        } else {
            flush(tokens, token, depth);
        }
    }

    private static void flush(List<String> tokens, StringBuilder token, int depth) {
        if (token.isEmpty()) return;
        if (depth == 0) tokens.add(token.toString().toUpperCase(Locale.ROOT));
        token.setLength(0);
    }

    private static int skipSingleQuote(String sql, int quote, boolean backslashEscapes) {
        int index = quote + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (backslashEscapes && current == '\\' && index + 1 < sql.length()) {
                index += 2;
            } else if (current == '\'') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }
        throw invalid();
    }

    private static int skipDoubleQuote(String sql, int quote) {
        return quotedIdentifier(sql, quote).end();
    }

    private static QuotedIdentifier quotedIdentifier(String sql, int quote) {
        StringBuilder value = new StringBuilder();
        int index = quote + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == '"') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                    value.append('"');
                    index += 2;
                } else {
                    return new QuotedIdentifier(value.toString(), index + 1);
                }
            } else {
                value.append(sql.charAt(index));
                index++;
            }
        }
        throw invalid();
    }

    private static int skipLineComment(String sql, int index) {
        while (index < sql.length()
                && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
        return index;
    }

    private static int skipBlockComment(String sql, int index, boolean oracleMode) {
        int depth = 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (current == '/' && next == '*') {
                if (oracleMode) throw invalid();
                depth++;
                index += 2;
            } else if (current == '*' && next == '/') {
                depth--;
                index += 2;
                if (depth == 0) return index;
            } else {
                index++;
            }
        }
        throw invalid();
    }

    private static int skipDollarQuote(String sql, int index, String delimiter) {
        int close = sql.indexOf(delimiter, index);
        if (close < 0) throw invalid();
        return close + delimiter.length();
    }

    private static int skipOracleQuote(String sql, int index, char close) {
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == close && sql.charAt(index + 1) == '\'') {
                return index + 2;
            }
            index++;
        }
        throw invalid();
    }

    private static boolean postgresEscapeStringAt(String sql, int quote, boolean oracleMode) {
        if (oracleMode || quote < 1) return false;
        char prefix = sql.charAt(quote - 1);
        return (prefix == 'e' || prefix == 'E')
                && (quote == 1 || !wordPart(sql.charAt(quote - 2)));
    }

    private static String dollarDelimiterAt(String sql, int offset) {
        if (offset > 0 && postgresIdentifierPart(sql.charAt(offset - 1), true)) return null;
        int index = offset + 1;
        while (index < sql.length() && postgresIdentifierPart(sql.charAt(index), false)) index++;
        if (index >= sql.length() || sql.charAt(index) != '$') return null;
        if (index > offset + 1 && asciiDigit(sql.charAt(offset + 1))) return null;
        return sql.substring(offset, index + 1);
    }

    private static OracleQuote oracleQuoteAt(String sql, int offset, boolean oracleMode) {
        if (!oracleMode || offset > 0 && wordPart(sql.charAt(offset - 1))) return null;
        int delimiterOffset;
        char first = sql.charAt(offset);
        if ((first == 'q' || first == 'Q')
                && offset + 2 < sql.length() && sql.charAt(offset + 1) == '\'') {
            delimiterOffset = offset + 2;
        } else if ((first == 'n' || first == 'N') && offset + 3 < sql.length()
                && (sql.charAt(offset + 1) == 'q' || sql.charAt(offset + 1) == 'Q')
                && sql.charAt(offset + 2) == '\'') {
            delimiterOffset = offset + 3;
        } else {
            return null;
        }
        char opening = sql.charAt(delimiterOffset);
        if (Character.isWhitespace(opening) || Character.isSpaceChar(opening)
                || Character.isISOControl(opening)) {
            throw invalid();
        }
        char close = switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        };
        return new OracleQuote(delimiterOffset - offset + 1, close);
    }

    private static boolean postgresUnicodeQuotedIdentifierAt(
            String sql, int offset, boolean oracleMode) {
        if (oracleMode || offset + 2 >= sql.length()
                || offset > 0 && wordPart(sql.charAt(offset - 1))) {
            return false;
        }
        char prefix = sql.charAt(offset);
        return (prefix == 'u' || prefix == 'U')
                && sql.charAt(offset + 1) == '&' && sql.charAt(offset + 2) == '"';
    }

    private static boolean wordPart(char value) {
        return value >= 0x80 || Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static boolean postgresIdentifierPart(char value, boolean allowDollar) {
        return value >= 0x80 || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || asciiDigit(value) || value == '_' || allowDollar && value == '$';
    }

    private static boolean asciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SQL 词法或括号结构不完整");
    }

    private record OracleQuote(int prefixLength, char close) {
    }

    private record QuotedIdentifier(String value, int end) {
    }
}
