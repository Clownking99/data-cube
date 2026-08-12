package com.datacube.provider.oracle;

import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.SchemaComparisonProjection;
import com.datacube.spi.schemadiff.SchemaComparisonProjector;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.TableDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** Oracle owner-relative comparison projection with exact reversible originals. */
final class OracleSchemaComparisonProjector implements SchemaComparisonProjector {
    private static final String INVALID = "Oracle schema comparison projection is invalid";
    private static final String SCHEMA_DOMAIN = "oracle-schema-v1\0";
    private static final String OBJECT_DOMAIN = "oracle-object-v1\0";
    private static final String COMPARISON_OBJECT_DOMAIN = "oracle-comparison-object-v1\0";
    private static final String ROUTINE_DOMAIN = "oracle-routine-signature-v1\0";

    @Override
    public SchemaComparisonProjection project(SchemaSnapshot snapshot) {
        try {
            String selfOwner = schemaOwner(snapshot.schema());
            SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>();
            SortedMap<ObjectKey, ObjectKey> originals = new TreeMap<>();
            for (SchemaObject original : snapshot.objects().values()) {
                if (!objectOwner(original.key()).equals(selfOwner)) throw invalid();
                SchemaObject comparison = projectObject(original, selfOwner);
                if (objects.put(comparison.key(), comparison) != null
                        || originals.put(comparison.key(), original.key()) != null) throw invalid();
            }
            return new SchemaComparisonProjection(snapshot, objects, originals);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(INVALID, failure);
        }
    }

    private static SchemaObject projectObject(SchemaObject object, String selfOwner) {
        if (object instanceof TableDefinition table) {
            return new TableDefinition(projectKey(table.key(), selfOwner),
                    table.columns().stream().map(column -> projectColumn(column, selfOwner)).toList(),
                    table.constraints().stream()
                            .map(constraint -> projectConstraint(constraint, selfOwner)).toList(),
                    table.indexes().stream().map(index -> projectIndex(index, selfOwner)).toList(),
                    projectKeys(table.dependencies(), selfOwner));
        }
        if (object instanceof SequenceDefinition sequence) {
            return new SequenceDefinition(projectKey(sequence.key(), selfOwner),
                    sequence.startValue(), sequence.incrementBy(), sequence.minimumValue(),
                    sequence.maximumValue(), sequence.cycle(), sequence.cacheSize(),
                    projectKeys(sequence.dependencies(), selfOwner), sequence.providerExtensions());
        }
        if (object instanceof DefinitionObject definition) {
            String normalized = definition.normalizedDefinition();
            String projectedDefinition = normalized;
            DefinitionConfidence confidence = definition.confidence();
            if (normalized != null) {
                if (confidence == DefinitionConfidence.LOW && isRoutine(definition.key())) {
                    projectedDefinition = manualDefinitionMarker(definition, selfOwner);
                } else {
                    try {
                        projectedDefinition = OracleSchemaChangeRenderer.comparisonDefinition(
                                normalized, selfOwner);
                    } catch (IllegalArgumentException failure) {
                        if (!isRoutine(definition.key())) throw failure;
                        projectedDefinition = manualDefinitionMarker(definition, selfOwner);
                        confidence = DefinitionConfidence.LOW;
                    }
                }
            }
            return new DefinitionObject(projectKey(definition.key(), selfOwner),
                    projectedDefinition,
                    definition.originalDefinition(), projectKeys(definition.dependencies(), selfOwner),
                    confidence);
        }
        throw invalid();
    }

    private static boolean isRoutine(ObjectKey key) {
        return key.type() == ObjectType.FUNCTION || key.type() == ObjectType.PROCEDURE;
    }

    private static String manualDefinitionMarker(DefinitionObject definition, String selfOwner) {
        String value = selfOwner + '\0' + definition.key().type() + '\0'
                + definition.key().name().comparisonKey() + '\0' + definition.key().signature()
                + '\0' + String.valueOf(definition.normalizedDefinition());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "\0oracle-manual-definition-v1\0" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ColumnDefinition projectColumn(ColumnDefinition column, String selfOwner) {
        return new ColumnDefinition(column.name(), projectType(column.dataType(), selfOwner),
                column.nullable(), projectFragment(column.normalizedDefault(), selfOwner),
                column.ordinal(), column.comment());
    }

    private static CanonicalDataType projectType(CanonicalDataType type, String selfOwner) {
        SortedMap<String, String> extensions = new TreeMap<>(type.providerExtensions());
        if (selfOwner.equals(extensions.get("oracle.typeOwner"))) {
            String prefix = selfOwner + ".";
            String formatted = extensions.get("formattedType");
            if (!type.baseType().startsWith(prefix) || type.baseType().length() == prefix.length()
                    || formatted == null) throw invalid();
            extensions.put("oracle.typeOwner", "\0oracle-self-owner\0");
            extensions.put("formattedType",
                    OracleSchemaChangeRenderer.comparisonTypeFragment(formatted, selfOwner));
            return new CanonicalDataType("\0oracle-self-owner\0."
                    + type.baseType().substring(prefix.length()), type.length(), type.precision(),
                    type.scale(), type.withTimeZone(), type.arrayDimensions(), extensions);
        }
        return type;
    }

    private static ConstraintDefinition projectConstraint(
            ConstraintDefinition constraint, String selfOwner) {
        return new ConstraintDefinition(projectKey(constraint.key(), selfOwner), constraint.kind(),
                constraint.columns(), projectNullableKey(constraint.referencedTable(), selfOwner),
                constraint.referencedColumns(),
                projectFragment(constraint.normalizedExpression(), selfOwner),
                constraint.updateAction(), constraint.deleteAction(),
                constraint.providerGeneratedName(), projectKeys(constraint.dependencies(), selfOwner));
    }

    private static IndexDefinition projectIndex(IndexDefinition index, String selfOwner) {
        return new IndexDefinition(projectKey(index.key(), selfOwner), index.unique(),
                index.normalizedExpressions().stream()
                        .map(expression -> projectFragment(expression, selfOwner)).toList(),
                projectFragment(index.normalizedPredicate(), selfOwner),
                index.providerGeneratedName(), projectKeys(index.dependencies(), selfOwner));
    }

    private static String projectFragment(String value, String selfOwner) {
        return value == null ? null
                : OracleSchemaChangeRenderer.comparisonFragment(value, selfOwner);
    }

    private static Set<ObjectKey> projectKeys(Set<ObjectKey> keys, String selfOwner) {
        TreeSet<ObjectKey> projected = new TreeSet<>();
        for (ObjectKey key : keys) projected.add(projectKey(key, selfOwner));
        return Set.copyOf(projected);
    }

    private static ObjectKey projectNullableKey(ObjectKey key, String selfOwner) {
        return key == null ? null : projectKey(key, selfOwner);
    }

    private static ObjectKey projectKey(ObjectKey key, String selfOwner) {
        NameParts parts = objectParts(key.name());
        QualifiedName name = parts.owner().equals(selfOwner)
                ? new QualifiedName(OracleSchemaIdentifierNormalizer.quote(parts.object()),
                        COMPARISON_OBJECT_DOMAIN + field(parts.object()), true)
                : key.name();
        String signature = key.signature();
        if (key.type() == ObjectType.FUNCTION || key.type() == ObjectType.PROCEDURE) {
            signature = projectRoutineSignature(signature, selfOwner);
        } else if (signature.startsWith(OBJECT_DOMAIN)) {
            NameParts signatureParts = objectParts(new QualifiedName("comparison", signature, true));
            if (signatureParts.owner().equals(selfOwner)) {
                signature = COMPARISON_OBJECT_DOMAIN + field(signatureParts.object());
            }
        }
        return new ObjectKey(key.type(), name, signature);
    }

    private static String projectRoutineSignature(String signature, String selfOwner) {
        if (!signature.startsWith(ROUTINE_DOMAIN)) throw invalid();
        StringBuilder projected = new StringBuilder(ROUTINE_DOMAIN);
        int offset = ROUTINE_DOMAIN.length();
        while (offset < signature.length()) {
            Field mode = fieldAt(signature, offset);
            Field type = fieldAt(signature, mode.next());
            if (!Set.of("IN", "INOUT").contains(mode.value())) throw invalid();
            appendField(projected, mode.value());
            String prefix = selfOwner + ".";
            String projectedType = type.value().startsWith("\"")
                    ? OracleSchemaChangeRenderer.comparisonTypeFragment(type.value(), selfOwner)
                    : type.value().startsWith(prefix) && type.value().length() > prefix.length()
                    ? "\0oracle-self-type\0." + type.value().substring(prefix.length())
                    : type.value();
            appendField(projected, projectedType);
            offset = type.next();
        }
        return projected.toString();
    }

    private static String schemaOwner(QualifiedName schema) {
        String value = schema.comparisonKey();
        if (!value.startsWith(SCHEMA_DOMAIN)) throw invalid();
        Field owner = fieldAt(value, SCHEMA_DOMAIN.length());
        if (owner.next() != value.length()) throw invalid();
        return owner.value();
    }

    private static String objectOwner(ObjectKey key) {
        return objectParts(key.name()).owner();
    }

    private static NameParts objectParts(QualifiedName name) {
        String value = name.comparisonKey();
        if (!value.startsWith(OBJECT_DOMAIN)) throw invalid();
        Field owner = fieldAt(value, OBJECT_DOMAIN.length());
        Field object = fieldAt(value, owner.next());
        if (object.next() != value.length()) throw invalid();
        return new NameParts(owner.value(), object.value());
    }

    private static Field fieldAt(String value, int start) {
        int colon = value.indexOf(':', start);
        if (colon <= start) throw invalid();
        String lengthText = value.substring(start, colon);
        if (!lengthText.matches("0|[1-9][0-9]*")) throw invalid();
        int length;
        try {
            length = Integer.parseInt(lengthText);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
        int valueStart = colon + 1;
        int end = valueStart + length;
        if (length == 0 || end < valueStart || end > value.length()) throw invalid();
        String decoded = value.substring(valueStart, end);
        if (decoded.indexOf('\0') >= 0) throw invalid();
        return new Field(decoded, end);
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }

    private static void appendField(StringBuilder target, String value) {
        target.append(field(value));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }

    private record NameParts(String owner, String object) {
    }

    private record Field(String value, int next) {
    }
}
