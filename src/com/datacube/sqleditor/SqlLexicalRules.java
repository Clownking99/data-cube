package com.datacube.sqleditor;

/** SQL 分句与安全分析共用的方言引号边界规则。 */
final class SqlLexicalRules {
    private SqlLexicalRules() {}

    enum TriviaStatus { TRIVIA, EXECUTABLE, INVALID }

    static TriviaStatus triviaStatus(String sql, boolean oracleMode) {
        int i = 0;
        while (i < sql.length()) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (Character.isWhitespace(current)) {
                i++;
            } else if (current == '-' && next == '-') {
                i += 2;
                while (i < sql.length()
                        && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
            } else if (current == '/' && next == '*') {
                int depth = 1;
                i += 2;
                while (i < sql.length() && depth > 0) {
                    current = sql.charAt(i);
                    next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
                    if (current == '/' && next == '*') {
                        if (oracleMode) return TriviaStatus.INVALID;
                        depth++;
                        i += 2;
                    } else if (current == '*' && next == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (depth != 0) return TriviaStatus.INVALID;
            } else if (current == '*' && next == '/') {
                return TriviaStatus.INVALID;
            } else {
                return TriviaStatus.EXECUTABLE;
            }
        }
        return TriviaStatus.TRIVIA;
    }

    static boolean isPostgresEscapeStringQuote(String sql, int quoteOffset, boolean oracleMode) {
        if (oracleMode || quoteOffset < 1 || sql.charAt(quoteOffset) != '\'') return false;
        char prefix = sql.charAt(quoteOffset - 1);
        if (prefix != 'E' && prefix != 'e') return false;
        return quoteOffset == 1 || !isWordPart(sql.charAt(quoteOffset - 2));
    }

    static OracleQuote oracleQuoteAt(String sql, int offset, boolean oracleMode) {
        if (!oracleMode || offset < 0 || offset >= sql.length()
                || offset > 0 && isWordPart(sql.charAt(offset - 1))) {
            return null;
        }

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
        return new OracleQuote(delimiterOffset - offset + 1, switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        });
    }

    static String dollarDelimiterAt(String sql, int offset, boolean oracleMode) {
        if (oracleMode || offset < 0 || offset >= sql.length() || sql.charAt(offset) != '$'
                || offset > 0 && isPostgresIdentifierPart(sql.charAt(offset - 1), true)) {
            return null;
        }
        int i = offset + 1;
        while (i < sql.length() && isPostgresIdentifierPart(sql.charAt(i), false)) {
            i++;
        }
        if (i >= sql.length() || sql.charAt(i) != '$') return null;
        if (i > offset + 1 && isAsciiDigit(sql.charAt(offset + 1))) return null;
        return sql.substring(offset, i + 1);
    }

    static boolean isWordPart(char value) {
        return value >= 0x80 || Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static boolean isPostgresIdentifierPart(char value, boolean allowDollar) {
        return value >= 0x80 || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z' || isAsciiDigit(value)
                || value == '_' || allowDollar && value == '$';
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    record OracleQuote(int prefixLength, char closingDelimiter) {}
}
