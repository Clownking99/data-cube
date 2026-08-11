package com.datacube.provider.postgres;

/** Conservative normalization for definitions returned by PostgreSQL catalog helpers. */
public final class PgSchemaDefinitionNormalizer {
    private PgSchemaDefinitionNormalizer() {
    }

    public static String normalize(String definition) {
        if (definition == null) return null;
        String normalized = definition.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (endsWithTopLevelSemicolon(normalized)) {
            normalized = normalized.substring(0, normalized.length() - 1).stripTrailing();
        }
        return normalized;
    }

    private static boolean endsWithTopLevelSemicolon(String text) {
        if (text.isEmpty() || text.charAt(text.length() - 1) != ';') return false;
        State state = State.NORMAL;
        String dollarTag = null;
        int blockDepth = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : '\0';
            switch (state) {
                case NORMAL -> {
                    if (current == '\'') {
                        state = hasEscapeStringPrefix(text, index)
                                ? State.ESCAPE_STRING : State.SINGLE_QUOTE;
                    } else if (current == '"') {
                        state = State.DOUBLE_QUOTE;
                    } else if (current == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        index++;
                    } else if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        blockDepth = 1;
                        index++;
                    } else if (current == '$') {
                        String candidate = dollarTagAt(text, index);
                        if (candidate != null) {
                            dollarTag = candidate;
                            state = State.DOLLAR_QUOTE;
                            index += candidate.length() - 1;
                        }
                    } else if (current == ';' && index == text.length() - 1) {
                        return true;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (current == '\'' && next == '\'') {
                        index++;
                    } else if (current == '\'') {
                        state = State.NORMAL;
                    }
                }
                case ESCAPE_STRING -> {
                    if (current == '\\' && next != '\0') {
                        index++;
                    } else if (current == '\'' && next == '\'') {
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
                case DOLLAR_QUOTE -> {
                    if (text.startsWith(dollarTag, index)) {
                        index += dollarTag.length() - 1;
                        state = State.NORMAL;
                        dollarTag = null;
                    }
                }
            }
        }
        return false;
    }

    private static String dollarTagAt(String text, int start) {
        if (start > 0 && identifierPart(text.charAt(start - 1))) return null;
        int end = text.indexOf('$', start + 1);
        if (end < 0) return null;
        if (end == start + 1) return "$$";
        for (int index = start + 1; index < end; index++) {
            char character = text.charAt(index);
            if (index == start + 1) {
                if (character != '_' && !Character.isLetter(character)) return null;
            } else if (character != '_' && !Character.isLetterOrDigit(character)) {
                return null;
            }
        }
        return text.substring(start, end + 1);
    }

    private static boolean identifierPart(char value) {
        return value == '_' || value == '$' || Character.isLetterOrDigit(value);
    }

    private static boolean hasEscapeStringPrefix(String text, int quoteIndex) {
        if (quoteIndex < 1) return false;
        char prefix = text.charAt(quoteIndex - 1);
        if (prefix != 'E' && prefix != 'e') return false;
        if (quoteIndex == 1) return true;
        char before = text.charAt(quoteIndex - 2);
        return before != '_' && before != '$' && !Character.isLetterOrDigit(before);
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, ESCAPE_STRING, DOUBLE_QUOTE,
        LINE_COMMENT, BLOCK_COMMENT, DOLLAR_QUOTE
    }
}
