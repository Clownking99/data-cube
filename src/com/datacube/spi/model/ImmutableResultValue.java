package com.datacube.spi.model;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral immutable storage for JDBC values that cannot safely outlive a result set.
 * Scalar JDBC values remain their normal Java types; only binary, array, and structured values
 * need this container.
 */
public final class ImmutableResultValue {
    private static final int BINARY_PREVIEW_BYTES = 64;
    private static final int MAX_NESTING_DEPTH = 64;
    private static final Object MISSING = new Object();

    private enum Kind { BINARY, ARRAY, STRUCT, REFERENCE }

    private final Kind kind;
    private final byte[] bytes;
    private final byte[] fingerprint;
    private final long binaryLength;
    private final List<Object> values;
    private final String label;

    private ImmutableResultValue(
            Kind kind, byte[] bytes, byte[] fingerprint,
            long binaryLength, List<Object> values, String label) {
        this.kind = kind;
        this.bytes = bytes;
        this.fingerprint = fingerprint;
        this.binaryLength = binaryLength;
        this.values = values;
        this.label = label;
    }

    /** Returns an immutable, connection-independent form of a result or filter value. */
    public static Object freeze(Object value) {
        try {
            return freezeJdbc(value);
        } catch (SQLException failure) {
            throw new IllegalArgumentException("无法读取 JDBC 结果值", failure);
        }
    }

    static Object freezeJdbc(Object value) throws SQLException {
        return freezeJdbc(value, new FreezeContext(), 0);
    }

    private static Object freezeJdbc(
            Object value, FreezeContext context, int depth) throws SQLException {
        if (value == null || isStandardImmutable(value)) return value;
        context.enter(value, depth);
        try {
            if (value instanceof Clob clob) return readAndFreeClob(clob);
            if (value instanceof Blob blob) return readAndFreeBlob(blob);
            if (value instanceof java.sql.Array array) return readAndFreeArray(array, context, depth);
            if (value instanceof SQLXML xml) return readAndFreeSqlXml(xml);
            if (value instanceof Struct struct) return freezeStruct(struct, context, depth);
            if (value instanceof Ref ref) return freezeRef(ref, context, depth);
            if (value instanceof RowId rowId) return binary(rowId.getBytes());
            if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
            if (value instanceof java.sql.Date date) return date.toLocalDate();
            if (value instanceof java.sql.Time time) return time.toLocalTime();
            if (value instanceof java.util.Date date) return date.toInstant();
            if (value instanceof Calendar calendar) return calendar.toInstant();
            if (value instanceof byte[] bytes) return binary(bytes);
            if (value.getClass().isArray()) return freezeArray(value, context, depth);
            if (value instanceof CharSequence text) return text.toString();
            if (value instanceof URL url) return url.toExternalForm();

            Object providerValue = freezeKnownProviderValue(value, context, depth);
            if (providerValue != MISSING) return providerValue;
            throw new IllegalArgumentException(
                    "不支持冻结的可变结果值类型: " + value.getClass().getName());
        } finally {
            context.exit(value);
        }
    }

    static Object freezeJdbc(Object value, int jdbcType) throws SQLException {
        return freezeJdbc(value, jdbcType, new FreezeContext(), 0);
    }

    private static Object freezeJdbc(
            Object value, int jdbcType, FreezeContext context, int depth) throws SQLException {
        if (value instanceof Clob || value instanceof Blob || value instanceof java.sql.Array
                || value instanceof SQLXML || value instanceof Struct
                || value instanceof Ref || value instanceof RowId) {
            return freezeJdbc(value, context, depth);
        }
        if (value instanceof Timestamp timestamp && jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
            return timestamp.toInstant();
        }
        return freezeJdbc(value, context, depth);
    }

    /** Stable display text shared by tables, search, comparisons, and clipboard formatting. */
    public String displayText() {
        return switch (kind) {
            case BINARY -> binaryText(bytes, binaryLength);
            case ARRAY -> arrayText(values);
            case STRUCT -> label + display(values.getFirst());
            case REFERENCE -> "REF " + label + "(" + display(values.getFirst()) + ")";
        };
    }

    /** Content-aware deterministic ordering used by local equality and comparison operators. */
    public int compareContent(ImmutableResultValue other) {
        Objects.requireNonNull(other, "other");
        int kindComparison = kind.compareTo(other.kind);
        if (kindComparison != 0) return kindComparison;
        int labelComparison = Objects.toString(label, "").compareTo(Objects.toString(other.label, ""));
        if (labelComparison != 0) return labelComparison;
        if (kind == Kind.BINARY) {
            int sharedLength = Math.min(bytes.length, other.bytes.length);
            for (int index = 0; index < sharedLength; index++) {
                int comparison = Integer.compare(bytes[index] & 0xff, other.bytes[index] & 0xff);
                if (comparison != 0) return comparison;
            }
            int storedLengthComparison = Integer.compare(bytes.length, other.bytes.length);
            if (storedLengthComparison != 0) return storedLengthComparison;
            int totalLengthComparison = Long.compare(binaryLength, other.binaryLength);
            return totalLengthComparison != 0
                    ? totalLengthComparison : compareUnsigned(fingerprint, other.fingerprint);
        }
        int sharedLength = Math.min(values.size(), other.values.size());
        for (int index = 0; index < sharedLength; index++) {
            int comparison = compareElement(values.get(index), other.values.get(index));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(values.size(), other.values.size());
    }

    @Override
    public String toString() {
        return displayText();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ImmutableResultValue value) || kind != value.kind) return false;
        if (!Objects.equals(label, value.label)) return false;
        return kind == Kind.BINARY
                ? binaryLength == value.binaryLength
                        && java.util.Arrays.equals(bytes, value.bytes)
                        && java.util.Arrays.equals(fingerprint, value.fingerprint)
                : values.equals(value.values);
    }

    @Override
    public int hashCode() {
        int content = kind == Kind.BINARY
                ? Objects.hash(java.util.Arrays.hashCode(bytes), java.util.Arrays.hashCode(fingerprint))
                : values.hashCode();
        return Objects.hash(kind, label, binaryLength, content);
    }

    private static boolean isStandardImmutable(Object value) {
        return value instanceof ImmutableResultValue || value instanceof String
                || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof UUID || value instanceof URI || value instanceof Enum<?>
                || "java.time".equals(value.getClass().getPackageName());
    }

    private static ImmutableResultValue binary(byte[] source) {
        byte[] value = Objects.requireNonNull(source, "binary value");
        int previewLength = Math.min(BINARY_PREVIEW_BYTES, value.length);
        byte[] preview = java.util.Arrays.copyOf(value, previewLength);
        return binary(preview, value.length, sha256().digest(value));
    }

    private static ImmutableResultValue binary(
            byte[] preview, long totalLength, byte[] fingerprint) {
        byte[] copiedPreview = Objects.requireNonNull(preview, "binary preview").clone();
        byte[] copiedFingerprint = Objects.requireNonNull(fingerprint, "binary fingerprint").clone();
        return new ImmutableResultValue(
                Kind.BINARY, copiedPreview, copiedFingerprint, totalLength, List.of(), null);
    }

    private static ImmutableResultValue freezeArray(
            Object array, FreezeContext context, int depth) throws SQLException {
        int length = Array.getLength(array);
        List<Object> copied = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            copied.add(freezeJdbc(Array.get(array, index), context, depth + 1));
        }
        return new ImmutableResultValue(
                Kind.ARRAY, null, null, 0, immutableNullableList(copied), null);
    }

    private static ImmutableResultValue freezeStruct(
            Struct struct, FreezeContext context, int depth) throws SQLException {
        Object attributes = freezeJdbc(struct.getAttributes(), context, depth + 1);
        return labeled(Kind.STRUCT, struct.getSQLTypeName(), attributes);
    }

    private static ImmutableResultValue freezeRef(
            Ref ref, FreezeContext context, int depth) throws SQLException {
        Object referenced = freezeJdbc(ref.getObject(), context, depth + 1);
        return labeled(Kind.REFERENCE, ref.getBaseTypeName(), referenced);
    }

    private static ImmutableResultValue labeled(Kind kind, String label, Object value) {
        return new ImmutableResultValue(kind, null, null, 0,
                Collections.singletonList(value), label == null ? "" : label);
    }

    private static Object readAndFreeClob(Clob clob) throws SQLException {
        return readAndCleanup(() -> {
            long length = clob.length();
            String text = clob.getSubString(1, (int) Math.min(500, length));
            return text + (length > 500 ? "..." : "");
        }, clob::free);
    }

    private static Object readAndFreeBlob(Blob blob) throws SQLException {
        return readAndCleanup(() -> {
            try (InputStream input = blob.getBinaryStream()) {
                if (input == null) throw new SQLException("BLOB 未返回二进制流");
                return binary(input);
            } catch (IOException failure) {
                throw new SQLException("读取 BLOB 失败", failure);
            }
        }, blob::free);
    }

    private static Object readAndFreeArray(
            java.sql.Array array, FreezeContext context, int depth) throws SQLException {
        return readAndCleanup(
                () -> freezeJdbc(array.getArray(), context, depth + 1), array::free);
    }

    private static Object readAndFreeSqlXml(SQLXML xml) throws SQLException {
        return readAndCleanup(xml::getString, xml::free);
    }

    private static Object freezeKnownProviderValue(
            Object value, FreezeContext context, int depth) throws SQLException {
        if (extendsNamed(value.getClass(), "org.postgresql.util.PGobject")) {
            Object text = invokeNoArg(value, "getValue");
            return text == null ? null : String.valueOf(text);
        }
        if (extendsNamed(value.getClass(), "oracle.sql.BFILE")) {
            Object directory = invokeNoArg(value, "getDirAlias");
            Object name = invokeNoArg(value, "getName");
            return labeled(Kind.STRUCT, "BFILE",
                    freezeJdbc(new Object[]{directory, name}, context, depth + 1));
        }
        if (!value.getClass().getName().startsWith("oracle.")) return MISSING;

        String simpleName = value.getClass().getSimpleName();
        String[] accessors = switch (simpleName) {
            case "NUMBER" -> new String[]{"bigDecimalValue", "stringValue"};
            case "BINARY_DOUBLE" -> new String[]{"doubleValue"};
            case "BINARY_FLOAT" -> new String[]{"floatValue"};
            case "BOOLEAN" -> new String[]{"booleanValue"};
            case "DATE", "TIMESTAMP" -> new String[]{"localDateTimeValue", "stringValue"};
            case "TIMESTAMPTZ" -> new String[]{"offsetDateTimeValue", "stringValue"};
            case "TIMESTAMPLTZ" -> new String[]{"toBytes"};
            case "INTERVALDS" -> new String[]{"getDuration", "stringValue"};
            case "INTERVALYM" -> new String[]{"getPeriod", "stringValue"};
            case "RAW", "ROWID" -> new String[]{"stringValue"};
            default -> new String[]{"getValue", "stringValue"};
        };
        for (String accessor : accessors) {
            Object candidate = invokeOptionalNoArg(value, accessor);
            if (candidate != MISSING && candidate != value) {
                return freezeJdbc(candidate, context, depth + 1);
            }
        }
        return MISSING;
    }

    private static Object invokeNoArg(Object target, String methodName) throws SQLException {
        Object result = invokeOptionalNoArg(target, methodName);
        if (result == MISSING) {
            throw new SQLException("JDBC 提供程序值缺少必要的读取方法");
        }
        return result;
    }

    private static Object invokeOptionalNoArg(Object target, String methodName) throws SQLException {
        try {
            Method method = accessiblePublicMethod(target.getClass(), methodName);
            if (method == null) return MISSING;
            return method.invoke(target);
        } catch (IllegalAccessException inaccessible) {
            throw new SQLException("无法访问 JDBC 提供程序值", inaccessible);
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof SQLException sqlFailure) throw sqlFailure;
            if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (cause instanceof Error error) throw error;
            throw new SQLException("读取 JDBC 提供程序值失败", cause);
        }
    }

    private static Method accessiblePublicMethod(Class<?> type, String methodName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (!Modifier.isPublic(current.getModifiers())) continue;
            try {
                return current.getMethod(methodName);
            } catch (NoSuchMethodException missing) {
                // Try the next public superclass.
            }
        }
        return null;
    }

    private static boolean extendsNamed(Class<?> type, String expectedName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (expectedName.equals(current.getName())) return true;
        }
        return false;
    }

    private static String binaryText(byte[] value, long totalLength) {
        int displayed = Math.min(BINARY_PREVIEW_BYTES, value.length);
        StringBuilder text = new StringBuilder(displayed * 2 + 24);
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < displayed; index++) {
            int unsigned = value[index] & 0xff;
            text.append(digits[unsigned >>> 4]).append(digits[unsigned & 0x0f]);
        }
        if (totalLength > displayed) {
            text.append("...(").append(totalLength).append(" bytes)");
        }
        return text.toString();
    }

    private static ImmutableResultValue binary(InputStream input) throws IOException {
        MessageDigest digest = sha256();
        byte[] preview = new byte[BINARY_PREVIEW_BYTES];
        byte[] buffer = new byte[8192];
        int previewLength = 0;
        long totalLength = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            digest.update(buffer, 0, count);
            int copied = Math.min(count, BINARY_PREVIEW_BYTES - previewLength);
            if (copied > 0) {
                System.arraycopy(buffer, 0, preview, previewLength, copied);
                previewLength += copied;
            }
            totalLength = Math.addExact(totalLength, count);
        }
        return binary(java.util.Arrays.copyOf(preview, previewLength), totalLength, digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 must be available", impossible);
        }
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int sharedLength = Math.min(left.length, right.length);
        for (int index = 0; index < sharedLength; index++) {
            int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private static String arrayText(List<Object> values) {
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) text.append(", ");
            text.append(display(values.get(index)));
        }
        return text.append(']').toString();
    }

    private static String display(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static int compareElement(Object left, Object right) {
        if (Objects.equals(left, right)) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if (left instanceof ImmutableResultValue leftValue
                && right instanceof ImmutableResultValue rightValue) {
            return leftValue.compareContent(rightValue);
        }
        int typeComparison = left.getClass().getName().compareTo(right.getClass().getName());
        if (typeComparison != 0) return typeComparison;
        int textComparison = String.valueOf(left).compareTo(String.valueOf(right));
        if (textComparison != 0) return textComparison;
        int hashComparison = Integer.compare(left.hashCode(), right.hashCode());
        return hashComparison == 0 ? 1 : hashComparison;
    }

    private static <T> List<T> immutableNullableList(List<? extends T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> T readAndCleanup(SqlSupplier<T> reader, SqlCleanup cleanup) throws SQLException {
        Throwable primary = null;
        try {
            return reader.get();
        } catch (SQLException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                cleanup.run();
            } catch (SQLException | RuntimeException | Error cleanupFailure) {
                if (primary == null) throw cleanupFailure;
                if (primary != cleanupFailure) primary.addSuppressed(cleanupFailure);
            }
        }
    }

    private static final class FreezeContext {
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();

        private void enter(Object value, int depth) {
            if (depth >= MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("JDBC 结果值嵌套层级过深");
            }
            if (active.containsKey(value)) {
                throw new IllegalArgumentException("JDBC 结果值包含循环引用");
            }
            active.put(value, Boolean.TRUE);
        }

        private void exit(Object value) {
            active.remove(value);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlCleanup {
        void run() throws SQLException;
    }
}
