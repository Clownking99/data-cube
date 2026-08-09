package com.datacube.sqleditor;

/** SQL 分句与安全分析共用的方言引号边界规则。 */
final class SqlLexicalRules {
    private SqlLexicalRules() {}

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

    static String dollarDelimiterAt(String sql, int offset) {
        if (offset < 0 || offset >= sql.length() || sql.charAt(offset) != '$') return null;
        int i = offset + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                || sql.charAt(i) == '_')) {
            i++;
        }
        if (i >= sql.length() || sql.charAt(i) != '$') return null;
        if (i > offset + 1 && Character.isDigit(sql.charAt(offset + 1))) return null;
        return sql.substring(offset, i + 1);
    }

    static boolean isWordPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    record OracleQuote(int prefixLength, char closingDelimiter) {}
}
