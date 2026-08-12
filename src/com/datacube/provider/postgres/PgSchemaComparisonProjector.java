package com.datacube.provider.postgres;

import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.DefinitionConfidence;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** PostgreSQL owner-relative comparison projection with exact reversible originals. */
final class PgSchemaComparisonProjector implements SchemaComparisonProjector {
    private static final String INVALID = "PostgreSQL schema comparison projection is invalid";
    private static final String SCHEMA_DOMAIN = "pg-schema-v1\0";
    private static final String OBJECT_DOMAIN = "pg-object-v1\0";
    private static final String COMPARISON_OBJECT_DOMAIN = "pg-comparison-object-v1\0";

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
                        || originals.put(comparison.key(), original.key()) != null) {
                    throw invalid();
                }
            }
            return new SchemaComparisonProjection(snapshot, objects, originals);
        } catch (IllegalArgumentException failure) {
            throw invalid();
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
            String projected = normalized;
            DefinitionConfidence confidence = definition.confidence();
            if (normalized != null) {
                try {
                    projected = PgSchemaChangeRenderer.comparisonDefinition(
                            normalized, definition.key().type(), selfOwner);
                } catch (IllegalArgumentException failure) {
                    if (definition.key().type() != ObjectType.FUNCTION
                            && definition.key().type() != ObjectType.PROCEDURE) throw failure;
                    projected = manualDefinitionMarker(normalized);
                    confidence = DefinitionConfidence.LOW;
                }
            }
            return new DefinitionObject(projectKey(definition.key(), selfOwner),
                    projected,
                    definition.originalDefinition(), projectKeys(definition.dependencies(), selfOwner),
                    confidence);
        }
        throw invalid();
    }

    private static ColumnDefinition projectColumn(ColumnDefinition column, String selfOwner) {
        return new ColumnDefinition(column.name(), projectType(column.dataType(), selfOwner),
                column.nullable(), projectFragment(column.normalizedDefault(), selfOwner),
                column.ordinal(), column.comment());
    }

    private static CanonicalDataType projectType(CanonicalDataType type, String selfOwner) {
        SortedMap<String, String> extensions = new TreeMap<>(type.providerExtensions());
        String owner = extensions.get("typeSchema");
        if (selfOwner.equals(owner)) {
            String formatted = extensions.get("formattedType");
            if (formatted == null) throw invalid();
            extensions.put("typeSchema", "\0pg-self-owner\0");
            extensions.put("formattedType",
                    PgSchemaChangeRenderer.comparisonTypeFragment(formatted, selfOwner));
        }
        return new CanonicalDataType(type.baseType(), type.length(), type.precision(), type.scale(),
                type.withTimeZone(), type.arrayDimensions(), extensions);
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
                : PgSchemaChangeRenderer.comparisonFragment(value, selfOwner);
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
                ? new QualifiedName(PgSchemaIdentifierNormalizer.quote(parts.object()),
                        COMPARISON_OBJECT_DOMAIN + parts.object(), true)
                : key.name();
        String signature = key.signature();
        if (key.type() == ObjectType.FUNCTION || key.type() == ObjectType.PROCEDURE) {
            signature = PgSchemaChangeRenderer.comparisonTypeFragment(signature, selfOwner);
        } else if (signature.startsWith(OBJECT_DOMAIN)) {
            signature = projectEncodedObjectKey(signature, selfOwner);
        }
        return new ObjectKey(key.type(), name, signature);
    }

    private static String projectEncodedObjectKey(String value, String selfOwner) {
        NameParts parts = objectParts(new QualifiedName("comparison", value, true));
        return parts.owner().equals(selfOwner)
                ? COMPARISON_OBJECT_DOMAIN + parts.object() : value;
    }

    private static String schemaOwner(QualifiedName schema) {
        String value = schema.comparisonKey();
        if (!value.startsWith(SCHEMA_DOMAIN)) throw invalid();
        String owner = value.substring(SCHEMA_DOMAIN.length());
        if (owner.isEmpty() || owner.indexOf('\0') >= 0) throw invalid();
        return owner;
    }

    private static String objectOwner(ObjectKey key) {
        return objectParts(key.name()).owner();
    }

    private static NameParts objectParts(QualifiedName name) {
        String value = name.comparisonKey();
        if (!value.startsWith(OBJECT_DOMAIN)) throw invalid();
        String identity = value.substring(OBJECT_DOMAIN.length());
        int separator = identity.indexOf('\0');
        if (separator <= 0 || separator == identity.length() - 1
                || identity.indexOf('\0', separator + 1) >= 0) throw invalid();
        return new NameParts(identity.substring(0, separator), identity.substring(separator + 1));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }

    private static String manualDefinitionMarker(String definition) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(definition.getBytes(StandardCharsets.UTF_8));
            StringBuilder marker = new StringBuilder("pg-manual-definition-v1:");
            for (byte value : hash) marker.append(String.format("%02x", value));
            return marker.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Definition digest is unavailable", failure);
        }
    }

    private record NameParts(String owner, String object) {
    }
}
