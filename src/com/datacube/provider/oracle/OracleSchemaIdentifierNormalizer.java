package com.datacube.provider.oracle;

import com.datacube.spi.schemadiff.QualifiedName;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Oracle catalog identifier to stable, safely rendered snapshot name. */
public final class OracleSchemaIdentifierNormalizer {
    private static final String SCHEMA_DOMAIN = "oracle-schema-v1\0";
    private static final String OBJECT_DOMAIN = "oracle-object-v1\0";
    private static final String CHILD_DOMAIN = "oracle-child-v1\0";
    private static final Set<String> RESERVED_WORDS = Set.of(
            "ACCESS", "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "AUDIT",
            "BETWEEN", "BY", "CHAR", "CHECK", "CLUSTER", "COLUMN", "COMMENT",
            "COMPRESS", "CONNECT", "CREATE", "CURRENT", "DATE", "DECIMAL", "DEFAULT",
            "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "EXCLUSIVE", "EXISTS",
            "FILE", "FLOAT", "FOR", "FROM", "GRANT", "GROUP", "HAVING", "IDENTIFIED",
            "IMMEDIATE", "IN", "INCREMENT", "INDEX", "INITIAL", "INSERT", "INTEGER",
            "INTERSECT", "INTO", "IS", "LEVEL", "LIKE", "LOCK", "LONG", "MAXEXTENTS",
            "MINUS", "MLSLABEL", "MODE", "MODIFY", "NOAUDIT", "NOCOMPRESS", "NOT",
            "NOWAIT", "NULL", "NUMBER", "OF", "OFFLINE", "ON", "ONLINE", "OPTION",
            "OR", "ORDER", "PCTFREE", "PRIOR", "PRIVILEGES", "PUBLIC", "RAW", "RENAME",
            "RESOURCE", "REVOKE", "ROW", "ROWID", "ROWNUM", "ROWS", "SELECT", "SESSION",
            "SET", "SHARE", "SIZE", "SMALLINT", "START", "SUCCESSFUL", "SYNONYM",
            "SYSDATE", "TABLE", "THEN", "TO", "TRIGGER", "UID", "UNION", "UNIQUE",
            "UPDATE", "USER", "VALIDATE", "VALUES", "VARCHAR", "VARCHAR2", "VIEW",
            "WHENEVER", "WHERE", "WITH");

    private OracleSchemaIdentifierNormalizer() {
    }

    public static QualifiedName schema(String catalogOwner) {
        String owner = catalogIdentifier(catalogOwner);
        return new QualifiedName(quote(owner), SCHEMA_DOMAIN + field(owner), requiresQuoting(owner));
    }

    public static QualifiedName object(String catalogOwner, String catalogObject) {
        String owner = catalogIdentifier(catalogOwner);
        String object = catalogIdentifier(catalogObject);
        return new QualifiedName(
                quote(owner) + "." + quote(object),
                OBJECT_DOMAIN + field(owner) + field(object),
                requiresQuoting(owner) || requiresQuoting(object));
    }

    public static QualifiedName child(String catalogName) {
        String name = catalogIdentifier(catalogName);
        return new QualifiedName(quote(name), CHILD_DOMAIN + field(name), requiresQuoting(name));
    }

    static String quote(String catalogName) {
        return "\"" + catalogName.replace("\"", "\"\"") + "\"";
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }

    private static String catalogIdentifier(String value) {
        Objects.requireNonNull(value, "catalog identifier");
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid Oracle catalog identifier");
        }
        return value;
    }

    private static boolean requiresQuoting(String identifier) {
        if (!identifier.equals(identifier.toUpperCase(Locale.ROOT))) return true;
        if (RESERVED_WORDS.contains(identifier)) return true;
        char first = identifier.charAt(0);
        if (first < 'A' || first > 'Z') return true;
        for (int index = 1; index < identifier.length(); index++) {
            char character = identifier.charAt(index);
            boolean allowed = character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '$' || character == '#';
            if (!allowed) return true;
        }
        return false;
    }
}
