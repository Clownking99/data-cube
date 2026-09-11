package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;

/** One bounded display snapshot; owns no driver, result set or mutable table state. */
public record ResultCellPreview(int displayRow, int sourceRow, int column, String label,
                                String type, int jdbcType, String text, int displayLength,
                                boolean nullValue, boolean emptyString, boolean displayOnly,
                                boolean truncated, boolean metadataTruncated) {
    public static final int MAX_TEXT_LENGTH = 65_536;
    public static final int MAX_METADATA_LENGTH = 512;

    public static ResultCellPreview capture(QueryResult result, int sourceRow, int column, int displayRow) {
        return capture(result, sourceRow, column, displayRow, MAX_TEXT_LENGTH);
    }

    static ResultCellPreview capture(QueryResult result, int sourceRow, int column, int displayRow, int textLimit) {
        if (textLimit < 1 || textLimit > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Invalid preview text limit");
        if (result == null || result.kind != QueryResult.Kind.QUERY || displayRow < 0
                || sourceRow < 0 || sourceRow >= result.rows.size()
                || column < 0 || column >= result.resultColumns.size()
                || column >= result.rows.get(sourceRow).size())
            throw new IllegalArgumentException("No result cell at the requested position");
        Object value = result.rows.get(sourceRow).get(column);
        var metadata = result.resultColumns.get(column);
        String formatted = ResultValueFormatter.format(value);
        String text = prefix(formatted, textLimit);
        return new ResultCellPreview(displayRow + 1, sourceRow + 1, column + 1,
                metadataText(metadata.label()), metadataText(metadata.jdbcTypeName()), metadata.jdbcType(),
                text, formatted.length(), value == null, value instanceof String s && s.isEmpty(),
                !ResultExportValuePolicy.isCompleteScalar(value), text.length() < formatted.length(),
                metadata.label().length() > MAX_METADATA_LENGTH || metadata.jdbcTypeName().length() > MAX_METADATA_LENGTH);
    }

    private static String metadataText(String value) {
        return value.length() > MAX_METADATA_LENGTH ? prefix(value, MAX_METADATA_LENGTH) + "…" : value;
    }

    private static String prefix(String value, int limit) {
        int end = Math.min(value.length(), limit);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }

    @Override public String toString() {
        return "ResultCellPreview[row=" + sourceRow + ", column=" + column + ", truncated=" + truncated + "]";
    }
}
