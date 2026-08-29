package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.List;
import java.util.Objects;

/** Pure state model for local and database-backed result filtering. */
public final class ResultFilterState {
    public enum DatabaseStatus { ORIGINAL, LOCAL_PREVIEW, APPLIED, DIRTY_AFTER_APPLY }

    public record DatabaseFilterRequest(
            String originalSql, QueryResult originalResult, List<FilterCondition> conditions) {
        public DatabaseFilterRequest {
            conditions = List.copyOf(conditions);
        }
    }

    /** A database filter request paired with the generation that owns its completion. */
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
    private long databaseGeneration;

    public synchronized void showOriginal(QueryResult result, String sql, String unavailableReason) {
        originalResult = Objects.requireNonNull(result, "result");
        activeResult = result;
        originalSql = Objects.requireNonNull(sql, "sql");
        searchText = "";
        conditions = List.of();
        databaseStatus = DatabaseStatus.ORIGINAL;
        databaseUnavailableReason = unavailableReason;
        recoverableError = null;
        invalidateDatabaseRequests();
        recompute();
    }

    public synchronized void setSearchText(String value) {
        searchText = value == null ? "" : value;
        markPreview();
        invalidateDatabaseRequests();
        recompute();
    }

    public synchronized void setConditions(List<FilterCondition> value) {
        conditions = List.copyOf(value);
        markPreview();
        invalidateDatabaseRequests();
        recompute();
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

    /** Starts an asynchronous request and returns its generation-bound immutable payload. */
    public synchronized DatabaseRequest beginDatabaseRequest() {
        return new DatabaseRequest(++databaseGeneration, databaseRequest());
    }

    public synchronized void databaseApplied(QueryResult result) {
        applyDatabaseResult(result);
        invalidateDatabaseRequests();
    }

    /** Applies a result only when it belongs to the most recently started request. */
    public synchronized boolean databaseApplied(long generation, QueryResult result) {
        if (generation != databaseGeneration) return false;
        applyDatabaseResult(result);
        return true;
    }

    public synchronized void databaseFailed(String message) {
        recoverableError = failureMessage(message);
        recompute();
    }

    /** Records a failure only when it belongs to the most recently started request. */
    public synchronized boolean databaseFailed(long generation, String message) {
        if (generation != databaseGeneration) return false;
        databaseFailed(message);
        return true;
    }

    public synchronized void clearFilters() {
        activeResult = originalResult;
        searchText = "";
        conditions = List.of();
        databaseStatus = DatabaseStatus.ORIGINAL;
        recoverableError = null;
        invalidateDatabaseRequests();
        recompute();
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
        invalidateDatabaseRequests();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(originalResult, activeResult, originalSql, searchText,
                conditions, visibleRowIndexes, databaseStatus,
                databaseUnavailableReason, recoverableError);
    }

    private void applyDatabaseResult(QueryResult result) {
        activeResult = Objects.requireNonNull(result, "result");
        databaseStatus = DatabaseStatus.APPLIED;
        recoverableError = null;
        recompute();
    }

    private void markPreview() {
        boolean empty = searchText.isBlank() && conditions.isEmpty();
        if (empty && activeResult == originalResult) {
            databaseStatus = DatabaseStatus.ORIGINAL;
        } else if (databaseStatus == DatabaseStatus.APPLIED
                || databaseStatus == DatabaseStatus.DIRTY_AFTER_APPLY) {
            databaseStatus = DatabaseStatus.DIRTY_AFTER_APPLY;
        } else {
            databaseStatus = DatabaseStatus.LOCAL_PREVIEW;
        }
        recoverableError = null;
    }

    private void invalidateDatabaseRequests() {
        databaseGeneration++;
    }

    private static String failureMessage(String message) {
        return message == null ? "数据库筛选失败" : message;
    }

    private void recompute() {
        visibleRowIndexes = activeResult == null ? List.of()
                : LocalResultFilter.visibleRowIndexes(activeResult, searchText, conditions);
    }
}
