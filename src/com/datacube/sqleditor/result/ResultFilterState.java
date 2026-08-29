package com.datacube.sqleditor.result;

import com.datacube.spi.model.ImmutableResultValue;
import com.datacube.spi.model.QueryResult;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/** Pure, synchronized state model for local and database-backed result filtering. */
public final class ResultFilterState {
    private static final long NO_IN_FLIGHT = -1L;
    private static final List<FilterCondition> NO_CONDITIONS = new FrozenConditions(List.of());
    private static final List<FilterCondition> NO_REDACTED_CONDITIONS =
            new RedactedConditions(List.of());
    private static final String REDACTED_FILTER_VALUE = "<redacted>";

    public enum DatabaseStatus { ORIGINAL, LOCAL_PREVIEW, APPLIED, DIRTY_AFTER_APPLY }

    public record DatabaseFilterRequest(long generation,
            String originalSql, String effectiveSchema,
            QueryResult originalResult, List<FilterCondition> conditions) {
        public DatabaseFilterRequest {
            originalSql = Objects.requireNonNull(originalSql, "originalSql");
            effectiveSchema = normalizeSchema(effectiveSchema);
            originalResult = Objects.requireNonNull(originalResult, "originalResult");
            conditions = freezeConditions(conditions);
        }

        @Override
        public String toString() {
            return "DatabaseFilterRequest[generation=" + generation
                    + ", originalSql=<redacted>, effectiveSchema=<redacted>"
                    + ", originalResult=" + resultSummary(originalResult)
                    + ", conditionCount=" + conditions.size() + "]";
        }
    }

    /** An immutable database filter payload paired with its one-shot completion token. */
    public record DatabaseRequest(long generation, DatabaseFilterRequest filter) {
    }

    public record Snapshot(
            QueryResult originalResult, QueryResult activeResult, String originalSql, String effectiveSchema,
            String searchText, List<FilterCondition> conditions,
            List<Integer> visibleRowIndexes, DatabaseStatus databaseStatus,
            String databaseUnavailableReason, String recoverableError) {
        public Snapshot {
            conditions = redactConditions(conditions);
            visibleRowIndexes = List.copyOf(visibleRowIndexes);
        }

        @Override
        public String toString() {
            return "Snapshot[originalResult=" + resultSummary(originalResult)
                    + ", activeResult=" + resultSummary(activeResult)
                    + ", originalSql=<redacted>, effectiveSchema=<redacted>, searchText=<redacted>"
                    + ", conditionCount=" + conditions.size()
                    + ", visibleRowIndexes=" + visibleRowIndexes
                    + ", databaseStatus=" + databaseStatus
                    + ", databaseUnavailableReason=<redacted>"
                    + ", recoverableError=<redacted>]";
        }
    }

    private QueryResult originalResult;
    private QueryResult activeResult;
    private String originalSql;
    private String effectiveSchema;
    private String searchText = "";
    private List<FilterCondition> conditions = NO_CONDITIONS;
    private List<FilterCondition> snapshotConditions = NO_REDACTED_CONDITIONS;
    private List<Integer> visibleRowIndexes = List.of();
    private DatabaseStatus databaseStatus = DatabaseStatus.ORIGINAL;
    private String databaseUnavailableReason;
    private String recoverableError;
    private long nextGeneration;
    private long inFlightGeneration = NO_IN_FLIGHT;

    public synchronized void showOriginal(QueryResult result, String sql, String unavailableReason) {
        showOriginal(result, sql, null, unavailableReason);
    }

    public synchronized void showOriginal(
            QueryResult result, String sql, String schema, String unavailableReason) {
        QueryResult copied = Objects.requireNonNull(result, "result");
        String candidateSql = Objects.requireNonNull(sql, "sql");
        List<Integer> indexes = indexesFor(copied, "", List.of());

        originalResult = copied;
        activeResult = copied;
        originalSql = candidateSql;
        effectiveSchema = normalizeSchema(schema);
        searchText = "";
        conditions = NO_CONDITIONS;
        snapshotConditions = NO_REDACTED_CONDITIONS;
        visibleRowIndexes = indexes;
        databaseStatus = DatabaseStatus.ORIGINAL;
        databaseUnavailableReason = unavailableReason;
        recoverableError = null;
        invalidateRequests();
    }

    public synchronized void setSearchText(String value) {
        String candidateSearch = value == null ? "" : value;
        DatabaseStatus candidateStatus = previewStatus(candidateSearch, conditions);
        List<Integer> indexes = indexesFor(activeResult, candidateSearch, conditions);

        searchText = candidateSearch;
        visibleRowIndexes = indexes;
        databaseStatus = candidateStatus;
        recoverableError = null;
        invalidateRequests();
    }

    public synchronized void setConditions(List<FilterCondition> value) {
        List<FilterCondition> candidateConditions = freezeConditions(value);
        updateConditions(candidateConditions);
    }

    /** Appends a raw execution condition without reading it back through a public snapshot. */
    public synchronized void appendCondition(FilterCondition value) {
        List<FilterCondition> candidateConditions = new ArrayList<>(conditions);
        candidateConditions.add(Objects.requireNonNull(value, "condition"));
        updateConditions(freezeConditions(candidateConditions));
    }

    /** Replaces a raw execution condition without reading it back through a public snapshot. */
    public synchronized void replaceCondition(int index, FilterCondition value) {
        Objects.checkIndex(index, conditions.size());
        List<FilterCondition> candidateConditions = new ArrayList<>(conditions);
        candidateConditions.set(index, Objects.requireNonNull(value, "condition"));
        updateConditions(freezeConditions(candidateConditions));
    }

    /** Removes a raw execution condition without reading it back through a public snapshot. */
    public synchronized void removeCondition(int index) {
        Objects.checkIndex(index, conditions.size());
        List<FilterCondition> candidateConditions = new ArrayList<>(conditions);
        candidateConditions.remove(index);
        updateConditions(freezeConditions(candidateConditions));
    }

    private void updateConditions(List<FilterCondition> candidateConditions) {
        List<FilterCondition> candidateSnapshotConditions = redactConditions(candidateConditions);
        DatabaseStatus candidateStatus = previewStatus(searchText, candidateConditions);
        List<Integer> indexes = indexesFor(activeResult, searchText, candidateConditions);

        conditions = candidateConditions;
        snapshotConditions = candidateSnapshotConditions;
        visibleRowIndexes = indexes;
        databaseStatus = candidateStatus;
        recoverableError = null;
        invalidateRequests();
    }

    /** Creates a generation-bound database filter request. */
    public synchronized DatabaseFilterRequest databaseRequest() {
        long generation = nextGeneration + 1;
        DatabaseFilterRequest request = requestSnapshot(generation);
        nextGeneration = generation;
        inFlightGeneration = generation;
        return request;
    }

    /** Compatibility wrapper for callers that previously requested a separate request envelope. */
    public synchronized DatabaseRequest beginDatabaseRequest() {
        DatabaseFilterRequest request = databaseRequest();
        return new DatabaseRequest(request.generation(), request);
    }

    /**
     * @deprecated A completion without a generation cannot be protected against stale callbacks.
     * Use {@link #databaseApplied(long, QueryResult)}.
     */
    @Deprecated
    public synchronized void databaseApplied(QueryResult result) {
        throw missingGeneration();
    }

    /** Applies a result only when it belongs to the current request. */
    public synchronized boolean databaseApplied(long generation, QueryResult result) {
        if (generation != inFlightGeneration) return false;
        AppliedCandidate candidate = appliedCandidate(result);
        commitApplied(candidate);
        inFlightGeneration = NO_IN_FLIGHT;
        return true;
    }

    /**
     * @deprecated A completion without a generation cannot be protected against stale callbacks.
     * Use {@link #databaseFailed(long, String)}.
     */
    @Deprecated
    public synchronized void databaseFailed(String message) {
        throw missingGeneration();
    }

    /** Records a failure only when it belongs to the current request. */
    public synchronized boolean databaseFailed(long generation, String message) {
        if (generation != inFlightGeneration) return false;
        FailureCandidate candidate = failureCandidate(message);
        commitFailure(candidate);
        inFlightGeneration = NO_IN_FLIGHT;
        return true;
    }

    public synchronized void clearFilters() {
        List<Integer> indexes = indexesFor(originalResult, "", List.of());

        activeResult = originalResult;
        searchText = "";
        conditions = NO_CONDITIONS;
        snapshotConditions = NO_REDACTED_CONDITIONS;
        visibleRowIndexes = indexes;
        databaseStatus = DatabaseStatus.ORIGINAL;
        recoverableError = null;
        invalidateRequests();
    }

    public synchronized void clearAll() {
        originalResult = null;
        activeResult = null;
        originalSql = null;
        effectiveSchema = null;
        searchText = "";
        conditions = NO_CONDITIONS;
        snapshotConditions = NO_REDACTED_CONDITIONS;
        visibleRowIndexes = List.of();
        databaseStatus = DatabaseStatus.ORIGINAL;
        databaseUnavailableReason = null;
        recoverableError = null;
        invalidateRequests();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(originalResult, activeResult, originalSql, effectiveSchema, searchText,
                snapshotConditions, visibleRowIndexes, databaseStatus,
                databaseUnavailableReason, recoverableError);
    }

    private DatabaseFilterRequest requestSnapshot(long generation) {
        if (originalResult == null || conditions.isEmpty()) {
            throw new IllegalStateException("没有可应用的数据库筛选条件");
        }
        if (databaseUnavailableReason != null) {
            throw new IllegalStateException(databaseUnavailableReason);
        }
        return new DatabaseFilterRequest(
                generation, originalSql, effectiveSchema, originalResult, conditions);
    }

    private AppliedCandidate appliedCandidate(QueryResult result) {
        QueryResult copied = Objects.requireNonNull(result, "result");
        return new AppliedCandidate(copied, indexesFor(copied, searchText, conditions));
    }

    private FailureCandidate failureCandidate(String message) {
        return new FailureCandidate(failureMessage(message), indexesFor(activeResult, searchText, conditions));
    }

    private void commitApplied(AppliedCandidate candidate) {
        activeResult = candidate.result();
        visibleRowIndexes = candidate.visibleIndexes();
        databaseStatus = DatabaseStatus.APPLIED;
        recoverableError = null;
    }

    private void commitFailure(FailureCandidate candidate) {
        visibleRowIndexes = candidate.visibleIndexes();
        recoverableError = candidate.message();
    }

    private DatabaseStatus previewStatus(String candidateSearch, List<FilterCondition> candidateConditions) {
        boolean empty = candidateSearch.isEmpty() && candidateConditions.isEmpty();
        if (empty && activeResult == originalResult) return DatabaseStatus.ORIGINAL;
        if (databaseStatus == DatabaseStatus.APPLIED || databaseStatus == DatabaseStatus.DIRTY_AFTER_APPLY) {
            return DatabaseStatus.DIRTY_AFTER_APPLY;
        }
        return DatabaseStatus.LOCAL_PREVIEW;
    }

    private long nextGeneration() {
        return ++nextGeneration;
    }

    private void invalidateRequests() {
        nextGeneration();
        inFlightGeneration = NO_IN_FLIGHT;
    }

    private static IllegalStateException missingGeneration() {
        return new IllegalStateException("数据库筛选完成必须携带 generation");
    }

    private static List<Integer> indexesFor(
            QueryResult result, String search, List<FilterCondition> filters) {
        return result == null ? List.of() : LocalResultFilter.visibleRowIndexes(result, search, filters);
    }

    private static String failureMessage(String message) {
        return message == null ? "数据库筛选失败" : message;
    }

    private static String normalizeSchema(String schema) {
        if (schema == null) return null;
        String normalized = schema.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String resultSummary(QueryResult result) {
        if (result == null) return "null";
        return switch (result.kind) {
            case QUERY -> "QUERY[rows=" + result.rows.size()
                    + ", elapsedMillis=" + result.elapsedMillis
                    + ", truncated=" + result.truncated + "]";
            case UPDATE -> "UPDATE[updateCount=" + result.updateCount
                    + ", elapsedMillis=" + result.elapsedMillis + "]";
            case ERROR -> "ERROR[failureKind=" + result.failureKind
                    + ", elapsedMillis=" + result.elapsedMillis + "]";
        };
    }

    private static List<FilterCondition> freezeConditions(List<FilterCondition> values) {
        Objects.requireNonNull(values, "conditions");
        if (values instanceof FrozenConditions) return values;
        if (values.isEmpty()) return NO_CONDITIONS;
        List<FilterCondition> copied = new ArrayList<>(values.size());
        for (FilterCondition condition : values) {
            FilterCondition source = Objects.requireNonNull(condition, "condition");
            Object frozenValue = ImmutableResultValue.freeze(source.value());
            copied.add(frozenValue == source.value() ? source
                    : new FilterCondition(source.columnIndex(), source.connector(), source.operator(), frozenValue));
        }
        return new FrozenConditions(copied);
    }

    private static List<FilterCondition> redactConditions(List<FilterCondition> values) {
        Objects.requireNonNull(values, "conditions");
        if (values instanceof RedactedConditions) return values;
        if (values.isEmpty()) return NO_REDACTED_CONDITIONS;
        List<FilterCondition> redacted = new ArrayList<>(values.size());
        for (FilterCondition condition : values) {
            FilterCondition source = Objects.requireNonNull(condition, "condition");
            Object safeValue = source.operator().valueRequired() ? REDACTED_FILTER_VALUE : null;
            redacted.add(new FilterCondition(source.columnIndex(), source.connector(),
                    source.operator(), safeValue));
        }
        return new RedactedConditions(redacted);
    }

    private record AppliedCandidate(QueryResult result, List<Integer> visibleIndexes) {
    }

    private record FailureCandidate(String message, List<Integer> visibleIndexes) {
    }

    private static final class FrozenConditions extends AbstractList<FilterCondition>
            implements RandomAccess {
        private final List<FilterCondition> values;

        private FrozenConditions(List<FilterCondition> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public FilterCondition get(int index) {
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }
    }

    private static final class RedactedConditions extends AbstractList<FilterCondition>
            implements RandomAccess {
        private final List<FilterCondition> values;

        private RedactedConditions(List<FilterCondition> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public FilterCondition get(int index) {
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }
    }
}
