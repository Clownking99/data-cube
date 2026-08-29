package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure, synchronized state model for local and database-backed result filtering. */
public final class ResultFilterState {
    private enum RequestMode { TAGGED, UNTAGGED }

    public enum DatabaseStatus { ORIGINAL, LOCAL_PREVIEW, APPLIED, DIRTY_AFTER_APPLY }

    public record DatabaseFilterRequest(
            String originalSql, QueryResult originalResult, List<FilterCondition> conditions) {
        public DatabaseFilterRequest {
            originalSql = Objects.requireNonNull(originalSql, "originalSql");
            originalResult = copyResult(Objects.requireNonNull(originalResult, "originalResult"));
            conditions = freezeConditions(conditions);
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
            originalResult = copyNullableResult(originalResult);
            activeResult = copyNullableResult(activeResult);
            conditions = freezeConditions(conditions);
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
    private InFlight inFlight;

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
        List<FilterCondition> candidateConditions = freezeConditions(value);
        DatabaseStatus candidateStatus = previewStatus(searchText, candidateConditions);
        List<Integer> indexes = indexesFor(activeResult, searchText, candidateConditions);

        conditions = candidateConditions;
        visibleRowIndexes = indexes;
        databaseStatus = candidateStatus;
        recoverableError = null;
        invalidateRequests();
    }

    /**
     * Creates the current untagged request lifecycle. Its matching no-token terminal method may
     * settle it exactly once; a later request or local mutation makes that terminal stale.
     */
    public synchronized DatabaseFilterRequest databaseRequest() {
        DatabaseFilterRequest request = requestSnapshot();
        inFlight = new InFlight(RequestMode.UNTAGGED, nextGeneration());
        return request;
    }

    /** Starts a tagged request, replacing any current untagged request. */
    public synchronized DatabaseRequest beginDatabaseRequest() {
        DatabaseFilterRequest request = requestSnapshot();
        long generation = nextGeneration();
        inFlight = new InFlight(RequestMode.TAGGED, generation);
        return new DatabaseRequest(generation, request);
    }

    /** Settles the current untagged request exactly once. */
    public synchronized void databaseApplied(QueryResult result) {
        requireMode(RequestMode.UNTAGGED);
        AppliedCandidate candidate = appliedCandidate(result);
        commitApplied(candidate);
        inFlight = null;
    }

    /** Applies a result only when it belongs to the current tagged request. */
    public synchronized boolean databaseApplied(long generation, QueryResult result) {
        if (!matches(RequestMode.TAGGED, generation)) return false;
        AppliedCandidate candidate = appliedCandidate(result);
        commitApplied(candidate);
        inFlight = null;
        return true;
    }

    /** Settles the current untagged request exactly once. */
    public synchronized void databaseFailed(String message) {
        requireMode(RequestMode.UNTAGGED);
        FailureCandidate candidate = failureCandidate(message);
        commitFailure(candidate);
        inFlight = null;
    }

    /** Records a failure only when it belongs to the current tagged request. */
    public synchronized boolean databaseFailed(long generation, String message) {
        if (!matches(RequestMode.TAGGED, generation)) return false;
        FailureCandidate candidate = failureCandidate(message);
        commitFailure(candidate);
        inFlight = null;
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

    private DatabaseFilterRequest requestSnapshot() {
        if (originalResult == null || conditions.isEmpty()) {
            throw new IllegalStateException("没有可应用的数据库筛选条件");
        }
        if (databaseUnavailableReason != null) {
            throw new IllegalStateException(databaseUnavailableReason);
        }
        return new DatabaseFilterRequest(originalSql, originalResult, conditions);
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

    private long nextGeneration() {
        return ++nextGeneration;
    }

    private void invalidateRequests() {
        nextGeneration();
        inFlight = null;
    }

    private boolean matches(RequestMode mode, long generation) {
        return inFlight != null && inFlight.mode() == mode && inFlight.generation() == generation;
    }

    private void requireMode(RequestMode mode) {
        if (inFlight == null || inFlight.mode() != mode) {
            throw new IllegalStateException("没有可结算的" + (mode == RequestMode.TAGGED ? "带 token" : "无 token") + "数据库筛选请求");
        }
    }

    private static List<Integer> indexesFor(
            QueryResult result, String search, List<FilterCondition> filters) {
        return result == null ? List.of() : LocalResultFilter.visibleRowIndexes(result, search, filters);
    }

    private static String failureMessage(String message) {
        return message == null ? "数据库筛选失败" : message;
    }

    private static QueryResult copyNullableResult(QueryResult source) {
        return source == null ? null : copyResult(source);
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
            List<Object> copiedRow = new ArrayList<>(Objects.requireNonNull(row, "result row").size());
            for (Object value : row) copiedRow.add(freezeValue(value));
            rows.add(Collections.unmodifiableList(copiedRow));
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

    private static List<FilterCondition> freezeConditions(List<FilterCondition> values) {
        Objects.requireNonNull(values, "conditions");
        List<FilterCondition> copied = new ArrayList<>(values.size());
        for (FilterCondition condition : values) {
            FilterCondition source = Objects.requireNonNull(condition, "condition");
            copied.add(new FilterCondition(source.columnIndex(), source.connector(), source.operator(),
                    freezeValue(source.value())));
        }
        return List.copyOf(copied);
    }

    private static Object freezeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal || value instanceof UUID
                || value instanceof Enum<?>) return value;
        if (value instanceof Timestamp timestamp) {
            Timestamp copied = new Timestamp(timestamp.getTime());
            copied.setNanos(timestamp.getNanos());
            return copied;
        }
        if (value instanceof java.sql.Date date) return new java.sql.Date(date.getTime());
        if (value instanceof Time time) return new Time(time.getTime());
        if (value instanceof java.util.Date date) return new java.util.Date(date.getTime());
        if (value instanceof Calendar calendar) return calendar.clone();
        if (value instanceof CharSequence text) return text.toString();
        if (value.getClass().isArray()) return freezeArray(value);
        if (value.getClass().getPackageName().startsWith("java.time")) return value;
        throw new IllegalArgumentException("不支持冻结的可变结果值类型: " + value.getClass().getName());
    }

    private static Object freezeArray(Object value) {
        int length = Array.getLength(value);
        Class<?> componentType = value.getClass().getComponentType();
        Object copied = Array.newInstance(componentType, length);
        if (componentType.isPrimitive()) {
            System.arraycopy(value, 0, copied, 0, length);
            return copied;
        }
        for (int index = 0; index < length; index++) {
            Array.set(copied, index, freezeValue(Array.get(value, index)));
        }
        return copied;
    }

    private static <T> List<T> immutableCopy(List<? extends T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private record InFlight(RequestMode mode, long generation) {
    }

    private record AppliedCandidate(QueryResult result, List<Integer> visibleIndexes) {
    }

    private record FailureCandidate(String message, List<Integer> visibleIndexes) {
    }
}
