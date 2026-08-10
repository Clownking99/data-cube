package com.datacube.schemadiff;

import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.TableDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PropertyDifference(
        String path, Object sourceValue, Object targetValue, String explanation) {
    private static final String INVALID_VALUE_MESSAGE = "Property value type is not allowed";
    private static final Set<Class<?>> IMMUTABLE_NUMBER_TYPES = Set.of(
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            BigInteger.class,
            BigDecimal.class);
    private static final Set<Class<?>> TRUSTED_IMMUTABLE_RECORDS = Set.of(
            QualifiedName.class,
            ObjectKey.class,
            CanonicalDataType.class,
            ColumnDefinition.class,
            ConstraintDefinition.class,
            IndexDefinition.class,
            TableDefinition.class,
            SequenceDefinition.class,
            DefinitionObject.class,
            SnapshotCompleteness.class,
            SchemaSnapshot.class,
            PropertyDifference.class,
            SchemaDifference.class,
            RenameSuggestion.class,
            SchemaDiffResult.class);

    public PropertyDifference {
        path = Objects.requireNonNull(path, "path");
        sourceValue = immutableCopy(sourceValue);
        targetValue = immutableCopy(targetValue);
        explanation = Objects.requireNonNull(explanation, "explanation");
    }

    private static Object immutableCopy(Object value) {
        Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        return immutableCopy(value, visiting);
    }

    private static Object immutableCopy(Object value, Set<Object> visiting) {
        if (value == null || value instanceof String || IMMUTABLE_NUMBER_TYPES.contains(value.getClass())
                || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>
                || TRUSTED_IMMUTABLE_RECORDS.contains(value.getClass())) {
            return value;
        }
        if (!visiting.add(value)) throw new IllegalArgumentException(INVALID_VALUE_MESSAGE);
        try {
            if (value instanceof List<?> list) {
                List<Object> copied = new ArrayList<>(list.size());
                for (Object element : list) copied.add(immutableCopy(element, visiting));
                return Collections.unmodifiableList(copied);
            }
            if (value instanceof Set<?> set) {
                Set<Object> copied = new LinkedHashSet<>();
                for (Object element : set) copied.add(immutableCopy(element, visiting));
                return Collections.unmodifiableSet(copied);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copied = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copied.put(immutableCopy(entry.getKey(), visiting),
                            immutableCopy(entry.getValue(), visiting));
                }
                return Collections.unmodifiableMap(copied);
            }
            throw new IllegalArgumentException(INVALID_VALUE_MESSAGE);
        } finally {
            visiting.remove(value);
        }
    }

    @Override
    public String toString() {
        return "PropertyDifference[sourcePresent=" + (sourceValue != null)
                + ", targetPresent=" + (targetValue != null) + "]";
    }
}
