package com.datacube.provider.postgres;

import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.schemadiff.SchemaDiffEngine;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshotReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSchemaDiffCapabilityTest {
    private static final Set<ObjectType> SUPPORTED_TYPES = Set.of(
            ObjectType.TABLE, ObjectType.SEQUENCE, ObjectType.VIEW,
            ObjectType.MATERIALIZED_VIEW, ObjectType.FUNCTION, ObjectType.PROCEDURE,
            ObjectType.TRIGGER, ObjectType.TYPE);

    @Test
    void readerIsNewAndBoundToExactlyTheSuppliedConnectionWhileRendererIsReusable() {
        PgSchemaDiffCapability capability = new PgSchemaDiffCapability();
        AtomicInteger firstInspections = new AtomicInteger();
        AtomicInteger secondInspections = new AtomicInteger();
        Connection first = rejectingConnection(firstInspections);
        Connection second = rejectingConnection(secondInspections);

        SchemaSnapshotReader firstReader = capability.snapshotReader(first);
        SchemaSnapshotReader anotherFirstReader = capability.snapshotReader(first);
        SchemaSnapshotReader secondReader = capability.snapshotReader(second);

        assertNotSame(firstReader, anotherFirstReader);
        SQLException firstFailure = assertThrows(SQLException.class,
                () -> firstReader.read("safe", PgSchemaIdentifierNormalizer.schema("source"),
                        SqlExecutionOptions.defaults(1)));
        assertEquals("Snapshot requires an auto-commit connection", firstFailure.getMessage());
        assertEquals(1, firstInspections.get());
        assertEquals(0, secondInspections.get());
        assertThrows(SQLException.class,
                () -> secondReader.read("safe", PgSchemaIdentifierNormalizer.schema("source"),
                        SqlExecutionOptions.defaults(1)));
        assertEquals(1, secondInspections.get());

        SchemaChangeRenderer renderer = capability.changeRenderer();
        assertInstanceOf(PgSchemaChangeRenderer.class, renderer);
        assertSame(renderer, capability.changeRenderer());
    }

    @Test
    void supportedTopLevelTypesAreExactImmutableAndExcludeNestedTableStructures() {
        PgSchemaDiffCapability capability = new PgSchemaDiffCapability();

        assertEquals(SUPPORTED_TYPES, capability.supportedObjectTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> capability.supportedObjectTypes().add(ObjectType.INDEX));
        assertTrue(Set.of(ObjectType.PRIMARY_KEY, ObjectType.UNIQUE_CONSTRAINT,
                        ObjectType.FOREIGN_KEY, ObjectType.CHECK_CONSTRAINT, ObjectType.INDEX)
                .stream().noneMatch(capability.supportedObjectTypes()::contains));
    }

    @Test
    void postgresProviderRegistersOneImmutableSchemaDiffCapability() {
        PostgresProvider provider = new PostgresProvider();

        SchemaDiffCapability first = provider.schemaDiffCapability().orElseThrow();
        SchemaDiffCapability second = provider.schemaDiffCapability().orElseThrow();

        assertSame(first, second);
        assertInstanceOf(PgSchemaDiffCapability.class, first);
        assertTrue(provider.supports("jdbc:postgresql://localhost/example"));
        assertSame(provider.dialect(), provider.dialect());
        assertSame(provider.connectionFactory(), provider.connectionFactory());
    }

    @Test
    void unprovableHighDefinitionIsProjectedAsObjectSpecificLowInsteadOfThrowing() {
        ObjectKey key = new ObjectKey(ObjectType.VIEW,
                PgSchemaIdentifierNormalizer.object("app", "broken"), "");
        DefinitionObject broken = new DefinitionObject(key,
                "CREATE VIEW app.broken AS SELECT * FROM app.",
                "CREATE VIEW app.broken AS SELECT * FROM app.", Set.of(), DefinitionConfidence.HIGH);
        SortedMap<ObjectKey, com.datacube.spi.schemadiff.SchemaObject> objects = new TreeMap<>();
        objects.put(key, broken);
        SchemaSnapshot snapshot = new SchemaSnapshot(DbType.POSTGRESQL, "source",
                PgSchemaIdentifierNormalizer.schema("app"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), objects, "fingerprint");

        DefinitionObject projected = (DefinitionObject) new PgSchemaDiffCapability()
                .comparisonProjector().project(snapshot).comparisonObjects().values()
                .iterator().next();

        assertEquals(DefinitionConfidence.LOW, projected.confidence());
        assertTrue(projected.normalizedDefinition().startsWith("pg-manual-definition-v1:"));
        SchemaSnapshot empty = new SchemaSnapshot(DbType.POSTGRESQL, "target", snapshot.schema(),
                Instant.EPOCH, new SnapshotCompleteness(true, new TreeMap<>()),
                new TreeMap<>(), "empty");
        assertEquals(AutomationLevel.MANUAL_ONLY, new SchemaDiffEngine().compare(
                snapshot, empty, new PgSchemaDiffCapability().comparisonProjector())
                .differences().getFirst().automation());
    }

    private static Connection rejectingConnection(AtomicInteger inspections) {
        return (Connection) Proxy.newProxyInstance(
                PgSchemaDiffCapabilityTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> {
                        inspections.incrementAndGet();
                        yield false;
                    }
                    case "isWrapperFor" -> false;
                    case "unwrap" -> throw new SQLException("not a wrapper");
                    case "toString" -> "safe-connection-proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
