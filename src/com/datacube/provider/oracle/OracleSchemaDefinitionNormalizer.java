package com.datacube.provider.oracle;

/** Conservative normalization for definitions returned by Oracle metadata helpers. */
public final class OracleSchemaDefinitionNormalizer {
    private OracleSchemaDefinitionNormalizer() {
    }

    public static String normalize(String definition) {
        if (definition == null) return null;
        String normalized = definition.replace("\r\n", "\n").replace('\r', '\n').strip();
        int slash = trailingSlashSeparator(normalized);
        if (slash >= 0) return normalized.substring(0, slash).stripTrailing();
        return normalized;
    }

    static boolean containsProviderStorageClause(String definition) {
        if (definition == null || definition.isBlank()) return false;
        String normalized = definition.replace("\r\n", "\n").replace('\r', '\n');
        String upper = normalized.toUpperCase(java.util.Locale.ROOT);
        String[] clauses = {"SEGMENT CREATION", " PCTFREE ", " PCTUSED ",
                " INITRANS ", " MAXTRANS ", " STORAGE ", " TABLESPACE "};
        for (String clause : clauses) {
            int from = 0;
            while (from < upper.length()) {
                int index = upper.indexOf(clause, from);
                if (index < 0) break;
                if (stateAt(normalized, index) == State.NORMAL) return true;
                from = index + clause.length();
            }
        }
        return false;
    }

    private static int trailingSlashSeparator(String text) {
        if (text.isEmpty() || text.charAt(text.length() - 1) != '/') return -1;
        int lineStart = text.lastIndexOf('\n') + 1;
        if (lineStart == 0 || !text.substring(lineStart).equals("/")) return -1;
        return stateAt(text, lineStart) == State.NORMAL ? lineStart : -1;
    }

    private static State stateAt(String text, int target) {
        State state = State.NORMAL;
        char alternativeClose = '\0';
        int blockDepth = 0;
        for (int index = 0; index < target; index++) {
            char current = text.charAt(index);
            char next = index + 1 < target ? text.charAt(index + 1) : '\0';
            switch (state) {
                case NORMAL -> {
                    if ((current == 'q' || current == 'Q') && next == '\''
                            && index + 2 < target) {
                        alternativeClose = closingDelimiter(text.charAt(index + 2));
                        state = State.ALTERNATIVE_QUOTE;
                        index += 2;
                    } else if (current == '\'') {
                        state = State.SINGLE_QUOTE;
                    } else if (current == '"') {
                        state = State.DOUBLE_QUOTE;
                    } else if (current == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        index++;
                    } else if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        blockDepth = 1;
                        index++;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (current == '\'' && next == '\'') {
                        index++;
                    } else if (current == '\'') {
                        state = State.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (current == '"' && next == '"') {
                        index++;
                    } else if (current == '"') {
                        state = State.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') state = State.NORMAL;
                }
                case BLOCK_COMMENT -> {
                    if (current == '/' && next == '*') {
                        blockDepth++;
                        index++;
                    } else if (current == '*' && next == '/') {
                        blockDepth--;
                        index++;
                        if (blockDepth == 0) state = State.NORMAL;
                    }
                }
                case ALTERNATIVE_QUOTE -> {
                    if (current == alternativeClose && next == '\'') {
                        state = State.NORMAL;
                        index++;
                    }
                }
            }
        }
        return state;
    }

    private static char closingDelimiter(char opener) {
        return switch (opener) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opener;
        };
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, LINE_COMMENT, BLOCK_COMMENT, ALTERNATIVE_QUOTE
    }
}
