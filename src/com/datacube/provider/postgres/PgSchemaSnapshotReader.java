package com.datacube.provider.postgres;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SchemaSnapshotReader;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.SnapshotFingerprint;
import com.datacube.spi.schemadiff.TableDefinition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reads one PostgreSQL schema into the provider-neutral schema-diff model. */
public final class PgSchemaSnapshotReader implements SchemaSnapshotReader {
    private static final String TABLES_SQL = """
            /* snapshot:tables */
            SELECT c.oid::bigint AS object_oid, c.relname AS object_name
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
            ORDER BY c.relname, c.oid
            """;

    private static final String COLUMNS_SQL = """
            /* snapshot:columns */
            SELECT c.oid::bigint AS table_oid, a.attname AS column_name,
                   a.attnum AS ordinal_position,
                   COALESCE(et.typname, t.typname) AS base_type,
                   CASE WHEN COALESCE(et.typname, t.typname) IN ('varchar', 'bpchar') AND a.atttypmod > 4
                        THEN (a.atttypmod - 4)::bigint END AS character_length,
                   CASE WHEN COALESCE(et.typname, t.typname) = 'numeric' AND a.atttypmod >= 4
                        THEN ((a.atttypmod - 4) >> 16) & 65535 END AS numeric_precision,
                   CASE WHEN COALESCE(et.typname, t.typname) = 'numeric' AND a.atttypmod >= 4
                        THEN CASE WHEN ((a.atttypmod - 4) & 65535) >= 32768
                                  THEN ((a.atttypmod - 4) & 65535) - 65536
                                  ELSE ((a.atttypmod - 4) & 65535) END END AS numeric_scale,
                   COALESCE(et.typname, t.typname) IN ('timestamptz', 'timetz') AS with_time_zone,
                   CASE WHEN t.typelem <> 0 THEN GREATEST(a.attndims, 1) ELSE 0 END AS array_dimensions,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS formatted_type,
                   COALESCE(etn.nspname, tn.nspname) AS type_schema,
                   NOT a.attnotnull AS nullable,
                   pg_catalog.pg_get_expr(ad.adbin, ad.adrelid, true) AS default_expression,
                   pg_catalog.col_description(a.attrelid, a.attnum) AS comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
            JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
            LEFT JOIN pg_catalog.pg_type et ON et.oid = t.typelem AND t.typelem <> 0
            LEFT JOIN pg_catalog.pg_namespace etn ON etn.oid = et.typnamespace
            LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """;

    private static final String CONSTRAINTS_SQL = """
            /* snapshot:constraints */
            SELECT con.oid::bigint AS constraint_oid, con.conrelid::bigint AS table_oid,
                   con.conname AS constraint_name, con.contype::text AS constraint_type,
                   positions.position, source_column.attname AS column_name,
                   NULLIF(con.confrelid, 0)::bigint AS referenced_table_oid,
                   target_column.attname AS referenced_column_name,
                   pg_catalog.pg_get_expr(con.conbin, con.conrelid, true) AS check_expression,
                   CASE con.confupdtype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
                        WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END AS update_action,
                   CASE con.confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
                        WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END AS delete_action,
                   false AS provider_generated
            FROM pg_catalog.pg_constraint con
            JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN LATERAL pg_catalog.generate_subscripts(con.conkey, 1) positions(position) ON true
            LEFT JOIN pg_catalog.pg_attribute source_column
                   ON source_column.attrelid = con.conrelid
                  AND source_column.attnum = con.conkey[positions.position]
            LEFT JOIN pg_catalog.pg_attribute target_column
                   ON target_column.attrelid = con.confrelid
                  AND target_column.attnum = con.confkey[positions.position]
            WHERE n.nspname = ? AND con.contype IN ('p', 'u', 'f', 'c')
            ORDER BY c.relname, con.conname, positions.position
            """;

    private static final String INDEXES_SQL = """
            /* snapshot:indexes */
            SELECT index_class.oid::bigint AS index_oid, table_class.oid::bigint AS table_oid,
                   index_class.relname AS index_name, index_meta.indisunique AS is_unique,
                   positions.position,
                   pg_catalog.pg_get_indexdef(index_class.oid, positions.position, true) AS index_expression,
                   pg_catalog.pg_get_expr(index_meta.indpred, index_meta.indrelid, true) AS predicate,
                   false AS provider_generated
            FROM pg_catalog.pg_index index_meta
            JOIN pg_catalog.pg_class index_class ON index_class.oid = index_meta.indexrelid
            JOIN pg_catalog.pg_class table_class ON table_class.oid = index_meta.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = table_class.relnamespace
            JOIN LATERAL pg_catalog.generate_series(1, index_meta.indnkeyatts) positions(position) ON true
            WHERE n.nspname = ? AND table_class.relkind IN ('r', 'p')
            ORDER BY table_class.relname, index_class.relname, positions.position
            """;

    private static final String SEQUENCES_SQL = """
            /* snapshot:sequences */
            SELECT c.oid::bigint AS object_oid, c.relname AS object_name,
                   s.seqstart::text AS start_value, s.seqincrement::text AS increment_by,
                   s.seqmin::text AS minimum_value, s.seqmax::text AS maximum_value,
                   s.seqcycle AS cycle, s.seqcache::integer AS cache_size
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_catalog.pg_sequence s ON s.seqrelid = c.oid
            WHERE n.nspname = ? AND c.relkind = 'S'
            ORDER BY c.relname, c.oid
            """;

    private static final String VIEWS_SQL = """
            /* snapshot:views */
            SELECT c.oid::bigint AS object_oid, c.relname AS object_name,
                   c.relkind::text AS relation_kind,
                   pg_catalog.pg_get_viewdef(c.oid, true) AS definition
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('v', 'm')
            ORDER BY c.relname, c.oid
            """;

    private static final String ROUTINES_SQL = """
            /* snapshot:routines */
            SELECT p.oid::bigint AS object_oid, p.proname AS object_name,
                   p.prokind::text AS routine_kind,
                   pg_catalog.pg_get_function_identity_arguments(p.oid) AS identity_arguments,
                   pg_catalog.pg_get_functiondef(p.oid) AS definition
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.prokind IN ('f', 'p')
            ORDER BY p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid), p.oid
            """;

    private static final String TRIGGERS_SQL = """
            /* snapshot:triggers */
            SELECT trigger.oid::bigint AS object_oid, trigger.tgname AS object_name,
                   relation.oid::bigint AS table_oid, relation.relname AS relation_name,
                   pg_catalog.pg_get_triggerdef(trigger.oid, true) AS definition
            FROM pg_catalog.pg_trigger trigger
            JOIN pg_catalog.pg_class relation ON relation.oid = trigger.tgrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = relation.relnamespace
            WHERE n.nspname = ? AND NOT trigger.tgisinternal
            ORDER BY relation.relname, trigger.tgname, trigger.oid
            """;

    private static final String ENUMS_SQL = """
            /* snapshot:enums */
            SELECT type.oid::bigint AS type_oid, type.typarray::bigint AS array_oid,
                   type.typname AS type_name,
                   enum.enumsortorder AS sort_order, enum.enumlabel AS enum_label
            FROM pg_catalog.pg_type type
            JOIN pg_catalog.pg_namespace n ON n.oid = type.typnamespace
            JOIN pg_catalog.pg_enum enum ON enum.enumtypid = type.oid
            WHERE n.nspname = ? AND type.typtype = 'e'
            ORDER BY type.typname, enum.enumsortorder
            """;

    private static final String COMPOSITES_SQL = """
            /* snapshot:composites */
            SELECT type.oid::bigint AS type_oid, type.typarray::bigint AS array_oid,
                   relation.oid::bigint AS relation_oid, type.typname AS type_name,
                   attribute.attnum AS position, attribute.attname AS attribute_name,
                   pg_catalog.format_type(attribute.atttypid, attribute.atttypmod) AS attribute_type
            FROM pg_catalog.pg_type type
            JOIN pg_catalog.pg_namespace n ON n.oid = type.typnamespace
            JOIN pg_catalog.pg_class relation ON relation.oid = type.typrelid
            JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = relation.oid
            WHERE n.nspname = ? AND type.typtype = 'c' AND relation.relkind = 'c'
              AND attribute.attnum > 0 AND NOT attribute.attisdropped
            ORDER BY type.typname, attribute.attnum
            """;

    private static final String DOMAINS_SQL = """
            /* snapshot:domains */
            SELECT type.oid::bigint AS type_oid, type.typarray::bigint AS array_oid,
                   type.typname AS type_name,
                   pg_catalog.format_type(type.typbasetype, type.typtypmod) AS base_type,
                   type.typnotnull AS not_null,
                   COALESCE(pg_catalog.pg_get_expr(type.typdefaultbin, 0, true), type.typdefault) AS default_expression,
                   constraint.oid::bigint AS constraint_oid, constraint.conname AS constraint_name,
                   pg_catalog.pg_get_constraintdef(constraint.oid, true) AS constraint_definition
            FROM pg_catalog.pg_type type
            JOIN pg_catalog.pg_namespace n ON n.oid = type.typnamespace
            LEFT JOIN pg_catalog.pg_constraint constraint
                   ON constraint.contypid = type.oid AND constraint.contype = 'c'
            WHERE n.nspname = ? AND type.typtype = 'd'
            ORDER BY type.typname, constraint.conname, constraint.oid
            """;

    private static final String DEPENDENCIES_SQL = """
            /* snapshot:dependencies */
            WITH entities AS (
                SELECT 'pg_class'::text AS catalog_name, c.oid::bigint AS object_oid,
                       n.nspname AS schema_name
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                UNION ALL
                SELECT 'pg_proc', p.oid::bigint, n.nspname
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                UNION ALL
                SELECT 'pg_type', type.oid::bigint, n.nspname
                FROM pg_catalog.pg_type type
                JOIN pg_catalog.pg_namespace n ON n.oid = type.typnamespace
                UNION ALL
                SELECT 'pg_trigger', trigger.oid::bigint, n.nspname
                FROM pg_catalog.pg_trigger trigger
                JOIN pg_catalog.pg_class c ON c.oid = trigger.tgrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                UNION ALL
                SELECT 'pg_constraint', constraint.oid::bigint,
                       COALESCE(relation_namespace.nspname, type_namespace.nspname)
                FROM pg_catalog.pg_constraint constraint
                LEFT JOIN pg_catalog.pg_class relation ON relation.oid = constraint.conrelid
                LEFT JOIN pg_catalog.pg_namespace relation_namespace
                          ON relation_namespace.oid = relation.relnamespace
                LEFT JOIN pg_catalog.pg_type type ON type.oid = constraint.contypid
                LEFT JOIN pg_catalog.pg_namespace type_namespace
                          ON type_namespace.oid = type.typnamespace
                WHERE COALESCE(relation_namespace.nspname, type_namespace.nspname) IS NOT NULL
            ), direct_dependencies AS (
                SELECT CASE dependency.classid
                           WHEN 'pg_catalog.pg_class'::pg_catalog.regclass THEN 'pg_class'
                           WHEN 'pg_catalog.pg_proc'::pg_catalog.regclass THEN 'pg_proc'
                           WHEN 'pg_catalog.pg_type'::pg_catalog.regclass THEN 'pg_type'
                           WHEN 'pg_catalog.pg_trigger'::pg_catalog.regclass THEN 'pg_trigger'
                           WHEN 'pg_catalog.pg_constraint'::pg_catalog.regclass THEN 'pg_constraint'
                       END AS source_catalog,
                       dependency.objid::bigint AS source_oid,
                       CASE dependency.refclassid
                           WHEN 'pg_catalog.pg_class'::pg_catalog.regclass THEN 'pg_class'
                           WHEN 'pg_catalog.pg_proc'::pg_catalog.regclass THEN 'pg_proc'
                           WHEN 'pg_catalog.pg_type'::pg_catalog.regclass THEN 'pg_type'
                           WHEN 'pg_catalog.pg_trigger'::pg_catalog.regclass THEN 'pg_trigger'
                           WHEN 'pg_catalog.pg_constraint'::pg_catalog.regclass THEN 'pg_constraint'
                       END AS target_catalog,
                       dependency.refobjid::bigint AS target_oid
                FROM pg_catalog.pg_depend dependency
            ), rewrite_dependencies AS (
                SELECT 'pg_class'::text AS source_catalog, rewrite.ev_class::bigint AS source_oid,
                       CASE dependency.refclassid
                           WHEN 'pg_catalog.pg_class'::pg_catalog.regclass THEN 'pg_class'
                           WHEN 'pg_catalog.pg_proc'::pg_catalog.regclass THEN 'pg_proc'
                           WHEN 'pg_catalog.pg_type'::pg_catalog.regclass THEN 'pg_type'
                       END AS target_catalog,
                       dependency.refobjid::bigint AS target_oid
                FROM pg_catalog.pg_depend dependency
                JOIN pg_catalog.pg_rewrite rewrite ON rewrite.oid = dependency.objid
                WHERE dependency.classid = 'pg_catalog.pg_rewrite'::pg_catalog.regclass
            ), all_dependencies AS (
                SELECT * FROM direct_dependencies WHERE source_catalog IS NOT NULL AND target_catalog IS NOT NULL
                UNION ALL
                SELECT * FROM rewrite_dependencies WHERE target_catalog IS NOT NULL
            )
            SELECT dependency.source_catalog, dependency.source_oid,
                   source.schema_name AS source_schema,
                   dependency.target_catalog, dependency.target_oid,
                   target.schema_name AS target_schema
            FROM all_dependencies dependency
            JOIN entities source ON source.catalog_name = dependency.source_catalog
                                AND source.object_oid = dependency.source_oid
            JOIN entities target ON target.catalog_name = dependency.target_catalog
                                AND target.object_oid = dependency.target_oid
            WHERE source.schema_name = ?
              AND NOT (dependency.source_catalog = dependency.target_catalog
                       AND dependency.source_oid = dependency.target_oid)
            ORDER BY dependency.source_catalog, dependency.source_oid,
                     dependency.target_catalog, dependency.target_oid
            """;

    private final Connection connection;

    public PgSchemaSnapshotReader(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public SchemaSnapshot read(String connectionId, QualifiedName schema,
                               SqlExecutionOptions options) throws SQLException {
        synchronized (connection) {
            return readSerially(connectionId, schema, options);
        }
    }

    private SchemaSnapshot readSerially(String connectionId, QualifiedName schema,
                                        SqlExecutionOptions options) throws SQLException {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(options, "options");
        String catalogSchema = catalogSchema(schema);
        QualifiedName snapshotSchema = PgSchemaIdentifierNormalizer.schema(catalogSchema);
        ReadState state = new ReadState(catalogSchema);

        attempt(state, options, Set.of(ObjectType.TABLE),
                () -> query(TABLES_SQL, catalogSchema, options, rows -> readTables(rows, state)));
        attempt(state, options, Set.of(ObjectType.TABLE),
                () -> query(COLUMNS_SQL, catalogSchema, options, rows -> readColumns(rows, state)));
        attempt(state, options, Set.of(ObjectType.PRIMARY_KEY, ObjectType.UNIQUE_CONSTRAINT,
                        ObjectType.FOREIGN_KEY, ObjectType.CHECK_CONSTRAINT),
                () -> query(CONSTRAINTS_SQL, catalogSchema, options, rows -> readConstraints(rows, state)));
        attempt(state, options, Set.of(ObjectType.INDEX),
                () -> query(INDEXES_SQL, catalogSchema, options, rows -> readIndexes(rows, state)));
        attempt(state, options, Set.of(ObjectType.SEQUENCE),
                () -> query(SEQUENCES_SQL, catalogSchema, options, rows -> readSequences(rows, state)));
        attempt(state, options, Set.of(ObjectType.VIEW, ObjectType.MATERIALIZED_VIEW),
                () -> query(VIEWS_SQL, catalogSchema, options, rows -> readViews(rows, state)));
        attempt(state, options, Set.of(ObjectType.FUNCTION, ObjectType.PROCEDURE),
                () -> query(ROUTINES_SQL, catalogSchema, options, rows -> readRoutines(rows, state)));
        attempt(state, options, Set.of(ObjectType.TRIGGER),
                () -> query(TRIGGERS_SQL, catalogSchema, options, rows -> readTriggers(rows, state)));
        attempt(state, options, Set.of(ObjectType.TYPE),
                () -> query(ENUMS_SQL, catalogSchema, options, rows -> readEnums(rows, state)));
        attempt(state, options, Set.of(ObjectType.TYPE),
                () -> query(COMPOSITES_SQL, catalogSchema, options, rows -> readComposites(rows, state)));
        attempt(state, options, Set.of(ObjectType.TYPE),
                () -> query(DOMAINS_SQL, catalogSchema, options, rows -> readDomains(rows, state)));
        attempt(state, options, state.knownTypes(),
                () -> query(DEPENDENCIES_SQL, catalogSchema, options, rows -> readDependencies(rows, state)));
        if (options.control().cancellationRequested()) {
            throw new SQLException("Snapshot metadata cancelled", "57014");
        }

        SortedMap<ObjectKey, SchemaObject> objects = state.materialize();
        SnapshotCompleteness completeness = new SnapshotCompleteness(
                state.diagnostics.isEmpty(), state.diagnostics);
        String fingerprint = SnapshotFingerprint.compute(
                DbType.POSTGRESQL, snapshotSchema, completeness, objects);
        return new SchemaSnapshot(DbType.POSTGRESQL, connectionId, snapshotSchema, Instant.now(),
                completeness, objects, fingerprint);
    }

    private static void attempt(ReadState state, SqlExecutionOptions options,
                                Set<ObjectType> scopes, SqlAttempt attempt) throws SQLException {
        try {
            attempt.run();
        } catch (SQLException failure) {
            if (failure instanceof SQLTimeoutException) {
                throw new SQLTimeoutException("Snapshot metadata timed out",
                        failure.getSQLState(), failure.getErrorCode());
            }
            if ("57014".equals(failure.getSQLState()) || options.control().cancellationRequested()) {
                throw new SQLException("Snapshot metadata cancelled", "57014", failure.getErrorCode());
            }
            String diagnostic = "42501".equals(failure.getSQLState())
                    ? SnapshotCompleteness.PERMISSION_DENIED
                    : SnapshotCompleteness.METADATA_UNAVAILABLE;
            scopes.forEach(scope -> state.diagnostic(scope, diagnostic));
        }
    }

    private void query(String sql, String schema, SqlExecutionOptions options,
                       RowReader reader) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            SqlExecutionControl.Activation activation = options.control()
                    .activate(statement, options.queryTimeoutSeconds());
            try {
                statement.setString(1, schema);
                options.control().ensureNotCancelled(activation);
                try (ResultSet rows = statement.executeQuery()) {
                    reader.read(rows);
                }
            } finally {
                options.control().release(activation);
            }
        }
    }

    private static void readTables(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            long oid = rows.getLong("object_oid");
            String name = rows.getString("object_name");
            ObjectKey key = state.key(ObjectType.TABLE, name, "");
            state.tables.put(oid, new TableBuilder(key));
            state.mapOid("pg_class", oid, key);
        }
    }

    private static void readColumns(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            TableBuilder table = state.tables.get(rows.getLong("table_oid"));
            if (table == null) continue;
            SortedMap<String, String> extensions = new TreeMap<>();
            putIfPresent(extensions, "formattedType", rows.getString("formatted_type"));
            putIfPresent(extensions, "typeSchema", rows.getString("type_schema"));
            CanonicalDataType type = new CanonicalDataType(
                    rows.getString("base_type"),
                    nullableLong(rows, "character_length"),
                    nullableInteger(rows, "numeric_precision"),
                    nullableInteger(rows, "numeric_scale"),
                    rows.getBoolean("with_time_zone"),
                    rows.getInt("array_dimensions"),
                    extensions);
            table.columns.add(new ColumnDefinition(
                    PgSchemaIdentifierNormalizer.child(rows.getString("column_name")), type,
                    rows.getBoolean("nullable"),
                    PgSchemaDefinitionNormalizer.normalize(rows.getString("default_expression")),
                    rows.getInt("ordinal_position"), rows.getString("comment")));
        }
    }

    private static void readConstraints(ResultSet rows, ReadState state) throws SQLException {
        Map<Long, ConstraintBuilder> constraints = new LinkedHashMap<>();
        while (rows.next()) {
            long tableOid = rows.getLong("table_oid");
            TableBuilder table = state.tables.get(tableOid);
            if (table == null) continue;
            long constraintOid = rows.getLong("constraint_oid");
            ConstraintBuilder constraint = constraints.get(constraintOid);
            if (constraint == null) {
                ConstraintKind kind = constraintKind(rows.getString("constraint_type"));
                ObjectKey key = state.key(objectType(kind), rows.getString("constraint_name"),
                        table.key.name().comparisonKey());
                constraint = new ConstraintBuilder(key, kind,
                        PgSchemaDefinitionNormalizer.normalize(rows.getString("check_expression")),
                        rows.getString("update_action"), rows.getString("delete_action"),
                        rows.getBoolean("provider_generated"));
                constraints.put(constraintOid, constraint);
                table.constraints.add(constraint);
                state.mapOid("pg_constraint", constraintOid, key);
            }
            Integer position = nullableInteger(rows, "position");
            String column = rows.getString("column_name");
            if (position != null && column != null) {
                constraint.columns.add(new PositionedName(position,
                        PgSchemaIdentifierNormalizer.child(column)));
            }
            Long referencedTableOid = nullableLong(rows, "referenced_table_oid");
            if (referencedTableOid != null) {
                constraint.referencedTable = state.oidKey("pg_class", referencedTableOid);
                if (constraint.referencedTable != null) {
                    constraint.dependencies.add(constraint.referencedTable);
                    if (!table.key.equals(constraint.referencedTable)) {
                        table.dependencies.add(constraint.referencedTable);
                    }
                }
            }
            String referencedColumn = rows.getString("referenced_column_name");
            if (position != null && referencedColumn != null) {
                constraint.referencedColumns.add(new PositionedName(position,
                        PgSchemaIdentifierNormalizer.child(referencedColumn)));
            }
        }
    }

    private static void readIndexes(ResultSet rows, ReadState state) throws SQLException {
        Map<Long, IndexBuilder> indexes = new LinkedHashMap<>();
        while (rows.next()) {
            TableBuilder table = state.tables.get(rows.getLong("table_oid"));
            if (table == null) continue;
            long indexOid = rows.getLong("index_oid");
            IndexBuilder index = indexes.get(indexOid);
            if (index == null) {
                ObjectKey key = state.key(ObjectType.INDEX, rows.getString("index_name"), "");
                index = new IndexBuilder(key, rows.getBoolean("is_unique"),
                        PgSchemaDefinitionNormalizer.normalize(rows.getString("predicate")),
                        rows.getBoolean("provider_generated"));
                indexes.put(indexOid, index);
                table.indexes.add(index);
                state.mapOid("pg_class", indexOid, key);
            }
            Integer position = nullableInteger(rows, "position");
            String expression = PgSchemaDefinitionNormalizer.normalize(rows.getString("index_expression"));
            if (position != null && expression != null) {
                index.expressions.add(new PositionedText(position, expression));
            }
        }
    }

    private static void readSequences(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            long oid = rows.getLong("object_oid");
            ObjectKey key = state.key(ObjectType.SEQUENCE, rows.getString("object_name"), "");
            SequenceDefinition sequence = new SequenceDefinition(key,
                    rows.getString("start_value"), rows.getString("increment_by"),
                    rows.getString("minimum_value"), rows.getString("maximum_value"),
                    rows.getBoolean("cycle"), nullableInteger(rows, "cache_size"), Set.of());
            state.sequences.put(key, sequence);
            state.mapOid("pg_class", oid, key);
        }
    }

    private static void readViews(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            long oid = rows.getLong("object_oid");
            String name = rows.getString("object_name");
            ObjectType type = "m".equals(rows.getString("relation_kind"))
                    ? ObjectType.MATERIALIZED_VIEW : ObjectType.VIEW;
            ObjectKey key = state.key(type, name, "");
            String body = rows.getString("definition");
            String original = body == null || body.isBlank() ? null : "CREATE "
                    + (type == ObjectType.MATERIALIZED_VIEW ? "MATERIALIZED VIEW " : "VIEW ")
                    + key.name().original() + " AS\n" + body;
            state.addDefinition(key, original, DefinitionConfidence.HIGH);
            state.mapOid("pg_class", oid, key);
        }
    }

    private static void readRoutines(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            long oid = rows.getLong("object_oid");
            ObjectType type = "p".equals(rows.getString("routine_kind"))
                    ? ObjectType.PROCEDURE : ObjectType.FUNCTION;
            String signature = rows.getString("identity_arguments");
            ObjectKey key = state.key(type, rows.getString("object_name"),
                    signature == null ? "" : signature);
            state.addDefinition(key, rows.getString("definition"), DefinitionConfidence.HIGH);
            state.mapOid("pg_proc", oid, key);
        }
    }

    private static void readTriggers(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            long oid = rows.getLong("object_oid");
            long tableOid = rows.getLong("table_oid");
            ObjectKey parent = state.oidKey("pg_class", tableOid);
            String signature = parent == null ? "" : parent.name().comparisonKey();
            ObjectKey key = state.key(ObjectType.TRIGGER, rows.getString("object_name"), signature);
            state.addDefinition(key, rows.getString("definition"), DefinitionConfidence.HIGH);
            state.mapOid("pg_trigger", oid, key);
            if (parent != null) state.addDependency(key, parent);
        }
    }

    private static void readEnums(ResultSet rows, ReadState state) throws SQLException {
        Map<Long, EnumBuilder> types = new LinkedHashMap<>();
        while (rows.next()) {
            long oid = rows.getLong("type_oid");
            EnumBuilder type = types.get(oid);
            if (type == null) {
                type = new EnumBuilder(state.key(ObjectType.TYPE, rows.getString("type_name"), "enum"),
                        rows.getLong("array_oid"));
                types.put(oid, type);
            }
            type.labels.add(rows.getString("enum_label"));
        }
        for (Map.Entry<Long, EnumBuilder> entry : types.entrySet()) {
            EnumBuilder type = entry.getValue();
            String definition = "CREATE TYPE " + type.key.name().original() + " AS ENUM (\n    "
                    + String.join(",\n    ", type.labels.stream().map(PgSchemaSnapshotReader::sqlString).toList())
                    + "\n);";
            state.addDefinition(type.key, definition, DefinitionConfidence.HIGH);
            state.mapOid("pg_type", entry.getKey(), type.key);
            if (type.arrayOid != 0) state.mapOid("pg_type", type.arrayOid, type.key);
        }
    }

    private static void readComposites(ResultSet rows, ReadState state) throws SQLException {
        Map<Long, CompositeBuilder> types = new LinkedHashMap<>();
        while (rows.next()) {
            long oid = rows.getLong("type_oid");
            CompositeBuilder type = types.get(oid);
            if (type == null) {
                type = new CompositeBuilder(state.key(ObjectType.TYPE,
                        rows.getString("type_name"), "composite"), rows.getLong("relation_oid"),
                        rows.getLong("array_oid"));
                types.put(oid, type);
            }
            type.attributes.add(new CompositeAttribute(rows.getInt("position"),
                    rows.getString("attribute_name"), rows.getString("attribute_type")));
        }
        for (Map.Entry<Long, CompositeBuilder> entry : types.entrySet()) {
            CompositeBuilder type = entry.getValue();
            type.attributes.sort(Comparator.comparingInt(CompositeAttribute::position));
            String definition = "CREATE TYPE " + type.key.name().original() + " AS (\n    "
                    + String.join(",\n    ", type.attributes.stream()
                    .map(attribute -> PgSchemaIdentifierNormalizer.quote(attribute.name())
                            + " " + attribute.type()).toList()) + "\n);";
            state.addDefinition(type.key, definition, DefinitionConfidence.HIGH);
            state.mapOid("pg_type", entry.getKey(), type.key);
            state.mapOid("pg_class", type.relationOid, type.key);
            if (type.arrayOid != 0) state.mapOid("pg_type", type.arrayOid, type.key);
        }
    }

    private static void readDomains(ResultSet rows, ReadState state) throws SQLException {
        Map<Long, DomainBuilder> types = new LinkedHashMap<>();
        while (rows.next()) {
            long oid = rows.getLong("type_oid");
            DomainBuilder type = types.get(oid);
            if (type == null) {
                type = new DomainBuilder(state.key(ObjectType.TYPE,
                        rows.getString("type_name"), "domain"),
                        rows.getString("base_type"), rows.getBoolean("not_null"),
                        rows.getString("default_expression"), rows.getLong("array_oid"));
                types.put(oid, type);
            }
            String constraintName = rows.getString("constraint_name");
            String constraintDefinition = rows.getString("constraint_definition");
            if (constraintName != null && constraintDefinition != null) {
                type.constraints.add(new DomainConstraint(rows.getLong("constraint_oid"),
                        constraintName, constraintDefinition));
            }
        }
        for (Map.Entry<Long, DomainBuilder> entry : types.entrySet()) {
            DomainBuilder type = entry.getValue();
            type.constraints.sort(Comparator.comparing(DomainConstraint::name));
            StringBuilder definition = new StringBuilder("CREATE DOMAIN ")
                    .append(type.key.name().original()).append(" AS ").append(type.baseType);
            if (type.defaultExpression != null) definition.append(" DEFAULT ").append(type.defaultExpression);
            if (type.notNull) definition.append(" NOT NULL");
            for (DomainConstraint constraint : type.constraints) {
                definition.append("\n    CONSTRAINT ")
                        .append(PgSchemaIdentifierNormalizer.quote(constraint.name()))
                        .append(' ').append(constraint.definition());
            }
            definition.append(';');
            state.addDefinition(type.key, definition.toString(), DefinitionConfidence.HIGH);
            state.mapOid("pg_type", entry.getKey(), type.key);
            if (type.arrayOid != 0) state.mapOid("pg_type", type.arrayOid, type.key);
            type.constraints.forEach(constraint -> state.mapOid(
                    "pg_constraint", constraint.oid(), type.key));
        }
    }

    private static void readDependencies(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            ObjectKey source = state.oidKey(rows.getString("source_catalog"),
                    rows.getLong("source_oid"));
            if (source == null || !state.schema.equals(rows.getString("source_schema"))) continue;
            if (!state.schema.equals(rows.getString("target_schema"))) continue;
            ObjectKey target = state.oidKey(rows.getString("target_catalog"),
                    rows.getLong("target_oid"));
            if (target == null) {
                state.diagnostic(source.type(), SnapshotCompleteness.DEPENDENCY_UNRESOLVED);
            } else if (!source.equals(target)) {
                state.addDependency(source, target);
            }
        }
    }

    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static ConstraintKind constraintKind(String pgType) throws SQLException {
        return switch (pgType) {
            case "p" -> ConstraintKind.PRIMARY_KEY;
            case "u" -> ConstraintKind.UNIQUE;
            case "f" -> ConstraintKind.FOREIGN_KEY;
            case "c" -> ConstraintKind.CHECK;
            default -> throw new SQLException("Unsupported PostgreSQL constraint type");
        };
    }

    private static ObjectType objectType(ConstraintKind kind) {
        return switch (kind) {
            case PRIMARY_KEY -> ObjectType.PRIMARY_KEY;
            case UNIQUE -> ObjectType.UNIQUE_CONSTRAINT;
            case FOREIGN_KEY -> ObjectType.FOREIGN_KEY;
            case CHECK -> ObjectType.CHECK_CONSTRAINT;
        };
    }

    private static Long nullableLong(ResultSet rows, String label) throws SQLException {
        Object value = rows.getObject(label);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInteger(ResultSet rows, String label) throws SQLException {
        Object value = rows.getObject(label);
        return value == null ? null : ((Number) value).intValue();
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    private static String catalogSchema(QualifiedName schema) {
        String prefix = "pg-schema-v1\0";
        if (schema.comparisonKey().startsWith(prefix)) {
            return schema.comparisonKey().substring(prefix.length());
        }
        String original = schema.original();
        if (original.length() >= 2 && original.charAt(0) == '"'
                && original.charAt(original.length() - 1) == '"') {
            return original.substring(1, original.length() - 1).replace("\"\"", "\"");
        }
        return original;
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet rows) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlAttempt {
        void run() throws SQLException;
    }

    private static final class ReadState {
        private final String schema;
        private final Map<Long, TableBuilder> tables = new LinkedHashMap<>();
        private final Map<CatalogOid, ObjectKey> oidKeys = new LinkedHashMap<>();
        private final SortedMap<ObjectKey, SequenceDefinition> sequences = new TreeMap<>();
        private final SortedMap<ObjectKey, DefinitionObject> definitions = new TreeMap<>();
        private final Map<ObjectKey, Set<ObjectKey>> discoveredDependencies = new LinkedHashMap<>();
        private final SortedMap<ObjectType, String> diagnostics = new TreeMap<>();

        private ReadState(String schema) {
            this.schema = schema;
        }

        private ObjectKey key(ObjectType type, String name, String signature) {
            return new ObjectKey(type, PgSchemaIdentifierNormalizer.object(schema, name), signature);
        }

        private void mapOid(String catalog, long oid, ObjectKey key) {
            oidKeys.put(new CatalogOid(catalog, oid), key);
        }

        private ObjectKey oidKey(String catalog, long oid) {
            return oidKeys.get(new CatalogOid(catalog, oid));
        }

        private void addDefinition(ObjectKey key, String original, DefinitionConfidence confidence) {
            DefinitionConfidence actualConfidence = original == null || original.isBlank()
                    ? DefinitionConfidence.LOW : confidence;
            if (actualConfidence == DefinitionConfidence.LOW) {
                diagnostic(key.type(), SnapshotCompleteness.DEFINITION_UNAVAILABLE);
            }
            definitions.put(key, new DefinitionObject(key,
                    PgSchemaDefinitionNormalizer.normalize(original), original, Set.of(), actualConfidence));
        }

        private void addDependency(ObjectKey source, ObjectKey target) {
            discoveredDependencies.computeIfAbsent(source, ignored -> new TreeSet<>()).add(target);
        }

        private Set<ObjectKey> dependencies(ObjectKey key) {
            return discoveredDependencies.getOrDefault(key, Set.of());
        }

        private Set<ObjectType> knownTypes() {
            Set<ObjectType> types = new TreeSet<>();
            oidKeys.values().forEach(key -> types.add(key.type()));
            return types;
        }

        private void diagnostic(ObjectType type, String code) {
            String current = diagnostics.get(type);
            if (current == null || diagnosticPriority(code) > diagnosticPriority(current)) {
                diagnostics.put(type, code);
            }
        }

        private static int diagnosticPriority(String code) {
            return switch (code) {
                case SnapshotCompleteness.PERMISSION_DENIED -> 4;
                case SnapshotCompleteness.METADATA_UNAVAILABLE -> 3;
                case SnapshotCompleteness.DEFINITION_UNAVAILABLE -> 2;
                case SnapshotCompleteness.DEPENDENCY_UNRESOLVED -> 1;
                default -> 0;
            };
        }

        private SortedMap<ObjectKey, SchemaObject> materialize() {
            SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>();
            for (TableBuilder builder : tables.values()) {
                builder.columns.sort(Comparator.comparingInt(ColumnDefinition::ordinal)
                        .thenComparing(ColumnDefinition::name));
                List<ConstraintDefinition> constraints = builder.constraints.stream()
                        .map(constraint -> constraint.build(this))
                        .sorted(Comparator.comparing(ConstraintDefinition::key)).toList();
                List<IndexDefinition> indexes = builder.indexes.stream()
                        .map(index -> index.build(this))
                        .sorted(Comparator.comparing(IndexDefinition::key)).toList();
                Set<ObjectKey> tableDependencies = new TreeSet<>(builder.dependencies);
                tableDependencies.addAll(dependencies(builder.key));
                TableDefinition table = new TableDefinition(builder.key, builder.columns, constraints,
                        indexes, tableDependencies);
                objects.put(table.key(), table);
            }
            for (SequenceDefinition sequence : sequences.values()) {
                objects.put(sequence.key(), new SequenceDefinition(sequence.key(), sequence.startValue(),
                        sequence.incrementBy(), sequence.minimumValue(), sequence.maximumValue(),
                        sequence.cycle(), sequence.cacheSize(), dependencies(sequence.key())));
            }
            for (DefinitionObject definition : definitions.values()) {
                objects.put(definition.key(), new DefinitionObject(definition.key(),
                        definition.normalizedDefinition(), definition.originalDefinition(),
                        dependencies(definition.key()), definition.confidence()));
            }
            return objects;
        }
    }

    private static final class TableBuilder {
        private final ObjectKey key;
        private final List<ColumnDefinition> columns = new ArrayList<>();
        private final List<ConstraintBuilder> constraints = new ArrayList<>();
        private final List<IndexBuilder> indexes = new ArrayList<>();
        private final Set<ObjectKey> dependencies = new TreeSet<>();

        private TableBuilder(ObjectKey key) {
            this.key = key;
        }
    }

    private static final class ConstraintBuilder {
        private final ObjectKey key;
        private final ConstraintKind kind;
        private final List<PositionedName> columns = new ArrayList<>();
        private final List<PositionedName> referencedColumns = new ArrayList<>();
        private final String expression;
        private final String updateAction;
        private final String deleteAction;
        private final boolean providerGenerated;
        private final Set<ObjectKey> dependencies = new TreeSet<>();
        private ObjectKey referencedTable;

        private ConstraintBuilder(ObjectKey key, ConstraintKind kind, String expression,
                                  String updateAction, String deleteAction, boolean providerGenerated) {
            this.key = key;
            this.kind = kind;
            this.expression = expression;
            this.updateAction = updateAction;
            this.deleteAction = deleteAction;
            this.providerGenerated = providerGenerated;
        }

        private ConstraintDefinition build(ReadState state) {
            Set<ObjectKey> allDependencies = new TreeSet<>(dependencies);
            allDependencies.addAll(state.dependencies(key));
            return new ConstraintDefinition(key, kind, names(columns), referencedTable,
                    names(referencedColumns), expression, updateAction, deleteAction,
                    providerGenerated, allDependencies);
        }
    }

    private static final class IndexBuilder {
        private final ObjectKey key;
        private final boolean unique;
        private final List<PositionedText> expressions = new ArrayList<>();
        private final String predicate;
        private final boolean providerGenerated;
        private final Set<ObjectKey> dependencies = new TreeSet<>();

        private IndexBuilder(ObjectKey key, boolean unique, String predicate, boolean providerGenerated) {
            this.key = key;
            this.unique = unique;
            this.predicate = predicate;
            this.providerGenerated = providerGenerated;
        }

        private IndexDefinition build(ReadState state) {
            expressions.sort(Comparator.comparingInt(PositionedText::position));
            Set<ObjectKey> allDependencies = new TreeSet<>(dependencies);
            allDependencies.addAll(state.dependencies(key));
            return new IndexDefinition(key, unique,
                    expressions.stream().map(PositionedText::text).toList(),
                    predicate, providerGenerated, allDependencies);
        }
    }

    private static List<QualifiedName> names(List<PositionedName> positionedNames) {
        return positionedNames.stream().sorted(Comparator.comparingInt(PositionedName::position))
                .map(PositionedName::name).toList();
    }

    private static final class EnumBuilder {
        private final ObjectKey key;
        private final long arrayOid;
        private final List<String> labels = new ArrayList<>();

        private EnumBuilder(ObjectKey key, long arrayOid) {
            this.key = key;
            this.arrayOid = arrayOid;
        }
    }

    private static final class CompositeBuilder {
        private final ObjectKey key;
        private final long relationOid;
        private final long arrayOid;
        private final List<CompositeAttribute> attributes = new ArrayList<>();

        private CompositeBuilder(ObjectKey key, long relationOid, long arrayOid) {
            this.key = key;
            this.relationOid = relationOid;
            this.arrayOid = arrayOid;
        }
    }

    private static final class DomainBuilder {
        private final ObjectKey key;
        private final String baseType;
        private final boolean notNull;
        private final String defaultExpression;
        private final long arrayOid;
        private final List<DomainConstraint> constraints = new ArrayList<>();

        private DomainBuilder(ObjectKey key, String baseType, boolean notNull,
                              String defaultExpression, long arrayOid) {
            this.key = key;
            this.baseType = baseType;
            this.notNull = notNull;
            this.defaultExpression = defaultExpression;
            this.arrayOid = arrayOid;
        }
    }

    private record CatalogOid(String catalog, long oid) {
    }

    private record CompositeAttribute(int position, String name, String type) {
    }

    private record DomainConstraint(long oid, String name, String definition) {
    }

    private record PositionedName(int position, QualifiedName name) {
    }

    private record PositionedText(int position, String text) {
    }
}
