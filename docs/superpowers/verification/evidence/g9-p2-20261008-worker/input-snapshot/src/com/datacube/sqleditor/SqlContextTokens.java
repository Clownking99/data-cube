package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offset-preserving lexical context. Shares dialect boundaries with script execution. */
public final class SqlContextTokens {
    private SqlContextTokens() {}
    public enum Kind { WORD, IDENTIFIER, STRING, COMMENT, SYMBOL }
    public record Token(Kind kind, String text, int start, int end, int depth) {
        public boolean is(String word) { return kind == Kind.WORD && text.equalsIgnoreCase(word); }
        public boolean identifier() { return kind == Kind.WORD || kind == Kind.IDENTIFIER; }
        public String name(boolean oracle) {
            return kind == Kind.IDENTIFIER ? text.substring(1, text.length() - 1).replace("\"\"", "\"")
                    : oracle ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
        }
    }
    public record Scan(List<Token> tokens, boolean closed, boolean balanced) {
        public List<Token> significant() { return tokens.stream().filter(t -> t.kind != Kind.COMMENT).toList(); }
    }

    public static Scan scan(String sql, boolean oracle) {
        List<Token> tokens = new ArrayList<>();
        int i = 0, depth = 0;
        boolean closed = true, balanced = true;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int start = i, tokenDepth = depth;
            Kind kind = Kind.SYMBOL;
            if (sql.startsWith("--", i)) {
                kind = Kind.COMMENT;
                while (i < sql.length() && sql.charAt(i) != '\r' && sql.charAt(i) != '\n') i++;
            } else if (sql.startsWith("/*", i)) {
                kind = Kind.COMMENT;
                int nesting = 1;
                i += 2;
                while (i < sql.length() && nesting > 0) {
                    if (sql.startsWith("/*", i)) {
                        if (oracle) closed = false;
                        nesting++; i += 2;
                    } else if (sql.startsWith("*/", i)) { nesting--; i += 2; }
                    else i++;
                }
                if (nesting != 0) closed = false;
            } else {
                var q = SqlLexicalRules.oracleQuoteAt(sql, i, oracle);
                String dollar = SqlLexicalRules.dollarDelimiterAt(sql, i, oracle);
                if (q != null || dollar != null) {
                    kind = Kind.STRING;
                    String terminator = q != null ? "" + q.closingDelimiter() + '\'' : dollar;
                    i += q != null ? q.prefixLength() : dollar.length();
                    int end = sql.indexOf(terminator, i);
                    if (end < 0) { closed = false; i = sql.length(); }
                    else i = end + terminator.length();
                } else if (c == '\'' || c == '"') {
                    kind = c == '"' ? Kind.IDENTIFIER : Kind.STRING;
                    boolean escapes = SqlLexicalRules.isPostgresEscapeStringQuote(sql, i, oracle);
                    boolean ended = false;
                    i++;
                    while (i < sql.length()) {
                        char ch = sql.charAt(i++);
                        if (escapes && ch == '\\' && i < sql.length()) i++;
                        else if (ch == c) {
                            if (i < sql.length() && sql.charAt(i) == c) i++;
                            else { ended = true; break; }
                        }
                    }
                    if (!ended) closed = false;
                } else if (SqlLexicalRules.isWordPart(c)) {
                    kind = Kind.WORD;
                    do { i++; } while (i < sql.length() && SqlLexicalRules.isWordPart(sql.charAt(i)));
                } else {
                    if (sql.startsWith("*/", i)) closed = false;
                    if (c == '(') depth++;
                    if (c == ')') { depth--; tokenDepth = depth; if (depth < 0) balanced = false; }
                    i++;
                }
            }
            tokens.add(new Token(kind, sql.substring(start, i), start, i, tokenDepth));
        }
        return new Scan(List.copyOf(tokens), closed, balanced && depth == 0);
    }
}
