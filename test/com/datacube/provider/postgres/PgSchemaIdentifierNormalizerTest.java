package com.datacube.provider.postgres;

import com.datacube.spi.schemadiff.QualifiedName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSchemaIdentifierNormalizerTest {
    @Test
    void preservesCatalogIdentityWithUnambiguousSchemaAndObjectKeys() {
        QualifiedName schemaObject = PgSchemaIdentifierNormalizer.object("a", "bc");
        QualifiedName otherSplit = PgSchemaIdentifierNormalizer.object("ab", "c");

        assertEquals("\"a\".\"bc\"", schemaObject.original());
        assertEquals("pg-object-v1\0a\0bc", schemaObject.comparisonKey());
        assertNotEquals(schemaObject.comparisonKey(), otherSplit.comparisonKey());
        assertFalse(schemaObject.quoted());
    }

    @Test
    void quotesCatalogNamesSafelyAndMarksOnlyNamesThatNeedPgQuoting() {
        QualifiedName ordinary = PgSchemaIdentifierNormalizer.schema("sales_2026");
        QualifiedName mixedCase = PgSchemaIdentifierNormalizer.object("sales", "Order\"Line");
        QualifiedName child = PgSchemaIdentifierNormalizer.child("select value");

        assertEquals("\"sales_2026\"", ordinary.original());
        assertFalse(ordinary.quoted());
        assertEquals("\"sales\".\"Order\"\"Line\"", mixedCase.original());
        assertEquals("pg-object-v1\0sales\0Order\"Line", mixedCase.comparisonKey());
        assertTrue(mixedCase.quoted());
        assertEquals("\"select value\"", child.original());
        assertEquals("pg-child-v1\0select value", child.comparisonKey());
        assertTrue(child.quoted());
    }

    @Test
    void followsPostgresUnquotedLowercaseIdentifierRules() {
        assertFalse(PgSchemaIdentifierNormalizer.child("_lower9$value").quoted());
        assertFalse(PgSchemaIdentifierNormalizer.child("数据").quoted());
        assertTrue(PgSchemaIdentifierNormalizer.child("HasUpper").quoted());
        assertTrue(PgSchemaIdentifierNormalizer.child("9starts_with_digit").quoted());
    }

    @Test
    void definitionNormalizerChangesOnlyLineEndingsEdgesAndOneTrailingDelimiter() {
        String definition = " \tCREATE FUNCTION f() RETURNS text\r\n"
                + "LANGUAGE sql AS $$\rSELECT 'a;  b' -- keep  gap\r$$; \t";

        assertEquals("CREATE FUNCTION f() RETURNS text\n"
                        + "LANGUAGE sql AS $$\nSELECT 'a;  b' -- keep  gap\n$$",
                PgSchemaDefinitionNormalizer.normalize(definition));
        assertEquals("SELECT  1;;", PgSchemaDefinitionNormalizer.normalize(" SELECT  1;;;  "));
        assertEquals("SELECT ';' -- ;", PgSchemaDefinitionNormalizer.normalize("SELECT ';' -- ;"));
        assertNull(PgSchemaDefinitionNormalizer.normalize(null));
    }

    @Test
    void definitionNormalizerDistinguishesStandardAndEscapeStrings() {
        assertEquals("SELECT 'path\\'",
                PgSchemaDefinitionNormalizer.normalize("SELECT 'path\\';"));
        assertEquals("SELECT E'it\\'s'",
                PgSchemaDefinitionNormalizer.normalize("SELECT E'it\\'s';"));
        assertEquals("SELECT e'it\\'s'",
                PgSchemaDefinitionNormalizer.normalize("SELECT e'it\\'s';"));
    }

    @Test
    void definitionNormalizerRequiresIdentifierBoundaryForDollarQuoteOpeners() {
        assertEquals("SELECT identifier$tag$value",
                PgSchemaDefinitionNormalizer.normalize("SELECT identifier$tag$value;"));
        assertEquals("SELECT $tag$semi;colon$tag$",
                PgSchemaDefinitionNormalizer.normalize("SELECT $tag$semi;colon$tag$;"));
        assertEquals("SELECT $tag$unterminated$Tag$;",
                PgSchemaDefinitionNormalizer.normalize("SELECT $tag$unterminated$Tag$;"));
    }
}
