package com.datacube.spi.schemadiff;

import com.datacube.spi.model.DbType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Produces a stable identity for schema contents only, never connection metadata. */
public final class SnapshotFingerprint {
    private SnapshotFingerprint() {
    }

    public static String compute(SchemaSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return compute(snapshot.databaseType(), snapshot.schema(), snapshot.completeness(), snapshot.objects());
    }

    public static String compute(
            DbType databaseType,
            QualifiedName schema,
            SnapshotCompleteness completeness,
            Map<ObjectKey, ? extends SchemaObject> objects) {
        Objects.requireNonNull(databaseType, "databaseType");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(objects, "objects");
        CanonicalWriter writer = new CanonicalWriter();
        writer.string("schema-snapshot-v1");
        writer.string(databaseType.name());
        writer.name(schema);
        writer.bool(completeness.complete());
        writer.map(completeness.unavailableScopes(), (type, scope) -> {
            writer.string(type.name());
            writer.string(scope);
        });
        writer.map(new TreeMap<>(objects), (key, object) -> writeObject(writer, key, object));
        return sha256(writer.value().toString());
    }

    private static void writeObject(CanonicalWriter writer, ObjectKey mapKey, SchemaObject object) {
        writer.key(mapKey);
        if (object instanceof TableDefinition table) {
            writer.string("table");
            writer.key(table.key());
            writer.sorted(table.columns(), Comparator
                    .comparingInt(ColumnDefinition::ordinal)
                    .thenComparing(ColumnDefinition::name), column -> writeColumn(writer, column));
            writer.sorted(table.constraints(), Comparator.comparing(ConstraintDefinition::key),
                    constraint -> writeConstraint(writer, constraint));
            writer.sorted(table.indexes(), Comparator.comparing(IndexDefinition::key), index -> writeIndex(writer, index));
            writer.keys(table.dependencies());
        } else if (object instanceof SequenceDefinition sequence) {
            writer.string("sequence");
            writer.key(sequence.key());
            writer.string(sequence.startValue());
            writer.string(sequence.incrementBy());
            writer.string(sequence.minimumValue());
            writer.string(sequence.maximumValue());
            writer.bool(sequence.cycle());
            writer.integer(sequence.cacheSize());
            writer.keys(sequence.dependencies());
        } else if (object instanceof DefinitionObject definition) {
            writer.string("definition");
            writer.key(definition.key());
            writer.string(definition.normalizedDefinition());
            writer.string(definition.originalDefinition());
            writer.keys(definition.dependencies());
            writer.string(definition.confidence().name());
        } else {
            throw new IllegalArgumentException("Unsupported schema object: " + object.getClass().getName());
        }
    }

    private static void writeColumn(CanonicalWriter writer, ColumnDefinition column) {
        writer.name(column.name());
        CanonicalDataType type = column.dataType();
        writer.string(type.baseType());
        writer.longValue(type.length());
        writer.integer(type.precision());
        writer.integer(type.scale());
        writer.bool(type.withTimeZone());
        writer.integer(type.arrayDimensions());
        writer.map(type.providerExtensions(), (key, value) -> {
            writer.string(key);
            writer.string(value);
        });
        writer.bool(column.nullable());
        writer.string(column.normalizedDefault());
        writer.integer(column.ordinal());
        writer.string(column.comment());
    }

    private static void writeConstraint(CanonicalWriter writer, ConstraintDefinition constraint) {
        writer.key(constraint.key());
        writer.string(constraint.kind().name());
        writer.names(constraint.columns());
        writer.key(constraint.referencedTable());
        writer.names(constraint.referencedColumns());
        writer.string(constraint.normalizedExpression());
        writer.string(constraint.updateAction());
        writer.string(constraint.deleteAction());
        writer.bool(constraint.providerGeneratedName());
        writer.keys(constraint.dependencies());
    }

    private static void writeIndex(CanonicalWriter writer, IndexDefinition index) {
        writer.key(index.key());
        writer.bool(index.unique());
        writer.strings(index.normalizedExpressions());
        writer.string(index.normalizedPredicate());
        writer.bool(index.providerGeneratedName());
        writer.keys(index.dependencies());
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte valueByte : hash) hex.append(String.format("%02x", valueByte));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record CanonicalWriter(StringBuilder value) {
        private CanonicalWriter() {
            this(new StringBuilder());
        }

        void string(String value) {
            if (value == null) {
                this.value.append("N;");
            } else {
                this.value.append('S').append(value.length()).append(':').append(value).append(';');
            }
        }

        void bool(boolean value) {
            this.value.append(value ? "B1;" : "B0;");
        }

        void integer(Integer value) {
            if (value == null) string(null); else string(value.toString());
        }

        void longValue(Long value) {
            if (value == null) string(null); else string(value.toString());
        }

        void name(QualifiedName name) {
            if (name == null) {
                string(null);
                return;
            }
            string(name.original());
            string(name.comparisonKey());
            bool(name.quoted());
        }

        void key(ObjectKey key) {
            if (key == null) {
                string(null);
                return;
            }
            string(key.type().name());
            name(key.name());
            string(key.signature());
        }

        void names(List<QualifiedName> values) {
            string(Integer.toString(values.size()));
            values.forEach(this::name);
        }

        void strings(List<String> values) {
            string(Integer.toString(values.size()));
            values.forEach(this::string);
        }

        void keys(Set<ObjectKey> values) {
            List<ObjectKey> sorted = new ArrayList<>(values);
            sorted.sort(ObjectKey::compareTo);
            string(Integer.toString(sorted.size()));
            sorted.forEach(this::key);
        }

        <K extends Comparable<? super K>, V> void map(
                SortedMap<K, V> values, java.util.function.BiConsumer<K, V> writer) {
            string(Integer.toString(values.size()));
            values.forEach(writer);
        }

        <T> void sorted(List<T> values, Comparator<? super T> comparator,
                        java.util.function.Consumer<T> writer) {
            List<T> sorted = new ArrayList<>(values);
            sorted.sort(comparator);
            string(Integer.toString(sorted.size()));
            sorted.forEach(writer);
        }
    }
}
