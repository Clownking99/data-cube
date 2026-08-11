package com.datacube.provider.oracle;

import com.datacube.spi.schemadiff.QualifiedName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaIdentifierNormalizerTest {
    @Test
    void preservesExactCatalogIdentityWithLengthPrefixedDomainKeys() {
        QualifiedName schemaObject = OracleSchemaIdentifierNormalizer.object("A", "BC");
        QualifiedName otherSplit = OracleSchemaIdentifierNormalizer.object("AB", "C");

        assertEquals("\"A\".\"BC\"", schemaObject.original());
        assertEquals("oracle-object-v1\0" + "1:A2:BC", schemaObject.comparisonKey());
        assertNotEquals(schemaObject.comparisonKey(), otherSplit.comparisonKey());
        assertFalse(schemaObject.quoted());
        assertEquals("oracle-schema-v1\0" + "5:OWNER",
                OracleSchemaIdentifierNormalizer.schema("OWNER").comparisonKey());
        assertEquals("oracle-child-v1\0" + "6:COLUMN",
                OracleSchemaIdentifierNormalizer.child("COLUMN").comparisonKey());
    }

    @Test
    void alwaysDoubleQuotesOriginalAndPreservesQuotedOracleIdentity() {
        QualifiedName ordinary = OracleSchemaIdentifierNormalizer.schema("SALES_2026");
        QualifiedName mixedCase = OracleSchemaIdentifierNormalizer.object("Sales", "Order\"Line");
        QualifiedName reserved = OracleSchemaIdentifierNormalizer.child("SELECT");

        assertEquals("\"SALES_2026\"", ordinary.original());
        assertFalse(ordinary.quoted());
        assertEquals("\"Sales\".\"Order\"\"Line\"", mixedCase.original());
        assertEquals("oracle-object-v1\0" + "5:Sales10:Order\"Line", mixedCase.comparisonKey());
        assertTrue(mixedCase.quoted());
        assertEquals("\"SELECT\"", reserved.original());
        assertTrue(reserved.quoted());
    }

    @Test
    void followsConservativeOracleUnquotedUppercaseRules() {
        assertFalse(OracleSchemaIdentifierNormalizer.child("UPPER9$VALUE#").quoted());
        assertTrue(OracleSchemaIdentifierNormalizer.child("lower").quoted());
        assertTrue(OracleSchemaIdentifierNormalizer.child("MixedCase").quoted());
        assertTrue(OracleSchemaIdentifierNormalizer.child("9STARTS_WITH_DIGIT").quoted());
        assertTrue(OracleSchemaIdentifierNormalizer.child("数据").quoted());
    }

    @Test
    void rejectsNullEmptyAndNulCatalogIdentifiersWithoutEchoingInput() {
        assertThrows(NullPointerException.class,
                () -> OracleSchemaIdentifierNormalizer.schema(null));

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> OracleSchemaIdentifierNormalizer.child(""));
        IllegalArgumentException nul = assertThrows(IllegalArgumentException.class,
                () -> OracleSchemaIdentifierNormalizer.object("OWNER", "SECRET\0NAME"));

        assertEquals("Invalid Oracle catalog identifier", empty.getMessage());
        assertEquals("Invalid Oracle catalog identifier", nul.getMessage());
    }

    @Test
    void definitionNormalizerChangesOnlyLineEndingsEdgesAndOneProviderDelimiter() {
        String plsql = " \tCREATE OR REPLACE FUNCTION F RETURN VARCHAR2 IS\r\n"
                + "BEGIN\rRETURN 'text; / keep'; -- / keep\rEND;\r\n/\r\n";

        assertEquals("CREATE OR REPLACE FUNCTION F RETURN VARCHAR2 IS\n"
                        + "BEGIN\nRETURN 'text; / keep'; -- / keep\nEND;",
                OracleSchemaDefinitionNormalizer.normalize(plsql));
        assertEquals("CREATE VIEW V AS SELECT '; /' VALUE FROM DUAL;",
                OracleSchemaDefinitionNormalizer.normalize(
                        "CREATE VIEW V AS SELECT '; /' VALUE FROM DUAL;"));
        assertEquals("SELECT 1;;;", OracleSchemaDefinitionNormalizer.normalize(" SELECT 1;;; "));
        assertEquals("SELECT 1\n/", OracleSchemaDefinitionNormalizer.normalize("SELECT 1\n/\n/"));
        assertNull(OracleSchemaDefinitionNormalizer.normalize(null));
    }

    @Test
    void definitionNormalizerPreservesTrailingSemicolonsWithoutSlashSeparator() {
        for (String ddl : java.util.List.of(
                "CREATE VIEW V AS SELECT 1;",
                "CREATE TABLE T (ID NUMBER);",
                "CREATE FUNCTION F RETURN NUMBER IS BEGIN RETURN 1; END;",
                "CREATE PROCEDURE P IS BEGIN NULL; END;",
                "CREATE PACKAGE PKG IS PROCEDURE P; END;",
                "CREATE TYPE ADDRESS_T AS OBJECT (CITY VARCHAR2(20));",
                "CREATE TRIGGER TRG BEFORE INSERT ON T BEGIN NULL; END;")) {
            assertEquals(ddl, OracleSchemaDefinitionNormalizer.normalize(ddl), ddl);
        }
    }
}
