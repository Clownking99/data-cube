package com.datacube.sqleditor.result;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative lexer and structural classifier for database re-query eligibility. */
public final class TopLevelSqlTokens {
    private static final Set<String> SAFE_SINGLE_SYMBOLS =
            Set.of("*", "+", "-", "/", "%", "=", "<", ">");
    private static final Set<String> SAFE_COMPARISON_SYMBOLS =
            Set.of("<=", ">=", "<>", "!=");

    private TopLevelSqlTokens() {
    }

    public static List<String> scan(String sql) {
        return scan(sql, false);
    }

    static List<String> scan(String sql, boolean oracleMode) {
        return analyze(sql, oracleMode).topLevelTokens();
    }

    static Analysis analyze(String sql, boolean oracleMode) {
        if (sql == null) throw new IllegalArgumentException("SQL 不能为空");
        List<LexToken> tokens = lex(sql, oracleMode);
        List<String> topLevel = tokens.stream()
                .filter(token -> token.kind() == Kind.WORD && token.depth() == 0)
                .map(LexToken::text)
                .toList();
        return new Analysis(topLevel,
                containsUnprovenCallable(tokens),
                containsUnsafeStructure(tokens, oracleMode),
                oracleMode && isOracleTrustedSysDualSelect(tokens),
                !oracleMode && isPostgresNativeLiteralSelect(tokens));
    }

    /**
     * Oracle permits zero-argument functions to be referenced without parentheses, making a bare
     * projection identifier indistinguishable from a column without database metadata. Views and
     * synonyms can also hide UDFs, policies, and database links. Static re-query admission is
     * therefore limited to the schema-qualified built-in {@code SYS.DUAL} object, with only a
     * wildcard projection.
     */
    private static boolean isOracleTrustedSysDualSelect(List<LexToken> tokens) {
        if (tokens.size() < 6 || !word(tokens.getFirst(), "SELECT")) return false;
        int index = 1;
        LexToken projectionQualifier = null;
        if (symbol(tokens.get(index), "*")) {
            index++;
        } else {
            if (!tokens.get(index).identifier()) return false;
            projectionQualifier = tokens.get(index++);
            if (index + 1 >= tokens.size()
                    || tokens.get(index).kind() != Kind.DOT
                    || !symbol(tokens.get(index + 1), "*")) {
                return false;
            }
            index += 2;
        }
        if (index >= tokens.size() || !word(tokens.get(index++), "FROM")) return false;
        if (index + 2 >= tokens.size()
                || !trustedIdentifier(tokens.get(index), "SYS")
                || tokens.get(index + 1).kind() != Kind.DOT
                || !trustedIdentifier(tokens.get(index + 2), "DUAL")) return false;
        LexToken relation = tokens.get(index + 2);
        index += 3;
        LexToken alias = null;
        if (index < tokens.size() && tokens.get(index).identifier()) {
            alias = tokens.get(index++);
        }
        if (index != tokens.size()) return false;
        return projectionQualifier == null
                || sameIdentifier(projectionQualifier, alias == null ? relation : alias);
    }

    /** PostgreSQL row sources, type prefixes, and operators may resolve to user code. */
    private static boolean isPostgresNativeLiteralSelect(List<LexToken> tokens) {
        if (tokens.size() < 2 || !word(tokens.getFirst(), "SELECT")) return false;
        int index = 1;
        while (true) {
            index = postgresLiteralExpressionEnd(tokens, index);
            if (index < 0) return false;
            if (index < tokens.size() && word(tokens.get(index), "AS")) {
                index++;
                if (index >= tokens.size() || !tokens.get(index).identifier()) return false;
                index++;
            }
            if (index == tokens.size()) return true;
            if (!symbol(tokens.get(index), ",")) return false;
            index++;
            if (index == tokens.size()) return false;
        }
    }

    private static int postgresLiteralExpressionEnd(List<LexToken> tokens, int index) {
        if (index >= tokens.size()) return -1;
        LexToken token = tokens.get(index);
        if (token.kind() == Kind.NUMBER || token.kind() == Kind.LITERAL
                || token.kind() == Kind.WORD
                        && Set.of("TRUE", "FALSE", "NULL").contains(token.text())) {
            return index + 1;
        }
        if (token.kind() != Kind.OPEN_PAREN) return -1;
        int end = postgresLiteralExpressionEnd(tokens, index + 1);
        return end >= 0 && end < tokens.size()
                && tokens.get(end).kind() == Kind.CLOSE_PAREN ? end + 1 : -1;
    }

    private static boolean trustedIdentifier(LexToken token, String expected) {
        return token.kind() == Kind.WORD && token.text().equals(expected)
                || token.kind() == Kind.QUOTED_IDENTIFIER && token.text().equals(expected);
    }

    private static boolean sameIdentifier(LexToken left, LexToken right) {
        return left.kind() == right.kind() && left.text().equals(right.text());
    }

    private static boolean word(LexToken token, String expected) {
        return token.kind() == Kind.WORD && token.text().equals(expected);
    }

    private static boolean symbol(LexToken token, String expected) {
        return token.kind() == Kind.SYMBOL && token.text().equals(expected);
    }

    private static List<LexToken> lex(String sql, boolean oracleMode) {
        List<LexToken> tokens = new ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;

            if (unsupportedControl(current)) throw invalid();
            if (postgresUnicodeQuotedIdentifierAt(sql, index, oracleMode)) throw invalid();
            OracleQuote oracleQuote = oracleQuoteAt(sql, index, oracleMode);
            if (oracleQuote != null) {
                tokens.add(new LexToken(Kind.LITERAL, "", depth));
                index = skipOracleQuote(sql, index + oracleQuote.prefixLength(), oracleQuote.close());
            } else if (current == '\'') {
                tokens.add(new LexToken(Kind.LITERAL, "", depth));
                index = skipSingleQuote(sql, index, postgresEscapeStringAt(sql, index, oracleMode));
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifier(sql, index);
                tokens.add(new LexToken(Kind.QUOTED_IDENTIFIER,
                        identifier.value(), depth));
                index = identifier.end();
            } else if (current == '-' && next == '-') {
                index = skipLineComment(sql, index + 2);
            } else if (current == '/' && next == '*') {
                index = skipBlockComment(sql, index + 2, oracleMode);
            } else if (current == '*' && next == '/') {
                throw invalid();
            } else if (!oracleMode && current == '$') {
                String delimiter = dollarDelimiterAt(sql, index);
                if (delimiter == null) {
                    Word word = word(sql, index);
                    tokens.add(new LexToken(Kind.WORD, word.value(), depth));
                    index = word.end();
                } else {
                    tokens.add(new LexToken(Kind.LITERAL, "", depth));
                    index = skipDollarQuote(sql, index + delimiter.length(), delimiter);
                }
            } else if (current == '(') {
                tokens.add(new LexToken(Kind.OPEN_PAREN, "(", depth));
                depth++;
                index++;
            } else if (current == ')') {
                if (depth == 0) throw invalid();
                depth--;
                tokens.add(new LexToken(Kind.CLOSE_PAREN, ")", depth));
                index++;
            } else if (current == '.') {
                tokens.add(new LexToken(Kind.DOT, ".", depth));
                index++;
            } else if (asciiDigit(current)) {
                index = numberEnd(sql, index);
                tokens.add(new LexToken(Kind.NUMBER, "", depth));
            } else if (wordPart(current)) {
                Word word = word(sql, index);
                tokens.add(new LexToken(Kind.WORD, word.value(), depth));
                index = word.end();
            } else if (Character.isWhitespace(current) || Character.isSpaceChar(current)) {
                index++;
            } else {
                tokens.add(new LexToken(Kind.SYMBOL, Character.toString(current), depth));
                index++;
            }
        }
        if (depth != 0) throw invalid();
        return List.copyOf(tokens);
    }

    private static boolean containsUnprovenCallable(List<LexToken> tokens) {
        for (int index = 0; index + 1 < tokens.size(); index++) {
            LexToken token = tokens.get(index);
            LexToken next = tokens.get(index + 1);
            if (next.kind() != Kind.OPEN_PAREN || !token.identifier()) continue;
            if (!grammarParenthesis(tokens, index)) return true;
        }
        for (int index = 1; index < tokens.size(); index++) {
            LexToken token = tokens.get(index);
            if (!token.identifier() || !Set.of("NEXTVAL", "CURRVAL").contains(token.text())) {
                continue;
            }
            if (tokens.get(index - 1).kind() == Kind.DOT) return true;
        }
        return false;
    }

    private static boolean grammarParenthesis(List<LexToken> tokens, int index) {
        LexToken token = tokens.get(index);
        if (token.kind() != Kind.WORD
                || index > 0 && tokens.get(index - 1).kind() == Kind.DOT) {
            return false;
        }
        return switch (token.text()) {
            case "SELECT", "WHERE", "HAVING", "AND", "OR", "NOT", "IN" -> true;
            case "FROM", "JOIN" -> nextParenthesizedWordIs(tokens, index, "SELECT");
            case "LATERAL" -> priorWord(tokens, index, "FROM", "JOIN")
                    && nextParenthesizedWordIs(tokens, index, "SELECT");
            case "EXISTS" -> nextParenthesizedWordIs(tokens, index, "SELECT");
            case "ANY", "ALL", "SOME" -> precededByComparison(tokens, index);
            case "BY" -> priorWord(tokens, index, "GROUP", "ORDER", "PARTITION");
            case "DISTINCT" -> priorWord(tokens, index, "SELECT");
            case "ON" -> priorWord(tokens, index, "DISTINCT")
                    || hasPriorWordAtDepth(tokens, index, "JOIN");
            case "USING" -> hasPriorWordAtDepth(tokens, index, "JOIN");
            case "WHEN", "THEN", "ELSE" -> hasPriorWordAtDepth(tokens, index, "CASE");
            case "VALUES" -> index >= 2
                    && tokens.get(index - 1).kind() == Kind.OPEN_PAREN
                    && tokens.get(index - 2).kind() == Kind.WORD
                    && Set.of("FROM", "JOIN").contains(tokens.get(index - 2).text());
            default -> false;
        };
    }

    private static boolean nextParenthesizedWordIs(
            List<LexToken> tokens, int prefixIndex, String expected) {
        int nestedIndex = prefixIndex + 2;
        return nestedIndex < tokens.size()
                && tokens.get(nestedIndex).kind() == Kind.WORD
                && tokens.get(nestedIndex).text().equals(expected);
    }

    private static boolean precededByComparison(List<LexToken> tokens, int index) {
        if (index == 0) return false;
        LexToken previous = tokens.get(index - 1);
        return previous.kind() == Kind.SYMBOL
                && Set.of("=", "<", ">", "!").contains(previous.text());
    }

    private static boolean priorWord(
            List<LexToken> tokens, int index, String... expected) {
        if (index == 0 || tokens.get(index - 1).kind() != Kind.WORD) return false;
        return Set.of(expected).contains(tokens.get(index - 1).text());
    }

    private static boolean hasPriorWordAtDepth(
            List<LexToken> tokens, int index, String expected) {
        int depth = tokens.get(index).depth();
        for (int prior = index - 1; prior >= 0; prior--) {
            LexToken token = tokens.get(prior);
            if (token.depth() < depth) return false;
            if (token.depth() == depth && token.kind() == Kind.WORD
                    && token.text().equals(expected)) return true;
        }
        return false;
    }

    private static boolean containsUnsafeStructure(List<LexToken> tokens, boolean oracleMode) {
        for (int index = 0; index < tokens.size(); index++) {
            LexToken token = tokens.get(index);
            if (token.kind() == Kind.SYMBOL) {
                if (token.text().equals(",")) continue;
                int end = index + 1;
                StringBuilder run = new StringBuilder(token.text());
                while (end < tokens.size()
                        && tokens.get(end).kind() == Kind.SYMBOL
                        && !tokens.get(end).text().equals(",")) {
                    run.append(tokens.get(end).text());
                    end++;
                }
                String symbol = run.toString();
                if (!SAFE_SINGLE_SYMBOLS.contains(symbol)
                        && !SAFE_COMPARISON_SYMBOLS.contains(symbol)) {
                    return true;
                }
                index = end - 1;
                continue;
            }
            if (token.kind() == Kind.WORD
                    && (token.text().equals("WITH") || token.text().equals("INTO"))) {
                return true;
            }
            if (token.kind() == Kind.WORD && token.text().equals("FOR")
                    && lockModeFollows(tokens, index + 1)) {
                return true;
            }
            if (wordSequence(tokens, index, "LOCK", "IN", "SHARE", "MODE")) return true;
            if (oracleMode && token.kind() == Kind.SYMBOL && token.text().equals("@")) return true;
        }
        return false;
    }

    private static boolean lockModeFollows(List<LexToken> tokens, int offset) {
        int words = 0;
        for (int index = offset; index < tokens.size() && words < 3; index++) {
            LexToken token = tokens.get(index);
            if (token.kind() == Kind.OPEN_PAREN || token.kind() == Kind.CLOSE_PAREN
                    || token.kind() == Kind.SYMBOL || token.kind() == Kind.LITERAL) {
                return false;
            }
            if (token.kind() != Kind.WORD) continue;
            if (token.text().equals("UPDATE") || token.text().equals("SHARE")) return true;
            if (!token.text().equals("NO") && !token.text().equals("KEY")) return false;
            words++;
        }
        return false;
    }

    private static boolean wordSequence(List<LexToken> tokens, int offset, String... expected) {
        if (offset + expected.length > tokens.size()) return false;
        for (int index = 0; index < expected.length; index++) {
            LexToken token = tokens.get(offset + index);
            if (token.kind() != Kind.WORD || !token.text().equals(expected[index])) return false;
        }
        return true;
    }

    private static Word word(String sql, int offset) {
        int index = offset;
        while (index < sql.length() && wordPart(sql.charAt(index))) index++;
        return new Word(sql.substring(offset, index).toUpperCase(Locale.ROOT), index);
    }

    private static int numberEnd(String sql, int offset) {
        int index = offset;
        while (index < sql.length() && asciiDigit(sql.charAt(index))) index++;
        if (index + 1 < sql.length() && sql.charAt(index) == '.'
                && asciiDigit(sql.charAt(index + 1))) {
            index += 2;
            while (index < sql.length() && asciiDigit(sql.charAt(index))) index++;
        }
        if (index < sql.length() && (sql.charAt(index) == 'e' || sql.charAt(index) == 'E')) {
            int exponent = index + 1;
            if (exponent < sql.length()
                    && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) exponent++;
            int digits = exponent;
            while (exponent < sql.length() && asciiDigit(sql.charAt(exponent))) exponent++;
            if (exponent > digits) index = exponent;
        }
        return index;
    }

    private static int skipSingleQuote(String sql, int quote, boolean backslashEscapes) {
        int index = quote + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (unsupportedControl(current)) throw invalid();
            if (backslashEscapes && current == '\\' && index + 1 < sql.length()) {
                if (unsupportedControl(sql.charAt(index + 1))) throw invalid();
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

    private static QuotedIdentifier quotedIdentifier(String sql, int quote) {
        StringBuilder value = new StringBuilder();
        int index = quote + 1;
        while (index < sql.length()) {
            if (unsupportedControl(sql.charAt(index))) throw invalid();
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
                && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            if (unsupportedControl(sql.charAt(index))) throw invalid();
            index++;
        }
        return index;
    }

    private static int skipBlockComment(String sql, int index, boolean oracleMode) {
        int depth = 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (unsupportedControl(current)) throw invalid();
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
        for (int offset = index; offset < close; offset++) {
            if (unsupportedControl(sql.charAt(offset))) throw invalid();
        }
        return close + delimiter.length();
    }

    private static int skipOracleQuote(String sql, int index, char close) {
        while (index + 1 < sql.length()) {
            if (unsupportedControl(sql.charAt(index))) throw invalid();
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

    private static boolean unsupportedControl(char value) {
        return Character.isISOControl(value)
                && value != '\t' && value != '\n' && value != '\r';
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SQL 词法或括号结构不完整");
    }

    record Analysis(List<String> topLevelTokens, boolean unprovenCallable,
                    boolean unsafeStructure, boolean oracleTrustedSysDualSelect,
                    boolean postgresNativeLiteralSelect) {
        Analysis {
            topLevelTokens = List.copyOf(topLevelTokens);
        }
    }

    private enum Kind {
        WORD, QUOTED_IDENTIFIER, NUMBER, LITERAL, OPEN_PAREN, CLOSE_PAREN, DOT, SYMBOL
    }

    private record LexToken(Kind kind, String text, int depth) {
        private boolean identifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED_IDENTIFIER;
        }
    }

    private record OracleQuote(int prefixLength, char close) {
    }

    private record QuotedIdentifier(String value, int end) {
    }

    private record Word(String value, int end) {
    }
}
