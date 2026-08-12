package com.datacube.spi;

import com.datacube.schemadiff.DifferenceKind;
import com.datacube.schemadiff.PropertyDifference;
import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.schemadiff.SchemaDifference;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaComparisonProjection;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffCapabilityContractTest {

    @Test
    void legacyProvidersUseAnEmptySchemaDiffCapabilityByDefault() {
        assertEquals(Optional.empty(), new LegacyProvider().schemaDiffCapability());
    }

    @Test
    void capabilityExposesReaderRendererAndAnImmutableSupportedTypeSnapshot() throws Exception {
        Set<ObjectType> mutableTypes = new HashSet<>(Set.of(ObjectType.TABLE));
        SchemaSnapshot expected = snapshot("reader-secret");
        SchemaDiffCapability capability = new SchemaDiffCapability() {
            private final Set<ObjectType> supportedTypes = Set.copyOf(mutableTypes);

            @Override
            public com.datacube.spi.schemadiff.SchemaSnapshotReader snapshotReader(Connection connection) {
                return (connectionId, schema, options) -> expected;
            }

            @Override
            public com.datacube.spi.schemadiff.SchemaChangeRenderer changeRenderer() {
                return (change, context) -> List.of();
            }

            @Override
            public Set<ObjectType> supportedObjectTypes() {
                return supportedTypes;
            }
        };

        mutableTypes.add(ObjectType.VIEW);

        assertEquals(Set.of(ObjectType.TABLE), capability.supportedObjectTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> capability.supportedObjectTypes().add(ObjectType.VIEW));
        assertEquals(expected, capability.snapshotReader(null).read(
                "connection-secret", name("public"), SqlExecutionOptions.defaults(10)));
        assertTrue(capability.changeRenderer().render(null, null).isEmpty());
        SchemaComparisonProjection identity = capability.comparisonProjector().project(expected);
        assertEquals(expected.objects(), identity.comparisonObjects());
        assertEquals(expected.objects().keySet(), Set.copyOf(identity.originalKeys().values()));
    }

    @Test
    void changeContractsCopyCollectionsAndExposeOnlySafeSummaries() {
        String secret = "jdbc:postgresql://alice:password@example.test/app";
        ObjectKey key = key(ObjectType.VIEW, "sensitive_view");
        DefinitionObject source = new DefinitionObject(key, "select 'source-secret'", secret,
                Set.of(), DefinitionConfidence.HIGH);
        DefinitionObject target = new DefinitionObject(key, "select 'target-secret'", secret,
                Set.of(), DefinitionConfidence.HIGH);
        PropertyDifference property = new PropertyDifference(
                "normalizedDefinition", "property-source-secret", "property-target-secret", "safe");
        Set<String> mutableDependencies = new HashSet<>(Set.of("chg:dependency"));
        SchemaChange change = new SchemaChange("chg:" + secret, ChangeKind.REPLACE, key, source, target,
                property, RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, false,
                mutableDependencies, "fixed explanation");
        Set<String> statementDependencies = new HashSet<>(Set.of("chg:dependency"));
        RenderedStatement statement = new RenderedStatement(
                "chg:" + secret, "drop view sensitive_view -- sql-secret", true,
                statementDependencies, "warning-secret");
        RenderContext context = new RenderContext(DbType.POSTGRESQL,
                name("source-secret"), name("target-secret"), false);

        mutableDependencies.clear();
        statementDependencies.clear();

        assertEquals(Set.of("chg:dependency"), change.dependencyChangeIds());
        assertEquals(Set.of("chg:dependency"), statement.dependencyIds());
        assertThrows(UnsupportedOperationException.class,
                () -> change.dependencyChangeIds().add("chg:other"));
        assertThrows(UnsupportedOperationException.class,
                () -> statement.dependencyIds().add("chg:other"));
        for (String summary : List.of(change.toString(), statement.toString(), context.toString())) {
            assertFalse(summary.contains(secret));
            assertFalse(summary.contains("source-secret"));
            assertFalse(summary.contains("target-secret"));
            assertFalse(summary.contains("sql-secret"));
            assertFalse(summary.contains("property-source-secret"));
            assertFalse(summary.contains("property-target-secret"));
            assertFalse(summary.contains("warning-secret"));
        }
    }

    @Test
    void planCopiesEveryCollectionAndDoesNotRenderItsDiffSnapshots() {
        SchemaSnapshot source = snapshot("source-connection-secret");
        SchemaSnapshot target = snapshot("target-connection-secret");
        SchemaDiffResult diff = new SchemaDiffResult(source, target, List.of(), List.of());
        ObjectKey key = key(ObjectType.TABLE, "orders");
        SchemaChange change = new SchemaChange("chg:id", ChangeKind.CREATE, key, null, null,
                null, RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");
        List<SchemaChange> mutableChanges = new ArrayList<>(List.of(change));
        Set<String> mutableSelected = new HashSet<>(Set.of("chg:id"));
        Set<String> mutableBlocked = new HashSet<>();

        SchemaChangePlan plan = new SchemaChangePlan(
                diff, mutableChanges, mutableSelected, mutableBlocked, "a".repeat(64));
        mutableChanges.clear();
        mutableSelected.clear();
        mutableBlocked.add("chg:id");

        assertEquals(1, plan.changes().size());
        assertEquals(Set.of("chg:id"), plan.selectedChangeIds());
        assertTrue(plan.blockedChangeIds().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> plan.changes().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.selectedChangeIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.blockedChangeIds().add("chg:other"));
        assertFalse(plan.toString().contains("source-connection-secret"));
        assertFalse(plan.toString().contains("target-connection-secret"));
    }

    private static SchemaSnapshot snapshot(String connectionId) {
        return new SchemaSnapshot(DbType.POSTGRESQL, connectionId, name("public"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(), "f".repeat(64));
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static ObjectKey key(ObjectType type, String value) {
        return new ObjectKey(type, name(value), "");
    }

    private static final class LegacyProvider implements DatabaseProvider {
        @Override public DbType type() { return DbType.POSTGRESQL; }
        @Override public boolean supports(String jdbcUrl) { return false; }
        @Override public ConnectionFactory connectionFactory() { return null; }
        @Override public SqlDialect dialect() { return null; }
        @Override public SqlRunner sqlRunner() { return null; }
        @Override public TableDdlBuilder tableDdlBuilder() { return null; }
        @Override public SequenceDdlBuilder sequenceDdlBuilder() { return null; }
        @Override public MetadataReader metadataReader(Connection connection) { return null; }
        @Override public DdlGenerator ddlGenerator(Connection connection) { return null; }
        @Override public DataAccessor dataAccessor(Connection connection) { return null; }
        @Override public DataEditor dataEditor(Connection connection) { return null; }
    }
}
