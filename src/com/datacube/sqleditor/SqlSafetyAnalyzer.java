package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对 SQL 脚本做保守的顶层词法风险分析。
 *
 * <p>这不是完整 SQL 解析器；它只识别安全策略所需的顶层关键字，并跳过字符串、
 * 注释、PostgreSQL dollar quote、Oracle q quote 以及括号中的内容。
 */
public final class SqlSafetyAnalyzer {
    private static final int MAX_CTE_SCOPES = 64;
    private static final Set<String> READ_KEYWORDS = Set.of(
            "SELECT", "SHOW", "DESCRIBE", "DESC", "VALUES", "TABLE");
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE",
            "CALL", "DO", "EXEC", "EXECUTE", "DECLARE");
    private static final Set<String> DDL_KEYWORDS = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "GRANT", "REVOKE", "RENAME");
    private static final Set<String> TRANSACTION_KEYWORDS = Set.of(
            "BEGIN", "START", "COMMIT", "ROLLBACK", "SET", "SAVEPOINT", "RELEASE");
    private static final Set<String> EXPLAINABLE_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "VALUES", "TABLE",
            "EXECUTE", "EXEC", "CALL", "DO", "DECLARE", "CREATE");
    private static final Set<String> CTE_COMMAND_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE");

    private SqlSafetyAnalyzer() {}

    public enum StatementKind { READ, WRITE, DDL, TRANSACTION_CONTROL, UNKNOWN }

    public enum Risk { MISSING_WHERE, DESTRUCTIVE_DDL, UNKNOWN_STATEMENT, SESSION_STATE_CONFLICT }

    public record StatementAnalysis(
            int index, String sql, String firstKeyword, StatementKind kind, Set<Risk> risks) {
        public StatementAnalysis {
            risks = Set.copyOf(risks);
        }
    }

    public record ScriptAnalysis(List<StatementAnalysis> statements) {
        public ScriptAnalysis {
            statements = List.copyOf(statements);
        }
    }

    public static ScriptAnalysis analyze(String script, boolean oracleMode) {
        List<String> statements = SqlScriptSplitter.split(script, oracleMode);
        List<StatementAnalysis> analyses = new ArrayList<>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            analyses.add(analyzeStatement(i + 1, statements.get(i), oracleMode));
        }
        return new ScriptAnalysis(analyses);
    }

    /**
     * 返回脚本中唯一可执行事务完成关键字（COMMIT/ROLLBACK）；否则返回空字符串。
     * 注释和空分句由共享 splitter/lexer 处理，不引入完整 SQL parser。
     */
    public static String transactionCompletionKeyword(String script, boolean oracleMode) {
        List<String> statements = SqlScriptSplitter.split(script, oracleMode);
        if (statements.size() != 1) return "";
        String statement = statements.getFirst();
        List<Token> tokens = lexicalTokens(statement, oracleMode);
        if (tokens.size() != 1) return "";
        Token token = tokens.getFirst();
        if (!"COMMIT".equals(token.word()) && !"ROLLBACK".equals(token.word())) return "";
        String before = statement.substring(0, token.offset());
        String after = statement.substring(token.offset() + token.word().length());
        return !SqlScriptSplitter.hasExecutableContent(before)
                && !SqlScriptSplitter.hasExecutableContent(after) ? token.word() : "";
    }

    private static StatementAnalysis analyzeStatement(int index, String sql, boolean oracleMode) {
        List<Token> lexicalTokens = lexicalTokens(sql, oracleMode);
        List<Token> tokens = lexicalTokens.stream().filter(token -> token.depth() == 0).toList();
        String first = tokens.isEmpty() ? "" : tokens.getFirst().word();
        boolean explainAnalyze = "EXPLAIN".equals(first) && lexicalTokens.stream()
                .anyMatch(token -> "ANALYZE".equals(token.word()));
        String effective = effectiveKeyword(tokens, explainAnalyze);
        boolean confirmedPlSqlBlock = oracleMode && "BEGIN".equals(effective)
                && tokens.stream().anyMatch(token -> "END".equals(token.word()));
        StatementKind kind = classify(effective, tokens, confirmedPlSqlBlock);
        EnumSet<Risk> risks = EnumSet.noneOf(Risk.class);
        int withIndex = cteWithIndex(tokens, first);
        if (withIndex >= 0) {
            CteSummary ctes = analyzeCteBodies(lexicalTokens, tokens.get(withIndex));
            if (ctes.write()) {
                kind = StatementKind.WRITE;
            } else if (ctes.unknown()) {
                kind = StatementKind.UNKNOWN;
            }
            if (ctes.missingWhere()) risks.add(Risk.MISSING_WHERE);
        }
        if (kind == StatementKind.UNKNOWN) risks.add(Risk.UNKNOWN_STATEMENT);
        if (("UPDATE".equals(effective) || "DELETE".equals(effective))
                && tokens.stream().noneMatch(token -> "WHERE".equals(token.word()))) {
            risks.add(Risk.MISSING_WHERE);
        }
        if ("DROP".equals(effective) || "TRUNCATE".equals(effective)) {
            risks.add(Risk.DESTRUCTIVE_DDL);
        }
        if (Set.of("BEGIN", "START", "SET", "SAVEPOINT", "RELEASE").contains(effective)
                && kind == StatementKind.TRANSACTION_CONTROL) {
            risks.add(Risk.SESSION_STATE_CONFLICT);
        }
        return new StatementAnalysis(index, sql, first, kind, risks);
    }

    private static int cteWithIndex(List<Token> tokens, String first) {
        if ("WITH".equals(first)) return 0;
        if (!"EXPLAIN".equals(first)) return -1;
        for (int i = 1; i < tokens.size(); i++) {
            if ("WITH".equals(tokens.get(i).word())) return i;
            if (EXPLAINABLE_KEYWORDS.contains(tokens.get(i).word())) return -1;
        }
        return -1;
    }

    private static CteSummary analyzeCteBodies(List<Token> lexicalTokens, Token initialWith) {
        List<WithScope> pending = new ArrayList<>();
        pending.add(new WithScope(initialWith, Integer.MAX_VALUE));
        CteSummary summary = new CteSummary(false, false, false);

        for (int cursor = 0; cursor < pending.size() && cursor < MAX_CTE_SCOPES; cursor++) {
            WithScope scope = pending.get(cursor);
            Token mainCommand = lexicalTokens.stream()
                    .filter(candidate -> candidate.offset() > scope.with().offset()
                            && candidate.offset() < scope.endOffset()
                            && candidate.depth() == scope.with().depth()
                            && CTE_COMMAND_KEYWORDS.contains(candidate.word()))
                    .findFirst()
                    .orElse(null);
            if (mainCommand == null) {
                summary = summary.merge(new CteSummary(false, true, true));
                continue;
            }

            boolean foundBody = false;
            for (Token token : lexicalTokens) {
                if (token.offset() <= scope.with().offset()) continue;
                if (token.offset() >= mainCommand.offset()) break;
                if (token.depth() != scope.with().depth() || !"AS".equals(token.word())) continue;
                foundBody = true;

                Token bodyCommand = lexicalTokens.stream()
                        .filter(candidate -> candidate.offset() > token.offset()
                                && candidate.offset() < mainCommand.offset()
                                && candidate.depth() > scope.with().depth())
                        .findFirst()
                        .orElse(null);
                if (bodyCommand == null) {
                    summary = summary.merge(new CteSummary(false, true, true));
                    continue;
                }
                int bodyEnd = lexicalTokens.stream()
                        .filter(candidate -> candidate.offset() > bodyCommand.offset()
                                && candidate.depth() <= scope.with().depth())
                        .mapToInt(Token::offset)
                        .findFirst()
                        .orElse(mainCommand.offset());
                if ("WITH".equals(bodyCommand.word())) {
                    if (pending.size() < MAX_CTE_SCOPES) {
                        pending.add(new WithScope(bodyCommand, bodyEnd));
                    } else {
                        summary = summary.merge(new CteSummary(false, true, true));
                    }
                } else {
                    summary = summary.merge(summarizeCommand(
                            lexicalTokens, bodyCommand, bodyEnd));
                }
            }
            if (!foundBody) summary = summary.merge(new CteSummary(false, true, true));
            summary = summary.merge(summarizeCommand(
                    lexicalTokens, mainCommand, scope.endOffset()));
        }

        return summary;
    }

    private static CteSummary summarizeCommand(List<Token> lexicalTokens, Token command,
                                               int endOffset) {
        StatementKind kind = classify(command.word(), List.of(command), false);
        boolean write = kind == StatementKind.WRITE;
        boolean unknown = kind != StatementKind.READ && kind != StatementKind.WRITE;
        boolean missingWhere = ("UPDATE".equals(command.word()) || "DELETE".equals(command.word()))
                && lexicalTokens.stream().noneMatch(candidate ->
                candidate.offset() > command.offset()
                        && candidate.offset() < endOffset
                        && candidate.depth() == command.depth()
                        && "WHERE".equals(candidate.word()));
        return new CteSummary(write, unknown, missingWhere);
    }

    private static String effectiveKeyword(List<Token> tokens, boolean explainAnalyze) {
        if (tokens.isEmpty()) return "";
        String first = tokens.getFirst().word();
        if ("WITH".equals(first)) return commandAfterWith(tokens, 1);
        if (!"EXPLAIN".equals(first)) return first;

        for (int i = 1; i < tokens.size(); i++) {
            String word = tokens.get(i).word();
            if (!EXPLAINABLE_KEYWORDS.contains(word)) continue;
            return "WITH".equals(word) ? commandAfterWith(tokens, i + 1) : word;
        }
        return explainAnalyze ? "" : "EXPLAIN";
    }

    private static String commandAfterWith(List<Token> tokens, int start) {
        Token command = commandTokenAfterWith(tokens, start);
        return command == null ? "WITH" : command.word();
    }

    private static Token commandTokenAfterWith(List<Token> tokens, int start) {
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (CTE_COMMAND_KEYWORDS.contains(token.word())) return token;
        }
        return null;
    }

    private static StatementKind classify(String effective, List<Token> tokens,
                                          boolean confirmedPlSqlBlock) {
        if (READ_KEYWORDS.contains(effective) || "EXPLAIN".equals(effective)) {
            return StatementKind.READ;
        }
        if (WRITE_KEYWORDS.contains(effective)) return StatementKind.WRITE;
        if (DDL_KEYWORDS.contains(effective)) return StatementKind.DDL;
        if (TRANSACTION_KEYWORDS.contains(effective)) {
            if ("BEGIN".equals(effective) && confirmedPlSqlBlock) return StatementKind.WRITE;
            return StatementKind.TRANSACTION_CONTROL;
        }
        return StatementKind.UNKNOWN;
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, LINE_COMMENT, BLOCK_COMMENT,
        DOLLAR_QUOTE, ORACLE_Q_QUOTE
    }

    private static List<Token> lexicalTokens(String sql, boolean oracleMode) {
        List<Token> tokens = new ArrayList<>();
        State state = State.NORMAL;
        int depth = 0;
        int blockCommentDepth = 0;
        String dollarDelimiter = null;
        char oracleClose = 0;
        boolean backslashEscapes = false;

        for (int i = 0; i < sql.length();) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            switch (state) {
                case NORMAL -> {
                    if (current == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        i += 2;
                    } else if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        blockCommentDepth = 1;
                        i += 2;
                    } else if (current == '\'') {
                        backslashEscapes = SqlLexicalRules.isPostgresEscapeStringQuote(
                                sql, i, oracleMode);
                        state = State.SINGLE_QUOTE;
                        i++;
                    } else if (current == '"') {
                        state = State.DOUBLE_QUOTE;
                        i++;
                    } else if (SqlLexicalRules.oracleQuoteAt(sql, i, oracleMode) != null) {
                        SqlLexicalRules.OracleQuote quote =
                                SqlLexicalRules.oracleQuoteAt(sql, i, oracleMode);
                        oracleClose = quote.closingDelimiter();
                        state = State.ORACLE_Q_QUOTE;
                        i += quote.prefixLength();
                    } else {
                        String delimiter = SqlLexicalRules.dollarDelimiterAt(
                                sql, i, oracleMode);
                        if (delimiter != null) {
                            dollarDelimiter = delimiter;
                            state = State.DOLLAR_QUOTE;
                            i += delimiter.length();
                        } else if (current == '(') {
                            depth++;
                            i++;
                        } else if (current == ')') {
                            if (depth > 0) depth--;
                            i++;
                        } else if (isWordStart(current)) {
                            int start = i++;
                            while (i < sql.length() && isWordPart(sql.charAt(i))) i++;
                            tokens.add(new Token(sql.substring(start, i).toUpperCase(Locale.ROOT),
                                    depth, start));
                        } else {
                            i++;
                        }
                    }
                }
                case SINGLE_QUOTE -> {
                    if (backslashEscapes && current == '\\' && i + 1 < sql.length()) {
                        i += 2;
                    } else if (current == '\'' && next == '\'') {
                        i += 2;
                    } else if (current == '\'') {
                        state = State.NORMAL;
                        backslashEscapes = false;
                        i++;
                    } else {
                        i++;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (current == '"' && next == '"') {
                        i += 2;
                    } else if (current == '"') {
                        state = State.NORMAL;
                        i++;
                    } else {
                        i++;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') state = State.NORMAL;
                    i++;
                }
                case BLOCK_COMMENT -> {
                    if (current == '/' && next == '*') {
                        blockCommentDepth++;
                        i += 2;
                    } else if (current == '*' && next == '/') {
                        blockCommentDepth--;
                        i += 2;
                        if (blockCommentDepth == 0) state = State.NORMAL;
                    } else {
                        i++;
                    }
                }
                case DOLLAR_QUOTE -> {
                    if (sql.startsWith(dollarDelimiter, i)) {
                        i += dollarDelimiter.length();
                        state = State.NORMAL;
                    } else {
                        i++;
                    }
                }
                case ORACLE_Q_QUOTE -> {
                    if (current == oracleClose && next == '\'') {
                        i += 2;
                        state = State.NORMAL;
                    } else {
                        i++;
                    }
                }
            }
        }
        return tokens;
    }

    private static boolean isWordStart(char value) {
        return value >= 0x80 || Character.isLetter(value) || value == '_';
    }

    private static boolean isWordPart(char value) {
        return SqlLexicalRules.isWordPart(value);
    }

    private record Token(String word, int depth, int offset) {}

    private record WithScope(Token with, int endOffset) {}

    private record CteSummary(boolean write, boolean unknown, boolean missingWhere) {
        CteSummary merge(CteSummary other) {
            return new CteSummary(write || other.write, unknown || other.unknown,
                    missingWhere || other.missingWhere);
        }
    }
}
