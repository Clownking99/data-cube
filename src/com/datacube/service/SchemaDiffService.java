package com.datacube.service;

import com.datacube.schemadiff.SchemaDiffEngine;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Service-layer orchestration for online same-provider schema comparison. */
public final class SchemaDiffService {
    private static final String UNSUPPORTED_DATABASE =
            "Schema comparison requires one supported relational database type";
    private static final String MISSING_CAPABILITY =
            "Schema comparison is unavailable for this database type";
    private static final String CANCELLED = "Schema comparison cancelled";
    private static final String SNAPSHOT_FAILED = "Schema snapshot failed";
    private static final String COMPARISON_FAILED = "Schema comparison failed";

    private final ConnectionManager connections;
    private final SchemaDiffEngine engine;

    public SchemaDiffService(ConnectionManager connections) {
        this(connections, new SchemaDiffEngine());
    }

    SchemaDiffService(ConnectionManager connections, SchemaDiffEngine engine) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public CompletionStage<SchemaDiffResult> compare(
            SchemaDiffRequest request, SchemaDeploymentControl control) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(control, "control");
        SchemaDiffRequest admitted = new SchemaDiffRequest(
                request.sourceConfig(), request.sourceSchema(),
                request.targetConfig(), request.targetSchema());
        ConnConfig source = admitted.sourceConfig();
        ConnConfig target = admitted.targetConfig();
        if (source.type() == DbType.REDIS
                || target.type() == DbType.REDIS
                || source.type() != target.type()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(UNSUPPORTED_DATABASE));
        }
        if (control.cancellationRequested()) {
            return CompletableFuture.failedFuture(new CancellationException(CANCELLED));
        }
        DatabaseProvider provider = connections.provider(admitted.sourceConfig());
        if (provider.type() != source.type()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(MISSING_CAPABILITY));
        }
        SchemaDiffCapability capability = provider.schemaDiffCapability().orElse(null);
        if (capability == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(MISSING_CAPABILITY));
        }

        CompletableFuture<SchemaDiffResult> settlement = new CompletableFuture<>();
        ExecutorService scope = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("schema-diff-reader-", 0).factory());
        AtomicReference<Throwable> primaryReadFailure = new AtomicReference<>();
        ReadOperation sourceRead = new ReadOperation(
                connections, source, admitted.sourceSchema(), provider, capability, control);
        ReadOperation targetRead = new ReadOperation(
                connections, target, admitted.targetSchema(), provider, capability, control);
        CompletableFuture<SchemaSnapshot> sourceFuture =
                CompletableFuture.supplyAsync(sourceRead::readUnchecked, scope);
        CompletableFuture<SchemaSnapshot> targetFuture =
                CompletableFuture.supplyAsync(targetRead::readUnchecked, scope);
        watchFailure(sourceFuture, primaryReadFailure, control);
        watchFailure(targetFuture, primaryReadFailure, control);
        settlement.whenComplete((ignored, failure) -> {
            if (settlement.isCancelled()) control.cancel();
        });
        Thread.ofVirtual().name("schema-diff-coordinator").start(() -> {
            try (scope) {
                CompletableFuture.allOf(sourceFuture, targetFuture).handle((ignored, failure) -> null).join();
                if (primaryReadFailure.get() != null) {
                    settlement.completeExceptionally(new IllegalStateException(SNAPSHOT_FAILED));
                } else if (control.cancellationRequested()) {
                    settlement.completeExceptionally(
                            new java.util.concurrent.CompletionException(
                                    new CancellationException(CANCELLED)));
                } else {
                    try {
                        settlement.complete(engine.compare(sourceFuture.join(), targetFuture.join()));
                    } catch (RuntimeException invalidSnapshots) {
                        settlement.completeExceptionally(new IllegalStateException(COMPARISON_FAILED));
                    }
                }
            } finally {
                sourceRead.close();
                targetRead.close();
            }
        });
        return settlement;
    }

    private static void watchFailure(
            CompletableFuture<?> future,
            AtomicReference<Throwable> primaryReadFailure,
            SchemaDeploymentControl control) {
        future.whenComplete((ignored, failure) -> {
            if (failure == null || control.cancellationRequested()) return;
            primaryReadFailure.compareAndSet(null, failure);
            control.cancel();
        });
    }

    private static final class ReadOperation {
        private final ConnectionManager connections;
        private final ConnConfig config;
        private final QualifiedName schema;
        private final DatabaseProvider provider;
        private final SchemaDiffCapability capability;
        private final SchemaDeploymentControl parentControl;
        private final SqlExecutionControl sqlControl = new SqlExecutionControl();
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final SchemaDeploymentControl.Registration registration;

        private ReadOperation(
                ConnectionManager connections,
                ConnConfig config,
                QualifiedName schema,
                DatabaseProvider provider,
                SchemaDiffCapability capability,
                SchemaDeploymentControl parentControl) {
            this.connections = connections;
            this.config = config;
            this.schema = schema;
            this.provider = provider;
            this.capability = capability;
            this.parentControl = parentControl;
            this.registration = parentControl.register(this::cancel);
        }

        private SchemaSnapshot readUnchecked() {
            try {
                return read();
            } catch (SQLException failure) {
                throw new IllegalStateException(SNAPSHOT_FAILED);
            }
        }

        private SchemaSnapshot read() throws SQLException {
            ensureNotCancelled();
            Connection opened = connections.openDedicated(config, provider);
            if (!connection.compareAndSet(null, opened)) {
                closeDirect(opened);
                throw new SQLException(SNAPSHOT_FAILED);
            }
            try {
                ensureNotCancelled();
                try {
                    opened.setReadOnly(true);
                } catch (SQLException unsupported) {
                    // Best effort only; the reader remains catalog-only and the connection is owned.
                }
                ensureNotCancelled();
                int timeout = ConnectionSafetyOptions.from(config).queryTimeoutSeconds();
                return capability.snapshotReader(opened).read(
                        config.id(), schema, new SqlExecutionOptions(0, timeout, sqlControl));
            } finally {
                closeConnection();
            }
        }

        private void ensureNotCancelled() throws SQLException {
            if (parentControl.cancellationRequested() || sqlControl.cancellationRequested()) {
                throw new SQLException(CANCELLED);
            }
        }

        private void cancel() throws SQLException {
            SQLException cancellationFailure = null;
            try {
                sqlControl.cancel();
            } catch (SQLException failure) {
                cancellationFailure = failure;
            }
            try {
                closeConnection();
            } catch (SQLException closeFailure) {
                if (cancellationFailure == null) cancellationFailure = closeFailure;
                else cancellationFailure.addSuppressed(closeFailure);
            }
            if (cancellationFailure != null) throw cancellationFailure;
        }

        private void close() {
            registration.close();
            try {
                closeConnection();
            } catch (SQLException ignored) {
                // Snapshot failure remains terminal; the connection has still received close once.
            }
        }

        private void closeConnection() throws SQLException {
            Connection owned = connection.getAndSet(null);
            if (owned != null) owned.close();
        }

        private static void closeDirect(Connection connection) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // No owner can retain this rejected connection.
            }
        }
    }
}
