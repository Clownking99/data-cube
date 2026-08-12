package com.datacube.provider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative owner retargeting for provider-deparsed SQL. */
public final class SqlScopeAwareOwnerRewriter {
    public enum Dialect { POSTGRESQL, ORACLE }

    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH",
            "UNION", "INTERSECT", "EXCEPT", "RETURNING", "CONNECT", "START",
            "MODEL", "MATCH_RECOGNIZE", "WINDOW", "QUALIFY", "SET", "VALUES",
            "WHEN", "ON", "USING", "JOIN", "LEFT", "RIGHT", "FULL", "INNER",
            "CROSS", "NATURAL", "OUTER", "INTO", "FROM");
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "AS", "SELECT", "WITH", "DELETE", "MERGE", "UPDATE", "INSERT",
            "ONLY", "LATERAL", "TABLE", "VIEW", "FUNCTION", "PROCEDURE",
            "TRIGGER", "TYPE", "PACKAGE", "BODY", "RETURN", "RETURNS",
            "LANGUAGE", "IS", "BEGIN", "END", "LOOP", "IF", "THEN", "ELSE");

    private SqlScopeAwareOwnerRewriter() {
    }

    public static String rewriteDefinition(
            String text, String sourceOwner, String replacement, Dialect dialect) {
        return rewrite(text, sourceOwner, replacement, dialect, false);
    }

    public static String rewriteFragment(
            String text, String sourceOwner, String replacement, Dialect dialect) {
        return rewrite(text, sourceOwner, replacement, dialect, false);
    }

    /** Retargets a caller-validated type/signature fragment where qualifiers cannot be aliases. */
    public static String rewriteStructuredIdentifierFragment(
            String text, String sourceOwner, String replacement, Dialect dialect) {
        return rewrite(text, sourceOwner, replacement, dialect, true);
    }

    public static String rewritePostgresRoutineDefinition(
            String text, String sourceOwner, String replacement) {
        DollarBody body = postgresDollarBody(text, false);
        if (body == null) {
            requireSupportedPostgresLanguage(tokenize(text, Dialect.POSTGRESQL));
            return rewriteDefinition(text, sourceOwner, replacement, Dialect.POSTGRESQL);
        }
        List<Token> outer = tokenize(text.substring(0, body.open())
                + text.substring(body.closeEnd()), Dialect.POSTGRESQL);
        requireSupportedPostgresLanguage(outer);
        String prefix = rewriteDefinition(text.substring(0, body.open()), sourceOwner,
                replacement, Dialect.POSTGRESQL);
        String rewrittenBody = rewriteRoutineBody(text.substring(body.bodyStart(), body.bodyEnd()),
                sourceOwner, replacement, Dialect.POSTGRESQL);
        String suffix = rewriteDefinition(text.substring(body.closeEnd()), sourceOwner,
                replacement, Dialect.POSTGRESQL);
        return prefix + body.tag() + rewrittenBody + body.tag() + suffix;
    }

    public static boolean supportsPostgresRoutineDefinition(String text, String sourceOwner) {
        try {
            rewritePostgresRoutineDefinition(text, sourceOwner, "\0pg-self-owner-check\0");
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    public static boolean containsOracleCallSpecLanguage(String text) {
        if (text == null || text.isBlank()) return false;
        List<Token> tokens = tokenize(text, Dialect.ORACLE);
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (keyword(tokens, index, "LANGUAGE")
                    && (keyword(tokens, index + 1, "JAVA")
                    || keyword(tokens, index + 1, "C"))) return true;
        }
        return false;
    }

    private static void requireSupportedPostgresLanguage(List<Token> outer) {
        String language = null;
        for (int index = 0; index < outer.size(); index++) {
            if (!outer.get(index).keyword("LANGUAGE")) continue;
            if (language != null || index + 1 >= outer.size()
                    || !outer.get(index + 1).identifier()) throw new IllegalArgumentException();
            language = outer.get(index + 1).value().toLowerCase(Locale.ROOT);
        }
        if (!Set.of("sql", "plpgsql").contains(language)) throw new IllegalArgumentException();
    }

    private static String rewrite(
            String text, String sourceOwner, String replacement, Dialect dialect,
            boolean fragment) {
        return rewrite(text, sourceOwner, replacement, dialect, fragment, false);
    }

    private static String rewriteRoutineBody(
            String text, String sourceOwner, String replacement, Dialect dialect) {
        return rewrite(text, sourceOwner, replacement, dialect, false, true);
    }

    private static String rewrite(
            String text, String sourceOwner, String replacement, Dialect dialect,
            boolean fragment, boolean routineBody) {
        if (text == null || sourceOwner == null || sourceOwner.isEmpty()
                || replacement == null || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException();
        }
        if (fragment && (text.indexOf(';') >= 0 || text.indexOf('\r') >= 0
                || text.indexOf('\n') >= 0)) throw new IllegalArgumentException();
        List<Token> tokens = tokenize(text, dialect);
        Map<Integer, Integer> parentheses = matchingParentheses(tokens);
        Scope root = scopes(tokens, parentheses, dialect, routineBody);
        Map<Integer, Scope> scopeByToken = mapScopes(root, tokens.size());
        Set<Integer> provenOwners = new HashSet<>();
        collectHeaderOwner(tokens, sourceOwner, dialect, provenOwners);
        if (dialect == Dialect.ORACLE && !routineBody) {
            collectOraclePlSqlBindings(root, tokens, parentheses,
                    sourceOwner, dialect, provenOwners);
        }
        if (dialect == Dialect.POSTGRESQL && routineBody) {
            collectPostgresRoutineBindings(root, tokens, dialect);
        }
        collectBindings(root, tokens, parentheses, sourceOwner, dialect, provenOwners);

        List<Replacement> replacements = dialect == Dialect.POSTGRESQL
                ? new ArrayList<>(postgresRegclassReplacements(
                        text, sourceOwner, replacement)) : new ArrayList<>();
        for (int index = 0; index + 2 < tokens.size(); index++) {
            Token owner = tokens.get(index);
            if (!owner.identifier() || !matches(owner, sourceOwner, dialect)
                    || !tokens.get(index + 1).symbol(".")
                    || !tokens.get(index + 2).identifier()) {
                continue;
            }
            if (provenOwners.contains(index)) {
                replacements.add(new Replacement(owner.start(), owner.end(), replacement));
                continue;
            }
            Scope scope = scopeByToken.getOrDefault(index, root);
            if (dialect == Dialect.ORACLE && visibleLabel(scope, owner, dialect)) {
                if (oracleLabelDeclaredChain(scope, tokens, index, dialect)) continue;
                if (oraclePackageCall(tokens, index)) {
                    replacements.add(new Replacement(owner.start(), owner.end(), replacement));
                    continue;
                }
                throw new IllegalArgumentException();
            }
            if (visibleBinding(scope, owner, dialect)
                    && !schemaFunctionCall(tokens, index, dialect, routineBody)) continue;
            if (fragment || provableNonRelationQualifier(
                    tokens, index, dialect, routineBody)) {
                replacements.add(new Replacement(owner.start(), owner.end(), replacement));
                continue;
            }
            throw new IllegalArgumentException();
        }
        if (replacements.isEmpty()) return text;
        replacements.sort(Comparator.comparingInt(Replacement::start));
        StringBuilder output = new StringBuilder(text.length());
        int cursor = 0;
        for (Replacement change : replacements) {
            output.append(text, cursor, change.start()).append(change.value());
            cursor = change.end();
        }
        return output.append(text, cursor, text.length()).toString();
    }

    private static List<Replacement> postgresRegclassReplacements(
            String text, String sourceOwner, String replacement) {
        List<Replacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'') {
                int end = singleQuoteEnd(text, index, escapeStringPrefix(text, index));
                int cast = end;
                while (cast < text.length() && Character.isWhitespace(text.charAt(cast))) cast++;
                int castEnd = postgresRegclassCastEnd(text, cast);
                if (castEnd >= 0) {
                    if (escapeStringPrefix(text, index)) throw new IllegalArgumentException();
                    String literal = text.substring(index + 1, end - 1).replace("''", "'");
                    RegclassName name = postgresRegclassName(literal);
                    if (name == null) throw new IllegalArgumentException();
                    if (name.schema() != null
                            && postgresIdentifierMatches(name.schema(), sourceOwner)) {
                        String rewritten = replacement + "." + name.object().raw();
                        replacements.add(new Replacement(index + 1, end - 1,
                                rewritten.replace("'", "''")));
                    }
                }
                index = end;
            } else if (current == '"') {
                index = quotedIdentifierEnd(text, index);
            } else if (current == '-' && at(text, index + 1) == '-') {
                int newline = text.indexOf('\n', index + 2);
                index = newline < 0 ? text.length() : newline + 1;
            } else if (current == '/' && at(text, index + 1) == '*') {
                index = blockCommentEnd(text, index, Dialect.POSTGRESQL);
            } else if (current == '$') {
                String tag = dollarTag(text, index);
                if (tag == null) index++;
                else {
                    int close = text.indexOf(tag, index + tag.length());
                    if (close < 0) throw new IllegalArgumentException();
                    index = close + tag.length();
                }
            } else index++;
        }
        return replacements;
    }

    private static int postgresRegclassCastEnd(String text, int start) {
        if (at(text, start) != ':' || at(text, start + 1) != ':') return -1;
        int index = start + 2;
        if (wordAt(text, index, "regclass")) return index + "regclass".length();
        if (!wordAt(text, index, "pg_catalog")) return -1;
        index += "pg_catalog".length();
        if (at(text, index++) != '.' || !wordAt(text, index, "regclass")) return -1;
        return index + "regclass".length();
    }

    private static boolean wordAt(String text, int start, String expected) {
        int end = start + expected.length();
        return start >= 0 && end <= text.length()
                && text.regionMatches(true, start, expected, 0, expected.length())
                && (end == text.length()
                        || !identifierPart(text.charAt(end), Dialect.POSTGRESQL));
    }

    private static RegclassName postgresRegclassName(String literal) {
        ParsedIdentifier first = postgresIdentifier(literal, 0);
        if (first == null) return null;
        int index = skipSpaces(literal, first.end());
        if (index == literal.length()) return new RegclassName(null, first);
        if (at(literal, index++) != '.') return null;
        ParsedIdentifier second = postgresIdentifier(literal, skipSpaces(literal, index));
        return second != null && skipSpaces(literal, second.end()) == literal.length()
                ? new RegclassName(first, second) : null;
    }

    private static ParsedIdentifier postgresIdentifier(String text, int start) {
        if (start >= text.length()) return null;
        if (text.charAt(start) == '"') {
            int end;
            try {
                end = quotedIdentifierEnd(text, start);
            } catch (IllegalArgumentException failure) {
                return null;
            }
            return new ParsedIdentifier(text.substring(start, end),
                    text.substring(start + 1, end - 1).replace("\"\"", "\""), true, end);
        }
        if (!identifierStart(text.charAt(start), Dialect.POSTGRESQL)) return null;
        int end = start + 1;
        while (end < text.length()
                && identifierPart(text.charAt(end), Dialect.POSTGRESQL)) end++;
        return new ParsedIdentifier(text.substring(start, end),
                text.substring(start, end).toLowerCase(Locale.ROOT), false, end);
    }

    private static boolean postgresIdentifierMatches(
            ParsedIdentifier identifier, String sourceOwner) {
        return identifier.quoted() ? identifier.value().equals(sourceOwner)
                : sourceOwner.equals(sourceOwner.toLowerCase(Locale.ROOT))
                        && identifier.value().equals(sourceOwner);
    }

    private static int skipSpaces(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static void collectHeaderOwner(
            List<Token> tokens, String source, Dialect dialect, Set<Integer> proven) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).keyword("CREATE")) continue;
            int cursor = index + 1;
            if (keyword(tokens, cursor, "OR") && keyword(tokens, cursor + 1, "REPLACE")) cursor += 2;
            if (dialect == Dialect.ORACLE && (keyword(tokens, cursor, "EDITIONABLE")
                    || keyword(tokens, cursor, "NONEDITIONABLE"))) cursor++;
            if (keyword(tokens, cursor, "MATERIALIZED")) cursor++;
            if (cursor >= tokens.size() || !Set.of(
                    "VIEW", "FUNCTION", "PROCEDURE", "TRIGGER", "TYPE", "PACKAGE")
                    .contains(tokens.get(cursor).keyword())) continue;
            cursor++;
            if (keyword(tokens, cursor, "BODY")) cursor++;
            if (cursor + 2 < tokens.size() && matches(tokens.get(cursor), source, dialect)
                    && tokens.get(cursor + 1).symbol(".")
                    && tokens.get(cursor + 2).identifier()) {
                proven.add(cursor);
            }
            return;
        }
    }

    private static boolean provableNonRelationQualifier(
            List<Token> tokens, int ownerIndex, Dialect dialect, boolean routineBody) {
        int objectIndex = ownerIndex + 2;
        if (objectIndex + 1 < tokens.size() && tokens.get(objectIndex + 1).keyword("AS")) return true;
        if (!routineBody && objectIndex + 2 < tokens.size()
                && tokens.get(objectIndex + 1).symbol(".")
                && tokens.get(objectIndex + 2).identifier()) return true;
        int routineBoundary = routineBodyBoundary(tokens);
        if (routineBoundary >= 0 && ownerIndex < routineBoundary) return true;
        return dialect == Dialect.POSTGRESQL && objectIndex + 1 < tokens.size()
                && tokens.get(objectIndex + 1).symbol("(");
    }

    private static boolean schemaFunctionCall(
            List<Token> tokens, int ownerIndex, Dialect dialect, boolean routineBody) {
        int objectIndex = ownerIndex + 2;
        return dialect == Dialect.POSTGRESQL && routineBody
                && objectIndex + 1 < tokens.size()
                && symbol(tokens, objectIndex + 1, "(");
    }

    private static int routineBodyBoundary(List<Token> tokens) {
        boolean routine = false;
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).keyword("FUNCTION") || tokens.get(index).keyword("PROCEDURE")) {
                routine = true;
            }
            if (routine && (tokens.get(index).keyword("AS") || tokens.get(index).keyword("IS")
                    || tokens.get(index).keyword("LANGUAGE"))) return index;
        }
        return -1;
    }

    private static Scope scopes(
            List<Token> tokens, Map<Integer, Integer> parentheses,
            Dialect dialect, boolean routineBody) {
        Scope root = new Scope(0, tokens.size(), null);
        if (dialect == Dialect.ORACLE) collectOracleBlockScopes(root, tokens, parentheses);
        if (dialect == Dialect.POSTGRESQL && routineBody) {
            collectPostgresBlockScopes(root, tokens);
        }
        for (Map.Entry<Integer, Integer> pair : parentheses.entrySet()) {
            int open = pair.getKey();
            int close = pair.getValue();
            if (open > close || open + 1 >= close) continue;
            if (!keyword(tokens, open + 1, "SELECT") && !keyword(tokens, open + 1, "WITH")) continue;
            Scope parent = innermost(root, open);
            parent.children.add(new Scope(open + 1, close, parent));
        }
        sortScopes(root);
        return root;
    }

    private static void collectPostgresBlockScopes(Scope root, List<Token> tokens) {
        Deque<Integer> starts = new ArrayDeque<>();
        Set<Integer> awaitingDeclarationBody = new HashSet<>();
        List<int[]> blocks = new ArrayList<>();
        boolean pendingLabel = false;
        for (int index = 0; index < tokens.size(); index++) {
            if (labelAt(tokens, index) != null) {
                starts.push(index);
                pendingLabel = true;
                index += 4;
            } else if (keyword(tokens, index, "DECLARE")) {
                if (!pendingLabel) starts.push(index);
                if (starts.isEmpty()) throw new IllegalArgumentException();
                awaitingDeclarationBody.add(starts.peek());
                pendingLabel = false;
            } else if (keyword(tokens, index, "BEGIN")) {
                if (pendingLabel) pendingLabel = false;
                else if (!starts.isEmpty()
                        && awaitingDeclarationBody.remove(starts.peek())) {
                    // BEGIN opens the body of the current DECLARE scope.
                } else starts.push(index);
            } else if (keyword(tokens, index, "END") && !starts.isEmpty()
                    && !keyword(tokens, index + 1, "IF")
                    && !keyword(tokens, index + 1, "LOOP")
                    && !keyword(tokens, index + 1, "CASE")) {
                int start = starts.pop();
                awaitingDeclarationBody.remove(start);
                int after = index + 1;
                if (after < tokens.size() && tokens.get(after).identifier()) after++;
                if (symbol(tokens, after, ";")) after++;
                blocks.add(new int[]{start, after});
                pendingLabel = false;
            }
        }
        if (!starts.isEmpty() || pendingLabel || !awaitingDeclarationBody.isEmpty()) {
            throw new IllegalArgumentException();
        }
        blocks.sort(Comparator.<int[]>comparingInt(block -> block[0])
                .thenComparing((left, right) -> Integer.compare(right[1], left[1])));
        for (int[] block : blocks) {
            Scope parent = innermost(root, block[0]);
            Scope child = new Scope(block[0], block[1], parent);
            child.declarationStart = declarationStart(tokens, block[0], block[1]);
            parent.children.add(child);
        }
    }

    private static int declarationStart(List<Token> tokens, int start, int end) {
        for (int index = start; index < end; index++) {
            if (keyword(tokens, index, "DECLARE")) return index + 1;
            if (keyword(tokens, index, "BEGIN")) return -1;
        }
        throw new IllegalArgumentException();
    }

    private static Token labelAt(List<Token> tokens, int index) {
        return symbol(tokens, index, "<") && symbol(tokens, index + 1, "<")
                && index + 4 < tokens.size() && tokens.get(index + 2).identifier()
                && symbol(tokens, index + 3, ">") && symbol(tokens, index + 4, ">")
                ? tokens.get(index + 2) : null;
    }

    private static void collectPostgresRoutineBindings(
            Scope scope, List<Token> tokens, Dialect dialect) {
        Token label = labelAt(tokens, scope.start);
        if (label != null) scope.bindings.add(identity(label, dialect));
        if (scope.declarationStart >= 0) {
            int end = routineDeclarationEnd(scope, tokens);
            int segment = scope.declarationStart;
            for (int index = segment; index <= end; index++) {
                if (index == end || symbol(tokens, index, ";")) {
                    if (segment < index && tokens.get(segment).identifier()) {
                        scope.bindings.add(identity(tokens.get(segment), dialect));
                    }
                    segment = index + 1;
                }
            }
        }
        for (Scope child : scope.children) {
            collectPostgresRoutineBindings(child, tokens, dialect);
        }
    }

    private static int routineDeclarationEnd(Scope scope, List<Token> tokens) {
        for (int index = scope.declarationStart; index < scope.end; index++) {
            if (innermost(scope, index) == scope && keyword(tokens, index, "BEGIN")) return index;
        }
        throw new IllegalArgumentException();
    }

    private static void collectOracleBlockScopes(
            Scope root, List<Token> tokens, Map<Integer, Integer> parentheses) {
        int routine = routineNoun(tokens);
        if (routine >= 0) {
            parseOracleRoutineScope(root, tokens, parentheses, routine, tokens.size(), true);
            return;
        }
        int container = oracleContainerNoun(tokens);
        if (container >= 0) {
            parseOracleContainerScope(root, tokens, parentheses, container, tokens.size());
            return;
        }
        int trigger = oracleDefinitionNoun(tokens, "TRIGGER");
        if (trigger >= 0) {
            parseOracleTriggerScope(root, tokens, parentheses, trigger, tokens.size());
            return;
        }
        collectLegacyOracleBlockScopes(root, tokens);
    }

    private static int oracleContainerNoun(List<Token> tokens) {
        int packageNoun = oracleDefinitionNoun(tokens, "PACKAGE");
        if (packageNoun >= 0 && keyword(tokens, packageNoun + 1, "BODY")) return packageNoun;
        int typeNoun = oracleDefinitionNoun(tokens, "TYPE");
        return typeNoun >= 0 && keyword(tokens, typeNoun + 1, "BODY") ? typeNoun : -1;
    }

    private static int oracleDefinitionNoun(List<Token> tokens, String noun) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!keyword(tokens, index, "CREATE")) continue;
            int cursor = index + 1;
            if (keyword(tokens, cursor, "OR") && keyword(tokens, cursor + 1, "REPLACE")) cursor += 2;
            if (keyword(tokens, cursor, "EDITIONABLE")
                    || keyword(tokens, cursor, "NONEDITIONABLE")) cursor++;
            return keyword(tokens, cursor, noun) ? cursor : -1;
        }
        return -1;
    }

    private static void parseOracleContainerScope(
            Scope root, List<Token> tokens, Map<Integer, Integer> parentheses,
            int noun, int limit) {
        int name = noun + 1;
        if (keyword(tokens, name, "BODY")) name++;
        if (name + 2 < limit && symbol(tokens, name + 1, ".")) name += 2;
        if (name >= limit || !tokens.get(name).identifier()) throw new IllegalArgumentException();
        Token containerName = tokens.get(name);
        int boundary = oracleRoutineBoundary(tokens, name + 1, limit, parentheses);
        if (boundary < 0) throw new IllegalArgumentException();
        root.declarationStart = boundary + 1;
        int segment = root.declarationStart;
        for (int index = segment; index < limit; index++) {
            int routine = oracleDeclaredRoutineAt(tokens, segment, index);
            if (index == segment && routine >= 0) {
                Scope child = new Scope(segment, limit, root);
                root.children.add(child);
                int after = parseOracleRoutineScope(
                        child, tokens, parentheses, routine, limit, false);
                index = after - 1;
                segment = after;
            } else if (keyword(tokens, index, "BEGIN")) {
                int end = oracleBlockEnd(tokens, index, limit);
                int after = oracleEndAfter(tokens, end, limit, containerName);
                requireOracleDefinitionEnd(tokens, after, limit);
                collectOracleBodyScopes(root, tokens, parentheses, index + 1, end);
                return;
            } else if (index == segment && keyword(tokens, index, "END")) {
                int after = oracleEndAfter(tokens, index, limit, containerName);
                requireOracleDefinitionEnd(tokens, after, limit);
                return;
            } else if (symbol(tokens, index, ";")) {
                segment = index + 1;
            }
        }
        throw new IllegalArgumentException();
    }

    private static int oracleDeclaredRoutineAt(
            List<Token> tokens, int segment, int cursor) {
        if (cursor != segment) return -1;
        int index = segment;
        if (keyword(tokens, index, "MEMBER") || keyword(tokens, index, "STATIC")
                || keyword(tokens, index, "MAP") || keyword(tokens, index, "ORDER")) index++;
        return keyword(tokens, index, "FUNCTION") || keyword(tokens, index, "PROCEDURE")
                ? index : -1;
    }

    private static void parseOracleTriggerScope(
            Scope root, List<Token> tokens, Map<Integer, Integer> parentheses,
            int trigger, int limit) {
        int name = trigger + 1;
        if (name + 2 < limit && symbol(tokens, name + 1, ".")) name += 2;
        if (name >= limit || !tokens.get(name).identifier()) throw new IllegalArgumentException();
        Token triggerName = tokens.get(name);
        int construct = -1;
        for (int index = name + 1; index < limit; index++) {
            if (keyword(tokens, index, "DECLARE") || keyword(tokens, index, "BEGIN")) {
                construct = index;
                break;
            }
        }
        if (construct < 0) throw new IllegalArgumentException();
        int begin;
        if (keyword(tokens, construct, "DECLARE")) {
            root.declarationStart = construct + 1;
            begin = oracleDeclarationBegin(root, tokens, parentheses, limit);
        } else begin = construct;
        int end = oracleBlockEnd(tokens, begin, limit);
        int after = oracleEndAfter(tokens, end, limit, triggerName);
        requireOracleDefinitionEnd(tokens, after, limit);
        collectOracleBodyScopes(root, tokens, parentheses, begin + 1, end);
    }

    private static void collectLegacyOracleBlockScopes(Scope root, List<Token> tokens) {
        Deque<Integer> declarations = new ArrayDeque<>();
        List<int[]> blocks = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).keyword("DECLARE")) {
                declarations.push(index);
            } else if (tokens.get(index).keyword("END") && !declarations.isEmpty()
                    && !keyword(tokens, index + 1, "IF")
                    && !keyword(tokens, index + 1, "LOOP")
                    && !keyword(tokens, index + 1, "CASE")) {
                blocks.add(new int[]{declarations.pop(), index + 1});
            }
        }
        if (!declarations.isEmpty()) throw new IllegalArgumentException();
        blocks.sort(Comparator.<int[]>comparingInt(block -> block[0])
                .thenComparing((left, right) -> Integer.compare(right[1], left[1])));
        for (int[] block : blocks) {
            Scope parent = innermost(root, block[0]);
            Scope child = new Scope(block[0], block[1], parent);
            child.declarationStart = block[0] + 1;
            parent.children.add(child);
        }
    }

    private static int parseOracleRoutineScope(
            Scope scope, List<Token> tokens, Map<Integer, Integer> parentheses,
            int routine, int limit, boolean root) {
        int name = routine + 1;
        if (name >= limit || !tokens.get(name).identifier()) throw new IllegalArgumentException();
        Token routineName = tokens.get(name);
        if (root && name + 2 < limit && symbol(tokens, name + 1, ".")) {
            name += 2;
            if (!tokens.get(name).identifier()) throw new IllegalArgumentException();
            routineName = tokens.get(name);
        }
        if (symbol(tokens, name + 1, "(")) {
            Integer close = parentheses.get(name + 1);
            if (close == null || close >= limit) throw new IllegalArgumentException();
            collectOracleParameters(scope, tokens, name + 2, close, Dialect.ORACLE);
        }
        int boundary = oracleRoutineBoundary(tokens, name + 1, limit, parentheses);
        if (boundary < 0) throw new IllegalArgumentException();
        scope.declarationStart = boundary + 1;
        int begin = oracleDeclarationBegin(scope, tokens, parentheses, limit);
        int end = oracleBlockEnd(tokens, begin, limit);
        int after = oracleEndAfter(tokens, end, limit, routineName);
        if (root) requireOracleDefinitionEnd(tokens, after, limit);
        else scope.end = after;
        collectOracleBodyScopes(scope, tokens, parentheses, begin + 1, end);
        return after;
    }

    private static int oracleRoutineBoundary(
            List<Token> tokens, int start, int limit, Map<Integer, Integer> parentheses) {
        for (int index = start; index < limit; index++) {
            if (symbol(tokens, index, "(")) {
                Integer close = parentheses.get(index);
                if (close == null || close >= limit) throw new IllegalArgumentException();
                index = close;
            } else if (keyword(tokens, index, "AS") || keyword(tokens, index, "IS")) {
                return index;
            } else if (symbol(tokens, index, ";")) {
                return -1;
            }
        }
        return -1;
    }

    private static int oracleDeclarationBegin(
            Scope scope, List<Token> tokens, Map<Integer, Integer> parentheses, int limit) {
        int segment = scope.declarationStart;
        for (int index = segment; index < limit; index++) {
            int routine = oracleDeclaredRoutineAt(tokens, segment, index);
            if (routine >= 0) {
                Scope child = new Scope(segment, limit, scope);
                scope.children.add(child);
                int after = parseOracleRoutineScope(
                        child, tokens, parentheses, routine, limit, false);
                index = after - 1;
                segment = after;
            } else if (keyword(tokens, index, "BEGIN")) {
                return index;
            } else if (symbol(tokens, index, ";")) {
                segment = index + 1;
            }
        }
        throw new IllegalArgumentException();
    }

    private static int oracleBlockEnd(List<Token> tokens, int begin, int limit) {
        Deque<String> constructs = new ArrayDeque<>();
        constructs.push("BLOCK");
        for (int index = begin + 1; index < limit; index++) {
            if (keyword(tokens, index, "BEGIN")) {
                constructs.push("BLOCK");
            } else if (keyword(tokens, index, "CASE")) {
                constructs.push("CASE");
            } else if (keyword(tokens, index, "END")
                    && !keyword(tokens, index + 1, "IF")
                    && !keyword(tokens, index + 1, "LOOP")) {
                if (constructs.isEmpty()) throw new IllegalArgumentException();
                if (keyword(tokens, index + 1, "CASE")) {
                    if (!constructs.peek().equals("CASE")) throw new IllegalArgumentException();
                    constructs.pop();
                    index++;
                } else {
                    constructs.pop();
                    if (constructs.isEmpty()) return index;
                }
            }
        }
        throw new IllegalArgumentException();
    }

    private static void requireOracleDefinitionEnd(
            List<Token> tokens, int after, int limit) {
        int cursor = after;
        if (symbol(tokens, cursor, "/")) cursor++;
        if (cursor != limit) throw new IllegalArgumentException();
    }

    private static int oracleEndAfter(
            List<Token> tokens, int end, int limit, Token expectedName) {
        int cursor = end + 1;
        if (cursor < limit && tokens.get(cursor).identifier()) {
            if (!oracleEndNameMatches(tokens.get(cursor), expectedName)) {
                throw new IllegalArgumentException();
            }
            cursor++;
        }
        if (!symbol(tokens, cursor, ";")) throw new IllegalArgumentException();
        return cursor + 1;
    }

    private static boolean oracleEndNameMatches(Token actual, Token expected) {
        if (actual.quoted() && expected.quoted()) return actual.value().equals(expected.value());
        if (!actual.quoted() && !expected.quoted()) {
            return actual.value().equalsIgnoreCase(expected.value());
        }
        Token quoted = actual.quoted() ? actual : expected;
        Token unquoted = actual.quoted() ? expected : actual;
        return quoted.value().equals(unquoted.value().toUpperCase(Locale.ROOT));
    }

    private static void collectOracleBodyScopes(
            Scope scope, List<Token> tokens, Map<Integer, Integer> parentheses,
            int start, int end) {
        for (int index = start; index < end; index++) {
            Token label = labelAt(tokens, index);
            int construct = label == null ? index : index + 5;
            if (construct >= end) throw new IllegalArgumentException();
            if (keyword(tokens, construct, "DECLARE") || keyword(tokens, construct, "BEGIN")) {
                Scope child = new Scope(index, end, scope);
                if (label != null) {
                    String labelIdentity = identity(label, Dialect.ORACLE);
                    child.bindings.add(labelIdentity);
                    child.labels.add(labelIdentity);
                }
                scope.children.add(child);
                int after = parseOracleAnonymousScope(
                        child, tokens, parentheses, construct, end);
                index = after - 1;
            } else if (label != null) {
                throw new IllegalArgumentException();
            }
        }
    }

    private static int parseOracleAnonymousScope(
            Scope scope, List<Token> tokens, Map<Integer, Integer> parentheses,
            int construct, int limit) {
        int begin;
        if (keyword(tokens, construct, "DECLARE")) {
            scope.declarationStart = construct + 1;
            begin = oracleDeclarationBegin(scope, tokens, parentheses, limit);
        } else if (keyword(tokens, construct, "BEGIN")) {
            begin = construct;
        } else {
            throw new IllegalArgumentException();
        }
        int end = oracleBlockEnd(tokens, begin, limit);
        int after = end + 1;
        if (after < limit && tokens.get(after).identifier()) after++;
        if (!symbol(tokens, after, ";")) throw new IllegalArgumentException();
        scope.end = after + 1;
        collectOracleBodyScopes(scope, tokens, parentheses, begin + 1, end);
        return scope.end;
    }

    private static void collectOraclePlSqlBindings(
            Scope root, List<Token> tokens, Map<Integer, Integer> parentheses,
            String source, Dialect dialect, Set<Integer> proven) {
        int routine = routineNoun(tokens);
        if (routine < 0 && oracleContainerNoun(tokens) < 0
                && oracleDefinitionNoun(tokens, "TRIGGER") < 0) return;
        collectOracleParameterTypes(tokens, parentheses, source, dialect, proven);
        collectOracleDeclarations(root, tokens, source, dialect, proven);
    }

    private static void collectOracleParameterTypes(
            List<Token> tokens, Map<Integer, Integer> parentheses,
            String source, Dialect dialect, Set<Integer> proven) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!keyword(tokens, index, "FUNCTION") && !keyword(tokens, index, "PROCEDURE")) {
                continue;
            }
            int name = index + 1;
            if (name + 2 < tokens.size() && symbol(tokens, name + 1, ".")) name += 2;
            if (!symbol(tokens, name + 1, "(")) continue;
            Integer close = parentheses.get(name + 1);
            if (close == null) throw new IllegalArgumentException();
            for (int token = name + 2; token + 2 < close; token++) {
                if (matches(tokens.get(token), source, dialect)
                        && symbol(tokens, token + 1, ".")
                        && tokens.get(token + 2).identifier()) proven.add(token);
            }
            index = close;
        }
    }

    private static int routineNoun(List<Token> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).keyword("CREATE")) continue;
            int cursor = index + 1;
            if (keyword(tokens, cursor, "OR") && keyword(tokens, cursor + 1, "REPLACE")) cursor += 2;
            if (keyword(tokens, cursor, "EDITIONABLE")
                    || keyword(tokens, cursor, "NONEDITIONABLE")) cursor++;
            return keyword(tokens, cursor, "FUNCTION") || keyword(tokens, cursor, "PROCEDURE")
                    ? cursor : -1;
        }
        return -1;
    }

    private static void collectOracleParameters(
            Scope scope, List<Token> tokens, int start, int end, Dialect dialect) {
        int segment = start;
        int depth = 0;
        for (int index = start; index <= end; index++) {
            if (index < end && symbol(tokens, index, "(")) depth++;
            else if (index < end && symbol(tokens, index, ")")) depth--;
            if (index == end || depth == 0 && symbol(tokens, index, ",")) {
                if (segment < index && tokens.get(segment).identifier()) {
                    scope.bindings.add(identity(tokens.get(segment), dialect));
                }
                segment = index + 1;
            }
        }
        if (depth != 0) throw new IllegalArgumentException();
    }

    private static void collectOracleDeclarations(
            Scope scope, List<Token> tokens, String source,
            Dialect dialect, Set<Integer> proven) {
        if (scope.declarationStart >= 0) {
            int end = oracleDeclarationEnd(scope, tokens);
            int segment = scope.declarationStart;
            for (int index = segment; index <= end; index++) {
                Scope current = index < end ? innermost(scope, index) : scope;
                if (current != scope) {
                    index = current.end - 1;
                    segment = current.end;
                    continue;
                }
                if (index == end || symbol(tokens, index, ";")) {
                    collectOracleDeclaration(scope, tokens, segment, index,
                            source, dialect, proven);
                    segment = index + 1;
                }
            }
        }
        for (Scope child : scope.children) {
            collectOracleDeclarations(child, tokens, source, dialect, proven);
        }
    }

    private static int oracleDeclarationEnd(Scope scope, List<Token> tokens) {
        for (int index = scope.declarationStart; index < scope.end; index++) {
            if (innermost(scope, index) == scope && keyword(tokens, index, "BEGIN")) return index;
            if (innermost(scope, index) == scope && keyword(tokens, index, "END")) return index;
        }
        throw new IllegalArgumentException();
    }

    private static void collectOracleDeclaration(
            Scope scope, List<Token> tokens, int start, int end,
            String source, Dialect dialect, Set<Integer> proven) {
        while (start < end && symbol(tokens, start, ";")) start++;
        if (start >= end || !tokens.get(start).identifier()) return;
        int name = start;
        if (keyword(tokens, start, "CURSOR") || keyword(tokens, start, "TYPE")
                || keyword(tokens, start, "SUBTYPE")) name++;
        if (name >= end || !tokens.get(name).identifier()) return;
        scope.bindings.add(identity(tokens.get(name), dialect));
        int typeStart = name + 1;
        if (keyword(tokens, typeStart, "CONSTANT")) typeStart++;
        if (typeStart + 2 < end && matches(tokens.get(typeStart), source, dialect)
                && symbol(tokens, typeStart + 1, ".")
                && tokens.get(typeStart + 2).identifier()) {
            proven.add(typeStart);
        }
    }

    private static void sortScopes(Scope scope) {
        scope.children.sort(Comparator.comparingInt(child -> child.start));
        scope.children.forEach(SqlScopeAwareOwnerRewriter::sortScopes);
    }

    private static Scope innermost(Scope scope, int tokenIndex) {
        for (Scope child : scope.children) {
            if (child.start <= tokenIndex && tokenIndex < child.end) return innermost(child, tokenIndex);
        }
        return scope;
    }

    private static Map<Integer, Scope> mapScopes(Scope root, int count) {
        Map<Integer, Scope> scopes = new HashMap<>();
        for (int index = 0; index < count; index++) scopes.put(index, innermost(root, index));
        return scopes;
    }

    private static void collectBindings(
            Scope scope, List<Token> tokens, Map<Integer, Integer> parentheses,
            String source, Dialect dialect, Set<Integer> proven) {
        collectCtes(scope, tokens, parentheses, dialect);
        for (int index = scope.start; index < scope.end; index++) {
            if (innermost(scope, index) != scope) continue;
            String keyword = tokens.get(index).keyword();
            boolean relation = Set.of("FROM", "JOIN", "UPDATE", "INTO", "USING")
                    .contains(keyword);
            if (keyword.equals("ON") && definitionKind(tokens, "TRIGGER")) relation = true;
            if (keyword.equals("DELETE") && keyword(tokens, index + 1, "FROM")) {
                index++;
                relation = true;
            } else if (keyword.equals("MERGE") && keyword(tokens, index + 1, "INTO")) {
                index++;
                relation = true;
            }
            if (!relation) continue;
            int next = parseRelation(scope, tokens, parentheses, index + 1,
                    source, dialect, proven);
            while (symbol(tokens, next, ",")) {
                next = parseRelation(scope, tokens, parentheses, next + 1,
                        source, dialect, proven);
            }
            if (next > index) index = next - 1;
        }
        for (Scope child : scope.children) {
            collectBindings(child, tokens, parentheses, source, dialect, proven);
        }
    }

    private static void collectCtes(
            Scope scope, List<Token> tokens, Map<Integer, Integer> parentheses, Dialect dialect) {
        for (int index = scope.start; index < scope.end; index++) {
            if (innermost(scope, index) != scope || !tokens.get(index).keyword("WITH")) continue;
            int cursor = index + 1;
            if (keyword(tokens, cursor, "RECURSIVE")) cursor++;
            while (cursor < scope.end && tokens.get(cursor).identifier()) {
                int probe = cursor + 1;
                if (symbol(tokens, probe, "(")) {
                    Integer close = parentheses.get(probe);
                    if (close == null) throw new IllegalArgumentException();
                    probe = close + 1;
                }
                if (!keyword(tokens, probe, "AS")) return;
                scope.bindings.add(identity(tokens.get(cursor), dialect));
                cursor++;
                if (symbol(tokens, cursor, "(")) {
                    Integer close = parentheses.get(cursor);
                    if (close == null) throw new IllegalArgumentException();
                    cursor = close + 1;
                }
                if (!keyword(tokens, cursor, "AS")) throw new IllegalArgumentException();
                cursor++;
                if (keyword(tokens, cursor, "NOT")) cursor++;
                if (keyword(tokens, cursor, "MATERIALIZED")) cursor++;
                if (!symbol(tokens, cursor, "(")) throw new IllegalArgumentException();
                Integer close = parentheses.get(cursor);
                if (close == null) throw new IllegalArgumentException();
                cursor = close + 1;
                if (!symbol(tokens, cursor, ",")) break;
                cursor++;
            }
            return;
        }
    }

    private static boolean definitionKind(List<Token> tokens, String kind) {
        for (int index = 0; index < Math.min(tokens.size(), 8); index++) {
            if (tokens.get(index).keyword(kind)) return true;
        }
        return false;
    }

    private static int parseRelation(
            Scope scope, List<Token> tokens, Map<Integer, Integer> parentheses, int start,
            String source, Dialect dialect, Set<Integer> proven) {
        int cursor = start;
        while (keyword(tokens, cursor, "ONLY") || keyword(tokens, cursor, "LATERAL")) cursor++;
        Token implicitAlias = null;
        if (symbol(tokens, cursor, "(")) {
            Integer close = parentheses.get(cursor);
            if (close == null) throw new IllegalArgumentException();
            cursor = close + 1;
        } else if (cursor < tokens.size() && tokens.get(cursor).identifier()) {
            Token first = tokens.get(cursor);
            implicitAlias = first;
            if (symbol(tokens, cursor + 1, ".")) {
                if (cursor + 2 >= tokens.size() || !tokens.get(cursor + 2).identifier()) {
                    throw new IllegalArgumentException();
                }
                if (matches(first, source, dialect)) proven.add(cursor);
                implicitAlias = tokens.get(cursor + 2);
                cursor += 3;
            } else {
                cursor++;
            }
            if (symbol(tokens, cursor, "(")) {
                Integer close = parentheses.get(cursor);
                if (close == null) throw new IllegalArgumentException();
                cursor = close + 1;
            }
        } else {
            throw new IllegalArgumentException();
        }
        if (keyword(tokens, cursor, "AS")) cursor++;
        if (cursor < tokens.size() && aliasToken(tokens.get(cursor))) {
            scope.bindings.add(identity(tokens.get(cursor), dialect));
            cursor++;
        } else if (implicitAlias != null) {
            scope.bindings.add(identity(implicitAlias, dialect));
        }
        return cursor;
    }

    private static boolean aliasToken(Token token) {
        return token.identifier() && !CLAUSE_KEYWORDS.contains(token.keyword())
                && !RESERVED_ALIASES.contains(token.keyword());
    }

    private static boolean visibleBinding(Scope scope, Token token, Dialect dialect) {
        String identity = identity(token, dialect);
        for (Scope current = scope; current != null; current = current.parent) {
            if (current.bindings.contains(identity)) return true;
        }
        return false;
    }

    private static boolean visibleLabel(Scope scope, Token token, Dialect dialect) {
        String identity = identity(token, dialect);
        for (Scope current = scope; current != null; current = current.parent) {
            if (current.labels.contains(identity)) return true;
        }
        return false;
    }

    private static boolean oracleLabelDeclaredChain(
            Scope scope, List<Token> tokens, int ownerIndex, Dialect dialect) {
        int bindingIndex = ownerIndex + 2;
        if (bindingIndex >= tokens.size() || !tokens.get(bindingIndex).identifier()) return false;
        String binding = identity(tokens.get(bindingIndex), dialect);
        for (Scope current = scope; current != null; current = current.parent) {
            if (current.bindings.contains(binding) && !current.labels.contains(binding)) return true;
        }
        return false;
    }

    private static boolean oraclePackageCall(List<Token> tokens, int ownerIndex) {
        int member = ownerIndex + 4;
        return member + 1 < tokens.size() && tokens.get(member).identifier()
                && symbol(tokens, member + 1, "(");
    }

    private static String identity(Token token, Dialect dialect) {
        if (token.quoted()) return "Q\0" + token.value();
        return "U\0" + (dialect == Dialect.POSTGRESQL
                ? token.value().toLowerCase(Locale.ROOT)
                : token.value().toUpperCase(Locale.ROOT));
    }

    private static boolean matches(Token token, String expected, Dialect dialect) {
        if (!token.identifier()) return false;
        if (token.quoted()) return token.value().equals(expected);
        String folded = dialect == Dialect.POSTGRESQL
                ? expected.toLowerCase(Locale.ROOT) : expected.toUpperCase(Locale.ROOT);
        return expected.equals(folded) && token.value().equalsIgnoreCase(expected);
    }

    private static Map<Integer, Integer> matchingParentheses(List<Token> tokens) {
        Map<Integer, Integer> pairs = new HashMap<>();
        Deque<Integer> opens = new ArrayDeque<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).symbol("(")) opens.push(index);
            if (!tokens.get(index).symbol(")")) continue;
            if (opens.isEmpty()) throw new IllegalArgumentException();
            int open = opens.pop();
            pairs.put(open, index);
            pairs.put(index, open);
        }
        if (!opens.isEmpty()) throw new IllegalArgumentException();
        return pairs;
    }

    private static List<Token> tokenize(String text, Dialect dialect) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (current == '-' && at(text, index + 1) == '-') {
                int newline = text.indexOf('\n', index + 2);
                index = newline < 0 ? text.length() : newline + 1;
            } else if (current == '/' && at(text, index + 1) == '*') {
                index = blockCommentEnd(text, index, dialect);
            } else if (current == '\'') {
                index = singleQuoteEnd(text, index, dialect == Dialect.POSTGRESQL
                        && escapeStringPrefix(text, index));
            } else if (current == '"') {
                int end = quotedIdentifierEnd(text, index);
                tokens.add(new Token(text.substring(index + 1, end - 1).replace("\"\"", "\""),
                        index, end, true, false));
                index = end;
            } else if (dialect == Dialect.POSTGRESQL && current == '$') {
                String tag = dollarTag(text, index);
                if (tag == null) {
                    tokens.add(Token.symbol("$", index));
                    index++;
                } else {
                    int close = text.indexOf(tag, index + tag.length());
                    if (close < 0) throw new IllegalArgumentException();
                    index = close + tag.length();
                }
            } else if (dialect == Dialect.ORACLE && alternativeQuoteAt(text, index)) {
                index = alternativeQuoteEnd(text, index);
            } else if (identifierStart(current, dialect)) {
                int end = index + 1;
                while (end < text.length() && identifierPart(text.charAt(end), dialect)) end++;
                tokens.add(new Token(text.substring(index, end), index, end, false, false));
                index = end;
            } else {
                if (current == '\0' || Character.isISOControl(current)
                        && current != '\r' && current != '\n' && current != '\t') {
                    throw new IllegalArgumentException();
                }
                tokens.add(Token.symbol(String.valueOf(current), index));
                index++;
            }
        }
        return List.copyOf(tokens);
    }

    private static int singleQuoteEnd(String text, int start, boolean escapes) {
        int index = start + 1;
        while (index < text.length()) {
            char current = text.charAt(index++);
            if (escapes && current == '\\' && index < text.length()) index++;
            else if (current == '\'') {
                if (index < text.length() && text.charAt(index) == '\'') index++;
                else return index;
            }
        }
        throw new IllegalArgumentException();
    }

    private static boolean escapeStringPrefix(String text, int quote) {
        return quote > 0 && (text.charAt(quote - 1) == 'E' || text.charAt(quote - 1) == 'e')
                && (quote == 1 || !identifierPart(text.charAt(quote - 2), Dialect.POSTGRESQL));
    }

    private static int quotedIdentifierEnd(String text, int start) {
        int index = start + 1;
        while (index < text.length()) {
            if (text.charAt(index++) != '"') continue;
            if (index < text.length() && text.charAt(index) == '"') index++;
            else return index;
        }
        throw new IllegalArgumentException();
    }

    private static int blockCommentEnd(String text, int start, Dialect dialect) {
        int depth = 1;
        int index = start + 2;
        while (index < text.length() && depth > 0) {
            if (dialect == Dialect.POSTGRESQL
                    && at(text, index) == '/' && at(text, index + 1) == '*') {
                depth++;
                index += 2;
            } else if (at(text, index) == '*' && at(text, index + 1) == '/') {
                depth--;
                index += 2;
            } else index++;
        }
        if (depth != 0) throw new IllegalArgumentException();
        return index;
    }

    private static String dollarTag(String text, int start) {
        int end = start + 1;
        if (at(text, end) == '$') return "$$";
        if (!identifierStart(at(text, end), Dialect.POSTGRESQL)) return null;
        while (end < text.length() && (text.charAt(end) == '_'
                || Character.isLetterOrDigit(text.charAt(end)))) end++;
        if (end >= text.length() || text.charAt(end) != '$') return null;
        return text.substring(start, end + 1);
    }

    private static DollarBody postgresDollarBody(String text, boolean required) {
        int index = 0;
        DollarBody found = null;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'') index = singleQuoteEnd(text, index, escapeStringPrefix(text, index));
            else if (current == '"') index = quotedIdentifierEnd(text, index);
            else if (current == '-' && at(text, index + 1) == '-') {
                int newline = text.indexOf('\n', index + 2);
                index = newline < 0 ? text.length() : newline + 1;
            } else if (current == '/' && at(text, index + 1) == '*') {
                index = blockCommentEnd(text, index, Dialect.POSTGRESQL);
            } else if (current == '$') {
                String tag = dollarTag(text, index);
                if (tag == null) {
                    index++;
                    continue;
                }
                int close = text.indexOf(tag, index + tag.length());
                if (close < 0 || found != null) throw new IllegalArgumentException();
                found = new DollarBody(tag, index, index + tag.length(), close,
                        close + tag.length());
                index = close + tag.length();
            } else index++;
        }
        if (found == null && required) throw new IllegalArgumentException();
        return found;
    }

    private static boolean alternativeQuoteAt(String text, int start) {
        return alternativeQuotePrefix(text, start) > 0;
    }

    private static int alternativeQuoteEnd(String text, int start) {
        int prefix = alternativeQuotePrefix(text, start);
        if (prefix == 0) throw new IllegalArgumentException();
        char delimiter = text.charAt(start + prefix);
        char close = switch (delimiter) {
            case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>';
            default -> delimiter;
        };
        int end = text.indexOf(String.valueOf(close) + '\'', start + 3);
        if (end < 0) throw new IllegalArgumentException();
        return end + 2;
    }

    private static int alternativeQuotePrefix(String text, int start) {
        if (start > 0 && identifierPart(at(text, start - 1), Dialect.ORACLE)) return 0;
        if ((at(text, start) == 'q' || at(text, start) == 'Q')
                && at(text, start + 1) == '\'' && start + 2 < text.length()) return 2;
        return (at(text, start) == 'n' || at(text, start) == 'N')
                && (at(text, start + 1) == 'q' || at(text, start + 1) == 'Q')
                && at(text, start + 2) == '\'' && start + 3 < text.length() ? 3 : 0;
    }

    private static boolean identifierStart(char value, Dialect dialect) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean identifierPart(char value, Dialect dialect) {
        return value == '_' || value == '$' || Character.isLetterOrDigit(value)
                || dialect == Dialect.ORACLE && value == '#';
    }

    private static boolean keyword(List<Token> tokens, int index, String value) {
        return index >= 0 && index < tokens.size() && tokens.get(index).keyword(value);
    }

    private static boolean symbol(List<Token> tokens, int index, String value) {
        return index >= 0 && index < tokens.size() && tokens.get(index).symbol(value);
    }

    private static char at(String text, int index) {
        return index >= 0 && index < text.length() ? text.charAt(index) : '\0';
    }

    private static final class Scope {
        private final int start;
        private int end;
        private final Scope parent;
        private final List<Scope> children = new ArrayList<>();
        private final Set<String> bindings = new HashSet<>();
        private final Set<String> labels = new HashSet<>();
        private int declarationStart = -1;

        private Scope(int start, int end, Scope parent) {
            this.start = start;
            this.end = end;
            this.parent = parent;
        }
    }

    private record Token(
            String value, int start, int end, boolean quoted, boolean symbol) {
        private static Token symbol(String value, int start) {
            return new Token(value, start, start + value.length(), false, true);
        }

        private boolean identifier() { return !symbol; }
        private boolean symbol(String expected) { return symbol && value.equals(expected); }
        private String keyword() { return quoted || symbol ? "" : value.toUpperCase(Locale.ROOT); }
        private boolean keyword(String expected) { return keyword().equals(expected); }
    }

    private record ParsedIdentifier(String raw, String value, boolean quoted, int end) {
    }

    private record RegclassName(ParsedIdentifier schema, ParsedIdentifier object) {
    }

    private record Replacement(int start, int end, String value) {
    }

    private record DollarBody(
            String tag, int open, int bodyStart, int bodyEnd, int closeEnd) {
    }
}
