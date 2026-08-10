package com.datacube.schemadiff;

import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRenameSuggestionTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @Test
    void uniqueStructurallyIdenticalPairProducesAdvisoryRenameWithoutChangingDifferences() {
        SequenceDefinition source = sequence("old_sequence", "1");
        SequenceDefinition target = sequence("new_sequence", "1");

        SchemaDiffResult result = engine.compare(snapshot(source), snapshot(target));

        assertEquals(List.of(DifferenceKind.EXTRA_IN_TARGET, DifferenceKind.MISSING_IN_TARGET),
                result.differences().stream().map(SchemaDifference::kind).toList());
        RenameSuggestion suggestion = result.renameSuggestions().getFirst();
        assertEquals(source.key(), suggestion.sourceObject());
        assertEquals(target.key(), suggestion.targetObject());
        assertEquals(1.0, suggestion.similarity());
    }

    @Test
    void ambiguousEquivalentCandidatesProduceNoRenameSuggestion() {
        SequenceDefinition sourceOne = sequence("old_one", "1");
        SequenceDefinition sourceTwo = sequence("old_two", "1");
        SequenceDefinition targetOne = sequence("new_one", "1");
        SequenceDefinition targetTwo = sequence("new_two", "1");

        SchemaDiffResult result = engine.compare(snapshot(sourceOne, sourceTwo), snapshot(targetOne, targetTwo));

        assertTrue(result.renameSuggestions().isEmpty());
        assertEquals(4, result.differences().size());
    }

    @Test
    void differentTypeOrDifferentStructureNeverProducesRenameSuggestion() {
        SequenceDefinition source = sequence("old_sequence", "1");
        SequenceDefinition structurallyChanged = sequence("new_sequence", "2");
        DefinitionObject differentType = new DefinitionObject(
                key(ObjectType.VIEW, "new_view"), "1", "original", Set.of(), DefinitionConfidence.HIGH);

        SchemaDiffResult result = engine.compare(snapshot(source), snapshot(structurallyChanged, differentType));

        assertTrue(result.renameSuggestions().isEmpty());
        assertEquals(3, result.differences().size());
    }

    @Test
    void sameStructureWithDifferentRoutineSignaturesIsNotARename() {
        DefinitionObject source = new DefinitionObject(key(ObjectType.FUNCTION, "calculate", "integer"),
                "return 1", "original", Set.of(), DefinitionConfidence.HIGH);
        DefinitionObject target = new DefinitionObject(key(ObjectType.FUNCTION, "calculate_new", "text"),
                "return 1", "original", Set.of(), DefinitionConfidence.HIGH);

        SchemaDiffResult result = engine.compare(snapshot(source), snapshot(target));

        assertTrue(result.renameSuggestions().isEmpty());
    }

    @Test
    void highConfidenceDefinitionsWithNonBlankNormalizedTextCanSuggestRename() {
        DefinitionObject source = definition("old_view", "select id from orders", DefinitionConfidence.HIGH);
        DefinitionObject target = definition("new_view", "select id from orders", DefinitionConfidence.HIGH);

        SchemaDiffResult result = engine.compare(snapshot(source), snapshot(target));

        assertEquals(1, result.renameSuggestions().size());
        assertEquals(List.of(DifferenceKind.EXTRA_IN_TARGET, DifferenceKind.MISSING_IN_TARGET),
                result.differences().stream().map(SchemaDifference::kind).toList());
    }

    @Test
    void lowConfidenceOnEitherDefinitionSuppressesRenameSuggestion() {
        SchemaDiffResult bothLow = engine.compare(
                snapshot(definition("old_low", "select id from orders", DefinitionConfidence.LOW)),
                snapshot(definition("new_low", "select id from orders", DefinitionConfidence.LOW)));
        SchemaDiffResult mixed = engine.compare(
                snapshot(definition("old_mixed", "select id from orders", DefinitionConfidence.LOW)),
                snapshot(definition("new_mixed", "select id from orders", DefinitionConfidence.HIGH)));

        assertTrue(bothLow.renameSuggestions().isEmpty());
        assertTrue(mixed.renameSuggestions().isEmpty());
        assertEquals(2, bothLow.differences().size());
        assertEquals(2, mixed.differences().size());
    }

    @Test
    void nullOrBlankNormalizedDefinitionSuppressesRenameSuggestion() {
        SchemaDiffResult nullDefinitions = engine.compare(
                snapshot(definition("old_null", null, DefinitionConfidence.HIGH)),
                snapshot(definition("new_null", null, DefinitionConfidence.HIGH)));
        SchemaDiffResult blankDefinitions = engine.compare(
                snapshot(definition("old_blank", "  ", DefinitionConfidence.HIGH)),
                snapshot(definition("new_blank", "  ", DefinitionConfidence.HIGH)));

        assertTrue(nullDefinitions.renameSuggestions().isEmpty());
        assertTrue(blankDefinitions.renameSuggestions().isEmpty());
        assertEquals(2, nullDefinitions.differences().size());
        assertEquals(2, blankDefinitions.differences().size());
    }

    private static SequenceDefinition sequence(String value, String increment) {
        return new SequenceDefinition(key(ObjectType.SEQUENCE, value), "1", increment, null, null, false, null,
                Set.of());
    }

    private static DefinitionObject definition(
            String value, String normalizedDefinition, DefinitionConfidence confidence) {
        return new DefinitionObject(key(ObjectType.VIEW, value), normalizedDefinition, "original", Set.of(), confidence);
    }

    private static SchemaSnapshot snapshot(SchemaObject... objects) {
        TreeMap<ObjectKey, SchemaObject> values = new TreeMap<>();
        for (SchemaObject object : objects) values.put(object.key(), object);
        return new SchemaSnapshot(DbType.POSTGRESQL, "connection", name("public"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), values, "fp");
    }

    private static ObjectKey key(ObjectType type, String value) {
        return key(type, value, "");
    }

    private static ObjectKey key(ObjectType type, String value, String signature) {
        return new ObjectKey(type, name(value), signature);
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }
}
