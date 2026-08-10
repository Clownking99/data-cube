package com.datacube.spi.schemadiff;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public record CanonicalDataType(
        String baseType, Long length, Integer precision, Integer scale,
        boolean withTimeZone, int arrayDimensions,
        SortedMap<String, String> providerExtensions) {
    public CanonicalDataType {
        baseType = Objects.requireNonNull(baseType, "baseType");
        providerExtensions = immutableSortedMap(providerExtensions);
    }

    private static <K extends Comparable<? super K>, V> SortedMap<K, V> immutableSortedMap(
            SortedMap<K, V> values) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(values, "values")));
    }
}
