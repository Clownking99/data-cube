package com.datacube.schemadiff;

import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SnapshotCompleteness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchemaDiffCompletenessTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @Test
    void sourceUnavailableScopeProducesUnsupportedForThatExactObjectType() {
        DefinitionObject targetView = definition(ObjectType.VIEW, "orders_view", "select 1");
        DefinitionObject targetFunction = definition(ObjectType.FUNCTION, "calculate", "return 1");
        SnapshotCompleteness sourceCompleteness = partial(ObjectType.VIEW, SnapshotCompleteness.PERMISSION_DENIED);

        SchemaDiffResult result = engine.compare(snapshot(sourceCompleteness),
                snapshot(complete(), targetView, targetFunction));

        SchemaDifference unsupported = result.differences().stream()
                .filter(difference -> difference.object().equals(targetView.key())).findFirst().orElseThrow();
        SchemaDifference unaffected = result.differences().stream()
                .filter(difference -> difference.object().equals(targetFunction.key())).findFirst().orElseThrow();
        assertEquals(DifferenceKind.UNSUPPORTED, unsupported.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, unsupported.automation());
        assertEquals(RiskLevel.HIGH, unsupported.risk());
        assertFalse(unsupported.explanation().contains(SnapshotCompleteness.PERMISSION_DENIED));
        assertEquals(DifferenceKind.EXTRA_IN_TARGET, unaffected.kind());
    }

    @Test
    void targetUnavailableScopeProducesUnsupportedForThatExactObjectType() {
        DefinitionObject sourceView = definition(ObjectType.VIEW, "orders_view", "select 1");
        DefinitionObject sourceProcedure = definition(ObjectType.PROCEDURE, "refresh_orders", "begin null; end");
        SnapshotCompleteness targetCompleteness = partial(ObjectType.VIEW, SnapshotCompleteness.DEFINITION_UNAVAILABLE);

        SchemaDiffResult result = engine.compare(snapshot(complete(), sourceView, sourceProcedure),
                snapshot(targetCompleteness));

        SchemaDifference unsupported = result.differences().stream()
                .filter(difference -> difference.object().equals(sourceView.key())).findFirst().orElseThrow();
        SchemaDifference unaffected = result.differences().stream()
                .filter(difference -> difference.object().equals(sourceProcedure.key())).findFirst().orElseThrow();
        assertEquals(DifferenceKind.UNSUPPORTED, unsupported.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, unsupported.automation());
        assertEquals(RiskLevel.HIGH, unsupported.risk());
        assertFalse(unsupported.explanation().contains(SnapshotCompleteness.DEFINITION_UNAVAILABLE));
        assertEquals(DifferenceKind.MISSING_IN_TARGET, unaffected.kind());
    }

    @Test
    void unavailableScopeWinsEvenWhenSameKeyExistsOnBothSides() {
        DefinitionObject source = definition(ObjectType.VIEW, "orders_view", "select 1");
        DefinitionObject target = definition(ObjectType.VIEW, "orders_view", "select 2");

        SchemaDifference difference = engine.compare(
                snapshot(partial(ObjectType.VIEW, SnapshotCompleteness.METADATA_UNAVAILABLE), source),
                snapshot(complete(), target)).differences().getFirst();

        assertEquals(DifferenceKind.UNSUPPORTED, difference.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, difference.automation());
        assertEquals(Set.of(), difference.properties().stream().collect(java.util.stream.Collectors.toSet()));
    }

    private static SnapshotCompleteness complete() {
        return new SnapshotCompleteness(true, new TreeMap<>());
    }

    private static SnapshotCompleteness partial(ObjectType type, String code) {
        return new SnapshotCompleteness(false, new TreeMap<>(Map.of(type, code)));
    }

    private static SchemaSnapshot snapshot(SnapshotCompleteness completeness, SchemaObject... objects) {
        TreeMap<ObjectKey, SchemaObject> values = new TreeMap<>();
        for (SchemaObject object : objects) values.put(object.key(), object);
        return new SchemaSnapshot(DbType.POSTGRESQL, "connection", name("public"), Instant.EPOCH,
                completeness, values, "fp");
    }

    private static DefinitionObject definition(ObjectType type, String name, String normalized) {
        return new DefinitionObject(new ObjectKey(type, name(name), ""), normalized, "original definition",
                Set.of(), DefinitionConfidence.HIGH);
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }
}
