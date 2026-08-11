package com.datacube.service;

import com.datacube.sqleditor.SqlScriptSplitter;
import com.datacube.spi.model.DbType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative lexical admission for renderer-produced schema SQL. */
final class SchemaDeploymentSqlAdmission {
    private SchemaDeploymentSqlAdmission() {
    }

    enum Classification { PROVEN_NON_DESTRUCTIVE, CREATE_OR_REPLACE, DESTRUCTIVE }

    static Classification classify(String sql, DbType databaseType) {
        boolean oracle = databaseType == DbType.ORACLE;
        List<String> statements = SqlScriptSplitter.split(sql, oracle);
        if (statements.size() != 1) return Classification.DESTRUCTIVE;

        LexicalResult lexical = tokenizeTopLevel(statements.getFirst(), oracle);
        if (!lexical.valid() || lexical.tokens().isEmpty()) return Classification.DESTRUCTIVE;
        List<Token> tokens = lexical.tokens();
        String first = word(tokens.getFirst());
        if ("CREATE".equals(first)) {
            return isCreateOrReplace(tokens)
                    ? Classification.CREATE_OR_REPLACE
                    : Classification.PROVEN_NON_DESTRUCTIVE;
        }
        if ("COMMENT".equals(first) || "GRANT".equals(first)) {
            return Classification.PROVEN_NON_DESTRUCTIVE;
        }
        if ("ALTER".equals(first) && isProvenAdditiveTableAlter(tokens)) {
            return Classification.PROVEN_NON_DESTRUCTIVE;
        }
        return Classification.DESTRUCTIVE;
    }

    static boolean isCreateOrReplace(String sql) {
        for (boolean oracle : List.of(false, true)) {
            List<String> statements = SqlScriptSplitter.split(sql, oracle);
            if (statements.size() != 1) continue;
            LexicalResult lexical = tokenizeTopLevel(statements.getFirst(), oracle);
            if (lexical.valid() && isCreateOrReplace(lexical.tokens())) return true;
        }
        return false;
    }

    private static boolean isCreateOrReplace(List<Token> tokens) {
        return tokens.size() >= 3
                && "CREATE".equals(word(tokens.get(0)))
                && "OR".equals(word(tokens.get(1)))
                && "REPLACE".equals(word(tokens.get(2)));
    }

    private static boolean isProvenAdditiveTableAlter(List<Token> tokens) {
        int cursor = 0;
        if (!consumeWord(tokens, cursor++, "ALTER") || !consumeWord(tokens, cursor++, "TABLE")) {
            return false;
        }
        if (consumeWord(tokens, cursor, "ONLY")) cursor++;
        if (cursor >= tokens.size() || !tokens.get(cursor).identifier()) return false;
        cursor++;
        while (cursor < tokens.size() && tokens.get(cursor).symbol('.')) {
            cursor++;
            if (cursor >= tokens.size() || !tokens.get(cursor).identifier()) return false;
            cursor++;
        }
        if (cursor < tokens.size() && tokens.get(cursor).symbol('*')) cursor++;
        if (!consumeWord(tokens, cursor, "ADD")) return false;

        boolean actionStart = false;
        for (; cursor < tokens.size(); cursor++) {
            Token token = tokens.get(cursor);
            if (token.symbol(',')) {
                actionStart = true;
                continue;
            }
            if (actionStart) {
                if (!consumeWord(tokens, cursor, "ADD")) return false;
                actionStart = false;
            }
            String word = word(token);
            if ("DROP".equals(word) || "ALTER".equals(word) || "RENAME".equals(word)
                    || "MODIFY".equals(word) || "SET".equals(word) || "RESET".equals(word)
                    || "TRUNCATE".equals(word) || "ATTACH".equals(word)
                    || "DETACH".equals(word)) {
                return false;
            }
        }
        return !actionStart;
    }

    private static boolean consumeWord(List<Token> tokens, int index, String expected) {
        return index < tokens.size() && expected.equals(word(tokens.get(index)));
    }

    private static String word(Token token) {
        return token.kind() == TokenKind.WORD ? token.text() : "";
    }

    private static LexicalResult tokenizeTopLevel(String sql, boolean oracle) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        for (int cursor = 0; cursor < sql.length();) {
            char current = sql.charAt(cursor);
            char next = cursor + 1 < sql.length() ? sql.charAt(cursor + 1) : '\0';
            if (Character.isWhitespace(current)) {
                cursor++;
            } else if (current == '-' && next == '-') {
                cursor += 2;
                while (cursor < sql.length()
                        && sql.charAt(cursor) != '\n' && sql.charAt(cursor) != '\r') cursor++;
            } else if (current == '/' && next == '*') {
                int end = blockCommentEnd(sql, cursor + 2, oracle);
                if (end < 0) return new LexicalResult(tokens, false);
                cursor = end;
            } else if (current == '\'') {
                int end = quotedEnd(sql, cursor + 1, '\'');
                if (end < 0) return new LexicalResult(tokens, false);
                cursor = end;
            } else if (current == '"') {
                int end = quotedEnd(sql, cursor + 1, '"');
                if (end < 0) return new LexicalResult(tokens, false);
                if (depth == 0) tokens.add(new Token(TokenKind.IDENTIFIER, ""));
                cursor = end;
            } else {
                OracleQuote quote = oracleQuote(sql, cursor, oracle);
                if (quote != null) {
                    int end = sql.indexOf(quote.closing() + "'", cursor + quote.prefixLength());
                    if (end < 0) return new LexicalResult(tokens, false);
                    cursor = end + 2;
                    continue;
                }
                String dollar = dollarDelimiter(sql, cursor, oracle);
                if (dollar != null) {
                    int end = sql.indexOf(dollar, cursor + dollar.length());
                    if (end < 0) return new LexicalResult(tokens, false);
                    cursor = end + dollar.length();
                } else if (current == '(') {
                    depth++;
                    cursor++;
                } else if (current == ')') {
                    if (depth == 0) return new LexicalResult(tokens, false);
                    depth--;
                    cursor++;
                } else if (depth == 0 && (current == ',' || current == '.' || current == '*')) {
                    tokens.add(new Token(TokenKind.SYMBOL, String.valueOf(current)));
                    cursor++;
                } else if (isWordPart(current)) {
                    int start = cursor++;
                    while (cursor < sql.length() && isWordPart(sql.charAt(cursor))) cursor++;
                    if (depth == 0) {
                        tokens.add(new Token(TokenKind.WORD,
                                sql.substring(start, cursor).toUpperCase(Locale.ROOT)));
                    }
                } else {
                    cursor++;
                }
            }
        }
        return new LexicalResult(tokens, depth == 0);
    }

    private static int blockCommentEnd(String sql, int cursor, boolean oracle) {
        int depth = 1;
        while (cursor < sql.length()) {
            char current = sql.charAt(cursor);
            char next = cursor + 1 < sql.length() ? sql.charAt(cursor + 1) : '\0';
            if (current == '/' && next == '*') {
                if (oracle) return -1;
                depth++;
                cursor += 2;
            } else if (current == '*' && next == '/') {
                depth--;
                cursor += 2;
                if (depth == 0) return cursor;
            } else {
                cursor++;
            }
        }
        return -1;
    }

    private static int quotedEnd(String sql, int cursor, char quote) {
        while (cursor < sql.length()) {
            if (sql.charAt(cursor) != quote) {
                cursor++;
            } else if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == quote) {
                cursor += 2;
            } else {
                return cursor + 1;
            }
        }
        return -1;
    }

    private static OracleQuote oracleQuote(String sql, int offset, boolean oracle) {
        if (!oracle || offset > 0 && isWordPart(sql.charAt(offset - 1))) return null;
        int delimiter;
        char first = sql.charAt(offset);
        if ((first == 'q' || first == 'Q')
                && offset + 2 < sql.length() && sql.charAt(offset + 1) == '\'') {
            delimiter = offset + 2;
        } else if ((first == 'n' || first == 'N') && offset + 3 < sql.length()
                && (sql.charAt(offset + 1) == 'q' || sql.charAt(offset + 1) == 'Q')
                && sql.charAt(offset + 2) == '\'') {
            delimiter = offset + 3;
        } else {
            return null;
        }
        char opening = sql.charAt(delimiter);
        char closing = switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        };
        return new OracleQuote(delimiter - offset + 1, closing);
    }

    private static String dollarDelimiter(String sql, int offset, boolean oracle) {
        if (oracle || sql.charAt(offset) != '$'
                || offset > 0 && isWordPart(sql.charAt(offset - 1))) return null;
        int cursor = offset + 1;
        while (cursor < sql.length()) {
            char value = sql.charAt(cursor);
            if (!(value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9' || value == '_')) break;
            cursor++;
        }
        if (cursor >= sql.length() || sql.charAt(cursor) != '$') return null;
        if (cursor > offset + 1 && Character.isDigit(sql.charAt(offset + 1))) return null;
        return sql.substring(offset, cursor + 1);
    }

    private static boolean isWordPart(char value) {
        return value >= 0x80 || Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private enum TokenKind { WORD, IDENTIFIER, SYMBOL }

    private record Token(TokenKind kind, String text) {
        private boolean identifier() {
            return kind == TokenKind.WORD || kind == TokenKind.IDENTIFIER;
        }

        private boolean symbol(char value) {
            return kind == TokenKind.SYMBOL && text.length() == 1 && text.charAt(0) == value;
        }
    }

    private record LexicalResult(List<Token> tokens, boolean valid) {
    }

    private record OracleQuote(int prefixLength, char closing) {
    }
}
