package com.datacube.provider.postgres;

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
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.TableDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSchemaChangeRendererTest {
    private static final String WRONG_DATABASE =
            "PostgreSQL renderer requires a PostgreSQL context";
    private static final String MANUAL_CHANGE =
            "Schema change requires manual execution";
    private static final String DESTRUCTIVE_APPROVAL =
            "Destructive schema change requires explicit approval";
    private static final String DESTRUCTIVE_WARNING =
            "Destructive PostgreSQL schema change";

    private final PgSchemaChangeRenderer renderer = new PgSchemaChangeRenderer();

    @Test
    void enforcesDatabaseManualAndDestructiveSafetyGatesWithFixedMessages() {
        ObjectKey secretKey = key(ObjectType.SEQUENCE, "source_secret", "credential_secret", "");
        SequenceDefinition sequence = new SequenceDefinition(
                secretKey, "1", "1", "1", "999", false, 1, Set.of());
        SchemaChange create = change("chg:create", ChangeKind.CREATE, secretKey,
                sequence, null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);

        IllegalArgumentException wrongDatabase = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(create, context(DbType.ORACLE, false)));
        assertEquals(WRONG_DATABASE, wrongDatabase.getMessage());

        SchemaChange manual = change("chg:manual", ChangeKind.MANUAL, secretKey,
                sequence, null, AutomationLevel.MANUAL_ONLY, RiskLevel.HIGH);
        IllegalArgumentException manualFailure = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(manual, context(DbType.POSTGRESQL, false)));
        assertEquals(MANUAL_CHANGE, manualFailure.getMessage());

        SchemaChange drop = change("chg:drop", ChangeKind.DROP, secretKey,
                null, sequence, AutomationLevel.DESTRUCTIVE_OPT_IN, RiskLevel.CRITICAL);
        IllegalArgumentException approvalFailure = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(drop, context(DbType.POSTGRESQL, false)));
        assertEquals(DESTRUCTIVE_APPROVAL, approvalFailure.getMessage());

        for (String message : List.of(wrongDatabase.getMessage(), manualFailure.getMessage(),
                approvalFailure.getMessage())) {
            assertFalse(message.contains("source_secret"));
            assertFalse(message.contains("credential_secret"));
        }
    }

    @Test
    void createsSequenceInTargetSchemaWithOneTrailingSemicolon() {
        ObjectKey key = key(ObjectType.SEQUENCE, "Source", "Order\"Seq", "");
        SequenceDefinition sequence = new SequenceDefinition(
                key, "7", "3", "1", "999", true, 20, Set.of());
        SchemaChange change = change("chg:create-sequence", ChangeKind.CREATE, key,
                sequence, null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);

        List<RenderedStatement> statements = renderer.render(change,
                context(DbType.POSTGRESQL, false));

        assertEquals(1, statements.size());
        assertEquals("CREATE SEQUENCE \"Target\".\"Order\"\"Seq\" START WITH 7 "
                + "INCREMENT BY 3 MINVALUE 1 MAXVALUE 999 CYCLE CACHE 20;",
                statements.getFirst().sql());
        assertStatementMetadata(statements.getFirst(), change, false, null);
        assertExactlyOneTrailingSemicolon(statements.getFirst().sql());
    }

    @Test
    void approvedOverloadedRoutineDropUsesOnlyStructuredIdentitySignature() {
        ObjectKey key = key(ObjectType.FUNCTION, "source", "calculate",
                "\"pg_catalog\".\"int4\", \"source\".\"money_type\"[]");
        DefinitionObject target = new DefinitionObject(key,
                "definition-secret", "display-secret", Set.of(), DefinitionConfidence.HIGH);
        SchemaChange change = new SchemaChange(
                "chg:drop-function", ChangeKind.DROP, key, null, target, null,
                RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN, false,
                Set.of("chg:dependency"), "safe");

        List<RenderedStatement> statements = renderer.render(change,
                context(DbType.POSTGRESQL, true));

        assertEquals(1, statements.size());
        assertEquals("DROP FUNCTION \"Target\".\"calculate\"(\"pg_catalog\".\"int4\", "
                + "\"source\".\"money_type\"[]);", statements.getFirst().sql());
        assertStatementMetadata(statements.getFirst(), change, true, DESTRUCTIVE_WARNING);
        assertFalse(statements.getFirst().sql().contains("definition-secret"));
        assertFalse(statements.getFirst().sql().contains("display-secret"));
        assertExactlyOneTrailingSemicolon(statements.getFirst().sql());
    }

    @Test
    void createsTableWithColumnsThenNonForeignKeysThenForeignKeysAndIndependentIndexes() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "Order\"Line", "");
        ObjectKey parentKey = key(ObjectType.TABLE, "Source", "Order", "");
        ColumnDefinition id = column("Id", "integer", false,
                "GENERATED ALWAYS AS IDENTITY", 1);
        ColumnDefinition amount = column("Amount", "numeric(12,2)", true, "0", 2);
        ColumnDefinition computed = column("Computed", "numeric(12,2)", true,
                "GENERATED ALWAYS AS ((\"Amount\" * 2)) STORED", 3);
        ConstraintDefinition primaryKey = constraint(
                ObjectType.PRIMARY_KEY, "pk_lines", ConstraintKind.PRIMARY_KEY,
                List.of(id.name()), null, List.of(), null, false, Set.of());
        ConstraintDefinition check = constraint(
                ObjectType.CHECK_CONSTRAINT, "ck_amount", ConstraintKind.CHECK,
                List.of(), null, List.of(), "CHECK ((\"Amount\" >= 0))", false, Set.of());
        ConstraintDefinition foreignKey = constraint(
                ObjectType.FOREIGN_KEY, "fk_order", ConstraintKind.FOREIGN_KEY,
                List.of(id.name()), parentKey, List.of(PgSchemaIdentifierNormalizer.child("Id")),
                null, false, Set.of(parentKey));
        IndexDefinition backingIndex = index("pk_lines", true,
                List.of("\"Id\""), null, true);
        IndexDefinition independentIndex = index("ix_amount", false,
                List.of("lower((\"Amount\")::text)", "\"Computed\" DESC"),
                "(\"Amount\" > 0)", false);
        TableDefinition source = new TableDefinition(tableKey,
                List.of(computed, amount, id), List.of(foreignKey, check, primaryKey),
                List.of(independentIndex, backingIndex), Set.of(parentKey));
        SchemaChange change = change("chg:create-table", ChangeKind.CREATE, tableKey,
                source, null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);

        List<RenderedStatement> statements = renderer.render(change,
                context(DbType.POSTGRESQL, false));

        assertEquals(List.of(
                "CREATE TABLE \"Target\".\"Order\"\"Line\" (\n"
                        + "    \"Id\" integer GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                        + "    \"Amount\" numeric(12,2) DEFAULT 0 NULL,\n"
                        + "    \"Computed\" numeric(12,2) GENERATED ALWAYS AS "
                        + "((\"Amount\" * 2)) STORED NULL,\n"
                        + "    CONSTRAINT \"pk_lines\" PRIMARY KEY (\"Id\"),\n"
                        + "    CONSTRAINT \"ck_amount\" CHECK ((\"Amount\" >= 0))\n"
                        + ");",
                "ALTER TABLE \"Target\".\"Order\"\"Line\" ADD CONSTRAINT \"fk_order\" "
                        + "FOREIGN KEY (\"Id\") REFERENCES \"Target\".\"Order\" (\"Id\");",
                "CREATE INDEX \"Target\".\"ix_amount\" ON \"Target\".\"Order\"\"Line\" "
                        + "(lower((\"Amount\")::text), \"Computed\" DESC) WHERE (\"Amount\" > 0);"),
                statements.stream().map(RenderedStatement::sql).toList());
        statements.forEach(statement -> {
            assertStatementMetadata(statement, change, false, null);
            assertExactlyOneTrailingSemicolon(statement.sql());
        });
    }

    @Test
    void addsOnlyAnExactWholeStructuredColumnAndPreservesArrayTypmodAndNullability() {
        ObjectKey tableKey = key(ObjectType.TABLE, "source", "events", "");
        ColumnDefinition added = column("Payload\"s", "timestamp(3) with time zone[][]",
                true, null, 2);
        TableDefinition source = table(tableKey, List.of(column("id", "bigint", false, null, 1), added));
        TableDefinition target = table(tableKey, List.of(column("id", "bigint", false, null, 1)));
        String path = "columns[" + added.name().comparisonKey() + "]";
        SchemaChange change = new SchemaChange(
                "chg:add-column", ChangeKind.ALTER, tableKey, source, target,
                new PropertyDifference(path, added, null, "safe"),
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");

        List<RenderedStatement> statements = renderer.render(change,
                context(DbType.POSTGRESQL, false));

        assertEquals(List.of("ALTER TABLE \"Target\".\"events\" ADD COLUMN "
                        + "\"Payload\"\"s\" timestamp(3) with time zone[][] NULL;"),
                statements.stream().map(RenderedStatement::sql).toList());
    }

    @Test
    void rejectsPseudoWholeColumnPathsWithoutExposingTheIdentifier() {
        ObjectKey tableKey = key(ObjectType.TABLE, "source", "events", "");
        ColumnDefinition added = column("secret_column", "text", true, null, 2);
        TableDefinition source = table(tableKey, List.of(added));
        TableDefinition target = table(tableKey, List.of());
        SchemaChange change = new SchemaChange(
                "chg:pseudo", ChangeKind.ALTER, tableKey, source, target,
                new PropertyDifference("columns[other]", added, null, "safe"),
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(change, context(DbType.POSTGRESQL, false)));

        assertEquals("Schema change shape is unsupported", failure.getMessage());
        assertFalse(failure.getMessage().contains("secret_column"));
    }

    @Test
    void rendersConstraintSetDifferenceWithRemovalBeforeAddition() {
        ObjectKey tableKey = key(ObjectType.TABLE, "source", "accounts", "");
        ColumnDefinition code = column("Code", "text", false, null, 1);
        ConstraintDefinition oldCheck = constraint(ObjectType.CHECK_CONSTRAINT, "old_rule",
                ConstraintKind.CHECK, List.of(), null, List.of(), "CHECK (length(\"Code\") > 0)",
                false, Set.of());
        ConstraintDefinition newUnique = constraint(ObjectType.UNIQUE_CONSTRAINT, "new_rule",
                ConstraintKind.UNIQUE, List.of(code.name()), null, List.of(), null,
                false, Set.of());
        TableDefinition source = new TableDefinition(tableKey, List.of(code),
                List.of(newUnique), List.of(), Set.of());
        TableDefinition target = new TableDefinition(tableKey, List.of(code),
                List.of(oldCheck), List.of(), Set.of());
        SchemaChange change = tablePropertyChange("chg:constraints", tableKey, source, target,
                "constraints", List.of(newUnique), List.of(oldCheck));

        List<RenderedStatement> statements = renderer.render(change,
                context(DbType.POSTGRESQL, true));

        assertEquals(List.of(
                "ALTER TABLE \"Target\".\"accounts\" DROP CONSTRAINT \"old_rule\";",
                "ALTER TABLE \"Target\".\"accounts\" ADD CONSTRAINT \"new_rule\" "
                        + "UNIQUE (\"Code\");"),
                statements.stream().map(RenderedStatement::sql).toList());
        statements.forEach(statement -> assertStatementMetadata(
                statement, change, true, DESTRUCTIVE_WARNING));
    }

    @Test
    void rendersIndependentIndexSetDifferenceAndSuppressesConstraintBackingIndexes() {
        ObjectKey tableKey = key(ObjectType.TABLE, "source", "accounts", "");
        ColumnDefinition code = column("Code", "text", false, null, 1);
        IndexDefinition oldIndex = index("old_ix", false, List.of("\"Code\""), null, false);
        IndexDefinition newIndex = index("new_ix", true, List.of("lower(\"Code\")"),
                "(\"Code\" IS NOT NULL)", false);
        IndexDefinition oldBacking = index("old_backing", true, List.of("\"Code\""), null, true);
        IndexDefinition newBacking = index("new_backing", true, List.of("\"Code\""), null, true);
        TableDefinition source = new TableDefinition(tableKey, List.of(code), List.of(),
                List.of(newBacking, newIndex), Set.of());
        TableDefinition target = new TableDefinition(tableKey, List.of(code), List.of(),
                List.of(oldBacking, oldIndex), Set.of());
        SchemaChange change = tablePropertyChange("chg:indexes", tableKey, source, target,
                "indexes", List.of(newBacking, newIndex), List.of(oldBacking, oldIndex));

        List<RenderedStatement> statements = renderer.render(change,
                context(DbType.POSTGRESQL, true));

        assertEquals(List.of(
                "DROP INDEX \"Target\".\"old_ix\";",
                "CREATE UNIQUE INDEX \"Target\".\"new_ix\" ON \"Target\".\"accounts\" "
                        + "(lower(\"Code\")) WHERE (\"Code\" IS NOT NULL);"),
                statements.stream().map(RenderedStatement::sql).toList());
    }

    @Test
    void rendersExactColumnTypeNullabilityDefaultCommentAndApprovedDropChanges() {
        ObjectKey tableKey = key(ObjectType.TABLE, "source", "accounts", "");
        ColumnDefinition oldColumn = column("Value\"X", "numeric(10,2)", true, "0", 1);
        ColumnDefinition newColumn = column("Value\"X", "numeric(20,4)", false, "1", 1);
        String basePath = "columns[" + newColumn.name().comparisonKey() + "]";

        assertEquals("ALTER TABLE \"Target\".\"accounts\" ALTER COLUMN \"Value\"\"X\" "
                        + "TYPE numeric(20,4);",
                renderColumnProperty(tableKey, oldColumn, newColumn, basePath + ".dataType",
                        newColumn.dataType(), oldColumn.dataType()));
        assertEquals("ALTER TABLE \"Target\".\"accounts\" ALTER COLUMN \"Value\"\"X\" SET NOT NULL;",
                renderColumnProperty(tableKey, oldColumn, newColumn, basePath + ".nullable",
                        false, true));
        assertEquals("ALTER TABLE \"Target\".\"accounts\" ALTER COLUMN \"Value\"\"X\" SET DEFAULT 1;",
                renderColumnProperty(tableKey, oldColumn, newColumn, basePath + ".normalizedDefault",
                        "1", "0"));
        assertEquals("COMMENT ON COLUMN \"Target\".\"accounts\".\"Value\"\"X\" IS 'owner''s note';",
                renderColumnProperty(tableKey,
                        withComment(oldColumn, null), withComment(newColumn, "owner's note"),
                        basePath + ".comment", "owner's note", null));

        TableDefinition source = table(tableKey, List.of());
        TableDefinition target = table(tableKey, List.of(oldColumn));
        SchemaChange drop = new SchemaChange("chg:drop-column", ChangeKind.ALTER, tableKey,
                source, target, new PropertyDifference(basePath, null, oldColumn, "safe"),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, false, Set.of(), "safe");
        assertEquals("ALTER TABLE \"Target\".\"accounts\" DROP COLUMN \"Value\"\"X\";",
                renderer.render(drop, context(DbType.POSTGRESQL, true)).getFirst().sql());
    }

    @Test
    void rejectsColumnOrdinalAndMismatchedStructuredPropertyValues() {
        ObjectKey tableKey = key(ObjectType.TABLE, "source", "accounts", "");
        ColumnDefinition oldColumn = column("secret_value", "integer", true, null, 2);
        ColumnDefinition newColumn = column("secret_value", "integer", true, null, 1);
        String path = "columns[" + newColumn.name().comparisonKey() + "].ordinal";
        TableDefinition source = table(tableKey, List.of(newColumn));
        TableDefinition target = table(tableKey, List.of(oldColumn));
        SchemaChange ordinal = tablePropertyChange("chg:ordinal", tableKey, source, target,
                path, 1, 2);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(ordinal, context(DbType.POSTGRESQL, true)));

        assertEquals("Schema change shape is unsupported", failure.getMessage());
        assertFalse(failure.getMessage().contains("secret_value"));
    }

    @Test
    void createsEveryDefinitionObjectInTargetSchemaAndNormalizesOneDelimiter() {
        ObjectKey owner = key(ObjectType.TABLE, "Source", "Owner", "");
        List<DefinitionCase> cases = List.of(
                new DefinitionCase(ObjectType.VIEW, "View", "",
                        "CREATE VIEW \"Source\".\"View\" AS SELECT 1;",
                        "CREATE VIEW \"Target\".\"View\" AS SELECT 1;", Set.of()),
                new DefinitionCase(ObjectType.MATERIALIZED_VIEW, "Mat", "",
                        "CREATE MATERIALIZED VIEW \"Source\".\"Mat\" AS SELECT 1;",
                        "CREATE MATERIALIZED VIEW \"Target\".\"Mat\" AS SELECT 1;", Set.of()),
                new DefinitionCase(ObjectType.FUNCTION, "Fn", "\"pg_catalog\".\"int4\"",
                        "CREATE OR REPLACE FUNCTION \"Source\".\"Fn\"(\"pg_catalog\".\"int4\") "
                                + "RETURNS integer LANGUAGE sql AS $$SELECT 1$$;",
                        "CREATE OR REPLACE FUNCTION \"Target\".\"Fn\"(\"pg_catalog\".\"int4\") "
                                + "RETURNS integer LANGUAGE sql AS $$SELECT 1$$;", Set.of()),
                new DefinitionCase(ObjectType.PROCEDURE, "Proc", "",
                        "CREATE OR REPLACE PROCEDURE \"Source\".\"Proc\"() LANGUAGE sql AS $$SELECT 1$$;",
                        "CREATE OR REPLACE PROCEDURE \"Target\".\"Proc\"() LANGUAGE sql AS $$SELECT 1$$;",
                        Set.of()),
                new DefinitionCase(ObjectType.TRIGGER, "Trig", owner.name().comparisonKey(),
                        "CREATE TRIGGER \"Trig\" AFTER INSERT ON \"Source\".\"Owner\" "
                                + "EXECUTE FUNCTION \"Source\".\"Fn\"();",
                        "CREATE TRIGGER \"Trig\" AFTER INSERT ON \"Target\".\"Owner\" "
                                + "EXECUTE FUNCTION \"Target\".\"Fn\"();", Set.of(owner)),
                new DefinitionCase(ObjectType.TYPE, "Mood", "enum",
                        "CREATE TYPE \"Source\".\"Mood\" AS ENUM ('happy;day', 'sad');",
                        "CREATE TYPE \"Target\".\"Mood\" AS ENUM ('happy;day', 'sad');", Set.of()));

        for (DefinitionCase definitionCase : cases) {
            ObjectKey objectKey = key(definitionCase.type(), "Source", definitionCase.name(),
                    definitionCase.signature());
            DefinitionObject source = new DefinitionObject(objectKey, definitionCase.definition(),
                    definitionCase.definition(), definitionCase.dependencies(), DefinitionConfidence.HIGH);
            SchemaChange change = change("chg:create-" + definitionCase.type(), ChangeKind.CREATE,
                    objectKey, source, null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);

            List<RenderedStatement> statements = renderer.render(change,
                    context(DbType.POSTGRESQL, false));

            assertEquals(List.of(definitionCase.expected()),
                    statements.stream().map(RenderedStatement::sql).toList());
            assertExactlyOneTrailingSemicolon(statements.getFirst().sql());
        }
    }

    @Test
    void replaceUsesTheWholeHighConfidenceDefinitionAndOnlyForSupportedObjectTypes() {
        for (ObjectType type : List.of(ObjectType.VIEW, ObjectType.FUNCTION, ObjectType.PROCEDURE)) {
            ObjectKey objectKey = key(type, "Source", "ReplaceMe", "");
            String noun = type == ObjectType.VIEW ? "VIEW"
                    : type == ObjectType.FUNCTION ? "FUNCTION" : "PROCEDURE";
            String suffix = type == ObjectType.VIEW ? " AS SELECT 2"
                    : "() LANGUAGE sql AS $$SELECT 2$$";
            DefinitionObject source = definition(objectKey,
                    "CREATE " + noun + " \"Source\".\"ReplaceMe\"" + suffix);
            DefinitionObject target = definition(objectKey,
                    "CREATE " + noun + " \"Source\".\"ReplaceMe\"" + suffix.replace('2', '1'));
            SchemaChange change = new SchemaChange("chg:replace", ChangeKind.REPLACE,
                    objectKey, source, target,
                    new PropertyDifference("normalizedDefinition", "sha256:new-secret",
                            "sha256:old-secret", "safe"), RiskLevel.HIGH,
                    AutomationLevel.DESTRUCTIVE_OPT_IN, false, Set.of("chg:dependency"), "safe");

            String sql = renderer.render(change, context(DbType.POSTGRESQL, true)).getFirst().sql();

            assertTrue(sql.startsWith("CREATE OR REPLACE " + noun + " \"Target\".\"ReplaceMe\""));
            assertTrue(sql.contains("2"));
            assertFalse(sql.contains("sha256:"));
        }

        for (ObjectType type : List.of(
                ObjectType.MATERIALIZED_VIEW, ObjectType.TRIGGER, ObjectType.TYPE)) {
            ObjectKey objectKey = key(type, "Source", "Unsupported", "");
            DefinitionObject definition = definition(objectKey, "CREATE " + type + " secret");
            SchemaChange change = new SchemaChange("chg:replace", ChangeKind.REPLACE,
                    objectKey, definition, definition,
                    new PropertyDifference("dependencies", Set.of(), Set.of(key(
                            ObjectType.TABLE, "Source", "dependency", "")), "safe"),
                    RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, false, Set.of(), "safe");
            assertEquals("Schema change shape is unsupported",
                    assertThrows(IllegalArgumentException.class,
                            () -> renderer.render(change, context(DbType.POSTGRESQL, true))).getMessage());
        }
    }

    @Test
    void lexicalRetargetChangesOnlyQualifiedIdentifierTokensAndRejectsUnsafeDollarBodies() {
        ObjectKey key = key(ObjectType.VIEW, "Source", "Lexical", "");
        String definition = "CREATE VIEW \"Source\".\"Lexical\" AS\n"
                + "SELECT '\"Source\".\"Table\"' AS literal -- \"Source\".\"LineComment\"\n"
                + "FROM \"Source\".\"RealTable\" /* \"Source\".\"BlockComment\" */";
        SchemaChange create = change("chg:lexical", ChangeKind.CREATE, key,
                definition(key, definition), null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);

        String rendered = renderer.render(create,
                context(DbType.POSTGRESQL, false)).getFirst().sql();

        assertEquals("CREATE VIEW \"Target\".\"Lexical\" AS\n"
                + "SELECT '\"Source\".\"Table\"' AS literal -- \"Source\".\"LineComment\"\n"
                + "FROM \"Target\".\"RealTable\" /* \"Source\".\"BlockComment\" */;", rendered);

        ObjectKey functionKey = key(ObjectType.FUNCTION, "Source", "Unsafe", "");
        String unsafe = "CREATE FUNCTION \"Source\".\"Unsafe\"() RETURNS integer LANGUAGE plpgsql "
                + "AS $body$ BEGIN PERFORM \"Source\".\"SecretTable\"; RETURN 1; END $body$";
        SchemaChange unsafeCreate = change("chg:unsafe", ChangeKind.CREATE, functionKey,
                definition(functionKey, unsafe), null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(unsafeCreate, context(DbType.POSTGRESQL, false)));
        assertEquals("Schema definition cannot be retargeted safely", failure.getMessage());
        assertFalse(failure.getMessage().contains("SecretTable"));

        String textOnly = "CREATE FUNCTION \"Source\".\"Unsafe\"() RETURNS integer LANGUAGE plpgsql "
                + "AS $body$ BEGIN RAISE NOTICE '\"Source\".\"TextOnly\"'; "
                + "-- \"Source\".\"CommentOnly\"\nRETURN 1; END $body$";
        SchemaChange textOnlyCreate = change("chg:text-only", ChangeKind.CREATE, functionKey,
                definition(functionKey, textOnly), null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);
        assertEquals("CREATE FUNCTION \"Target\".\"Unsafe\"() RETURNS integer LANGUAGE plpgsql "
                        + "AS $body$ BEGIN RAISE NOTICE '\"Source\".\"TextOnly\"'; "
                        + "-- \"Source\".\"CommentOnly\"\nRETURN 1; END $body$;",
                renderer.render(textOnlyCreate,
                        context(DbType.POSTGRESQL, false)).getFirst().sql());
    }

    @Test
    void approvedDropMatrixUsesPostgresSyntaxAndTriggerOwnerDependency() {
        ObjectKey owner = key(ObjectType.TABLE, "Source", "Owner", "");
        List<DropCase> cases = List.of(
                new DropCase(ObjectType.TABLE, "Table", "", Set.of(),
                        "DROP TABLE \"Target\".\"Table\";"),
                new DropCase(ObjectType.SEQUENCE, "Sequence", "", Set.of(),
                        "DROP SEQUENCE \"Target\".\"Sequence\";"),
                new DropCase(ObjectType.VIEW, "View", "", Set.of(),
                        "DROP VIEW \"Target\".\"View\";"),
                new DropCase(ObjectType.MATERIALIZED_VIEW, "Mat", "", Set.of(),
                        "DROP MATERIALIZED VIEW \"Target\".\"Mat\";"),
                new DropCase(ObjectType.FUNCTION, "Routine", "\"pg_catalog\".\"int4\"", Set.of(),
                        "DROP FUNCTION \"Target\".\"Routine\"(\"pg_catalog\".\"int4\");"),
                new DropCase(ObjectType.PROCEDURE, "Routine", "", Set.of(),
                        "DROP PROCEDURE \"Target\".\"Routine\"();"),
                new DropCase(ObjectType.TYPE, "Type", "enum", Set.of(),
                        "DROP TYPE \"Target\".\"Type\";"),
                new DropCase(ObjectType.TRIGGER, "Trigger", owner.name().comparisonKey(), Set.of(owner),
                        "DROP TRIGGER \"Trigger\" ON \"Target\".\"Owner\";"));
        for (DropCase dropCase : cases) {
            ObjectKey objectKey = key(dropCase.type(), "Source", dropCase.name(), dropCase.signature());
            com.datacube.spi.schemadiff.SchemaObject target = dropCase.type() == ObjectType.TABLE
                    ? table(objectKey, List.of(column("id", "integer", false, null, 1)))
                    : dropCase.type() == ObjectType.SEQUENCE
                    ? new SequenceDefinition(objectKey, "1", "1", "1", null, false, 1, Set.of())
                    : new DefinitionObject(objectKey, "secret", "secret", dropCase.dependencies(),
                    DefinitionConfidence.HIGH);
            SchemaChange change = change("chg:drop-" + dropCase.type(), ChangeKind.DROP,
                    objectKey, null, target, AutomationLevel.DESTRUCTIVE_OPT_IN, RiskLevel.CRITICAL);

            RenderedStatement statement = renderer.render(change,
                    context(DbType.POSTGRESQL, true)).getFirst();

            assertEquals(dropCase.expected(), statement.sql());
            assertStatementMetadata(statement, change, true, DESTRUCTIVE_WARNING);
            assertExactlyOneTrailingSemicolon(statement.sql());
        }
    }

    @Test
    void lexicalRetargetDecodesQuotedSchemaIdentifiersWithEmbeddedQuotes() {
        ObjectKey objectKey = key(ObjectType.VIEW, "a\"b", "V\"Q", "");
        String definition = "CREATE VIEW \"a\"\"b\".\"V\"\"Q\" AS SELECT * FROM "
                + "\"a\"\"b\".\"T\"\"Q\" WHERE note = '\"a\"\"b\".\"literal\"'";
        SchemaChange change = change("chg:embedded", ChangeKind.CREATE, objectKey,
                definition(objectKey, definition), null,
                AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);
        RenderContext context = new RenderContext(DbType.POSTGRESQL,
                PgSchemaIdentifierNormalizer.schema("a\"b"),
                PgSchemaIdentifierNormalizer.schema("t\"g"), false);

        assertEquals("CREATE VIEW \"t\"\"g\".\"V\"\"Q\" AS SELECT * FROM "
                        + "\"t\"\"g\".\"T\"\"Q\" WHERE note = '\"a\"\"b\".\"literal\"';",
                renderer.render(change, context).getFirst().sql());
    }

    @Test
    void rendersEveryStructuredSequenceAlterProperty() {
        ObjectKey key = key(ObjectType.SEQUENCE, "Source", "Seq", "");
        SequenceDefinition source = new SequenceDefinition(
                key, "5", "2", null, "1000", true, 20, Set.of());
        SequenceDefinition target = new SequenceDefinition(
                key, "1", "1", "1", null, false, 1, Set.of());
        assertEquals("ALTER SEQUENCE \"Target\".\"Seq\" START WITH 5;",
                renderSequenceProperty(source, target, "startValue", "5", "1"));
        assertEquals("ALTER SEQUENCE \"Target\".\"Seq\" INCREMENT BY 2;",
                renderSequenceProperty(source, target, "incrementBy", "2", "1"));
        assertEquals("ALTER SEQUENCE \"Target\".\"Seq\" NO MINVALUE;",
                renderSequenceProperty(source, target, "minimumValue", null, "1"));
        assertEquals("ALTER SEQUENCE \"Target\".\"Seq\" MAXVALUE 1000;",
                renderSequenceProperty(source, target, "maximumValue", "1000", null));
        assertEquals("ALTER SEQUENCE \"Target\".\"Seq\" CYCLE;",
                renderSequenceProperty(source, target, "cycle", true, false));
        assertEquals("ALTER SEQUENCE \"Target\".\"Seq\" CACHE 20;",
                renderSequenceProperty(source, target, "cacheSize", 20, 1));
    }

    @Test
    void rejectsLowConfidenceMultipleStatementsUnsafeSignaturesAndNumericFragmentsSafely() {
        ObjectKey viewKey = key(ObjectType.VIEW, "Source", "secret_view", "");
        DefinitionObject low = new DefinitionObject(viewKey, "secret-sql", "secret-sql",
                Set.of(), DefinitionConfidence.LOW);
        SchemaChange lowCreate = change("chg:low", ChangeKind.CREATE, viewKey, low, null,
                AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);
        assertSafeFailure(MANUAL_CHANGE, "secret-sql",
                () -> renderer.render(lowCreate, context(DbType.POSTGRESQL, false)));

        DefinitionObject multiple = definition(viewKey,
                "CREATE VIEW \"Source\".\"secret_view\" AS SELECT 1; DROP TABLE secret_table");
        SchemaChange multipleCreate = change("chg:multiple", ChangeKind.CREATE, viewKey,
                multiple, null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);
        assertSafeFailure("Schema definition cannot be retargeted safely", "secret_table",
                () -> renderer.render(multipleCreate, context(DbType.POSTGRESQL, false)));

        ObjectKey routineKey = key(ObjectType.FUNCTION, "Source", "secret_function",
                "integer); DROP TABLE secret_table; --");
        SchemaChange routineDrop = change("chg:unsafe-signature", ChangeKind.DROP, routineKey,
                null, definition(routineKey, "secret-definition"),
                AutomationLevel.DESTRUCTIVE_OPT_IN, RiskLevel.CRITICAL);
        assertSafeFailure("Schema change shape is unsupported", "secret_table",
                () -> renderer.render(routineDrop, context(DbType.POSTGRESQL, true)));

        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "Source", "secret_sequence", "");
        SequenceDefinition unsafeSequence = new SequenceDefinition(sequenceKey,
                "1; DROP TABLE secret_table", "1", "1", "9", false, 1, Set.of());
        SchemaChange sequenceCreate = change("chg:unsafe-sequence", ChangeKind.CREATE, sequenceKey,
                unsafeSequence, null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);
        assertSafeFailure("Schema change shape is unsupported", "secret_table",
                () -> renderer.render(sequenceCreate, context(DbType.POSTGRESQL, false)));

        for (String malformed : List.of(
                "CREATE VIEW \"Source\".\"secret_view\" AS SELECT 'unterminated",
                "CREATE VIEW \"Source\".\"secret_view\" AS SELECT \"unterminated",
                "CREATE VIEW \"Source\".\"secret_view\" AS SELECT 1 /* unterminated",
                "CREATE VIEW \"Source\".\"secret_view\" AS SELECT $tag$unterminated",
                "CREATE VIEW \"Source\".\"secret_view\" AS SELECT 1\0secret",
                "CREATE VIEW \"Source\".\"secret_view\" AS SELECT 1 -- trailing comment")) {
            SchemaChange malformedCreate = change("chg:malformed", ChangeKind.CREATE, viewKey,
                    definition(viewKey, malformed), null,
                    AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);
            assertSafeFailure("Schema definition cannot be retargeted safely", "secret",
                    () -> renderer.render(malformedCreate,
                            context(DbType.POSTGRESQL, false)));
        }
    }

    @Test
    void retargetsStructuredTypeAndDefaultFragmentsAndRejectsStatementInjection() {
        ObjectKey tableKey = key(ObjectType.TABLE, "Source", "typed_table", "");
        ColumnDefinition added = column("payload", "\"Source\".\"PayloadType\"[]", true,
                "\"Source\".\"next_payload\"()", 1);
        SchemaChange safe = new SchemaChange("chg:add-typed", ChangeKind.ALTER, tableKey,
                table(tableKey, List.of(added)), table(tableKey, List.of()),
                new PropertyDifference("columns[" + added.name().comparisonKey() + "]",
                        added, null, "safe"), RiskLevel.LOW,
                AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");

        assertEquals("ALTER TABLE \"Target\".\"typed_table\" ADD COLUMN \"payload\" "
                        + "\"Target\".\"PayloadType\"[] DEFAULT \"Target\".\"next_payload\"() NULL;",
                renderer.render(safe, context(DbType.POSTGRESQL, false)).getFirst().sql());

        ColumnDefinition unsafe = column("payload", "text; DROP TABLE secret_table", true,
                null, 1);
        SchemaChange unsafeChange = new SchemaChange("chg:unsafe-type", ChangeKind.ALTER, tableKey,
                table(tableKey, List.of(unsafe)), table(tableKey, List.of()),
                new PropertyDifference("columns[" + unsafe.name().comparisonKey() + "]",
                        unsafe, null, "safe"), RiskLevel.LOW,
                AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");
        assertSafeFailure("Schema change shape is unsupported", "secret_table",
                () -> renderer.render(unsafeChange, context(DbType.POSTGRESQL, false)));
    }

    @Test
    void triggerCreateAndDropRequireExactlyOneStructuredOwningTable() {
        ObjectKey triggerKey = key(ObjectType.TRIGGER, "Source", "secret_trigger", "owner");
        DefinitionObject missingOwner = new DefinitionObject(triggerKey,
                "CREATE TRIGGER \"secret_trigger\" AFTER INSERT ON \"Source\".\"secret_table\" "
                        + "EXECUTE FUNCTION \"Source\".\"secret_fn\"()",
                "CREATE TRIGGER \"secret_trigger\" AFTER INSERT ON \"Source\".\"secret_table\" "
                        + "EXECUTE FUNCTION \"Source\".\"secret_fn\"()",
                Set.of(), DefinitionConfidence.HIGH);
        SchemaChange create = change("chg:create-trigger", ChangeKind.CREATE, triggerKey,
                missingOwner, null, AutomationLevel.SAFE_AUTOMATIC, RiskLevel.LOW);
        SchemaChange drop = change("chg:drop-trigger", ChangeKind.DROP, triggerKey,
                null, missingOwner, AutomationLevel.DESTRUCTIVE_OPT_IN, RiskLevel.CRITICAL);

        assertSafeFailure("Schema change shape is unsupported", "secret_table",
                () -> renderer.render(create, context(DbType.POSTGRESQL, false)));
        assertSafeFailure("Schema change shape is unsupported", "secret_table",
                () -> renderer.render(drop, context(DbType.POSTGRESQL, true)));
    }

    private static SchemaChange change(
            String id, ChangeKind kind, ObjectKey key,
            com.datacube.spi.schemadiff.SchemaObject source,
            com.datacube.spi.schemadiff.SchemaObject target,
            AutomationLevel automation, RiskLevel risk) {
        return new SchemaChange(id, kind, key, source, target, null, risk, automation,
                automation == AutomationLevel.SAFE_AUTOMATIC, Set.of(), "safe");
    }

    private static ObjectKey key(ObjectType type, String schema, String name, String signature) {
        return new ObjectKey(type, PgSchemaIdentifierNormalizer.object(schema, name), signature);
    }

    private static ColumnDefinition column(
            String name, String formattedType, boolean nullable,
            String normalizedDefault, int ordinal) {
        TreeMap<String, String> extensions = new TreeMap<>();
        extensions.put("formattedType", formattedType);
        return new ColumnDefinition(PgSchemaIdentifierNormalizer.child(name),
                new CanonicalDataType("ignored", null, null, null, false, 0, extensions),
                nullable, normalizedDefault, ordinal, null);
    }

    private static TableDefinition table(ObjectKey key, List<ColumnDefinition> columns) {
        return new TableDefinition(key, columns, List.of(), List.of(), Set.of());
    }

    private static SchemaChange tablePropertyChange(
            String id, ObjectKey key, TableDefinition source, TableDefinition target,
            String path, Object sourceValue, Object targetValue) {
        return new SchemaChange(id, ChangeKind.ALTER, key, source, target,
                new PropertyDifference(path, sourceValue, targetValue, "safe"),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, false,
                Set.of("chg:before"), "safe");
    }

    private String renderColumnProperty(
            ObjectKey key, ColumnDefinition oldColumn, ColumnDefinition newColumn,
            String path, Object sourceValue, Object targetValue) {
        SchemaChange change = tablePropertyChange("chg:column", key,
                table(key, List.of(newColumn)), table(key, List.of(oldColumn)),
                path, sourceValue, targetValue);
        List<RenderedStatement> statements = renderer.render(change,
                context(DbType.POSTGRESQL, true));
        assertEquals(1, statements.size());
        return statements.getFirst().sql();
    }

    private static ColumnDefinition withComment(ColumnDefinition column, String comment) {
        return new ColumnDefinition(column.name(), column.dataType(), column.nullable(),
                column.normalizedDefault(), column.ordinal(), comment);
    }

    private static DefinitionObject definition(ObjectKey key, String definition) {
        return new DefinitionObject(key, definition, definition, Set.of(), DefinitionConfidence.HIGH);
    }

    private String renderSequenceProperty(
            SequenceDefinition source, SequenceDefinition target,
            String path, Object sourceValue, Object targetValue) {
        SchemaChange change = new SchemaChange("chg:sequence", ChangeKind.ALTER, source.key(),
                source, target, new PropertyDifference(path, sourceValue, targetValue, "safe"),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, false, Set.of(), "safe");
        return renderer.render(change, context(DbType.POSTGRESQL, true)).getFirst().sql();
    }

    private static void assertSafeFailure(
            String expectedMessage, String secret, org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, executable);
        assertEquals(expectedMessage, failure.getMessage());
        assertFalse(failure.getMessage().contains(secret));
    }

    private record DefinitionCase(
            ObjectType type, String name, String signature, String definition,
            String expected, Set<ObjectKey> dependencies) {
    }

    private record DropCase(
            ObjectType type, String name, String signature,
            Set<ObjectKey> dependencies, String expected) {
    }

    private static ConstraintDefinition constraint(
            ObjectType type, String name, ConstraintKind kind, List<com.datacube.spi.schemadiff.QualifiedName> columns,
            ObjectKey referencedTable, List<com.datacube.spi.schemadiff.QualifiedName> referencedColumns,
            String expression, boolean generated, Set<ObjectKey> dependencies) {
        return new ConstraintDefinition(key(type, "Source", name, ""), kind, columns,
                referencedTable, referencedColumns, expression, null, null, generated, dependencies);
    }

    private static IndexDefinition index(
            String name, boolean unique, List<String> expressions,
            String predicate, boolean generated) {
        return new IndexDefinition(key(ObjectType.INDEX, "Source", name, ""), unique,
                expressions, predicate, generated, Set.of());
    }

    private static RenderContext context(DbType databaseType, boolean destructiveApproved) {
        return new RenderContext(databaseType,
                PgSchemaIdentifierNormalizer.schema("Source"),
                PgSchemaIdentifierNormalizer.schema("Target"), destructiveApproved);
    }

    private static void assertStatementMetadata(
            RenderedStatement statement, SchemaChange change,
            boolean destructive, String warning) {
        assertEquals(change.id(), statement.changeId());
        assertEquals(destructive, statement.destructive());
        assertEquals(change.dependencyChangeIds(), statement.dependencyIds());
        assertEquals(warning, statement.warning());
    }

    private static void assertExactlyOneTrailingSemicolon(String sql) {
        assertTrue(sql.endsWith(";"));
        assertFalse(sql.endsWith(";;"));
        assertFalse(sql.substring(0, sql.length() - 1).stripTrailing().endsWith(";"));
    }
}
