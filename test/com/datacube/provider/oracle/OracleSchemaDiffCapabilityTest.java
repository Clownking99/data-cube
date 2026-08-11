package com.datacube.provider.oracle;

import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshotReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaDiffCapabilityTest {
    private static final Set<ObjectType> SUPPORTED_TYPES = Set.of(
            ObjectType.TABLE, ObjectType.SEQUENCE, ObjectType.VIEW,
            ObjectType.MATERIALIZED_VIEW, ObjectType.FUNCTION, ObjectType.PROCEDURE,
            ObjectType.TRIGGER, ObjectType.PACKAGE_SPEC, ObjectType.PACKAGE_BODY,
            ObjectType.TYPE);

    @Test
    void readerIsFreshAndBoundToExactSuppliedConnectionWhileRendererIsReusable() {
        OracleSchemaDiffCapability capability = new OracleSchemaDiffCapability();
        AtomicInteger firstPrepares = new AtomicInteger();
        AtomicInteger secondPrepares = new AtomicInteger();
        Connection first = rejectingConnection(firstPrepares);
        Connection second = rejectingConnection(secondPrepares);

        SchemaSnapshotReader firstReader = capability.snapshotReader(first);
        SchemaSnapshotReader anotherFirstReader = capability.snapshotReader(first);
        SchemaSnapshotReader secondReader = capability.snapshotReader(second);

        assertNotSame(firstReader, anotherFirstReader);
        SQLException firstFailure = assertThrows(SQLException.class,
                () -> firstReader.read("safe-connection",
                        OracleSchemaIdentifierNormalizer.schema("Source"),
                        SqlExecutionOptions.defaults(1)));
        assertEquals("Snapshot metadata failed", firstFailure.getMessage());
        assertEquals(1, firstPrepares.get());
        assertEquals(0, secondPrepares.get());
        assertThrows(SQLException.class,
                () -> secondReader.read("safe-connection",
                        OracleSchemaIdentifierNormalizer.schema("Source"),
                        SqlExecutionOptions.defaults(1)));
        assertEquals(1, secondPrepares.get());

        SchemaChangeRenderer renderer = capability.changeRenderer();
        assertInstanceOf(OracleSchemaChangeRenderer.class, renderer);
        assertSame(renderer, capability.changeRenderer());
        assertThrows(NullPointerException.class, () -> capability.snapshotReader(null));
    }

    @Test
    void supportedTopLevelTypesAreExactImmutableAndExcludeNestedTableStructures() {
        OracleSchemaDiffCapability capability = new OracleSchemaDiffCapability();

        assertEquals(SUPPORTED_TYPES, capability.supportedObjectTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> capability.supportedObjectTypes().add(ObjectType.INDEX));
        assertTrue(Set.of(ObjectType.PRIMARY_KEY, ObjectType.UNIQUE_CONSTRAINT,
                        ObjectType.FOREIGN_KEY, ObjectType.CHECK_CONSTRAINT, ObjectType.INDEX)
                .stream().noneMatch(capability.supportedObjectTypes()::contains));
    }

    @Test
    void oracleProviderRegistersOneImmutableSchemaDiffCapability() {
        OracleProvider provider = new OracleProvider();

        SchemaDiffCapability first = provider.schemaDiffCapability().orElseThrow();
        SchemaDiffCapability second = provider.schemaDiffCapability().orElseThrow();

        assertSame(first, second);
        assertInstanceOf(OracleSchemaDiffCapability.class, first);
        assertTrue(provider.supports("jdbc:oracle:thin:@example.test:1521/FREEPDB1"));
        assertFalse(provider.supports("jdbc:postgresql://example.test/app"));
        assertSame(provider.dialect(), provider.dialect());
        assertSame(provider.connectionFactory(), provider.connectionFactory());
    }

    private static Connection rejectingConnection(AtomicInteger prepares) {
        return (Connection) Proxy.newProxyInstance(
                OracleSchemaDiffCapabilityTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        prepares.incrementAndGet();
                        throw new SQLException("driver connection credential secret", "99999", 99999);
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
