package com.datacube.provider.postgres;

import com.datacube.spi.schemadiff.QualifiedName;

import java.util.Locale;
import java.util.Objects;

/** PostgreSQL catalog identifier to stable, safely rendered snapshot name. */
public final class PgSchemaIdentifierNormalizer {
    private static final String SCHEMA_DOMAIN = "pg-schema-v1\0";
    private static final String OBJECT_DOMAIN = "pg-object-v1\0";
    private static final String CHILD_DOMAIN = "pg-child-v1\0";

    private PgSchemaIdentifierNormalizer() {
    }

    public static QualifiedName schema(String catalogSchema) {
        String schema = catalogIdentifier(catalogSchema);
        return new QualifiedName(quote(schema), SCHEMA_DOMAIN + schema, requiresQuoting(schema));
    }

    public static QualifiedName object(String catalogSchema, String catalogObject) {
        String schema = catalogIdentifier(catalogSchema);
        String object = catalogIdentifier(catalogObject);
        return new QualifiedName(
                quote(schema) + "." + quote(object),
                OBJECT_DOMAIN + schema + '\0' + object,
                requiresQuoting(schema) || requiresQuoting(object));
    }

    public static QualifiedName child(String catalogName) {
        String name = catalogIdentifier(catalogName);
        return new QualifiedName(quote(name), CHILD_DOMAIN + name, requiresQuoting(name));
    }

    static String quote(String catalogName) {
        return "\"" + catalogName.replace("\"", "\"\"") + "\"";
    }

    private static String catalogIdentifier(String value) {
        Objects.requireNonNull(value, "catalog identifier");
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid PostgreSQL catalog identifier");
        }
        return value;
    }

    private static boolean requiresQuoting(String identifier) {
        if (!identifier.equals(identifier.toLowerCase(Locale.ROOT))) return true;
        int offset = 0;
        int first = identifier.codePointAt(offset);
        if (first != '_' && !Character.isLetter(first)) return true;
        offset += Character.charCount(first);
        while (offset < identifier.length()) {
            int codePoint = identifier.codePointAt(offset);
            if (codePoint != '_' && codePoint != '$'
                    && !Character.isLetter(codePoint) && !Character.isDigit(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }
}
