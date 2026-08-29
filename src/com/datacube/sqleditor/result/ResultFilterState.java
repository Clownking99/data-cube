package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure, synchronized state model for local and database-backed result filtering. */
public final class ResultFilterState {
    private static final long NO_IN_FLIGHT = -1L;

    public enum DatabaseStatus { ORIGINAL, LOCAL_PREVIEW, APPLIED, DIRTY_AFTER_APPLY }

    public record DatabaseFilterRequest(
            String originalSql, QueryResult originalResult, List<FilterCondition> conditions) {
        public DatabaseFilterRequest {
            conditions = List.copyOf(conditions);
        }
    }

    /** An immutable database filter payload paired with its one-shot completion token. */
    public record DatabaseRequest(long generation, DatabaseFilterRequest filter) {
    }

    public record Snapshot(
            QueryResult originalResult, QueryResult activeResult, String originalSql,
            String searchText, List<FilterCondition> conditions,
            List<Integer> visibleRowIndexes, DatabaseStatus databaseStatus,
            String databaseUnavailableReason, String recoverableError) {
        public Snapshot {
            conditions = List.copyOf(conditions);
            visibleRowIndexes = List.copyOf(visibleRowIndexes);
        }
    }

    private QueryResult originalResult;
    private QueryResult activeResult;
    private String originalSql;
    private String searchText = "";
    private List<FilterCondition> conditions = List.of();
    private List<Integer> visibleRowIndexes = List.of();
    private DatabaseStatus databaseStatus = DatabaseStatus.ORIGINAL;
    private String databaseUnavailableReason;
    private String recoverableError;
    private long nextGeneration;
    private long inFlightGeneration = NO_IN_FLIGHT;
    private boolean generationProtocolUsed;

    public synchronized void showOriginal(QueryResult result, String sql, String unavailableReason) {
        QueryResult copied = copyResult(Objects.requireNonNull(result, "result"));
        String candidateSql = Objects.requireNonNull(sql, "sql");
        List<Integer> indexes = indexesFor(copied, "", List.of());

        originalResult = copied;
        activeResult = copied;
        originalSql = candidateSql;
        searchText = "";
        conditions = List.of();
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
        List<FilterCondition> candidateConditions = List.copyOf(value);
        DatabaseStatus candidateStatus = previewStatus(searchText, candidateConditions);
        List<Integer> indexes = indexesFor(activeResult, searchText, candidateConditions);

        conditions = candidateConditions;
        visibleRowIndexes = indexes;
        databaseStatus = candidateStatus;
        recoverableError = null;
        invalidateRequests();
    }

    public synchronized DatabaseFilterRequest databaseRequest() {
        if (originalResult == null || conditions.isEmpty()) {
            throw new IllegalStateException("没有可应用的数据库筛选条件");
        }
        if (databaseUnavailableReason != null) {
            throw new IllegalStateException(databaseUnavailableReason);
        }
        return new DatabaseFilterRequest(originalSql, originalResult, conditions);
    }

    /** Starts a request. Only the matching token may complete it, once. */
    public synchronized DatabaseRequest beginDatabaseRequest() {
        DatabaseFilterRequest request = databaseRequest();
        long generation = nextGeneration + 1;
        nextGeneration = generation;
        inFlightGeneration = generation;
        generationProtocolUsed = true;
        return new DatabaseRequest(generation, request);
    }

    /**
     * Compatibility entry point for synchronous callers. It is unavailable once a generation-aware
     * request has been started, so it cannot overwrite an asynchronously protected result.
     */
    public synchronized void databaseApplied(QueryResult result) {
        rejectUnsafeUntaggedCompletion();
        AppliedCandidate candidate = appliedCandidate(result);
        commitApplied(candidate);
    }

    /** Applies a result only when it belongs to the current in-flight request. */
    public synchronized boolean databaseApplied(long generation, QueryResult result) {
        if (generation != inFlightGeneration) return false;
        AppliedCandidate candidate = appliedCandidate(result);
        commitApplied(candidate);
        inFlightGeneration = NO_IN_FLIGHT;
        return true;
    }

    /** See {@link #databaseApplied(QueryResult)} for the compatibility safety rule. */
    public synchronized void databaseFailed(String message) {
        rejectUnsafeUntaggedCompletion();
        FailureCandidate candidate = failureCandidate(message);
        commitFailure(candidate);
    }

    /** Records a failure only when it belongs to the current in-flight request. */
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
        conditions = List.of();
        visibleRowIndexes = indexes;
        databaseStatus = DatabaseStatus.ORIGINAL;
        recoverableError = null;
        invalidateRequests();
    }

    public synchronized void clearAll() {
        originalResult = null;
        activeResult = null;
        originalSql = null;
        searchText = "";
        conditions = List.of();
        visibleRowIndexes = List.of();
        databaseStatus = DatabaseStatus.ORIGINAL;
        databaseUnavailableReason = null;
        recoverableError = null;
        invalidateRequests();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(originalResult, activeResult, originalSql, searchText,
                conditions, visibleRowIndexes, databaseStatus,
                databaseUnavailableReason, recoverableError);
    }

    private AppliedCandidate appliedCandidate(QueryResult result) {
        QueryResult copied = copyResult(Objects.requireNonNull(result, "result"));
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

    private void invalidateRequests() {
        nextGeneration++;
        inFlightGeneration = NO_IN_FLIGHT;
    }

    private void rejectUnsafeUntaggedCompletion() {
        if (generationProtocolUsed || inFlightGeneration != NO_IN_FLIGHT) {
            throw new IllegalStateException("异步数据库筛选必须使用 generation 完成请求");
        }
    }

    private static List<Integer> indexesFor(
            QueryResult result, String search, List<FilterCondition> filters) {
        return result == null ? List.of() : LocalResultFilter.visibleRowIndexes(result, search, filters);
    }

    private static String failureMessage(String message) {
        return message == null ? "数据库筛选失败" : message;
    }

    private static QueryResult copyResult(QueryResult source) {
        return switch (source.kind) {
            case QUERY -> copyQuery(source);
            case UPDATE -> QueryResult.update(source.elapsedMillis, source.updateCount);
            case ERROR -> copyError(source);
        };
    }

    private static QueryResult copyQuery(QueryResult source) {
        List<List<Object>> rows = new ArrayList<>(source.rows.size());
        for (List<Object> row : source.rows) {
            rows.add(immutableCopy(Objects.requireNonNull(row, "result row")));
        }
        QueryResult copied = QueryResult.queryWithMetadata(immutableCopy(source.resultColumns),
                Collections.unmodifiableList(rows), source.elapsedMillis, source.truncated);
        return source.columnComments.isEmpty() ? copied
                : copied.withColumnComments(immutableCopy(source.columnComments));
    }

    private static QueryResult copyError(QueryResult source) {
        QueryResult.FailureKind kind = source.failureKind;
        if (kind == QueryResult.FailureKind.CANCELLED) {
            return QueryResult.cancelled(source.errorMessage, source.elapsedMillis);
        }
        if (kind == QueryResult.FailureKind.TIMEOUT) {
            return QueryResult.timeout(source.errorMessage, source.elapsedMillis);
        }
        return QueryResult.error(source.errorMessage, source.elapsedMillis);
    }

    private static <T> List<T> immutableCopy(List<? extends T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private record AppliedCandidate(QueryResult result, List<Integer> visibleIndexes) {
    }

    private record FailureCandidate(String message, List<Integer> visibleIndexes) {
    }
}
