package com.datacube.provider.oracle;

import com.datacube.schemadiff.PropertyDifference;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.TableDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaChangeRendererTest {
    private static final OracleSchemaChangeRenderer RENDERER =
            new OracleSchemaChangeRenderer();

    @Test
    void definitionOwnerRetargetingDistinguishesScopedAliasesCtesAndRealRelations() {
        ObjectKey viewKey = key(ObjectType.VIEW, "Source", "SCOPED", "");
        String ddl = """
                CREATE VIEW "Source"."SCOPED" AS
                WITH "Source" AS (SELECT 1 AS ID FROM DUAL)
                SELECT "Source".ID, "Source"."ORDERS", "Source".VALUE,
                       NESTED.ID, '"Source"."LITERAL"' AS LABEL
                FROM "Source"."ORDERS" "Source"
                JOIN (SELECT "Source".ID
                      FROM "Source"."NESTED" AS "Source") NESTED ON 1 = 1
                JOIN "Source"."REAL" REAL ON REAL.ID = NESTED.ID
                /* "Source"."COMMENT" */
                ;
                """;
        SchemaChange create = change(ChangeKind.CREATE, viewKey, definition(viewKey, ddl),
                null, null, AutomationLevel.SAFE_AUTOMATIC);

        String rendered = RENDERER.render(create, context(DbType.ORACLE, false)).getFirst().sql();
        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");

        assertTrue(rendered.startsWith("CREATE VIEW \"Target\"\"Owner\".\"SCOPED\""));
        assertTrue(rendered.contains("FROM \"Target\"\"Owner\".\"ORDERS\" \"Source\""));
        assertTrue(rendered.contains(
                "SELECT \"Source\".ID, \"Source\".\"ORDERS\", \"Source\".VALUE"), rendered);
        assertTrue(rendered.contains("FROM \"Target\"\"Owner\".\"NESTED\" AS \"Source\""));
        assertTrue(rendered.contains("JOIN \"Target\"\"Owner\".\"REAL\" REAL"));
        assertEquals(2, countOccurrences(rendered, "SELECT \"Source\".ID"));
        assertTrue(rendered.contains("'\"Source\".\"LITERAL\"'"));
        assertTrue(rendered.contains("/* \"Source\".\"COMMENT\" */"));
        assertEquals(2, countOccurrences(projected, "SELECT \"Source\".ID"));
        assertTrue(projected.contains(
                "SELECT \"Source\".ID, \"Source\".\"ORDERS\", \"Source\".VALUE"), projected);
        assertEquals(4, countOccurrences(projected, "\0oracle-self-owner\0"));
    }

    @Test
    void commaSeparatedRelationAliasesAreNeverTreatedAsSchemaQualifiers() {
        String ddl = "CREATE VIEW \"Source\".\"COMMA_ALIAS\" AS "
                + "SELECT \"Source\".ID FROM EXTERNAL_TABLE, OTHER_TABLE AS \"Source\";";

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");

        assertTrue(projected.contains("SELECT \"Source\".ID"));
        assertEquals(1, countOccurrences(projected, "\0oracle-self-owner\0"));
        assertEquals(OracleSchemaChangeRenderer.UNSAFE_DEFINITION,
                assertThrows(IllegalArgumentException.class,
                        () -> OracleSchemaChangeRenderer.comparisonFragment(
                                "\"Source\".ID", "Source")).getMessage());
    }

    @Test
    void nestedAliasDoesNotShadowAnOuterSchemaQualifier() {
        String ddl = "CREATE VIEW \"Source\".\"SHADOW\" AS SELECT \"Source\".\"PKG\".FN(), "
                + "(SELECT \"Source\".ID FROM NESTED_TABLE AS \"Source\") FROM OUTER_TABLE;";

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");

        assertTrue(projected.contains("SELECT \0oracle-self-owner\0.\"PKG\".FN()"));
        assertTrue(projected.contains("SELECT \"Source\".ID FROM NESTED_TABLE"));
    }

    @Test
    void plSqlBindingsShadowOwnerCallsWhileRealPackageReferencesRetargetByBlockScope() {
        ObjectKey functionKey = key(ObjectType.FUNCTION, "Source", "SCOPED_FN",
                oracleSignature("IN", "NUMBER"));
        String ddl = """
                CREATE OR REPLACE EDITIONABLE FUNCTION "Source"."SCOPED_FN" ("Source" IN NUMBER)
                RETURN NUMBER AS
                  local_value "Source"."OBJ_T";
                BEGIN
                  "Source"."RUN"();
                  "Source"."PKG"."RUN"();
                  DECLARE
                    "Source" "Source"."OBJ_T";
                  BEGIN
                    "Source"."RUN"();
                  END;
                  "Source"."PKG"."RUN"();
                  RETURN 1;
                END;
                /
                """;
        SchemaChange create = change(ChangeKind.CREATE, functionKey,
                definition(functionKey, ddl), null, null, AutomationLevel.SAFE_AUTOMATIC);

        String rendered = RENDERER.render(create, context(DbType.ORACLE, false)).getFirst().sql();
        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");

        assertTrue(rendered.startsWith(
                "CREATE OR REPLACE EDITIONABLE FUNCTION \"Target\"\"Owner\".\"SCOPED_FN\""), rendered);
        assertTrue(rendered.contains("local_value \"Target\"\"Owner\".\"OBJ_T\""), rendered);
        assertEquals(2, countOccurrences(rendered, "\"Source\".\"RUN\"()"));
        assertEquals(2, countOccurrences(rendered,
                "\"Source\".\"PKG\".\"RUN\"()"), rendered);
        assertTrue(rendered.contains("\"Source\" \"Target\"\"Owner\".\"OBJ_T\""));
        assertFalse(rendered.contains("\"Target\"\"Owner\".\"RUN\"()"));
        assertEquals(2, countOccurrences(projected, "\"Source\".\"RUN\"()"));
        assertEquals(2, countOccurrences(projected,
                "\"Source\".\"PKG\".\"RUN\"()"));

        ObjectKey unshadowedKey = key(ObjectType.FUNCTION, "Source", "PACKAGE_FN",
                oracleSignature());
        String unshadowed = """
                CREATE FUNCTION "Source"."PACKAGE_FN" RETURN NUMBER AS
                BEGIN
                  "Source"."PKG"."RUN"();
                  RETURN 1;
                END;
                /
                """;
        String renderedUnshadowed = RENDERER.render(change(ChangeKind.CREATE, unshadowedKey,
                definition(unshadowedKey, unshadowed), null, null,
                AutomationLevel.SAFE_AUTOMATIC), context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(renderedUnshadowed.contains(
                "\"Target\"\"Owner\".\"PKG\".\"RUN\"()"), renderedUnshadowed);
        assertTrue(OracleSchemaChangeRenderer.comparisonDefinition(unshadowed, "Source")
                .contains("\0oracle-self-owner\0.\"PKG\".\"RUN\"()"));
    }

    @Test
    void plSqlLabelsAndNestedLocalRoutinesKeepIndependentBindingsAndRetargetRealPackages() {
        ObjectKey functionKey = key(ObjectType.FUNCTION, "Source", "NESTED_FN",
                oracleSignature());
        String ddl = """
                CREATE FUNCTION "Source"."NESTED_FN" RETURN NUMBER AS
                  outer_value "Source"."OBJ_T";
                  FUNCTION local_fn("Source" IN "Source"."OBJ_T") RETURN NUMBER IS
                    local_record "Source"."OBJ_T";
                    PROCEDURE nested_proc("Source" IN NUMBER) IS
                      nested_record "Source"."OBJ_T";
                    BEGIN
                      "Source".nested_record.field := 1;
                    END nested_proc;
                  BEGIN
                    "Source".local_record.field := 2;
                    RETURN 1;
                  END local_fn;
                BEGIN
                  <<"Source">>
                  DECLARE
                    label_record "Source"."OBJ_T";
                  BEGIN
                    "Source".label_record.field := 3;
                  END;
                  "Source"."PKG"."RUN"();
                  RETURN local_fn(outer_value);
                END;
                /
                """;

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");
        String rendered = RENDERER.render(change(ChangeKind.CREATE, functionKey,
                        definition(functionKey, ddl), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();

        assertTrue(projected.contains("\"Source\".nested_record.field"), projected);
        assertTrue(projected.contains("\"Source\".local_record.field"), projected);
        assertTrue(projected.contains("\"Source\".label_record.field"), projected);
        assertTrue(projected.contains("\0oracle-self-owner\0.\"PKG\".\"RUN\"()"), projected);
        assertTrue(rendered.contains("\"Source\".nested_record.field"), rendered);
        assertTrue(rendered.contains("\"Source\".local_record.field"), rendered);
        assertTrue(rendered.contains("\"Source\".label_record.field"), rendered);
        assertTrue(rendered.contains("\"Target\"\"Owner\".\"PKG\".\"RUN\"()"), rendered);

        String targetDdl = ddl.replace("\"Source\".\"NESTED_FN\"",
                        "\"Target\"\"Owner\".\"NESTED_FN\"")
                .replace("\"Source\".\"OBJ_T\"", "\"Target\"\"Owner\".\"OBJ_T\"")
                .replace("\"Source\".\"PKG\"", "\"Target\"\"Owner\".\"PKG\"");
        assertDoesNotThrow(() -> OracleSchemaChangeRenderer.comparisonDefinition(
                targetDdl, "Target\"Owner"));
    }

    @Test
    void callSpecClassificationIgnoresLanguageTokensInStringsAndComments() {
        String plSql = "CREATE FUNCTION \"Source\".\"SAFE_F\" RETURN VARCHAR2 IS "
                + "BEGIN /* LANGUAGE C */ RETURN 'LANGUAGE JAVA'; END;";
        String javaCallSpec = "CREATE FUNCTION \"Source\".\"JAVA_F\" RETURN NUMBER "
                + "AS LANGUAGE JAVA NAME 'example.Owner.call() return int';";
        String cCallSpec = "CREATE PROCEDURE \"Source\".\"C_P\" AS LANGUAGE C "
                + "LIBRARY \"Source\".\"NATIVE_LIB\" NAME \"native_call\";";

        assertTrue(OracleSchemaChangeRenderer.supportsAutomaticRoutineDefinition(
                plSql, "Source"));
        assertFalse(OracleSchemaChangeRenderer.supportsAutomaticRoutineDefinition(
                javaCallSpec, "Source"));
        assertFalse(OracleSchemaChangeRenderer.supportsAutomaticRoutineDefinition(
                cCallSpec, "Source"));
    }

    @Test
    void plSqlCaseExpressionsStatementsAndFollowingNestedScopesKeepRoutineBoundaries() {
        ObjectKey functionKey = key(ObjectType.FUNCTION, "Source", "CASE_FN",
                oracleSignature());
        String ddl = """
                CREATE FUNCTION "Source"."CASE_FN" RETURN NUMBER AS
                  FUNCTION local_fn(value IN NUMBER) RETURN NUMBER IS
                  BEGIN
                    RETURN CASE WHEN value > 0 THEN value ELSE 0 END;
                  END local_fn;
                BEGIN
                  CASE local_fn(1)
                    WHEN 1 THEN NULL;
                    ELSE NULL;
                  END CASE;
                  <<after_case>>
                  DECLARE
                    "Source" "Source"."OBJ_T";
                  BEGIN
                    "Source".value := CASE WHEN 1 = 1 THEN 1 ELSE 0 END;
                  END;
                  "Source"."PKG"."RUN"();
                  RETURN local_fn(1);
                END;
                /
                """;

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");
        String rendered = RENDERER.render(change(ChangeKind.CREATE, functionKey,
                        definition(functionKey, ddl), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();

        assertTrue(projected.contains("\"Source\".value := CASE"), projected);
        assertTrue(projected.contains("\0oracle-self-owner\0.\"PKG\".\"RUN\"()"), projected);
        assertTrue(rendered.contains("\"Source\".value := CASE"), rendered);
        assertTrue(rendered.contains("\"Target\"\"Owner\".\"PKG\".\"RUN\"()"), rendered);

        String trailingGarbage = ddl + " unexpected_token";
        assertFalse(OracleSchemaChangeRenderer.supportsAutomaticRoutineDefinition(
                trailingGarbage, "Source"));
        assertEquals(OracleSchemaChangeRenderer.UNSAFE_DEFINITION,
                assertThrows(IllegalArgumentException.class,
                        () -> OracleSchemaChangeRenderer.comparisonDefinition(
                                trailingGarbage, "Source")).getMessage());
    }

    @Test
    void packageTypeBodyAndTriggerUseRecursiveMemberLocalLabelAndCaseScopes() {
        ObjectKey packageKey = key(ObjectType.PACKAGE_BODY, "Source", "API", "");
        ObjectKey packageSpec = key(ObjectType.PACKAGE_SPEC, "Source", "API", "");
        String packageDdl = """
                CREATE PACKAGE BODY "Source"."API" AS
                  FUNCTION member_fn(value IN NUMBER) RETURN NUMBER IS
                    PROCEDURE local_proc("Source" IN NUMBER) IS
                    BEGIN
                      CASE WHEN "Source" > 0 THEN NULL; ELSE NULL; END CASE;
                    END local_proc;
                  BEGIN
                    <<"Source">>
                    DECLARE rec "Source"."OBJ_T";
                    BEGIN "Source".rec.value := 1; END;
                    "Source"."HELPERS"."RUN"();
                    RETURN CASE WHEN value > 0 THEN value ELSE 0 END;
                  END member_fn;
                BEGIN
                  NULL;
                END API;
                /
                """;
        ObjectKey typeKey = key(ObjectType.TYPE, "Source", "OBJ_T", "BODY");
        ObjectKey typeSpec = key(ObjectType.TYPE, "Source", "OBJ_T", "SPEC");
        String typeDdl = """
                CREATE TYPE BODY "Source"."OBJ_T" AS
                  MEMBER FUNCTION value RETURN NUMBER IS
                  BEGIN
                    RETURN CASE WHEN 1 = 1 THEN 1 ELSE 0 END;
                  END value;
                END;
                /
                """;
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ObjectKey triggerKey = key(ObjectType.TRIGGER, "Source", "ORDERS_TRG", "");
        String triggerDdl = """
                CREATE TRIGGER "Source"."ORDERS_TRG" BEFORE INSERT ON "Source"."ORDERS"
                DECLARE
                  PROCEDURE local_proc("Source" IN NUMBER) IS
                  BEGIN "Source".value := 1; END local_proc;
                BEGIN
                  CASE WHEN INSERTING THEN NULL; ELSE NULL; END CASE;
                  "Source"."AUDIT"."RUN"();
                END;
                /
                """;
        assertDoesNotThrow(() -> OracleSchemaChangeRenderer.comparisonDefinition(
                packageDdl, "Source"), "package body");
        assertDoesNotThrow(() -> OracleSchemaChangeRenderer.comparisonDefinition(
                typeDdl, "Source"), "type body");
        assertDoesNotThrow(() -> OracleSchemaChangeRenderer.comparisonDefinition(
                triggerDdl, "Source"), "trigger");

        for (DefinitionCase definitionCase : List.of(
                new DefinitionCase(packageKey, packageDdl, Set.of(packageSpec), true),
                new DefinitionCase(typeKey, typeDdl, Set.of(typeSpec), true),
                new DefinitionCase(triggerKey, triggerDdl, Set.of(tableKey), true))) {
            String projected = OracleSchemaChangeRenderer.comparisonDefinition(
                    definitionCase.ddl(), "Source");
            String rendered = RENDERER.render(change(ChangeKind.CREATE, definitionCase.key(),
                            definition(definitionCase.key(), definitionCase.ddl(),
                                    definitionCase.dependencies()), null, null,
                            AutomationLevel.SAFE_AUTOMATIC),
                    context(DbType.ORACLE, false)).getFirst().sql();

            assertTrue(projected.contains("\0oracle-self-owner\0"), projected);
            assertTrue(rendered.contains("\"Target\"\"Owner\""), rendered);
            assertFalse(rendered.contains("\0oracle-"), rendered);
        }
        String projectedPackage = OracleSchemaChangeRenderer.comparisonDefinition(
                packageDdl, "Source");
        assertTrue(projectedPackage.contains("\"Source\".rec.value"), projectedPackage);
        assertTrue(projectedPackage.contains(
                "\0oracle-self-owner\0.\"HELPERS\".\"RUN\"()"), projectedPackage);
    }

    @Test
    void oracleLabelThreePartChainsRequireDeclaredBindingOrProvablePackageCall() {
        String safe = """
                CREATE FUNCTION "Source"."LABEL_FN" RETURN NUMBER AS
                BEGIN
                  <<"Source">>
                  DECLARE
                    rec "Source"."OBJ_T";
                  BEGIN
                    "Source".rec.value := 1;
                    "Source"."PKG"."RUN"();
                  END;
                  "Source"."PKG"."RUN"();
                  RETURN 1;
                END;
                /
                """;
        String projected = OracleSchemaChangeRenderer.comparisonDefinition(safe, "Source");
        assertTrue(projected.contains("\"Source\".rec.value"), projected);
        assertEquals(2, countOccurrences(projected,
                "\0oracle-self-owner\0.\"PKG\".\"RUN\"()"), projected);

        String undeclared = safe.replace("\"Source\".rec.value := 1;",
                "\"Source\".missing.value := 1;");
        assertFalse(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(
                undeclared, "Source"));
        assertEquals(OracleSchemaChangeRenderer.UNSAFE_DEFINITION,
                assertThrows(IllegalArgumentException.class,
                        () -> OracleSchemaChangeRenderer.comparisonDefinition(
                                undeclared, "Source")).getMessage());
    }

    @Test
    void oracleLabelUsesOnlyItsExactOwningScopeDeclarations() {
        String safe = """
                CREATE FUNCTION "Source"."LABEL_SCOPE" RETURN NUMBER AS
                  outer_record "Source"."OBJ_T";
                BEGIN
                  <<"Source">>
                  DECLARE own_record "Source"."OBJ_T";
                  BEGIN
                    "Source".own_record.value := 1;
                    SELECT ID INTO own_record.value
                    FROM "Source"."ORDERS" "Source";
                  END "Source";
                  RETURN 1;
                END;
                /
                """;

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(safe, "Source");
        assertTrue(projected.contains("\"Source\".own_record.value"), projected);
        assertTrue(projected.contains("FROM \0oracle-self-owner\0.\"ORDERS\""), projected);

        String outerBinding = safe.replace("\"Source\".own_record.value := 1",
                "\"Source\".outer_record.value := 1");
        assertTrue(OracleSchemaChangeRenderer.comparisonDefinition(outerBinding, "Source")
                .contains("\"Source\".outer_record.value := 1"));
        String sqlAlias = safe.replace("\"Source\".own_record.value := 1",
                "\"Source\".ID.value := 1");
        assertTrue(OracleSchemaChangeRenderer.comparisonDefinition(sqlAlias, "Source")
                .contains("\"Source\".ID.value := 1"));
    }

    @Test
    void oracleClosingLabelsMatchTheirOpeningScopeExactly() {
        String quoted = "CREATE FUNCTION \"Source\".\"LABELS\" RETURN NUMBER AS BEGIN "
                + "<<\"MiXeD\">> BEGIN <<inner>> BEGIN NULL; END inner; END \"MiXeD\"; "
                + "RETURN 1; END; /";
        assertTrue(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(
                quoted, "Source"));

        for (String invalid : List.of(
                quoted.replace("END \"MiXeD\"", "END mixed"),
                quoted.replace("END inner", "END missing"),
                quoted.replace("<<inner>> BEGIN NULL; END inner;", "BEGIN NULL; END orphan;"))) {
            assertFalse(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(
                    invalid, "Source"));
        }
    }

    @Test
    void packageSpecDeclarationsAreScopedAndUnknownGrammarFailsClosed() {
        String ddl = """
                CREATE PACKAGE "Source"."API" AS
                  "Source" CONSTANT "Source"."OBJ_T" := NULL;
                  same_name NUMBER := "Source".value;
                  TYPE rec_t IS RECORD (value "Source"."OBJ_T");
                  public_record rec_t := "Source".orders;
                  FUNCTION make(value IN "Source"."OBJ_T") RETURN "Source"."OBJ_T";
                  PROCEDURE forward(value IN "External"."OBJ_T");
                END API;
                /
                """;

        assertTrue(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(ddl, "Source"));
        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");
        assertEquals(5, countOccurrences(projected, "\0oracle-self-owner\0"), projected);
        assertTrue(projected.contains("same_name NUMBER := \"Source\".value"), projected);
        assertTrue(projected.contains("public_record rec_t := \"Source\".orders"), projected);
        assertTrue(projected.contains("\"External\".\"OBJ_T\""), projected);

        String unknown = ddl.replace("TYPE rec_t IS RECORD (value \"Source\".\"OBJ_T\");",
                "MYSTERY DECLARATION \"Source\".thing;");
        assertFalse(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(
                unknown, "Source"));
        assertEquals(OracleSchemaChangeRenderer.UNSAFE_DEFINITION,
                assertThrows(IllegalArgumentException.class,
                        () -> OracleSchemaChangeRenderer.comparisonDefinition(
                                unknown, "Source")).getMessage());
    }

    @Test
    void labelOwnedDeclarationWinsForOrdinaryChainButRelationSourceStillRetargets() {
        String ddl = """
                CREATE FUNCTION "Source"."LABEL_RELATION" RETURN NUMBER AS
                BEGIN
                  <<"Source">>
                  DECLARE orders "Source"."ORDER_REC";
                  BEGIN
                    "Source".orders.value := 1;
                    SELECT ID INTO orders.value FROM "Source".orders;
                  END "Source";
                  RETURN 1;
                END;
                /
                """;

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");
        assertTrue(projected.contains("\"Source\".orders.value := 1"), projected);
        assertTrue(projected.contains("FROM \0oracle-self-owner\0.orders"), projected);
    }

    @Test
    void proceduralIntoTargetsStayBoundWhileInsertMergeAndFromRelationsRetarget() {
        String ddl = """
                CREATE FUNCTION "Source"."INTO_SCOPE" RETURN NUMBER AS
                BEGIN
                  <<"Source">>
                  DECLARE orders "Source"."ORDER_REC";
                  BEGIN
                    SELECT VALUE INTO "Source".orders.value FROM "Source".orders;
                    WITH picked AS (SELECT VALUE FROM "Source".orders)
                      SELECT VALUE INTO "Source".orders.value FROM picked;
                    SELECT VALUE BULK COLLECT INTO "Source".orders.values
                      FROM "Source".orders;
                    UPDATE "Source".orders SET VALUE = 1
                      RETURNING VALUE INTO "Source".orders.value;
                    INSERT INTO "Source".orders(VALUE) VALUES (1)
                      RETURNING VALUE INTO "Source".orders.value;
                    MERGE INTO "Source".orders target
                      USING "Source".incoming source ON (target.ID = source.ID)
                      WHEN MATCHED THEN UPDATE SET target.VALUE = source.VALUE;
                  END "Source";
                  RETURN 1;
                END;
                /
                """;

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");

        assertTrue(projected.contains(
                "SELECT VALUE INTO \"Source\".orders.value FROM"), projected);
        assertEquals(2, countOccurrences(projected,
                "SELECT VALUE INTO \"Source\".orders.value FROM"), projected);
        assertEquals(2, countOccurrences(projected,
                "RETURNING VALUE INTO \"Source\".orders.value"), projected);
        assertTrue(projected.contains(
                "BULK COLLECT INTO \"Source\".orders.values"), projected);
        assertEquals(3, countOccurrences(projected,
                "FROM \0oracle-self-owner\0.orders"), projected);
        assertTrue(projected.contains("UPDATE \0oracle-self-owner\0.orders"), projected);
        assertTrue(projected.contains(
                "INSERT INTO \0oracle-self-owner\0.orders"), projected);
        assertTrue(projected.contains(
                "MERGE INTO \0oracle-self-owner\0.orders"), projected);
        assertTrue(projected.contains(
                "USING \0oracle-self-owner\0.incoming"), projected);
    }

    @Test
    void oracleFormalTypesExcludeDefaultExpressionsFromOwnerProof() {
        String safe = """
                CREATE FUNCTION "Source"."FORMALS"(
                  value IN "Source"."ARG_T" DEFAULT NULL,
                  copied IN "Source".source_table.column_name%TYPE,
                  row_value IN "Source".source_table%ROWTYPE)
                RETURN "Source"."RESULT_T" AS
                BEGIN RETURN NULL; END;
                /
                """;
        String projected = OracleSchemaChangeRenderer.comparisonDefinition(safe, "Source");
        assertTrue(projected.contains("IN \0oracle-self-owner\0.\"ARG_T\" DEFAULT"), projected);
        assertTrue(projected.contains("RETURN \0oracle-self-owner\0.\"RESULT_T\""), projected);
        assertTrue(projected.contains(
                "\0oracle-self-owner\0.source_table.column_name%TYPE"), projected);
        assertTrue(projected.contains(
                "\0oracle-self-owner\0.source_table%ROWTYPE"), projected);

        String ambiguousDefault = safe.replace("DEFAULT NULL",
                "DEFAULT \"Source\".unknown.field");
        assertFalse(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(
                ambiguousDefault, "Source"));
    }

    @Test
    void oracleTypeSpecProjectsOnlyAttributeAndMethodTypePositions() {
        String ddl = """
                CREATE TYPE "Source"."ORDER_T" AS OBJECT (
                  id "Source"."ID_T",
                  external_value "External"."VALUE_T",
                  MEMBER FUNCTION current_value RETURN "Source"."RESULT_T",
                  MEMBER FUNCTION convert(value IN "Source"."ARG_T")
                    RETURN "Source"."RESULT_T"
                );
                """;
        String projected = OracleSchemaChangeRenderer.comparisonDefinition(ddl, "Source");
        assertEquals(5, countOccurrences(projected, "\0oracle-self-owner\0"), projected);
        assertTrue(projected.contains("\"External\".\"VALUE_T\""), projected);

        String unknown = ddl.replace("id \"Source\".\"ID_T\",",
                "MYSTERY id \"Source\".\"ID_T\",");
        assertFalse(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(
                unknown, "Source"));

        for (String unsupported : List.of(
                "CREATE TYPE \"Source\".\"LIST_T\" AS VARRAY(10) OF \"Source\".\"ID_T\";",
                "CREATE TYPE \"Source\".\"TABLE_T\" AS TABLE OF \"Source\".\"ID_T\";")) {
            assertFalse(OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(
                    unsupported, "Source"));
        }
    }

    @Test
    void embeddedQuoteOwnerAndMixedCaseBindingAreProjectedConservatively() {
        String ddl = "CREATE FUNCTION \"Source\"\"Owner\".\"F\"(value IN "
                + "\"Source\"\"Owner\".\"Self.Type\") RETURN NUMBER AS BEGIN DECLARE "
                + "\"Source\"\"Owner\" \"Source\"\"Owner\".\"Self.Type\"; BEGIN "
                + "\"Source\"\"Owner\".RUN(); END; \"Source\"\"Owner\".\"PKG\".RUN(); "
                + "RETURN 1; END;";

        String projected = OracleSchemaChangeRenderer.comparisonDefinition(
                ddl, "Source\"Owner");

        assertTrue(projected.contains("\"Source\"\"Owner\".RUN()"), projected);
        assertEquals(4, countOccurrences(projected, "\0oracle-self-owner\0"), projected);
    }

    @Test
    void enforcesDatabaseManualShapeAndDerivedDestructiveSafetyWithFixedWarnings() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "Order\"Line", "");
        TableDefinition table = table(tableKey, List.of());
        SchemaChange create = change(ChangeKind.CREATE, tableKey, table, null, null,
                AutomationLevel.SAFE_AUTOMATIC);

        assertEquals(OracleSchemaChangeRenderer.WRONG_DATABASE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(create,
                                context(DbType.POSTGRESQL, false))).getMessage());
        SchemaChange manual = change(ChangeKind.MANUAL, tableKey, null, null, null,
                AutomationLevel.MANUAL_ONLY);
        assertEquals(OracleSchemaChangeRenderer.MANUAL_CHANGE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(manual, context(DbType.ORACLE, false))).getMessage());
        SchemaChange malformed = change(ChangeKind.CREATE, tableKey, null, null, null,
                AutomationLevel.SAFE_AUTOMATIC);
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(malformed, context(DbType.ORACLE, false))).getMessage());

        SchemaChange misclassifiedDrop = change(ChangeKind.DROP, tableKey, null, table, null,
                AutomationLevel.SAFE_AUTOMATIC);
        assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_APPROVAL,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(misclassifiedDrop,
                                context(DbType.ORACLE, false))).getMessage());
        RenderedStatement drop = RENDERER.render(
                misclassifiedDrop, context(DbType.ORACLE, true)).getFirst();
        assertEquals("DROP TABLE \"Target\"\"Owner\".\"Order\"\"Line\";", drop.sql());
        assertTrue(drop.destructive());
        assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_WARNING, drop.warning());
        assertFalse(drop.sql().contains("BEGIN"));
        assertFalse(drop.sql().contains("COMMIT"));
        assertFalse(drop.sql().contains("ROLLBACK"));
    }

    @Test
    void createsOnlyASequenceWithProvableStartAndOracleDeclarativeOptions() {
        ObjectKey key = key(ObjectType.SEQUENCE, "Source", "MiX\"Seq", "");
        SequenceDefinition sequence = new SequenceDefinition(
                key, "42", "-2", "-9", "999", true, 20, Set.of(),
                Map.of("oracle.order", "ORDER", "oracle.startValueKnown", "true"));

        RenderedStatement statement = RENDERER.render(
                change(ChangeKind.CREATE, key, sequence, null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst();

        assertEquals("CREATE SEQUENCE \"Target\"\"Owner\".\"MiX\"\"Seq\""
                + " START WITH 42 INCREMENT BY -2 MINVALUE -9 MAXVALUE 999"
                + " CYCLE CACHE 20 ORDER;", statement.sql());
        assertFalse(statement.destructive());
        assertEquals(OracleSchemaChangeRenderer.IMPLICIT_COMMIT_WARNING, statement.warning());
        assertEquals(Set.of("chg:dependency"), statement.dependencyIds());
        assertEquals("chg:test", statement.changeId());
    }

    @Test
    void refusesUnknownOrUnprovableSequenceStartWithoutGuessing() {
        ObjectKey key = key(ObjectType.SEQUENCE, "Source", "secret-sequence", "");
        SequenceDefinition unknown = new SequenceDefinition(
                key, null, "1", "1", "999", false, 0, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "false"));
        SchemaChange create = change(ChangeKind.CREATE, key, unknown, null, null,
                AutomationLevel.SAFE_AUTOMATIC);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RENDERER.render(create, context(DbType.ORACLE, false)));
        assertEquals(OracleSchemaChangeRenderer.UNKNOWN_SEQUENCE_START, failure.getMessage());
        assertFalse(failure.getMessage().contains("secret-sequence"));

        SequenceDefinition malformed = new SequenceDefinition(
                key, "1; DROP USER secret", "1", "1", "999", false, 20, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"));
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, key, malformed, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());
    }

    @Test
    void createsStructuredTableThenForeignKeysCommentsAndIndependentIndexes() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "Order\"Line", "");
        ObjectKey customerKey = key(ObjectType.TABLE, "Source", "Customer", "");
        CanonicalDataType identityType = new CanonicalDataType(
                "NUMBER", null, 12, -2, false, 0,
                extensions("oracle.identity", "ALWAYS",
                        "oracle.identityOptions", "START WITH: 1, INCREMENT BY: 5, "
                                + "MAX_VALUE: 999, MIN_VALUE: 1, CYCLE_FLAG: N, "
                                + "CACHE_SIZE: 20, ORDER_FLAG: Y"));
        CanonicalDataType codeType = new CanonicalDataType(
                "VARCHAR2", 40L, null, null, false, 0,
                extensions("oracle.lengthSemantics", "CHAR",
                        "oracle.defaultOnNull", "true"));
        CanonicalDataType addressType = type("Source.ADDRESS_T",
                extensions("formattedType", "\"Source\".\"ADDRESS_T\"",
                        "oracle.typeOwner", "Source"));
        ColumnDefinition id = column("ID", identityType, false,
                "GENERATED ALWAYS AS IDENTITY", 1, null);
        ColumnDefinition code = column("Co\"de", codeType, true,
                "DEFAULT ON NULL 'new''value'", 2, "owner's code");
        ColumnDefinition address = column("ADDRESS", addressType, true, null, 3, null);
        ConstraintDefinition primary = constraint(
                ObjectType.PRIMARY_KEY, "PK_ORDERS", ConstraintKind.PRIMARY_KEY,
                List.of(id), null, List.of(), null, null, false);
        ConstraintDefinition check = constraint(
                ObjectType.CHECK_CONSTRAINT, "CK_ORDERS", ConstraintKind.CHECK,
                List.of(), null, List.of(), "\"ID\" > 0", null, false);
        ConstraintDefinition foreign = constraint(
                ObjectType.FOREIGN_KEY, "FK_ORDERS_CUSTOMER", ConstraintKind.FOREIGN_KEY,
                List.of(code), customerKey,
                List.of(OracleSchemaIdentifierNormalizer.child("CODE")),
                null, "CASCADE", false);
        IndexDefinition backing = index("PK_ORDERS", false, List.of("\"ID\""), true);
        IndexDefinition function = index("IX_ORDERS_CODE", false,
                List.of("UPPER(\"Co\"\"de\") DESC"), false);
        TableDefinition table = new TableDefinition(tableKey,
                List.of(address, code, id), List.of(foreign, primary, check),
                List.of(function, backing), Set.of(customerKey));

        List<RenderedStatement> statements = RENDERER.render(
                change(ChangeKind.CREATE, tableKey, table, null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false));

        assertEquals(List.of(
                "CREATE TABLE \"Target\"\"Owner\".\"Order\"\"Line\" (\n"
                        + "    \"ID\" NUMBER(12,-2) GENERATED ALWAYS AS IDENTITY "
                        + "(START WITH 1 INCREMENT BY 5 MAXVALUE 999 MINVALUE 1 "
                        + "NOCYCLE CACHE 20 ORDER) NOT NULL,\n"
                        + "    \"Co\"\"de\" VARCHAR2(40 CHAR) DEFAULT ON NULL "
                        + "'new''value' NULL,\n"
                        + "    \"ADDRESS\" \"Target\"\"Owner\".\"ADDRESS_T\" NULL,\n"
                        + "    CONSTRAINT \"PK_ORDERS\" PRIMARY KEY (\"ID\"),\n"
                        + "    CONSTRAINT \"CK_ORDERS\" CHECK (\"ID\" > 0)\n);",
                "ALTER TABLE \"Target\"\"Owner\".\"Order\"\"Line\" ADD CONSTRAINT "
                        + "\"FK_ORDERS_CUSTOMER\" FOREIGN KEY (\"Co\"\"de\") REFERENCES "
                        + "\"Target\"\"Owner\".\"Customer\" (\"CODE\") ON DELETE CASCADE;",
                "COMMENT ON COLUMN \"Target\"\"Owner\".\"Order\"\"Line\".\"Co\"\"de\""
                        + " IS 'owner''s code';",
                "CREATE INDEX \"Target\"\"Owner\".\"IX_ORDERS_CODE\" ON "
                        + "\"Target\"\"Owner\".\"Order\"\"Line\" (UPPER(\"Co\"\"de\") DESC);"),
                statements.stream().map(RenderedStatement::sql).toList());
        assertTrue(statements.stream().allMatch(statement ->
                OracleSchemaChangeRenderer.IMPLICIT_COMMIT_WARNING.equals(statement.warning())));
        assertTrue(statements.stream().noneMatch(RenderedStatement::destructive));
    }

    @Test
    void addsOnlyAnExactWholeStructuredColumnAndItsComment() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ColumnDefinition added = column("New\"Column",
                new CanonicalDataType("VARCHAR2", 30L, null, null, false, 0,
                        extensions("oracle.lengthSemantics", "BYTE")),
                true, null, 2, "new column");
        TableDefinition desired = table(tableKey, List.of(added));
        TableDefinition current = table(tableKey, List.of());
        PropertyDifference exact = new PropertyDifference(
                "columns[" + added.name().comparisonKey() + "]", added, null, "safe");

        List<RenderedStatement> statements = RENDERER.render(
                change(ChangeKind.ALTER, tableKey, desired, current, exact,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false));

        assertEquals(List.of(
                "ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" ADD "
                        + "(\"New\"\"Column\" VARCHAR2(30 BYTE) NULL);",
                "COMMENT ON COLUMN \"Target\"\"Owner\".\"ORDERS\".\"New\"\"Column\""
                        + " IS 'new column';"),
                statements.stream().map(RenderedStatement::sql).toList());

        PropertyDifference fake = new PropertyDifference(
                "columns[secret-path]", added, null, "unsafe");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RENDERER.render(change(ChangeKind.ALTER, tableKey, desired, current, fake,
                                AutomationLevel.SAFE_AUTOMATIC),
                        context(DbType.ORACLE, false)));
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE, failure.getMessage());
        assertFalse(failure.getMessage().contains("secret-path"));
    }

    @Test
    void blankDefaultsUseTheSameNoDefaultPredicateForDestructiveApproval() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        for (String noDefault : new String[]{null, "", "   \t"}) {
            ColumnDefinition added = column("OPTIONAL", type("NUMBER", extensions()),
                    true, noDefault, 1, null);
            SchemaChange change = change(ChangeKind.ALTER, tableKey,
                    table(tableKey, List.of(added)), table(tableKey, List.of()),
                    new PropertyDifference("columns[" + added.name().comparisonKey() + "]",
                            added, null, "safe"), AutomationLevel.SAFE_AUTOMATIC);
            assertFalse(RENDERER.render(change, context(DbType.ORACLE, false))
                    .getFirst().destructive());
        }
        ColumnDefinition withDefault = column("OPTIONAL", type("NUMBER", extensions()),
                true, "0", 1, null);
        SchemaChange destructive = change(ChangeKind.ALTER, tableKey,
                table(tableKey, List.of(withDefault)), table(tableKey, List.of()),
                new PropertyDifference("columns[" + withDefault.name().comparisonKey() + "]",
                        withDefault, null, "safe"), AutomationLevel.SAFE_AUTOMATIC);
        assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_APPROVAL,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(destructive, context(DbType.ORACLE, false)))
                        .getMessage());
    }

    @Test
    void rendersConstraintAndIndexSetDifferencesRemovalFirstWithDerivedDestructiveMetadata() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ColumnDefinition id = column("ID",
                new CanonicalDataType("NUMBER", null, 10, 0, false, 0, extensions()),
                false, null, 1, null);
        ConstraintDefinition oldCheck = constraint(ObjectType.CHECK_CONSTRAINT,
                "CK_OLD", ConstraintKind.CHECK, List.of(), null, List.of(),
                "\"ID\" > 0", null, false);
        ConstraintDefinition desiredUnique = constraint(ObjectType.UNIQUE_CONSTRAINT,
                "UK_NEW", ConstraintKind.UNIQUE, List.of(id), null, List.of(),
                null, null, false);
        IndexDefinition oldIndex = index("IX_OLD", false, List.of("\"ID\""), false);
        IndexDefinition desiredIndex = index("IX_NEW", true,
                List.of("\"ID\" DESC"), false);
        IndexDefinition generated = index("SYS_C001", true, List.of("\"ID\""), true);
        TableDefinition desiredConstraints = new TableDefinition(tableKey, List.of(id),
                List.of(desiredUnique), List.of(), Set.of());
        TableDefinition currentConstraints = new TableDefinition(tableKey, List.of(id),
                List.of(oldCheck), List.of(), Set.of());
        PropertyDifference constraints = new PropertyDifference("constraints",
                desiredConstraints.constraints(), currentConstraints.constraints(), "safe");
        SchemaChange constraintChange = change(ChangeKind.ALTER, tableKey,
                desiredConstraints, currentConstraints, constraints,
                AutomationLevel.SAFE_AUTOMATIC);

        assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_APPROVAL,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(constraintChange,
                                context(DbType.ORACLE, false))).getMessage());
        List<RenderedStatement> constraintStatements = RENDERER.render(
                constraintChange, context(DbType.ORACLE, true));
        assertEquals(List.of(
                "ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" DROP CONSTRAINT \"CK_OLD\";",
                "ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" ADD CONSTRAINT \"UK_NEW\""
                        + " UNIQUE (\"ID\");"),
                constraintStatements.stream().map(RenderedStatement::sql).toList());
        assertTrue(constraintStatements.stream().allMatch(RenderedStatement::destructive));
        assertTrue(constraintStatements.stream().allMatch(statement ->
                OracleSchemaChangeRenderer.DESTRUCTIVE_WARNING.equals(statement.warning())));

        TableDefinition desiredIndexes = new TableDefinition(tableKey, List.of(id), List.of(),
                List.of(desiredIndex, generated), Set.of());
        TableDefinition currentIndexes = new TableDefinition(tableKey, List.of(id), List.of(),
                List.of(oldIndex, generated), Set.of());
        PropertyDifference indexes = new PropertyDifference("indexes",
                desiredIndexes.indexes(), currentIndexes.indexes(), "safe");
        List<RenderedStatement> indexStatements = RENDERER.render(
                change(ChangeKind.ALTER, tableKey, desiredIndexes, currentIndexes, indexes,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, true));
        assertEquals(List.of(
                "DROP INDEX \"Target\"\"Owner\".\"IX_OLD\";",
                "CREATE UNIQUE INDEX \"Target\"\"Owner\".\"IX_NEW\" ON "
                        + "\"Target\"\"Owner\".\"ORDERS\" (\"ID\" DESC);"),
                indexStatements.stream().map(RenderedStatement::sql).toList());
        assertTrue(indexStatements.stream().allMatch(RenderedStatement::destructive));
    }

    @Test
    void rendersExactOracleColumnPropertiesAndApprovedWholeColumnDrop() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ColumnDefinition desired = column("Amount",
                new CanonicalDataType("NUMBER", null, 14, 2, false, 0, extensions()),
                false, null, 1, null);
        ColumnDefinition current = column("Amount",
                new CanonicalDataType("NUMBER", null, 10, 0, false, 0, extensions()),
                true, "1", 1, "old comment");
        TableDefinition source = table(tableKey, List.of(desired));
        TableDefinition target = table(tableKey, List.of(current));

        assertEquals("ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" MODIFY "
                        + "(\"Amount\" NUMBER(14,2));",
                renderApproved(tablePropertyChange(tableKey, source, target,
                        "columns[" + desired.name().comparisonKey() + "].dataType",
                        desired.dataType(), current.dataType())).getFirst().sql());
        assertEquals("ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" MODIFY "
                        + "(\"Amount\" NOT NULL);",
                renderApproved(tablePropertyChange(tableKey, source, target,
                        "columns[" + desired.name().comparisonKey() + "].nullable",
                        false, true)).getFirst().sql());
        assertEquals("ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" MODIFY "
                        + "(\"Amount\" DEFAULT NULL);",
                renderApproved(tablePropertyChange(tableKey, source, target,
                        "columns[" + desired.name().comparisonKey() + "].normalizedDefault",
                        null, "1")).getFirst().sql());

        SchemaChange commentChange = tablePropertyChange(tableKey, source, target,
                "columns[" + desired.name().comparisonKey() + "].comment", null, "old comment");
        RenderedStatement comment = RENDERER.render(
                commentChange, context(DbType.ORACLE, false)).getFirst();
        assertEquals("COMMENT ON COLUMN \"Target\"\"Owner\".\"ORDERS\".\"Amount\" IS '';",
                comment.sql());
        assertFalse(comment.destructive());

        TableDefinition absent = table(tableKey, List.of());
        PropertyDifference dropProperty = new PropertyDifference(
                "columns[" + current.name().comparisonKey() + "]", null, current, "drop");
        SchemaChange drop = change(ChangeKind.ALTER, tableKey, absent, target, dropProperty,
                AutomationLevel.SAFE_AUTOMATIC);
        assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_APPROVAL,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(drop, context(DbType.ORACLE, false))).getMessage());
        assertEquals("ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" DROP COLUMN \"Amount\";",
                RENDERER.render(drop, context(DbType.ORACLE, true)).getFirst().sql());

        PropertyDifference mismatch = new PropertyDifference(
                "columns[" + desired.name().comparisonKey() + "].nullable", true, false, "bad");
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.ALTER, tableKey, source, target,
                                        mismatch, AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, true))).getMessage());
    }

    @Test
    void rendersOnlyProvableOracleSequenceAltersIncludingOrderAndRefusesStartChanges() {
        ObjectKey key = key(ObjectType.SEQUENCE, "Source", "ORDERS_SEQ", "");
        Map<String, String> desiredExtensions = Map.of(
                "oracle.order", "ORDER", "oracle.startValueKnown", "false");
        Map<String, String> currentExtensions = Map.of(
                "oracle.order", "NOORDER", "oracle.startValueKnown", "false");
        SequenceDefinition desired = new SequenceDefinition(key, null, "5", null,
                null, true, 0, Set.of(), desiredExtensions);
        SequenceDefinition current = new SequenceDefinition(key, null, "1", "1",
                "999", false, 20, Set.of(), currentExtensions);

        List<PropertyDifference> properties = List.of(
                new PropertyDifference("incrementBy", "5", "1", "safe"),
                new PropertyDifference("minimumValue", null, "1", "safe"),
                new PropertyDifference("maximumValue", null, "999", "safe"),
                new PropertyDifference("cycle", true, false, "safe"),
                new PropertyDifference("cacheSize", 0, 20, "safe"),
                new PropertyDifference("providerExtensions",
                        desiredExtensions, currentExtensions, "safe"));
        assertEquals(List.of(
                        "ALTER SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\" INCREMENT BY 5;",
                        "ALTER SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\" NOMINVALUE;",
                        "ALTER SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\" NOMAXVALUE;",
                        "ALTER SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\" CYCLE;",
                        "ALTER SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\" NOCACHE;",
                        "ALTER SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\" ORDER;"),
                properties.stream().map(property -> RENDERER.render(
                                change(ChangeKind.ALTER, key, desired, current, property,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, true)).getFirst().sql())
                        .toList());

        SequenceDefinition unknownDesired = new SequenceDefinition(key, null, "1", "1",
                "999", false, 20, Set.of(), currentExtensions);
        SequenceDefinition knownCurrent = new SequenceDefinition(key, "50", "1", "1",
                "999", false, 20, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"));
        SchemaChange unknownStart = change(ChangeKind.ALTER, key, unknownDesired, knownCurrent,
                new PropertyDifference("startValue", null, "50", "unsafe"),
                AutomationLevel.SAFE_AUTOMATIC);
        assertEquals(OracleSchemaChangeRenderer.UNKNOWN_SEQUENCE_START,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(unknownStart,
                                context(DbType.ORACLE, true))).getMessage());

        SequenceDefinition knownDesired = new SequenceDefinition(key, "40", "1", "1",
                "999", false, 20, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"));
        SchemaChange versionDependentStart = change(ChangeKind.ALTER, key,
                knownDesired, knownCurrent,
                new PropertyDifference("startValue", "40", "50", "unsafe"),
                AutomationLevel.SAFE_AUTOMATIC);
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(versionDependentStart,
                                context(DbType.ORACLE, true))).getMessage());
    }

    @Test
    void createsEveryOracleDefinitionWithRetargetedOwnerAndExactClientDelimiter() {
        ObjectKey tableOwner = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ObjectKey typeSpec = key(ObjectType.TYPE, "Source", "ADDRESS_T", "SPEC");
        List<DefinitionCase> cases = List.of(
                new DefinitionCase(key(ObjectType.VIEW, "Source", "ORDERS_V", ""),
                        "CREATE OR REPLACE VIEW \"Source\".\"ORDERS_V\" AS SELECT * "
                                + "FROM \"Source\".\"ORDERS\";", Set.of(), false),
                new DefinitionCase(key(ObjectType.MATERIALIZED_VIEW,
                        "Source", "ORDERS_MV", ""),
                        "CREATE MATERIALIZED VIEW \"Source\".\"ORDERS_MV\" AS SELECT * "
                                + "FROM \"Source\".\"ORDERS\";", Set.of(), false),
                new DefinitionCase(key(ObjectType.FUNCTION, "Source", "CALC",
                        oracleSignature("IN", "NUMBER")),
                        "CREATE OR REPLACE FUNCTION \"Source\".\"CALC\" "
                                + "(\"P\" IN NUMBER) RETURN NUMBER IS\n"
                                + "BEGIN\n RETURN 1;\nEND;\n/", Set.of(), true),
                new DefinitionCase(key(ObjectType.PROCEDURE,
                        "Source", "REFRESH_ORDERS", oracleSignature()),
                        "CREATE OR REPLACE PROCEDURE \"Source\".\"REFRESH_ORDERS\" IS\n"
                                + "BEGIN\n NULL;\nEND;", Set.of(), true),
                new DefinitionCase(key(ObjectType.TRIGGER, "Source", "AUDIT_ORDERS", ""),
                        "CREATE OR REPLACE TRIGGER \"Source\".\"AUDIT_ORDERS\" "
                                + "BEFORE INSERT ON \"Source\".\"ORDERS\"\n"
                                + "BEGIN NULL; END;\n/", Set.of(tableOwner), true),
                new DefinitionCase(key(ObjectType.PACKAGE_SPEC, "Source", "ORDER_API", ""),
                        "CREATE OR REPLACE PACKAGE \"Source\".\"ORDER_API\" IS\n"
                                + " PROCEDURE refresh;\nEND;\n/", Set.of(), true),
                new DefinitionCase(key(ObjectType.PACKAGE_BODY, "Source", "ORDER_API", ""),
                        "CREATE OR REPLACE PACKAGE BODY \"Source\".\"ORDER_API\" IS\n"
                                + " PROCEDURE refresh IS BEGIN NULL; END;\nEND;\n/",
                        Set.of(key(ObjectType.PACKAGE_SPEC, "Source", "ORDER_API", "")), true),
                new DefinitionCase(typeSpec,
                        "CREATE TYPE \"Source\".\"ADDRESS_T\" AS OBJECT "
                                + "(\"CITY\" VARCHAR2(20));", Set.of(), true),
                new DefinitionCase(key(ObjectType.TYPE, "Source", "ADDRESS_T", "BODY"),
                        "CREATE OR REPLACE TYPE BODY \"Source\".\"ADDRESS_T\" AS\n"
                                + " MEMBER FUNCTION value RETURN NUMBER IS\n"
                                + " BEGIN RETURN 1; END;\nEND;\n/", Set.of(typeSpec), true));

        for (DefinitionCase definitionCase : cases) {
            DefinitionObject definition = definition(definitionCase.key(),
                    definitionCase.ddl(), definitionCase.dependencies());
            RenderedStatement statement = RENDERER.render(
                    change(ChangeKind.CREATE, definitionCase.key(), definition, null, null,
                            AutomationLevel.SAFE_AUTOMATIC),
                    context(DbType.ORACLE, false)).getFirst();

            assertTrue(statement.sql().contains("\"Target\"\"Owner\"."));
            assertFalse(statement.sql().contains("\"Source\"."));
            assertEquals(definitionCase.slash(), statement.sql().endsWith("\n/"));
            assertEquals(definitionCase.slash() ? 1 : 0,
                    statement.sql().split("(?m)^/$", -1).length - 1);
            if (definitionCase.slash()
                    && definitionCase.key().type() != ObjectType.TYPE
                            || definitionCase.key().signature().equals("BODY")) {
                assertTrue(statement.sql().contains("END;"));
            }
            assertFalse(statement.sql().startsWith("BEGIN\n"));
            assertEquals(OracleSchemaChangeRenderer.IMPLICIT_COMMIT_WARNING,
                    statement.warning());
        }
    }

    @Test
    void replacesOnlyDefinitionsWhoseOriginalOracleHeaderProvesReplaceSemantics() {
        ObjectKey packageKey = key(ObjectType.PACKAGE_BODY, "Source", "ORDER_API", "");
        DefinitionObject desiredPackage = definition(packageKey,
                "CREATE OR REPLACE PACKAGE BODY \"Source\".\"ORDER_API\" IS\nEND;\n/",
                Set.of(key(ObjectType.PACKAGE_SPEC, "Source", "ORDER_API", "")));
        DefinitionObject currentPackage = definition(packageKey,
                "CREATE OR REPLACE PACKAGE BODY \"Source\".\"ORDER_API\" IS\n"
                        + " PROCEDURE old;\nEND;\n/", desiredPackage.dependencies());
        SchemaChange replacePackage = changeWithDependencies(ChangeKind.REPLACE, packageKey,
                desiredPackage, currentPackage,
                new PropertyDifference("normalizedDefinition", "desired-digest",
                        "current-digest", "safe"),
                AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of("chg:package-spec"));

        RenderedStatement rendered = RENDERER.render(
                replacePackage, context(DbType.ORACLE, true)).getFirst();
        assertTrue(rendered.sql().startsWith("CREATE OR REPLACE PACKAGE BODY "
                + "\"Target\"\"Owner\".\"ORDER_API\""));
        assertEquals(Set.of("chg:package-spec"), rendered.dependencyIds());
        assertTrue(rendered.destructive());

        ObjectKey materializedKey = key(ObjectType.MATERIALIZED_VIEW,
                "Source", "ORDERS_MV", "");
        DefinitionObject materialized = definition(materializedKey,
                "CREATE MATERIALIZED VIEW \"Source\".\"ORDERS_MV\" AS SELECT 1 X FROM DUAL;",
                Set.of());
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.REPLACE, materializedKey,
                                        materialized, materialized,
                                        new PropertyDifference("normalizedDefinition", "a", "b", "safe"),
                                        AutomationLevel.DESTRUCTIVE_OPT_IN),
                                context(DbType.ORACLE, true))).getMessage());

        ObjectKey typeKey = key(ObjectType.TYPE, "Source", "ADDRESS_T", "SPEC");
        DefinitionObject nonReplaceType = definition(typeKey,
                "CREATE TYPE \"Source\".\"ADDRESS_T\" AS OBJECT (\"CITY\" VARCHAR2(20));",
                Set.of());
        assertEquals(OracleSchemaChangeRenderer.UNSAFE_DEFINITION,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.REPLACE, typeKey,
                                        nonReplaceType, nonReplaceType,
                                        new PropertyDifference("normalizedDefinition", "a", "b", "safe"),
                                        AutomationLevel.DESTRUCTIVE_OPT_IN),
                                context(DbType.ORACLE, true))).getMessage());
    }

    @Test
    void crossSchemaDropAlterAndReplaceUseComparisonIdentityAndTargetOwner() {
        ObjectKey sourceTableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ObjectKey targetTableKey = key(ObjectType.TABLE, "Target\"Owner", "ORDERS", "");
        ColumnDefinition desiredColumn = column("STATUS",
                type("VARCHAR2", extensions("formattedType", "VARCHAR2(30)")),
                false, null, 1, null);
        ColumnDefinition currentColumn = column("STATUS",
                type("VARCHAR2", extensions("formattedType", "VARCHAR2(10)")),
                false, null, 1, null);
        TableDefinition desiredTable = table(sourceTableKey, List.of(desiredColumn));
        TableDefinition currentTable = table(targetTableKey, List.of(currentColumn));
        SchemaChange alterTable = tablePropertyChange(sourceTableKey, desiredTable, currentTable,
                "columns[" + desiredColumn.name().comparisonKey() + "].dataType",
                desiredColumn.dataType(), currentColumn.dataType());
        assertEquals("ALTER TABLE \"Target\"\"Owner\".\"ORDERS\" "
                        + "MODIFY (\"STATUS\" VARCHAR2(30));",
                renderApproved(alterTable).getFirst().sql());

        ObjectKey sourceSequenceKey = key(ObjectType.SEQUENCE, "Source", "ORDERS_SEQ", "");
        ObjectKey targetSequenceKey = key(
                ObjectType.SEQUENCE, "Target\"Owner", "ORDERS_SEQ", "");
        SequenceDefinition desiredSequence = new SequenceDefinition(sourceSequenceKey,
                "1", "5", "1", "999", false, 20, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"));
        SequenceDefinition currentSequence = new SequenceDefinition(targetSequenceKey,
                "1", "1", "1", "999", false, 20, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"));
        SchemaChange alterSequence = change(ChangeKind.ALTER, sourceSequenceKey,
                desiredSequence, currentSequence,
                new PropertyDifference("incrementBy", "5", "1", "safe"),
                AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertEquals("ALTER SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\" "
                        + "INCREMENT BY 5;",
                renderApproved(alterSequence).getFirst().sql());

        SchemaChange drop = change(ChangeKind.DROP, targetTableKey,
                null, currentTable, null, AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertEquals("DROP TABLE \"Target\"\"Owner\".\"ORDERS\";",
                renderApproved(drop).getFirst().sql());

        ObjectKey sourceViewKey = key(ObjectType.VIEW, "Source", "ORDERS_V", "");
        ObjectKey targetViewKey = key(ObjectType.VIEW, "Target\"Owner", "ORDERS_V", "");
        DefinitionObject desiredView = definition(sourceViewKey,
                "CREATE OR REPLACE VIEW \"Source\".\"ORDERS_V\" AS SELECT 1 X FROM DUAL;");
        DefinitionObject currentView = definition(targetViewKey,
                "CREATE OR REPLACE VIEW \"Target\"\"Owner\".\"ORDERS_V\" "
                        + "AS SELECT 2 X FROM DUAL;");
        SchemaChange replace = change(ChangeKind.REPLACE, sourceViewKey,
                desiredView, currentView,
                new PropertyDifference("normalizedDefinition", "desired", "current", "safe"),
                AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertEquals("CREATE OR REPLACE VIEW \"Target\"\"Owner\".\"ORDERS_V\" "
                        + "AS SELECT 1 X FROM DUAL;",
                renderApproved(replace).getFirst().sql());

        ObjectKey wrongTargetKey = key(ObjectType.TABLE, "Target\"Owner", "OTHER", "");
        SchemaChange mismatched = tablePropertyChange(sourceTableKey, desiredTable,
                table(wrongTargetKey, List.of(currentColumn)),
                "columns[" + desiredColumn.name().comparisonKey() + "].dataType",
                desiredColumn.dataType(), currentColumn.dataType());
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(mismatched,
                                context(DbType.ORACLE, true))).getMessage());

        ObjectKey unrelatedOwnerViewKey = key(ObjectType.VIEW, "Other", "ORDERS_V", "");
        DefinitionObject unrelatedOwnerView = definition(unrelatedOwnerViewKey,
                "CREATE OR REPLACE VIEW \"Other\".\"ORDERS_V\" AS SELECT 2 X FROM DUAL;");
        SchemaChange unrelatedTargetOwner = change(ChangeKind.REPLACE, sourceViewKey,
                desiredView, unrelatedOwnerView,
                new PropertyDifference("normalizedDefinition", "desired", "current", "safe"),
                AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(unrelatedTargetOwner,
                                context(DbType.ORACLE, true))).getMessage());

        ObjectKey unrelatedSourceTableKey = key(ObjectType.TABLE, "Other", "ORDERS", "");
        TableDefinition unrelatedDesiredTable = table(
                unrelatedSourceTableKey, List.of(desiredColumn));
        SchemaChange unrelatedSourceOwner = tablePropertyChange(unrelatedSourceTableKey,
                unrelatedDesiredTable, currentTable,
                "columns[" + desiredColumn.name().comparisonKey() + "].dataType",
                desiredColumn.dataType(), currentColumn.dataType());
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(unrelatedSourceOwner,
                                context(DbType.ORACLE, true))).getMessage());
    }

    @Test
    void crossSchemaRoutineReplaceRetargetsOnlyExactSourceOwnedUdtIdentity() {
        ObjectKey sourceKey = key(ObjectType.FUNCTION, "Source", "FORMAT_ADDRESS",
                oracleSignature("IN", "Source.ADDRESS_T"));
        ObjectKey targetKey = key(ObjectType.FUNCTION, "Target\"Owner", "FORMAT_ADDRESS",
                oracleSignature("IN", "Target\"Owner.ADDRESS_T"));
        DefinitionObject desired = definition(sourceKey,
                "CREATE OR REPLACE FUNCTION \"Source\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Source\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                        + "BEGIN RETURN 'ok'; END;");
        DefinitionObject current = definition(targetKey,
                "CREATE OR REPLACE FUNCTION \"Target\"\"Owner\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Target\"\"Owner\".\"ADDRESS_T\") "
                        + "RETURN VARCHAR2 IS BEGIN RETURN 'old'; END;");
        SchemaChange replace = change(ChangeKind.REPLACE, sourceKey, desired, current,
                new PropertyDifference("normalizedDefinition", "desired", "current", "safe"),
                AutomationLevel.DESTRUCTIVE_OPT_IN);

        String sql = renderApproved(replace).getFirst().sql();
        assertTrue(sql.startsWith("CREATE OR REPLACE FUNCTION "
                + "\"Target\"\"Owner\".\"FORMAT_ADDRESS\""));
        assertTrue(sql.contains("P_ADDRESS IN \"Target\"\"Owner\".\"ADDRESS_T\""));

        for (String wrongType : List.of("Other.ADDRESS_T", "source.ADDRESS_T")) {
            ObjectKey wrongTargetKey = key(ObjectType.FUNCTION,
                    "Target\"Owner", "FORMAT_ADDRESS",
                    oracleSignature("IN", wrongType));
            DefinitionObject wrongTarget = definition(wrongTargetKey,
                    "CREATE OR REPLACE FUNCTION \"Target\"\"Owner\".\"FORMAT_ADDRESS\" "
                            + "(P_ADDRESS IN \"Other\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                            + "BEGIN RETURN 'old'; END;");
            SchemaChange wrongOwner = change(ChangeKind.REPLACE, sourceKey,
                    desired, wrongTarget,
                    new PropertyDifference(
                            "normalizedDefinition", "desired", "current", "safe"),
                    AutomationLevel.DESTRUCTIVE_OPT_IN);
            assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                    assertThrows(IllegalArgumentException.class,
                            () -> RENDERER.render(wrongOwner,
                                    context(DbType.ORACLE, true))).getMessage());
        }

        ObjectKey malformedTargetKey = key(ObjectType.FUNCTION,
                "Target\"Owner", "FORMAT_ADDRESS",
                "oracle-routine-signature-v1\0" + "2:IN99:secret");
        SchemaChange malformed = change(ChangeKind.REPLACE, sourceKey, desired,
                definition(malformedTargetKey, current.originalDefinition()),
                new PropertyDifference("normalizedDefinition", "desired", "current", "safe"),
                AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertSafeDefinitionFailure(malformed);
    }

    @Test
    void routineIdentityCanonicalizationUsesTheOwningSideForSelfTypes() {
        RenderContext sideContext = new RenderContext(DbType.ORACLE,
                OracleSchemaIdentifierNormalizer.schema("Source"),
                OracleSchemaIdentifierNormalizer.schema("Target"), true);
        PropertyDifference difference = new PropertyDifference(
                "normalizedDefinition", "desired", "current", "safe");

        ObjectKey sourceSelfKey = key(ObjectType.FUNCTION,
                "Source", "FORMAT_ADDRESS", oracleSignature("IN", "Source.ADDRESS_T"));
        ObjectKey targetSelfKey = key(ObjectType.FUNCTION,
                "Target", "FORMAT_ADDRESS", oracleSignature("IN", "Target.ADDRESS_T"));
        DefinitionObject sourceSelf = definition(sourceSelfKey,
                "CREATE OR REPLACE FUNCTION \"Source\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Source\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                        + "BEGIN RETURN 'new'; END;");
        DefinitionObject targetSelf = definition(targetSelfKey,
                "CREATE OR REPLACE FUNCTION \"Target\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Target\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                        + "BEGIN RETURN 'old'; END;");
        SchemaChange selfToSelf = change(ChangeKind.REPLACE, sourceSelfKey,
                sourceSelf, targetSelf, difference, AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertTrue(RENDERER.render(selfToSelf, sideContext).getFirst().sql()
                .contains("P_ADDRESS IN \"Target\".\"ADDRESS_T\""));

        ObjectKey targetExternalSourceKey = key(ObjectType.FUNCTION,
                "Target", "FORMAT_ADDRESS", oracleSignature("IN", "Source.ADDRESS_T"));
        DefinitionObject targetExternalSource = definition(targetExternalSourceKey,
                "CREATE OR REPLACE FUNCTION \"Target\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Source\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                        + "BEGIN RETURN 'old'; END;");
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.REPLACE, sourceSelfKey,
                                        sourceSelf, targetExternalSource, difference,
                                        AutomationLevel.DESTRUCTIVE_OPT_IN),
                                sideContext)).getMessage());

        ObjectKey sourceExternalTargetKey = key(ObjectType.FUNCTION,
                "Source", "FORMAT_ADDRESS", oracleSignature("IN", "Target.ADDRESS_T"));
        DefinitionObject sourceExternalTarget = definition(sourceExternalTargetKey,
                "CREATE OR REPLACE FUNCTION \"Source\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Target\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                        + "BEGIN RETURN 'new'; END;");
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.REPLACE,
                                        sourceExternalTargetKey, sourceExternalTarget,
                                        targetSelf, difference,
                                        AutomationLevel.DESTRUCTIVE_OPT_IN),
                                sideContext)).getMessage());

        ObjectKey sourceThirdPartyKey = key(ObjectType.FUNCTION,
                "Source", "FORMAT_ADDRESS", oracleSignature("IN", "Other.ADDRESS_T"));
        ObjectKey targetThirdPartyKey = key(ObjectType.FUNCTION,
                "Target", "FORMAT_ADDRESS", oracleSignature("IN", "Other.ADDRESS_T"));
        DefinitionObject sourceThirdParty = definition(sourceThirdPartyKey,
                "CREATE OR REPLACE FUNCTION \"Source\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Other\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                        + "BEGIN RETURN 'new'; END;");
        DefinitionObject targetThirdParty = definition(targetThirdPartyKey,
                "CREATE OR REPLACE FUNCTION \"Target\".\"FORMAT_ADDRESS\" "
                        + "(P_ADDRESS IN \"Other\".\"ADDRESS_T\") RETURN VARCHAR2 IS "
                        + "BEGIN RETURN 'old'; END;");
        assertTrue(RENDERER.render(change(ChangeKind.REPLACE, sourceThirdPartyKey,
                        sourceThirdParty, targetThirdParty, difference,
                        AutomationLevel.DESTRUCTIVE_OPT_IN), sideContext).getFirst().sql()
                .contains("P_ADDRESS IN \"Other\".\"ADDRESS_T\""));

        for (String externalType : List.of("OtherTwo.ADDRESS_T", "other.ADDRESS_T")) {
            ObjectKey mismatchedExternalKey = key(ObjectType.FUNCTION,
                    "Target", "FORMAT_ADDRESS", oracleSignature("IN", externalType));
            assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                    assertThrows(IllegalArgumentException.class,
                            () -> RENDERER.render(change(ChangeKind.REPLACE,
                                            sourceThirdPartyKey, sourceThirdParty,
                                            definition(mismatchedExternalKey,
                                                    targetThirdParty.originalDefinition()),
                                            difference, AutomationLevel.DESTRUCTIVE_OPT_IN),
                                    sideContext)).getMessage());
        }

        ObjectKey malformedTargetKey = key(ObjectType.FUNCTION,
                "Target", "FORMAT_ADDRESS",
                "oracle-routine-signature-v1\0" + "2:IN99:secret");
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> RENDERER.render(change(ChangeKind.REPLACE, sourceSelfKey,
                                sourceSelf,
                                definition(malformedTargetKey, targetSelf.originalDefinition()),
                                difference, AutomationLevel.DESTRUCTIVE_OPT_IN),
                        sideContext));
        assertEquals(OracleSchemaChangeRenderer.UNSAFE_DEFINITION, malformed.getMessage());
        assertFalse(malformed.getMessage().contains("secret"));
    }

    @Test
    void changeObjectOwnerMustFollowKindShapeAndRenderContext() {
        ObjectKey sourceTableKey = key(ObjectType.TABLE, "Source", "OWNER_MATRIX", "");
        ObjectKey targetTableKey = key(
                ObjectType.TABLE, "Target\"Owner", "OWNER_MATRIX", "");
        ObjectKey otherTableKey = key(ObjectType.TABLE, "Other", "OWNER_MATRIX", "");
        ColumnDefinition desiredColumn = column("VALUE",
                type("VARCHAR2", extensions("formattedType", "VARCHAR2(20)")),
                false, null, 1, null);
        ColumnDefinition currentColumn = column("VALUE",
                type("VARCHAR2", extensions("formattedType", "VARCHAR2(10)")),
                false, null, 1, null);
        TableDefinition desiredTable = table(sourceTableKey, List.of(desiredColumn));
        TableDefinition currentTable = table(targetTableKey, List.of(currentColumn));

        SchemaChange create = change(ChangeKind.CREATE, sourceTableKey,
                desiredTable, null, null, AutomationLevel.SAFE_AUTOMATIC);
        assertTrue(RENDERER.render(create, context(DbType.ORACLE, false)).getFirst().sql()
                .startsWith("CREATE TABLE \"Target\"\"Owner\".\"OWNER_MATRIX\""));
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, otherTableKey,
                                        desiredTable, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        SchemaChange drop = change(ChangeKind.DROP, targetTableKey,
                null, currentTable, null, AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertEquals("DROP TABLE \"Target\"\"Owner\".\"OWNER_MATRIX\";",
                renderApproved(drop).getFirst().sql());
        for (ObjectKey wrong : List.of(sourceTableKey, otherTableKey)) {
            assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                    assertThrows(IllegalArgumentException.class,
                            () -> RENDERER.render(change(ChangeKind.DROP, wrong,
                                            null, currentTable, null,
                                            AutomationLevel.DESTRUCTIVE_OPT_IN),
                                    context(DbType.ORACLE, true))).getMessage());
        }

        String path = "columns[" + desiredColumn.name().comparisonKey() + "].dataType";
        SchemaChange alter = tablePropertyChange(sourceTableKey, desiredTable, currentTable,
                path, desiredColumn.dataType(), currentColumn.dataType());
        assertTrue(renderApproved(alter).getFirst().sql()
                .startsWith("ALTER TABLE \"Target\"\"Owner\".\"OWNER_MATRIX\""));
        for (ObjectKey wrong : List.of(targetTableKey, otherTableKey)) {
            SchemaChange wrongOwner = tablePropertyChange(wrong, desiredTable, currentTable,
                    path, desiredColumn.dataType(), currentColumn.dataType());
            assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                    assertThrows(IllegalArgumentException.class,
                            () -> RENDERER.render(wrongOwner,
                                    context(DbType.ORACLE, true))).getMessage());
        }

        ObjectKey sourceViewKey = key(ObjectType.VIEW, "Source", "OWNER_MATRIX_V", "");
        ObjectKey targetViewKey = key(
                ObjectType.VIEW, "Target\"Owner", "OWNER_MATRIX_V", "");
        ObjectKey otherViewKey = key(ObjectType.VIEW, "Other", "OWNER_MATRIX_V", "");
        DefinitionObject desiredView = definition(sourceViewKey,
                "CREATE OR REPLACE VIEW \"Source\".\"OWNER_MATRIX_V\" "
                        + "AS SELECT 1 X FROM DUAL;");
        DefinitionObject currentView = definition(targetViewKey,
                "CREATE OR REPLACE VIEW \"Target\"\"Owner\".\"OWNER_MATRIX_V\" "
                        + "AS SELECT 2 X FROM DUAL;");
        PropertyDifference definitionDifference = new PropertyDifference(
                "normalizedDefinition", "desired", "current", "safe");
        SchemaChange replace = change(ChangeKind.REPLACE, sourceViewKey,
                desiredView, currentView, definitionDifference,
                AutomationLevel.DESTRUCTIVE_OPT_IN);
        assertTrue(renderApproved(replace).getFirst().sql().startsWith(
                "CREATE OR REPLACE VIEW \"Target\"\"Owner\".\"OWNER_MATRIX_V\""));
        for (ObjectKey wrong : List.of(targetViewKey, otherViewKey)) {
            SchemaChange wrongOwner = change(ChangeKind.REPLACE, wrong,
                    desiredView, currentView, definitionDifference,
                    AutomationLevel.DESTRUCTIVE_OPT_IN);
            assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                    assertThrows(IllegalArgumentException.class,
                            () -> RENDERER.render(wrongOwner,
                                    context(DbType.ORACLE, true))).getMessage());
        }
    }

    @Test
    void lexicalRetargetChangesOnlyOracleQualifiedOwnerTokensAcrossCommentsAndQuotes() {
        String sourceOwner = "Src\"Owner";
        ObjectKey key = key(ObjectType.VIEW, sourceOwner, "Mixed\"View", "");
        String ddl = "CREATE OR REPLACE FORCE EDITIONABLE VIEW "
                + "\"Src\"\"Owner\".\"Mixed\"\"View\" AS\n"
                + "SELECT '\"Src\"\"Owner\".\"literal;value\"' TXT,\n"
                + "       q'[\"Src\"\"Owner\".\"q;value\"]' QTXT\n"
                + "FROM \"Src\"\"Owner\" /* keep \"Src\"\"Owner\".\"comment\" */ . \"T\"\n"
                + "-- keep \"Src\"\"Owner\".\"line\"\n"
                + "WHERE \"Src\"\"Owner\".\"T\".\"ID\" > 0;";
        RenderContext context = new RenderContext(DbType.ORACLE,
                OracleSchemaIdentifierNormalizer.schema(sourceOwner),
                OracleSchemaIdentifierNormalizer.schema("Tgt\"Owner"), false);

        String sql = RENDERER.render(change(ChangeKind.CREATE, key,
                        definition(key, ddl), null, null, AutomationLevel.SAFE_AUTOMATIC),
                context).getFirst().sql();

        assertTrue(sql.startsWith("CREATE OR REPLACE FORCE EDITIONABLE VIEW "
                + "\"Tgt\"\"Owner\".\"Mixed\"\"View\""));
        assertTrue(sql.contains("FROM \"Tgt\"\"Owner\" "
                + "/* keep \"Src\"\"Owner\".\"comment\" */ . \"T\""));
        assertTrue(sql.contains("WHERE \"Tgt\"\"Owner\".\"T\".\"ID\" > 0"));
        assertTrue(sql.contains("'\"Src\"\"Owner\".\"literal;value\"'"));
        assertTrue(sql.contains("q'[\"Src\"\"Owner\".\"q;value\"]'"));
        assertTrue(sql.contains("-- keep \"Src\"\"Owner\".\"line\""));
        assertFalse(sql.endsWith("\n/"));
    }

    @Test
    void supportsNationalAlternativeQuotesAndValidatesOptionalEndLabelIdentity() {
        ObjectKey viewKey = key(ObjectType.VIEW, "Source", "MESSAGES_V", "");
        String viewDdl = "CREATE OR REPLACE VIEW \"Source\".\"MESSAGES_V\" AS\n"
                + "SELECT nq'[owner's; \"Source\".\"literal\"]' LOWER_NQ,\n"
                + "       NQ'<other's; \"Source\".\"literal\">' UPPER_NQ\n"
                + "FROM DUAL;";
        String viewSql = RENDERER.render(change(ChangeKind.CREATE, viewKey,
                        definition(viewKey, viewDdl), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(viewSql.contains("nq'[owner's; \"Source\".\"literal\"]'"));
        assertTrue(viewSql.contains("NQ'<other's; \"Source\".\"literal\">'"));

        ObjectKey functionKey = key(
                ObjectType.FUNCTION, "Source", "SAFE_F", oracleSignature());
        String correctlyLabeled = "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" "
                + "RETURN NVARCHAR2 IS\nBEGIN RETURN NQ'[owner's; value]'; END SAFE_F;\n/";
        String functionSql = RENDERER.render(change(ChangeKind.CREATE, functionKey,
                        definition(functionKey, correctlyLabeled), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(functionSql.endsWith("END SAFE_F;\n/"));

        assertSafeDefinitionFailure(change(ChangeKind.CREATE, functionKey,
                definition(functionKey,
                        "CREATE FUNCTION \"Source\".\"SAFE_F\" RETURN NUMBER IS "
                                + "BEGIN RETURN 1; END OTHER_F;"),
                null, null, AutomationLevel.SAFE_AUTOMATIC));

        assertSafeDefinitionFailure(change(ChangeKind.CREATE, viewKey,
                definition(viewKey,
                        "CREATE VIEW \"Source\".\"MESSAGES_V\" AS SELECT $tag$secret; "
                                + "DROP TABLE hidden;$tag$ FROM DUAL;"),
                null, null, AutomationLevel.SAFE_AUTOMATIC));
    }

    @Test
    void routineAndTriggerIdentityMustMatchStructuredSignatureAndOwningDependency() {
        ObjectKey routineKey = key(ObjectType.FUNCTION, "Source", "CALC",
                oracleSignature("IN", "NUMBER", "INOUT", "Source.ADDRESS_T"));
        String routineDdl = "CREATE OR REPLACE EDITIONABLE FUNCTION \"Source\".\"CALC\" (\n"
                + "  \"P_AMOUNT\" IN NUMBER DEFAULT 1,\n"
                + "  \"P_ADDRESS\" IN OUT \"Source\".\"ADDRESS_T\",\n"
                + "  \"P_RESULT\" OUT VARCHAR2\n"
                + ") RETURN NUMBER IS\nBEGIN RETURN 1; END;\n/";
        String routineSql = RENDERER.render(change(ChangeKind.CREATE, routineKey,
                        definition(routineKey, routineDdl), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(routineSql.contains("\"Tgt" ) == false,
                "the shared context uses Target owner, not an unrelated target token");
        assertTrue(routineSql.contains("\"Target\"\"Owner\".\"ADDRESS_T\""));

        ObjectKey wrongRoutineKey = key(ObjectType.FUNCTION, "Source", "CALC",
                oracleSignature("IN", "VARCHAR2", "INOUT", "Source.ADDRESS_T"));
        assertSafeDefinitionFailure(change(ChangeKind.CREATE, wrongRoutineKey,
                definition(wrongRoutineKey, routineDdl), null, null,
                AutomationLevel.SAFE_AUTOMATIC));
        ObjectKey malformedSignature = key(ObjectType.FUNCTION, "Source", "CALC",
                "oracle-routine-signature-v1\0secret");
        assertSafeDefinitionFailure(change(ChangeKind.CREATE, malformedSignature,
                definition(malformedSignature, routineDdl), null, null,
                AutomationLevel.SAFE_AUTOMATIC));

        ObjectKey table = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ObjectKey view = key(ObjectType.VIEW, "Source", "ORDERS_V", "");
        ObjectKey triggerKey = key(ObjectType.TRIGGER, "Source", "AUDIT_ORDERS", "");
        String triggerDdl = "CREATE OR REPLACE TRIGGER \"Source\".\"AUDIT_ORDERS\"\n"
                + "/* ON \"Source\".\"FAKE\" */ BEFORE INSERT ON "
                + "\"Source\".\"ORDERS\"\nBEGIN NULL; END;\n/";
        String triggerSql = RENDERER.render(change(ChangeKind.CREATE, triggerKey,
                        definition(triggerKey, triggerDdl, Set.of(table)), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(triggerSql.contains("ON \"Target\"\"Owner\".\"ORDERS\""));

        assertSafeDefinitionFailure(change(ChangeKind.CREATE, triggerKey,
                definition(triggerKey, triggerDdl, Set.of(view)), null, null,
                AutomationLevel.SAFE_AUTOMATIC));
        ObjectKey helper = key(ObjectType.PROCEDURE, "Source", "AUDIT_HELPER",
                oracleSignature());
        String withBodyDependencies = RENDERER.render(change(ChangeKind.CREATE, triggerKey,
                        definition(triggerKey, triggerDdl, Set.of(table, view, helper)),
                        null, null, AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(withBodyDependencies.contains(
                "ON \"Target\"\"Owner\".\"ORDERS\""));
        assertSafeDefinitionFailure(change(ChangeKind.CREATE, triggerKey,
                definition(triggerKey, triggerDdl, Set.of(helper)), null, null,
                AutomationLevel.SAFE_AUTOMATIC));
        ObjectKey ambiguousView = key(ObjectType.VIEW, "Source", "ORDERS", "");
        assertSafeDefinitionFailure(change(ChangeKind.CREATE, triggerKey,
                definition(triggerKey, triggerDdl, Set.of(table, ambiguousView)), null, null,
                AutomationLevel.SAFE_AUTOMATIC));

        ObjectKey viewTriggerKey = key(ObjectType.TRIGGER, "Source", "AUDIT_VIEW", "");
        String viewTriggerDdl = "CREATE OR REPLACE TRIGGER \"Source\".\"AUDIT_VIEW\" "
                + "INSTEAD OF INSERT ON \"Source\".\"ORDERS_V\"\nBEGIN NULL; END;\n/";
        assertTrue(RENDERER.render(change(ChangeKind.CREATE, viewTriggerKey,
                        definition(viewTriggerKey, viewTriggerDdl, Set.of(view)), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql()
                .contains("ON \"Target\"\"Owner\".\"ORDERS_V\""));
    }

    @Test
    void failsClosedForLowConfidenceMultipleStatementsAndMalformedLexicalState() {
        ObjectKey viewKey = key(ObjectType.VIEW, "Source", "SAFE_V", "");
        List<String> unsafeViews = List.of(
                "CREATE VIEW \"Source\".\"SAFE_V\" AS SELECT 1 X FROM DUAL; "
                        + "DROP TABLE secret;",
                "CREATE VIEW \"Source\".\"SAFE_V\" AS SELECT 'secret FROM DUAL;",
                "CREATE VIEW \"Source\".\"SAFE_V AS SELECT 1 X FROM DUAL;",
                "CREATE VIEW \"Source\".\"SAFE_V\" AS SELECT 1 /* secret FROM DUAL;",
                "CREATE VIEW \"Source\".\"SAFE_V\" AS SELECT q'[secret' X FROM DUAL;",
                "CREATE VIEW \"Source\".\"SAFE_V\" AS SELECT 1 X FROM DUAL;\0secret",
                "CREATE VIEW \"Source\".\"SAFE_V\" AS SELECT 1 X FROM DUAL;\n/\n/");
        for (String unsafe : unsafeViews) {
            assertSafeDefinitionFailure(change(ChangeKind.CREATE, viewKey,
                    definition(viewKey, unsafe), null, null,
                    AutomationLevel.SAFE_AUTOMATIC));
        }

        ObjectKey functionKey = key(ObjectType.FUNCTION,
                "Source", "SAFE_F", oracleSignature());
        assertSafeDefinitionFailure(change(ChangeKind.CREATE, functionKey,
                definition(functionKey,
                        "CREATE FUNCTION \"Source\".\"SAFE_F\" RETURN NUMBER IS\n"
                                + "BEGIN RETURN 1; END; DROP TABLE secret;"),
                null, null, AutomationLevel.SAFE_AUTOMATIC));

        DefinitionObject low = new DefinitionObject(viewKey,
                "CREATE VIEW safe", "CREATE VIEW secret", Set.of(), DefinitionConfidence.LOW);
        IllegalArgumentException lowFailure = assertThrows(IllegalArgumentException.class,
                () -> RENDERER.render(change(ChangeKind.CREATE, viewKey, low, null, null,
                                AutomationLevel.SAFE_AUTOMATIC),
                        context(DbType.ORACLE, false)));
        assertEquals(OracleSchemaChangeRenderer.MANUAL_CHANGE, lowFailure.getMessage());
        assertFalse(lowFailure.getMessage().contains("secret"));

        ObjectKey unsupportedKey = key(ObjectType.TABLE, "Source", "secret", "");
        DefinitionObject unsupported = definition(unsupportedKey,
                "CREATE TABLE \"Source\".\"secret\" (\"ID\" NUMBER);");
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, unsupportedKey,
                                        unsupported, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        DefinitionObject desired = definition(viewKey,
                "CREATE OR REPLACE VIEW \"Source\".\"SAFE_V\" AS SELECT 1 X FROM DUAL;");
        DefinitionObject current = definition(viewKey,
                "CREATE OR REPLACE VIEW \"Source\".\"SAFE_V\" AS SELECT 2 X FROM DUAL;");
        RenderedStatement replacement = RENDERER.render(change(ChangeKind.REPLACE, viewKey,
                        desired, current,
                        new PropertyDifference("dependencies", "DROP USER unrelated-secret",
                                "jdbc:oracle:thin:credential-secret", "safe"),
                        AutomationLevel.DESTRUCTIVE_OPT_IN),
                context(DbType.ORACLE, true)).getFirst();
        assertFalse(replacement.sql().contains("unrelated-secret"));
        assertFalse(replacement.sql().contains("credential-secret"));
        assertFalse(replacement.toString().contains("SELECT 1"));
    }

    @Test
    void rejectsInternalStandaloneSlashAndMultipleDefinitionSegments() {
        ObjectKey functionKey = key(
                ObjectType.FUNCTION, "Source", "SAFE_F", oracleSignature());
        List<String> unsafeDefinitions = List.of(
                "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" RETURN NUMBER IS\n"
                        + "BEGIN RETURN 1; END;\n/\n"
                        + "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" "
                        + "RETURN NUMBER IS BEGIN RETURN 2; END;",
                "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" RETURN NUMBER IS\n"
                        + "BEGIN NULL;\n  /  \nRETURN 1; END;",
                "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" RETURN NUMBER IS "
                        + "BEGIN RETURN 1; END; "
                        + "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" "
                        + "RETURN NUMBER IS BEGIN RETURN 2; END;");
        for (String unsafe : unsafeDefinitions) {
            assertSafeDefinitionFailure(change(ChangeKind.CREATE, functionKey,
                    definition(functionKey, unsafe), null, null,
                    AutomationLevel.SAFE_AUTOMATIC));
        }

        String safe = "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" "
                + "RETURN VARCHAR2 IS\nBEGIN RETURN q'[line one / line two]'; END;";
        String sql = RENDERER.render(change(ChangeKind.CREATE, functionKey,
                        definition(functionKey, safe), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertEquals(1, sql.split("(?m)^/$", -1).length - 1);
        assertTrue(sql.endsWith("END;\n/"));
    }

    @Test
    void oracleBlockCommentEndsAtFirstClosingDelimiterAndUnclosedCommentFails() {
        ObjectKey functionKey = key(
                ObjectType.FUNCTION, "Source", "SAFE_F", oracleSignature());
        String ddl = "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_F\" "
                + "RETURN NUMBER IS\nBEGIN\n"
                + "  /* outer text /* is not nested */\n"
                + "  RETURN \"Source\".\"NUMBERS\".NEXTVAL;\n"
                + "END SAFE_F;\n/";

        String sql = RENDERER.render(change(ChangeKind.CREATE, functionKey,
                        definition(functionKey, ddl), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(sql.contains("/* outer text /* is not nested */"));
        assertTrue(sql.contains("RETURN \"Target\"\"Owner\".\"NUMBERS\".NEXTVAL;"));
        assertEquals(1, sql.split("(?m)^/$", -1).length - 1);

        assertSafeDefinitionFailure(change(ChangeKind.CREATE, functionKey,
                definition(functionKey,
                        "CREATE FUNCTION \"Source\".\"SAFE_F\" RETURN NUMBER IS "
                                + "BEGIN /* never closed secret RETURN 1; END;"),
                null, null, AutomationLevel.SAFE_AUTOMATIC));
    }

    @Test
    void approvedDropMatrixUsesOracleSyntaxWithoutClientDelimiterAndRevalidatesIdentity() {
        ObjectKey table = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ObjectKey sequence = key(ObjectType.SEQUENCE, "Source", "ORDERS_SEQ", "");
        ObjectKey view = key(ObjectType.VIEW, "Source", "ORDERS_V", "");
        ObjectKey materialized = key(ObjectType.MATERIALIZED_VIEW, "Source", "ORDERS_MV", "");
        ObjectKey function = key(ObjectType.FUNCTION, "Source", "CALC",
                oracleSignature("IN", "NUMBER"));
        ObjectKey procedure = key(ObjectType.PROCEDURE, "Source", "REFRESH", oracleSignature());
        ObjectKey trigger = key(ObjectType.TRIGGER, "Source", "AUDIT_ORDERS", "");
        ObjectKey packageSpec = key(ObjectType.PACKAGE_SPEC, "Source", "ORDER_API", "");
        ObjectKey packageBody = key(ObjectType.PACKAGE_BODY, "Source", "ORDER_API", "");
        ObjectKey typeSpec = key(ObjectType.TYPE, "Source", "ADDRESS_T", "SPEC");
        ObjectKey typeBody = key(ObjectType.TYPE, "Source", "ADDRESS_T", "BODY");
        SequenceDefinition sequenceDefinition = new SequenceDefinition(sequence,
                null, "1", "1", "999", false, 0, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "false"));
        List<DropCase> cases = List.of(
                new DropCase(table, table(table, List.of()),
                        "DROP TABLE \"Target\"\"Owner\".\"ORDERS\";"),
                new DropCase(sequence, sequenceDefinition,
                        "DROP SEQUENCE \"Target\"\"Owner\".\"ORDERS_SEQ\";"),
                new DropCase(view, definition(view,
                        "CREATE VIEW \"Source\".\"ORDERS_V\" AS SELECT 1 X FROM DUAL;"),
                        "DROP VIEW \"Target\"\"Owner\".\"ORDERS_V\";"),
                new DropCase(materialized, definition(materialized,
                        "CREATE MATERIALIZED VIEW \"Source\".\"ORDERS_MV\" "
                                + "AS SELECT 1 X FROM DUAL;"),
                        "DROP MATERIALIZED VIEW \"Target\"\"Owner\".\"ORDERS_MV\";"),
                new DropCase(function, definition(function,
                        "CREATE FUNCTION \"Source\".\"CALC\" (P IN NUMBER) RETURN NUMBER IS\n"
                                + "BEGIN RETURN 1; END;"),
                        "DROP FUNCTION \"Target\"\"Owner\".\"CALC\";"),
                new DropCase(procedure, definition(procedure,
                        "CREATE PROCEDURE \"Source\".\"REFRESH\" IS\nBEGIN NULL; END;"),
                        "DROP PROCEDURE \"Target\"\"Owner\".\"REFRESH\";"),
                new DropCase(trigger, definition(trigger,
                        "CREATE TRIGGER \"Source\".\"AUDIT_ORDERS\" BEFORE INSERT ON "
                                + "\"Source\".\"ORDERS\" BEGIN NULL; END;", Set.of(table)),
                        "DROP TRIGGER \"Target\"\"Owner\".\"AUDIT_ORDERS\";"),
                new DropCase(packageSpec, definition(packageSpec,
                        "CREATE PACKAGE \"Source\".\"ORDER_API\" IS END;"),
                        "DROP PACKAGE \"Target\"\"Owner\".\"ORDER_API\";"),
                new DropCase(packageBody, definition(packageBody,
                        "CREATE PACKAGE BODY \"Source\".\"ORDER_API\" IS END;",
                        Set.of(packageSpec)),
                        "DROP PACKAGE BODY \"Target\"\"Owner\".\"ORDER_API\";"),
                new DropCase(typeSpec, definition(typeSpec,
                        "CREATE TYPE \"Source\".\"ADDRESS_T\" AS OBJECT (CITY VARCHAR2(20));"),
                        "DROP TYPE \"Target\"\"Owner\".\"ADDRESS_T\";"),
                new DropCase(typeBody, definition(typeBody,
                        "CREATE TYPE BODY \"Source\".\"ADDRESS_T\" AS END;", Set.of(typeSpec)),
                        "DROP TYPE BODY \"Target\"\"Owner\".\"ADDRESS_T\";"));

        for (DropCase dropCase : cases) {
            SchemaChange drop = change(ChangeKind.DROP, dropCase.key(),
                    null, dropCase.target(), null, AutomationLevel.SAFE_AUTOMATIC);
            assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_APPROVAL,
                    assertThrows(IllegalArgumentException.class,
                            () -> RENDERER.render(drop,
                                    context(DbType.ORACLE, false))).getMessage());
            RenderedStatement statement = RENDERER.render(
                    drop, context(DbType.ORACLE, true)).getFirst();
            assertEquals(dropCase.sql(), statement.sql());
            assertTrue(statement.destructive());
            assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_WARNING, statement.warning());
            assertFalse(statement.sql().contains("\n/"));
        }

        DefinitionObject wrongTriggerOwner = definition(trigger,
                "CREATE TRIGGER \"Source\".\"AUDIT_ORDERS\" BEFORE INSERT ON "
                        + "\"Source\".\"OTHER\" BEGIN NULL; END;", Set.of(table));
        assertSafeDefinitionFailure(change(ChangeKind.DROP, trigger,
                null, wrongTriggerOwner, null, AutomationLevel.SAFE_AUTOMATIC));
        ObjectKey malformedFunction = key(ObjectType.FUNCTION, "Source", "CALC", "secret");
        assertSafeDefinitionFailure(change(ChangeKind.DROP, malformedFunction, null,
                definition(malformedFunction,
                        "CREATE FUNCTION \"Source\".\"CALC\" RETURN NUMBER IS "
                                + "BEGIN RETURN 1; END;"),
                null, AutomationLevel.SAFE_AUTOMATIC));
    }

    @Test
    void structuredFragmentsSupportOracleAlternativeQuotesAndRejectUnsafeIdentityOrStatements() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "MESSAGES", "");
        CanonicalDataType varchar = new CanonicalDataType("VARCHAR2", 100L,
                null, null, false, 0, extensions("oracle.lengthSemantics", "CHAR"));
        ColumnDefinition quotedDefault = column("TEXT_VALUE", varchar, true,
                "q'[owner's; \"Source\".\"literal\"]'", 1, null);
        ColumnDefinition qualifiedDefault = column("NEXT_ID",
                new CanonicalDataType("NUMBER", null, 10, 0,
                        false, 0, extensions()), true,
                "\"Source\".\"ORDERS_SEQ\".NEXTVAL", 2, null);

        String tableSql = RENDERER.render(change(ChangeKind.CREATE, tableKey,
                        table(tableKey, List.of(quotedDefault, qualifiedDefault)), null, null,
                        AutomationLevel.SAFE_AUTOMATIC),
                context(DbType.ORACLE, false)).getFirst().sql();
        assertTrue(tableSql.contains("DEFAULT q'[owner's; \"Source\".\"literal\"]'"));
        assertTrue(tableSql.contains("DEFAULT \"Target\"\"Owner\"."
                + "\"ORDERS_SEQ\".NEXTVAL"));

        ColumnDefinition malformedQuote = column("BAD", varchar, true,
                "q'[owner's secret'", 1, null);
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        table(tableKey, List.of(malformedQuote)), null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        CanonicalDataType unsafeIdentity = new CanonicalDataType(
                "NUMBER", null, 10, 0, false, 0,
                extensions("oracle.identity", "ALWAYS",
                        "oracle.identityOptions", "START WITH: 1, INCREMENT BY: 1, "
                                + "MAX_VALUE: 999, MIN_VALUE: 1, CYCLE_FLAG: N, "
                                + "CACHE_SIZE: 20, ORDER_FLAG: Y; DROP USER secret"));
        ColumnDefinition identity = column("ID", unsafeIdentity, false,
                "GENERATED ALWAYS AS IDENTITY", 1, null);
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        table(tableKey, List.of(identity)), null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        IndexDefinition injectedIndex = new IndexDefinition(
                key(ObjectType.INDEX, "Source", "IX_BAD", ""), false,
                List.of("\"TEXT_VALUE\"); DROP TABLE secret"), null, false, Set.of());
        TableDefinition badIndexTable = new TableDefinition(tableKey,
                List.of(quotedDefault), List.of(), List.of(injectedIndex), Set.of());
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        badIndexTable, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());
    }

    @Test
    void refusesGuessedTableRebuildOwnerOrdinalAndIncompleteStructuredPaths() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ColumnDefinition first = column("ID",
                new CanonicalDataType("NUMBER", null, 10, 0, false, 0, extensions()),
                true, null, 1, null);
        ColumnDefinition reordered = column("ID", first.dataType(), true, null, 2, null);
        TableDefinition desired = table(tableKey, List.of(first));
        TableDefinition current = table(tableKey, List.of(reordered));

        for (PropertyDifference property : List.of(
                new PropertyDifference("columns", desired.columns(), current.columns(), "unsafe"),
                new PropertyDifference("columns[" + first.name().comparisonKey() + "].ordinal",
                        1, 2, "unsafe"))) {
            assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                    assertThrows(IllegalArgumentException.class,
                            () -> RENDERER.render(change(ChangeKind.ALTER, tableKey,
                                            desired, current, property,
                                            AutomationLevel.SAFE_AUTOMATIC),
                                    context(DbType.ORACLE, true))).getMessage());
        }

        ObjectKey wrongOwner = key(ObjectType.TABLE, "Other", "ORDERS", "");
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, wrongOwner,
                                        table(wrongOwner, List.of(first)), null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        ColumnDefinition duplicateOrdinal = column("OTHER", first.dataType(), true, null, 1, null);
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        table(tableKey, List.of(first, duplicateOrdinal)), null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        IndexDefinition partial = new IndexDefinition(
                key(ObjectType.INDEX, "Source", "IX_PARTIAL", ""), false,
                List.of("\"ID\""), "\"ID\" > 0", false, Set.of());
        TableDefinition partialTable = new TableDefinition(tableKey,
                List.of(first), List.of(), List.of(partial), Set.of());
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        partialTable, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        ColumnDefinition required = column("REQUIRED", first.dataType(), false, null, 2, null);
        TableDefinition desiredRequired = table(tableKey, List.of(first, required));
        PropertyDifference addRequired = new PropertyDifference(
                "columns[" + required.name().comparisonKey() + "]", required, null, "unsafe");
        assertEquals(OracleSchemaChangeRenderer.DESTRUCTIVE_APPROVAL,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.ALTER, tableKey,
                                        desiredRequired, desired, addRequired,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());
    }

    @Test
    void rejectsInternallyInconsistentKeysExtensionsBodyDependenciesAndNulComments() {
        ObjectKey disguisedTableKey = key(ObjectType.VIEW, "Source", "FAKE_TABLE", "");
        ColumnDefinition id = column("ID",
                new CanonicalDataType("NUMBER", null, 10, 0, false, 0, extensions()),
                true, null, 1, null);
        TableDefinition disguisedTable = table(disguisedTableKey, List.of(id));
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, disguisedTableKey,
                                        disguisedTable, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        ObjectKey signedSequenceKey = key(ObjectType.SEQUENCE, "Source", "SEQ", "secret");
        SequenceDefinition signedSequence = new SequenceDefinition(signedSequenceKey,
                "1", "1", "1", "999", false, 0, Set.of(),
                Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"));
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, signedSequenceKey,
                                        signedSequence, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "ORDERS", "");
        ConstraintDefinition mismatchedConstraint = new ConstraintDefinition(
                key(ObjectType.INDEX, "Source", "PK_BAD", ""),
                ConstraintKind.PRIMARY_KEY, List.of(id.name()), null, List.of(),
                null, null, null, false, Set.of());
        TableDefinition badConstraintTable = new TableDefinition(tableKey,
                List.of(id), List.of(mismatchedConstraint), List.of(), Set.of());
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        badConstraintTable, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        IndexDefinition mismatchedIndex = new IndexDefinition(
                key(ObjectType.VIEW, "Source", "IX_BAD", ""), false,
                List.of("\"ID\""), null, false, Set.of());
        TableDefinition badIndexTable = new TableDefinition(tableKey,
                List.of(id), List.of(), List.of(mismatchedIndex), Set.of());
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        badIndexTable, null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        ObjectKey packageBody = key(ObjectType.PACKAGE_BODY, "Source", "ORDER_API", "");
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, packageBody,
                                        definition(packageBody,
                                                "CREATE PACKAGE BODY \"Source\".\"ORDER_API\" "
                                                        + "IS END;"),
                                        null, null, AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());
        ObjectKey typeBody = key(ObjectType.TYPE, "Source", "ADDRESS_T", "BODY");
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, typeBody,
                                        definition(typeBody,
                                                "CREATE TYPE BODY \"Source\".\"ADDRESS_T\" "
                                                        + "AS END;"),
                                        null, null, AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        ColumnDefinition unknownExtension = column("UNKNOWN", type("NUMBER",
                extensions("oracle.unproved", "secret")), true, null, 1, null);
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE,
                assertThrows(IllegalArgumentException.class,
                        () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                        table(tableKey, List.of(unknownExtension)), null, null,
                                        AutomationLevel.SAFE_AUTOMATIC),
                                context(DbType.ORACLE, false))).getMessage());

        ColumnDefinition nulComment = column("NUL_COMMENT", id.dataType(),
                true, null, 1, "safe\0secret");
        IllegalArgumentException nulFailure = assertThrows(IllegalArgumentException.class,
                () -> RENDERER.render(change(ChangeKind.CREATE, tableKey,
                                table(tableKey, List.of(nulComment)), null, null,
                                AutomationLevel.SAFE_AUTOMATIC),
                        context(DbType.ORACLE, false)));
        assertEquals(OracleSchemaChangeRenderer.UNSUPPORTED_SHAPE, nulFailure.getMessage());
        assertFalse(nulFailure.getMessage().contains("secret"));
    }

    private static SchemaChange change(
            ChangeKind kind, ObjectKey key, SchemaObject source, SchemaObject target,
            PropertyDifference property, AutomationLevel automation) {
        return changeWithDependencies(kind, key, source, target, property, automation,
                Set.of("chg:dependency"));
    }

    private static SchemaChange changeWithDependencies(
            ChangeKind kind, ObjectKey key, SchemaObject source, SchemaObject target,
            PropertyDifference property, AutomationLevel automation, Set<String> dependencies) {
        return new SchemaChange("chg:test", kind, key, source, target, property,
                RiskLevel.LOW, automation, automation == AutomationLevel.SAFE_AUTOMATIC,
                dependencies, "fixed explanation");
    }

    private static SchemaChange tablePropertyChange(
            ObjectKey key, TableDefinition source, TableDefinition target,
            String path, Object sourceValue, Object targetValue) {
        return change(ChangeKind.ALTER, key, source, target,
                new PropertyDifference(path, sourceValue, targetValue, "safe"),
                AutomationLevel.SAFE_AUTOMATIC);
    }

    private static List<RenderedStatement> renderApproved(SchemaChange change) {
        return RENDERER.render(change, context(DbType.ORACLE, true));
    }

    private static void assertSafeDefinitionFailure(SchemaChange change) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RENDERER.render(change, context(DbType.ORACLE, true)));
        assertEquals(OracleSchemaChangeRenderer.UNSAFE_DEFINITION, failure.getMessage());
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getMessage().contains("jdbc:"));
    }

    private static ObjectKey key(
            ObjectType type, String schema, String name, String signature) {
        return new ObjectKey(type, OracleSchemaIdentifierNormalizer.object(schema, name), signature);
    }

    private static ColumnDefinition column(
            String name, CanonicalDataType type, boolean nullable,
            String defaultExpression, int ordinal, String comment) {
        return new ColumnDefinition(OracleSchemaIdentifierNormalizer.child(name), type,
                nullable, defaultExpression, ordinal, comment);
    }

    private static CanonicalDataType type(String baseType, SortedMap<String, String> extensions) {
        return new CanonicalDataType(baseType, null, null, null,
                false, 0, extensions);
    }

    private static TableDefinition table(ObjectKey key, List<ColumnDefinition> columns) {
        return new TableDefinition(key, columns, List.<ConstraintDefinition>of(),
                List.<IndexDefinition>of(), Set.of());
    }

    private static ConstraintDefinition constraint(
            ObjectType type, String name, ConstraintKind kind, List<ColumnDefinition> columns,
            ObjectKey referencedTable,
            List<com.datacube.spi.schemadiff.QualifiedName> referencedColumns,
            String expression, String deleteAction, boolean providerGenerated) {
        return new ConstraintDefinition(key(type, "Source", name, ""), kind,
                columns.stream().map(ColumnDefinition::name).toList(), referencedTable,
                referencedColumns, expression, null, deleteAction, providerGenerated, Set.of());
    }

    private static IndexDefinition index(
            String name, boolean unique, List<String> expressions, boolean providerGenerated) {
        return new IndexDefinition(key(ObjectType.INDEX, "Source", name, ""), unique,
                expressions, null, providerGenerated, Set.of());
    }

    private static DefinitionObject definition(ObjectKey key, String definition) {
        return definition(key, definition, Set.of());
    }

    private static DefinitionObject definition(
            ObjectKey key, String definition, Set<ObjectKey> dependencies) {
        return new DefinitionObject(key, definition, definition, dependencies,
                DefinitionConfidence.HIGH);
    }

    private static String oracleSignature(String... modeAndType) {
        StringBuilder signature = new StringBuilder("oracle-routine-signature-v1\0");
        for (String value : modeAndType) signature.append(value.length()).append(':').append(value);
        return signature.toString();
    }

    private static SortedMap<String, String> extensions(String... entries) {
        SortedMap<String, String> values = new TreeMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return values;
    }

    private static int countOccurrences(String value, String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static RenderContext context(DbType type, boolean destructiveApproved) {
        return new RenderContext(type,
                OracleSchemaIdentifierNormalizer.schema("Source"),
                OracleSchemaIdentifierNormalizer.schema("Target\"Owner"),
                destructiveApproved);
    }

    private record DefinitionCase(
            ObjectKey key, String ddl, Set<ObjectKey> dependencies, boolean slash) {
    }

    private record DropCase(ObjectKey key, SchemaObject target, String sql) {
    }
}
