package com.datacube.sqleditor;

import java.util.*;
import static com.datacube.sqleditor.SqlContextTokens.Kind;
import static com.datacube.sqleditor.SqlContextTokens.Token;

/** Deliberately bounded query context, not a validating SQL parser. Unknown sources never guess columns. */
public final class SqlCompletionContext {
    private static final Set<String> CLAUSES = Set.of("WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET",
            "FETCH", "FOR", "UNION", "EXCEPT", "INTERSECT", "RETURNING", "CONNECT", "START", "MODEL");
    private static final Set<String> RESERVED = new HashSet<>(CLAUSES);
    static { RESERVED.addAll(Set.of("SELECT", "FROM", "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER",
            "CROSS", "NATURAL", "ON", "USING", "AS", "LATERAL", "WITH", "WINDOW", "PARTITION", "PIVOT", "UNPIVOT",
            "NULL", "TRUE", "FALSE")); }
    public record Input(boolean allowed, int start, int end, String prefix, String qualifier) {}
    public record Relation(String schema, String table, List<String> columns, String notice) {
        public Relation { columns = List.copyOf(columns); }
        public boolean physical() { return schema != null && table != null && notice.isEmpty(); }
    }
    private record Scope(int lo, int hi, int depth) {}
    private SqlCompletionContext() {}

    /** Replacement start and qualifier respect quoted identifiers and suppress literal/comment completions. */
    public static Input input(String text, int caret, boolean oracle) {
        if (caret < 0 || caret > text.length() || text.length() > SqlCurrentStatement.MAX_CONTEXT_CHARS)
            return new Input(false, caret, caret, "", null);
        var scan = SqlContextTokens.scan(text.substring(0, caret), oracle);
        var all = scan.tokens();
        int start = caret;
        String prefix = "";
        if (!all.isEmpty()) {
            var last = all.getLast();
            if (last.end() == caret) {
                if (last.kind() == Kind.COMMENT || last.kind() == Kind.STRING)
                    return new Input(false, caret, caret, "", null);
                if (last.identifier()) { start = last.start(); prefix = text.substring(start, caret); }
            }
        }
        var tokens = all.stream().filter(t -> t.end() <= startOffset(all, caret) && t.kind() != Kind.COMMENT).toList();
        int end = tokens.size() - 1;
        String qualifier = null;
        if (end >= 1 && tokens.get(end).text().equals(".")) {
            int qEnd = end - 1, qStart = qEnd;
            if (tokens.get(qEnd).identifier()) {
                while (qStart >= 2 && tokens.get(qStart - 1).text().equals(".") && tokens.get(qStart - 2).identifier()) qStart -= 2;
                qualifier = text.substring(tokens.get(qStart).start(), tokens.get(qEnd).end());
            }
        }
        int replacementEnd = caret;
        if (start < caret && caret < text.length()) {
            for (var token : SqlContextTokens.scan(text, oracle).tokens())
                if (token.start() == start && token.identifier()) { replacementEnd = token.end(); break; }
        }
        return new Input(true, start, replacementEnd, prefix, qualifier);
    }
    private static int startOffset(List<Token> all, int caret) {
        if (!all.isEmpty() && all.getLast().end() == caret && all.getLast().identifier()) return all.getLast().start();
        return caret;
    }

    public static String identifier(String name, boolean oracle) {
        // Quote returned names consistently: database keyword sets vary with server version.
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static boolean matches(String candidate, String prefix) {
        boolean quoted = prefix.startsWith("\"");
        if (quoted && !candidate.startsWith("\"")) return false;
        String name = candidate.startsWith("\"") ? candidate.substring(1, candidate.length() - 1).replace("\"\"", "\"") : candidate;
        String typed = quoted ? prefix.substring(1).replace("\"\"", "\"") : prefix;
        return (quoted ? name.startsWith(typed) : name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT)))
                && !candidate.equals(prefix);
    }
    public static Relation resolve(String text, int caret, String qualifier, boolean oracle, String defaultSchema) {
        if (text.length() > SqlCurrentStatement.MAX_CONTEXT_CHARS) return unknown("文本过大，未分析补全作用域");
        if (!oracle && SqlCurrentStatement.unsupportedAtomicBody(text)) return unknown("BEGIN ATOMIC 函数体补全作用域未支持");
        var qScan = SqlContextTokens.scan(qualifier, oracle);
        var q = qScan.significant();
        if (!qScan.closed() || q.isEmpty() || !q.getFirst().identifier()) return unknown("限定符不完整");
        String key = q.getFirst().name(oracle);
        if (q.size() == 3 && q.get(1).text().equals(".") && q.get(2).identifier()) {
            var alias = resolve(text, caret, q.getFirst().text(), oracle, defaultSchema);
            if (alias.physical() || !alias.columns().isEmpty()) return unknown("复合字段路径未推断为 Schema");
            return physical(key, q.get(2).name(oracle));
        }
        if (q.size() != 1) return unknown("仅支持明确的 schema.table 或当前查询别名");
        SqlScriptSplitter.Fragment fragment = null;
        for (var candidate : SqlScriptSplitter.fragments(text, oracle))
            if (caret >= candidate.start() && caret <= candidate.end()) { fragment = candidate; break; }
        if (fragment == null || fragment.procedural()) return unknown("此处没有可确认的查询作用域");
        String statement = fragment.text(text);
        var scan = SqlContextTokens.scan(statement, oracle);
        var tokens = scan.significant();
        if (tokens.size() > 20_000) return unknown("语句超过补全分析预算");
        var parser = new Parser(tokens, oracle, defaultSchema, caret - fragment.start());
        var scopes = parser.enclosing(caret - fragment.start());
        Map<String, Relation> ctes = new HashMap<>();
        var visible = new ArrayList<Map<String, Relation>>();
        for (var scope : scopes) {
            if (!parser.correlated(scope)) visible.clear();
            ctes = new HashMap<>(ctes);
            parser.ctes(scope, ctes);
            visible.add(parser.sources(scope, ctes));
        }
        for (int i = visible.size() - 1; i >= 0; i--) {
            if (visible.get(i).containsKey("*")) return visible.get(i).get("*");
            var source = visible.get(i).get(key);
            if (source != null) return source;
        }
        // Unknown alias is not assumed to be a real table in another schema.
        return unknown("当前作用域未找到唯一的限定符来源");
    }

    private static Relation physical(String schema, String table) {
        return schema == null ? unknown("请明确 Schema 后读取表列") : new Relation(schema, table, List.of(), "");
    }
    private static Relation unknown(String why) { return new Relation(null, null, List.of(), why); }
    private static Relation projected(List<String> columns) {
        return columns.isEmpty() ? unknown("复杂或通配投影未推断列，请显式命名列") : new Relation(null, null, columns, "");
    }

    private static final class Parser {
        final List<Token> t; final boolean oracle; final String defaultSchema; final int caret;
        final Map<Integer, Integer> ends = new HashMap<>();
        Parser(List<Token> t, boolean oracle, String defaultSchema, int caret) {
            this.t = t; this.oracle = oracle; this.defaultSchema = defaultSchema; this.caret = caret;
            var stack = new ArrayDeque<Integer>();
            for (int i = 0; i < t.size(); i++) {
                if (symbol(i, "(")) stack.push(i);
                else if (symbol(i, ")") && !stack.isEmpty()) ends.put(stack.pop(), i);
            }
            while (!stack.isEmpty()) ends.put(stack.pop(), t.size());
        }
        boolean symbol(int i, String value) { return i >= 0 && i < t.size() && t.get(i).text().equals(value); }
        boolean word(int i, String value) { return i >= 0 && i < t.size() && t.get(i).is(value); }
        boolean id(int i) { return i >= 0 && i < t.size() && t.get(i).identifier() && (t.get(i).kind() == Kind.IDENTIFIER || (Character.isLetter(t.get(i).text().charAt(0)) || t.get(i).text().charAt(0) == '_') && !RESERVED.contains(t.get(i).text().toUpperCase(Locale.ROOT))); }
        int end(int i) { return ends.getOrDefault(i, t.size()); }
        boolean correlated(Scope scope) {
            if (scope.lo == 0) return true;
            int before = scope.lo - 2;
            if (word(before, "AS") || word(before, "MATERIALIZED")) return false;
            if (word(before, "LATERAL")) return true;
            if (word(before, "FROM") || word(before, "JOIN")) return false;
            if (symbol(before, ",")) {
                boolean inFrom = false;
                for (int i = 0; i < before; i++) if (t.get(i).depth() == scope.depth - 1) {
                    if (word(i, "FROM")) inFrom = true;
                    if (word(i, "SELECT") || t.get(i).kind() == Kind.WORD && CLAUSES.contains(t.get(i).text().toUpperCase(Locale.ROOT))) inFrom = false;
                }
                if (inFrom) return false;
            }
            return true;
        }
        List<Scope> enclosing(int caret) {
            var scopes = new ArrayList<Scope>(); scopes.add(new Scope(0, t.size(), 0));
            for (int i = 0; i < t.size(); i++) {
                if (!symbol(i, "(") || !(word(i + 1, "SELECT") || word(i + 1, "WITH"))) continue;
                int end = end(i);
                if (t.get(i).end() <= caret && (end == t.size() || t.get(end).start() >= caret)) {
                    if (scopes.size() >= 32) return List.of(new Scope(t.size(), t.size(), 0));
                    scopes.add(new Scope(i + 1, end, t.get(i).depth() + 1));
                }
            }
            return scopes;
        }
        void ctes(Scope scope, Map<String, Relation> ctes) {
            int i = scope.lo;
            if (!word(i, "WITH")) return;
            i++; boolean recursive = word(i, "RECURSIVE"); if (recursive) i++;
            while (i < scope.hi && id(i)) {
                int declaredAt = t.get(i).start();
                String name = t.get(i++).name(oracle);
                List<String> declared = List.of();
                if (symbol(i, "(")) { declared = columnList(i + 1, end(i)); i = end(i) + 1; }
                if (!word(i, "AS")) { ctes.put(name, unknown("CTE 定义不完整")); return; }
                i++;
                if (word(i, "NOT")) i++;
                if (word(i, "MATERIALIZED")) i++;
                if (!symbol(i, "(")) { ctes.put(name, unknown("CTE 定义不完整")); return; }
                int close = end(i);
                boolean inside = caret >= t.get(i).end() && (close == t.size() || caret <= t.get(close).start());
                Relation value = declared.isEmpty() ? projection(new Scope(i + 1, close, t.get(i).depth() + 1)) : projected(declared);
                if (close >= t.size() || declaredAt > caret || inside && (!recursive || declared.isEmpty()))
                    value = unknown("此 CTE 的列在当前位置尚不可确认");
                ctes.put(name, value);
                i = close + 1;
                if (!symbol(i, ",")) return;
                i++;
            }
        }
        List<String> columnList(int lo, int hi) {
            List<String> names = new ArrayList<>();
            for (int i = lo; i < hi; i++) {
                if ((i - lo) % 2 == 0) { if (!id(i)) return List.of(); names.add(identifier(t.get(i).name(oracle), oracle)); }
                else if (!symbol(i, ",")) return List.of();
            }
            return (hi - lo) % 2 == 1 ? names : List.of();
        }
        Relation projection(Scope scope) {
            for (int i = scope.lo; i < scope.hi; i++) if (t.get(i).depth() == scope.depth
                    && (word(i, "UNION") || word(i, "INTERSECT") || word(i, "EXCEPT")))
                return unknown("集合查询投影未推断");
            int select = -1, stop = scope.hi;
            for (int i = scope.lo; i < scope.hi; i++) if (t.get(i).depth() == scope.depth) {
                if (word(i, "SELECT")) { if (select >= 0) return unknown("集合查询投影未推断"); select = i; }
                else if (word(i, "FROM") && select >= 0) { stop = i; break; }
            }
            if (select < 0) return unknown("投影不是 SELECT");
            int lo = select + 1;
            if (word(lo, "DISTINCT") || word(lo, "ALL")) lo++;
            var columns = new ArrayList<String>();
            for (int i = lo; i <= stop; i++) if (i == stop || t.get(i).depth() == scope.depth && symbol(i, ",")) {
                String name = projectionName(lo, i, scope.depth);
                if (name == null) return unknown("复杂或通配投影未推断列，请显式命名列");
                columns.add(identifier(name, oracle)); lo = i + 1;
            }
            return projected(columns);
        }
        String projectionName(int lo, int hi, int depth) {
            if (hi <= lo) return null;
            if (hi - lo >= 3 && word(hi - 2, "AS") && id(hi - 1) && t.get(hi - 2).depth() == depth)
                return t.get(hi - 1).name(oracle);
            // Only bare or qualified column references are unambiguously named without AS.
            for (int i = lo; i < hi; i++) if ((i - lo) % 2 == 0 ? !id(i) : !symbol(i, ".")) return null;
            return (hi - lo) % 2 == 1 ? t.get(hi - 1).name(oracle) : null;
        }
        Map<String, Relation> sources(Scope scope, Map<String, Relation> ctes) {
            var sources = new HashMap<String, Relation>();
            boolean from = false; int selects = 0;
            for (int i = scope.lo; i < scope.hi; i++) {
                if (t.get(i).depth() != scope.depth) continue;
                if (word(i, "PIVOT") || word(i, "UNPIVOT") || word(i, "MATCH_RECOGNIZE"))
                    return Map.of("*", unknown("此扩展查询作用域未支持"));
                if (word(i, "SELECT")) selects++;
                if (t.get(i).kind() == Kind.WORD && CLAUSES.contains(t.get(i).text().toUpperCase(Locale.ROOT))) from = false;
                boolean relation = word(i, "FROM") || word(i, "JOIN") || from && symbol(i, ",");
                if (!relation) continue;
                from = true; int cursor = i + 1;
                if (word(cursor, "LATERAL") || word(cursor, "ONLY")) cursor++;
                Relation value; String table = null;
                if (symbol(cursor, "(")) {
                    int close = end(cursor);
                    value = projection(new Scope(cursor + 1, close, scope.depth + 1)); cursor = close + 1;
                } else if (id(cursor)) {
                    table = t.get(cursor++).name(oracle);
                    String schema = defaultSchema;
                    boolean qualified = symbol(cursor, ".");
                    if (qualified) {
                        if (!id(cursor + 1)) continue;
                        schema = table; table = t.get(cursor + 1).name(oracle); cursor += 2;
                    }
                    value = !qualified && ctes.containsKey(table) ? ctes.get(table) : physical(schema, table);
                    if (symbol(cursor, "(")) { value = unknown("表函数列未推断"); cursor = end(cursor) + 1; }
                    if (symbol(cursor, ".")) return Map.of("*", unknown("跨数据库限定符未支持"));
                } else continue;
                if (word(cursor, "AS")) cursor++;
                String alias = id(cursor) ? t.get(cursor++).name(oracle) : table;
                if (alias == null) continue;
                if (symbol(cursor, "(")) {
                    var names = columnList(cursor + 1, end(cursor));
                    // Alias column lists may rename only a prefix. Do not invent the remaining projection.
                    value = !value.physical() && names.size() == value.columns().size() && !names.isEmpty()
                            ? projected(names) : unknown("列别名列表无法完整确认");
                }
                if (sources.containsKey(alias)) sources.put(alias, unknown("限定符存在多个来源"));
                else sources.put(alias, value);
            }
            if (selects > 1) sources.replaceAll((key, value) -> unknown("集合查询作用域未推断"));
            return sources;
        }
    }
}
