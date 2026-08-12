package com.datacube.provider.oracle;

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
import java.sql.SQLFeatureNotSupportedException;
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

/** Reads one Oracle owner into the provider-neutral immutable schema snapshot model. */
public final class OracleSchemaSnapshotReader implements SchemaSnapshotReader {
    private static final String TABLES_SQL = """
            /* snapshot:tables */
            SELECT t.TABLE_NAME
            FROM ALL_TABLES t
            WHERE t.OWNER = ?
              AND t.NESTED = 'NO'
              AND t.SECONDARY = 'N'
              AND (t.IOT_TYPE IS NULL OR t.IOT_TYPE = 'IOT')
            ORDER BY t.TABLE_NAME
            """;

    private static final String COLUMNS_SQL = """
            /* snapshot:columns */
            SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_ID, c.DATA_TYPE,
                   c.DATA_LENGTH, c.CHAR_LENGTH, c.CHAR_USED,
                   c.DATA_PRECISION, c.DATA_SCALE, c.DATA_TYPE_OWNER, c.DATA_TYPE_MOD,
                   c.NULLABLE, c.IDENTITY_COLUMN, identity.GENERATION_TYPE,
                   c.DEFAULT_ON_NULL, c.VIRTUAL_COLUMN, c.HIDDEN_COLUMN,
                   c.USER_GENERATED, identity.IDENTITY_OPTIONS, comments.COMMENTS,
                   c.DATA_DEFAULT
            FROM ALL_TAB_COLS c
            LEFT JOIN ALL_COL_COMMENTS comments
              ON comments.OWNER = c.OWNER
             AND comments.TABLE_NAME = c.TABLE_NAME
             AND comments.COLUMN_NAME = c.COLUMN_NAME
            LEFT JOIN ALL_TAB_IDENTITY_COLS identity
              ON identity.OWNER = c.OWNER
             AND identity.TABLE_NAME = c.TABLE_NAME
             AND identity.COLUMN_NAME = c.COLUMN_NAME
            WHERE c.OWNER = ?
              AND (c.HIDDEN_COLUMN = 'NO' OR c.USER_GENERATED = 'YES')
            ORDER BY c.TABLE_NAME, c.COLUMN_ID
            """;

    private static final String CONSTRAINTS_SQL = """
            /* snapshot:constraints */
            SELECT c.TABLE_NAME, c.CONSTRAINT_NAME, c.CONSTRAINT_TYPE,
                   columns.POSITION, columns.COLUMN_NAME,
                   referenced.OWNER AS REFERENCED_OWNER,
                   referenced.TABLE_NAME AS REFERENCED_TABLE_NAME,
                   referenced_columns.COLUMN_NAME AS REFERENCED_COLUMN_NAME,
                   c.DELETE_RULE, c.GENERATED, c.SEARCH_CONDITION
            FROM ALL_CONSTRAINTS c
            LEFT JOIN ALL_CONS_COLUMNS columns
              ON columns.OWNER = c.OWNER
             AND columns.CONSTRAINT_NAME = c.CONSTRAINT_NAME
             AND columns.TABLE_NAME = c.TABLE_NAME
            LEFT JOIN ALL_CONSTRAINTS referenced
              ON referenced.OWNER = c.R_OWNER
             AND referenced.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME
            LEFT JOIN ALL_CONS_COLUMNS referenced_columns
              ON referenced_columns.OWNER = referenced.OWNER
             AND referenced_columns.CONSTRAINT_NAME = referenced.CONSTRAINT_NAME
             AND referenced_columns.TABLE_NAME = referenced.TABLE_NAME
             AND referenced_columns.POSITION = columns.POSITION
            WHERE c.OWNER = ? AND c.CONSTRAINT_TYPE IN ('P', 'U', 'R', 'C')
            ORDER BY c.TABLE_NAME, c.CONSTRAINT_NAME, columns.POSITION
            """;

    private static final String INDEXES_SQL = """
            /* snapshot:indexes */
            SELECT indexes.TABLE_NAME, indexes.INDEX_NAME, indexes.INDEX_TYPE,
                   indexes.UNIQUENESS, columns.COLUMN_POSITION, columns.COLUMN_NAME,
                   constraints.CONSTRAINT_NAME, columns.DESCEND, expressions.COLUMN_EXPRESSION
            FROM ALL_INDEXES indexes
            JOIN ALL_IND_COLUMNS columns
              ON columns.INDEX_OWNER = indexes.OWNER
             AND columns.INDEX_NAME = indexes.INDEX_NAME
            LEFT JOIN ALL_IND_EXPRESSIONS expressions
              ON expressions.INDEX_OWNER = columns.INDEX_OWNER
             AND expressions.INDEX_NAME = columns.INDEX_NAME
             AND expressions.COLUMN_POSITION = columns.COLUMN_POSITION
            LEFT JOIN ALL_CONSTRAINTS constraints
              ON constraints.INDEX_OWNER = indexes.OWNER
             AND constraints.INDEX_NAME = indexes.INDEX_NAME
             AND constraints.OWNER = indexes.TABLE_OWNER
             AND constraints.TABLE_NAME = indexes.TABLE_NAME
            WHERE indexes.TABLE_OWNER = ?
            ORDER BY indexes.TABLE_NAME, indexes.INDEX_NAME, columns.COLUMN_POSITION
            """;

    private static final String SEQUENCES_SQL = """
            /* snapshot:sequences */
            SELECT sequence.SEQUENCE_NAME, sequence.MIN_VALUE, sequence.MAX_VALUE,
                   sequence.INCREMENT_BY, sequence.CYCLE_FLAG, sequence.CACHE_SIZE,
                   sequence.ORDER_FLAG
            FROM ALL_SEQUENCES sequence
            WHERE sequence.SEQUENCE_OWNER = ?
            ORDER BY sequence.SEQUENCE_NAME
            """;

    private static final String DEFINITIONS_SQL = """
            /* snapshot:definitions */
            WITH requested_owner AS (SELECT ? AS OWNER FROM DUAL)
            SELECT objects.OBJECT_NAME, objects.OBJECT_TYPE, objects.OBJECT_ID,
                   0 AS SUBPROGRAM_ID,
                   triggers.TABLE_OWNER AS BASE_OBJECT_OWNER,
                   triggers.BASE_OBJECT_TYPE,
                   triggers.TABLE_NAME AS BASE_OBJECT_NAME
            FROM ALL_OBJECTS objects
            JOIN requested_owner requested ON requested.OWNER = objects.OWNER
            LEFT JOIN ALL_TRIGGERS triggers
              ON triggers.OWNER = objects.OWNER
             AND triggers.TRIGGER_NAME = objects.OBJECT_NAME
             AND objects.OBJECT_TYPE = 'TRIGGER'
            WHERE objects.OBJECT_TYPE IN (
                  'VIEW', 'MATERIALIZED VIEW', 'TRIGGER',
                  'PACKAGE', 'PACKAGE BODY', 'TYPE', 'TYPE BODY')
            UNION ALL
            SELECT procedures.OBJECT_NAME, procedures.OBJECT_TYPE, procedures.OBJECT_ID,
                   procedures.SUBPROGRAM_ID,
                   CAST(NULL AS VARCHAR2(128)) AS BASE_OBJECT_OWNER,
                   CAST(NULL AS VARCHAR2(30)) AS BASE_OBJECT_TYPE,
                   CAST(NULL AS VARCHAR2(128)) AS BASE_OBJECT_NAME
            FROM ALL_PROCEDURES procedures
            JOIN requested_owner requested ON requested.OWNER = procedures.OWNER
            WHERE procedures.OBJECT_TYPE IN ('FUNCTION', 'PROCEDURE')
              AND procedures.PROCEDURE_NAME IS NULL
            ORDER BY OBJECT_TYPE, OBJECT_NAME, OBJECT_ID, SUBPROGRAM_ID
            """;

    private static final String ARGUMENTS_SQL = """
            /* snapshot:arguments */
            SELECT arguments.OBJECT_NAME, arguments.OBJECT_ID, arguments.SUBPROGRAM_ID,
                   arguments.POSITION, arguments.SEQUENCE, arguments.DATA_LEVEL,
                   arguments.IN_OUT, arguments.DATA_TYPE, arguments.DATA_LENGTH,
                   arguments.DATA_PRECISION, arguments.DATA_SCALE,
                   arguments.TYPE_OWNER, arguments.TYPE_NAME, arguments.TYPE_SUBNAME,
                   arguments.PLS_TYPE
            FROM ALL_ARGUMENTS arguments
            WHERE arguments.OWNER = ? AND arguments.PACKAGE_NAME IS NULL
            ORDER BY arguments.OBJECT_NAME, arguments.OBJECT_ID,
                     arguments.SUBPROGRAM_ID, arguments.SEQUENCE
            """;

    private static final String DDL_SQL = """
            /* snapshot:ddl */
            SELECT DBMS_METADATA.GET_DDL(?, ?, ?) AS DDL
            FROM DUAL
            """;

    private static final String DEPENDENCIES_SQL = """
            /* snapshot:dependencies */
            SELECT dependency.NAME AS SOURCE_NAME, dependency.TYPE AS SOURCE_TYPE,
                   dependency.REFERENCED_OWNER, dependency.REFERENCED_NAME,
                   dependency.REFERENCED_TYPE
            FROM ALL_DEPENDENCIES dependency
            WHERE dependency.OWNER = ?
            ORDER BY dependency.TYPE, dependency.NAME,
                     dependency.REFERENCED_OWNER, dependency.REFERENCED_TYPE,
                     dependency.REFERENCED_NAME
            """;

    private static final List<String> DEFINITION_TYPES = List.of(
            "VIEW", "MATERIALIZED VIEW", "FUNCTION", "PROCEDURE", "TRIGGER",
            "PACKAGE", "PACKAGE BODY", "TYPE", "TYPE BODY");

    private final Connection connection;

    public OracleSchemaSnapshotReader(Connection connection) {
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
        String owner = catalogOwner(schema);
        QualifiedName snapshotSchema = OracleSchemaIdentifierNormalizer.schema(owner);
        ReadState state = new ReadState(owner);

        state = attempt(state, options, Set.of(ObjectType.TABLE), candidate ->
                query(TABLES_SQL, owner, options, rows -> readTables(rows, candidate)));
        state = attempt(state, options, Set.of(ObjectType.TABLE), candidate ->
                query(COLUMNS_SQL, owner, options, rows -> readColumns(rows, candidate)));
        state = attempt(state, options, Set.of(ObjectType.TABLE, ObjectType.PRIMARY_KEY,
                        ObjectType.UNIQUE_CONSTRAINT, ObjectType.FOREIGN_KEY,
                        ObjectType.CHECK_CONSTRAINT), candidate ->
                query(CONSTRAINTS_SQL, owner, options, rows -> readConstraints(rows, candidate)));
        state = attempt(state, options, Set.of(ObjectType.TABLE, ObjectType.INDEX), candidate ->
                query(INDEXES_SQL, owner, options, rows -> readIndexes(rows, candidate)));
        state = attempt(state, options, Set.of(ObjectType.SEQUENCE), candidate ->
                query(SEQUENCES_SQL, owner, options, rows -> readSequences(rows, candidate)));
        Set<ObjectType> definitionScopes = Set.of(
                ObjectType.VIEW, ObjectType.MATERIALIZED_VIEW,
                ObjectType.FUNCTION, ObjectType.PROCEDURE, ObjectType.TRIGGER,
                ObjectType.PACKAGE_SPEC, ObjectType.PACKAGE_BODY, ObjectType.TYPE);
        state = attempt(state, options, definitionScopes, candidate ->
                query(DEFINITIONS_SQL, owner, options, rows -> readDefinitionInventory(rows, candidate)));
        state = attempt(state, options, Set.of(ObjectType.FUNCTION, ObjectType.PROCEDURE), candidate ->
                query(ARGUMENTS_SQL, owner, options, rows -> readArguments(rows, candidate)));
        for (String oracleType : DEFINITION_TYPES) {
            if (oracleType.equals("TYPE BODY")) continue;
            Set<ObjectType> scopes = definitionScopes(oracleType);
            state = attempt(state, options, scopes, candidate -> {
                readDefinitionGroup(candidate, oracleType, owner, options);
                if (oracleType.equals("TYPE")) {
                    readDefinitionGroup(candidate, "TYPE BODY", owner, options);
                }
            });
        }
        Set<ObjectType> dependencyScopes = state.knownTypes();
        state = attempt(state, options, dependencyScopes, candidate ->
                query(DEPENDENCIES_SQL, owner, options, rows -> readDependencies(rows, candidate)));
        ensureReadNotCancelled(options);

        SortedMap<ObjectKey, SchemaObject> objects = state.materialize();
        SnapshotCompleteness completeness = new SnapshotCompleteness(
                state.diagnostics.isEmpty(), state.diagnostics);
        String fingerprint = SnapshotFingerprint.compute(
                DbType.ORACLE, snapshotSchema, completeness, objects);
        return new SchemaSnapshot(DbType.ORACLE, connectionId, snapshotSchema, Instant.now(),
                completeness, objects, fingerprint);
    }

    private static ReadState attempt(ReadState state, SqlExecutionOptions options,
                                     Set<ObjectType> scopes, SqlAttempt attempt) throws SQLException {
        ReadState candidate = state.copy();
        try {
            attempt.run(candidate);
            return candidate;
        } catch (SQLException failure) {
            if (failure instanceof SQLTimeoutException) {
                throw new SQLTimeoutException("Snapshot metadata timed out",
                        failure.getSQLState(), failure.getErrorCode());
            }
            if (options.control().cancellationRequested() || isCancellation(failure)) {
                throw new SQLException("Snapshot metadata cancelled", "57014", failure.getErrorCode());
            }
            String diagnostic;
            if (failure.getErrorCode() == 1031) {
                diagnostic = SnapshotCompleteness.PERMISSION_DENIED;
            } else if (failure instanceof DefinitionUnavailableSQLException) {
                diagnostic = SnapshotCompleteness.DEFINITION_UNAVAILABLE;
            } else if (failure instanceof SQLFeatureNotSupportedException) {
                diagnostic = SnapshotCompleteness.NOT_SUPPORTED;
            } else {
                throw new SQLException("Snapshot metadata failed",
                        failure.getSQLState(), failure.getErrorCode());
            }
            scopes.forEach(scope -> state.diagnostic(scope, diagnostic));
            return state;
        }
    }

    private void query(String sql, String owner, SqlExecutionOptions options,
                       RowReader reader) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            SqlExecutionControl.Activation activation = options.control()
                    .activate(statement, options.queryTimeoutSeconds());
            try {
                statement.setString(1, owner);
                options.control().ensureNotCancelled(activation);
                try (ResultSet rows = statement.executeQuery()) {
                    reader.read(rows);
                }
                options.control().ensureNotCancelled(activation);
            } finally {
                options.control().release(activation);
            }
        }
    }

    private static void readTables(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            String name = requiredCatalogValue(rows.getString("table_name"));
            ObjectKey key = state.key(ObjectType.TABLE, name, "");
            state.tables.put(name, new TableBuilder(key));
            state.register("TABLE", name, key);
        }
    }

    private static void readColumns(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            String tableName = requiredCatalogValue(rows.getString("table_name"));
            String columnName = requiredCatalogValue(rows.getString("column_name"));
            int ordinal = rows.getInt("column_id");
            String dataType = requiredCatalogValue(rows.getString("data_type"));
            Long dataLength = nullableLong(rows, "data_length");
            Long characterLength = nullableLong(rows, "char_length");
            String characterUsed = rows.getString("char_used");
            Integer precision = nullableInteger(rows, "data_precision");
            Integer scale = nullableInteger(rows, "data_scale");
            String typeOwner = rows.getString("data_type_owner");
            String typeModifier = rows.getString("data_type_mod");
            boolean nullable = "Y".equals(rows.getString("nullable"));
            boolean identity = "YES".equals(rows.getString("identity_column"));
            String generationType = rows.getString("generation_type");
            boolean defaultOnNull = "YES".equals(rows.getString("default_on_null"));
            boolean virtual = "YES".equals(rows.getString("virtual_column"));
            boolean hidden = "YES".equals(rows.getString("hidden_column"));
            boolean userGenerated = "YES".equals(rows.getString("user_generated"));
            boolean invisible = hidden && userGenerated;
            String identityOptions = rows.getString("identity_options");
            String comment = rows.getString("comments");
            String defaultExpression = rows.getString("data_default");

            TableBuilder table = state.tables.get(tableName);
            if (table == null || hidden && !userGenerated) continue;
            CanonicalDataType canonicalType = canonicalType(dataType, dataLength,
                    characterLength, characterUsed, precision, scale, typeOwner, typeModifier,
                    identity, generationType, identityOptions,
                    defaultOnNull, virtual, invisible);
            table.columns.add(new ColumnDefinition(
                    OracleSchemaIdentifierNormalizer.child(columnName), canonicalType, nullable,
                    columnDefault(defaultExpression, identity, generationType, defaultOnNull, virtual),
                    ordinal, comment));
        }
    }

    private static void readConstraints(ResultSet rows, ReadState state) throws SQLException {
        Map<ConstraintIdentity, ConstraintBuilder> constraints = new LinkedHashMap<>();
        while (rows.next()) {
            String tableName = requiredCatalogValue(rows.getString("table_name"));
            String constraintName = requiredCatalogValue(rows.getString("constraint_name"));
            String oracleType = requiredCatalogValue(rows.getString("constraint_type"));
            Integer position = nullableInteger(rows, "position");
            String columnName = rows.getString("column_name");
            String referencedOwner = rows.getString("referenced_owner");
            String referencedTableName = rows.getString("referenced_table_name");
            String referencedColumnName = rows.getString("referenced_column_name");
            String deleteRule = rows.getString("delete_rule");
            boolean generated = "GENERATED NAME".equals(rows.getString("generated"));
            String searchCondition = rows.getString("search_condition");

            TableBuilder table = state.tables.get(tableName);
            if (table == null) continue;
            ConstraintIdentity identity = new ConstraintIdentity(tableName, constraintName);
            ConstraintBuilder builder = constraints.get(identity);
            if (builder == null) {
                ConstraintKind kind = constraintKind(oracleType);
                ObjectKey key = state.key(objectType(kind), constraintName,
                        table.key.name().comparisonKey());
                builder = new ConstraintBuilder(key, kind,
                        OracleSchemaDefinitionNormalizer.normalize(searchCondition),
                        deleteRule, generated);
                constraints.put(identity, builder);
                table.constraints.add(builder);
            }
            if (position != null && columnName != null) {
                builder.columns.add(new PositionedName(position,
                        OracleSchemaIdentifierNormalizer.child(columnName)));
            }
            if (referencedOwner != null && referencedTableName != null) {
                ObjectKey referencedTable = new ObjectKey(ObjectType.TABLE,
                        OracleSchemaIdentifierNormalizer.object(referencedOwner, referencedTableName), "");
                builder.referencedTable = referencedTable;
                if (state.owner.equals(referencedOwner)) {
                    builder.dependencies.add(referencedTable);
                    if (!table.key.equals(referencedTable)) table.dependencies.add(referencedTable);
                }
            }
            if (position != null && referencedColumnName != null) {
                builder.referencedColumns.add(new PositionedName(position,
                        OracleSchemaIdentifierNormalizer.child(referencedColumnName)));
            }
        }
    }

    private static void readIndexes(ResultSet rows, ReadState state) throws SQLException {
        Map<IndexIdentity, IndexBuilder> indexes = new LinkedHashMap<>();
        while (rows.next()) {
            String tableName = requiredCatalogValue(rows.getString("table_name"));
            String indexName = requiredCatalogValue(rows.getString("index_name"));
            String indexType = requiredCatalogValue(rows.getString("index_type"));
            boolean unique = "UNIQUE".equals(rows.getString("uniqueness"));
            Integer position = nullableInteger(rows, "column_position");
            String columnName = rows.getString("column_name");
            boolean backingConstraint = rows.getString("constraint_name") != null;
            String direction = rows.getString("descend");
            String expression = rows.getString("column_expression");

            if (!"NORMAL".equals(indexType) && !"FUNCTION-BASED NORMAL".equals(indexType)) {
                throw new SQLFeatureNotSupportedException("Unsupported Oracle index metadata");
            }
            TableBuilder table = state.tables.get(tableName);
            if (table == null) continue;
            IndexIdentity identity = new IndexIdentity(tableName, indexName);
            IndexBuilder builder = indexes.get(identity);
            if (builder == null) {
                ObjectKey key = state.key(ObjectType.INDEX, indexName,
                        table.key.name().comparisonKey());
                builder = new IndexBuilder(key, unique, backingConstraint);
                indexes.put(identity, builder);
                table.indexes.add(builder);
            }
            String normalizedExpression = OracleSchemaDefinitionNormalizer.normalize(expression);
            if (normalizedExpression == null && columnName != null) {
                normalizedExpression = OracleSchemaIdentifierNormalizer.child(columnName).original();
            }
            if (position == null || normalizedExpression == null) {
                throw new SQLFeatureNotSupportedException("Unsupported Oracle index metadata");
            }
            if ("DESC".equals(direction)) {
                normalizedExpression += " DESC";
            } else if (!"ASC".equals(direction)) {
                throw new SQLFeatureNotSupportedException("Unsupported Oracle index metadata");
            }
            builder.expressions.add(new PositionedText(position, normalizedExpression));
        }
    }

    private static void readSequences(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            String name = requiredCatalogValue(rows.getString("sequence_name"));
            ObjectKey key = state.key(ObjectType.SEQUENCE, name, "");
            Map<String, String> extensions = Map.of(
                    "oracle.order", "Y".equals(rows.getString("order_flag")) ? "ORDER" : "NOORDER",
                    "oracle.startValueKnown", "false");
            SequenceDefinition sequence = new SequenceDefinition(key, null,
                    rows.getString("increment_by"), rows.getString("min_value"),
                    rows.getString("max_value"), "Y".equals(rows.getString("cycle_flag")),
                    nullableInteger(rows, "cache_size"), Set.of(), extensions);
            state.sequences.put(key, sequence);
            state.register("SEQUENCE", name, key);
        }
    }

    private static void readDefinitionInventory(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            String name = requiredCatalogValue(rows.getString("object_name"));
            String oracleType = requiredCatalogValue(rows.getString("object_type"));
            long objectId = rows.getLong("object_id");
            int subprogramId = rows.getInt("subprogram_id");
            String baseObjectOwner = rows.getString("base_object_owner");
            String baseObjectType = rows.getString("base_object_type");
            String baseObjectName = rows.getString("base_object_name");
            if (!DEFINITION_TYPES.contains(oracleType)) {
                throw new SQLException("Snapshot metadata failed");
            }
            state.definitionEntries.add(new DefinitionEntry(
                    name, oracleType, objectId, subprogramId,
                    baseObjectOwner, baseObjectType, baseObjectName));
        }
        state.definitionEntries.sort(Comparator
                .comparing(DefinitionEntry::oracleType)
                .thenComparing(DefinitionEntry::name)
                .thenComparingLong(DefinitionEntry::objectId)
                .thenComparingInt(DefinitionEntry::subprogramId));
    }

    private static void readArguments(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            String objectName = requiredCatalogValue(rows.getString("object_name"));
            long objectId = rows.getLong("object_id");
            int subprogramId = rows.getInt("subprogram_id");
            int position = rows.getInt("position");
            int sequence = rows.getInt("sequence");
            int dataLevel = rows.getInt("data_level");
            String mode = rows.getString("in_out");
            String dataType = rows.getString("data_type");
            Long dataLength = nullableLong(rows, "data_length");
            Integer precision = nullableInteger(rows, "data_precision");
            Integer scale = nullableInteger(rows, "data_scale");
            String typeOwner = rows.getString("type_owner");
            String typeName = rows.getString("type_name");
            String typeSubname = rows.getString("type_subname");
            String plsType = rows.getString("pls_type");
            if (position <= 0 || dataLevel != 0 || "OUT".equals(mode)) continue;
            String normalizedMode = switch (mode == null ? "IN" : mode) {
                case "IN/OUT", "IN OUT", "INOUT" -> "INOUT";
                case "IN" -> "IN";
                default -> throw new SQLException("Snapshot metadata failed");
            };
            String identityType = argumentType(dataType, dataLength, precision, scale,
                    typeOwner, typeName, typeSubname, plsType);
            RoutineIdentity identity = new RoutineIdentity(objectName, objectId, subprogramId);
            state.arguments.computeIfAbsent(identity, ignored -> new ArrayList<>())
                    .add(new RoutineArgument(sequence, normalizedMode, identityType));
        }
    }

    private void readDefinitionGroup(ReadState state, String oracleType, String owner,
                                     SqlExecutionOptions options) throws SQLException {
        for (DefinitionEntry entry : state.entries(oracleType)) {
            ObjectKey key = definitionKey(state, entry);
            String ddl = queryDdl(entry.ddlType(), entry.name(), owner, options);
            boolean plSql = isPlSqlDefinition(key);
            boolean automaticPlSql = !plSql
                    || OracleSchemaChangeRenderer.supportsAutomaticPlSqlDefinition(ddl, owner);
            DefinitionConfidence confidence = ddl == null || ddl.isBlank()
                    || OracleSchemaDefinitionNormalizer.containsProviderStorageClause(ddl)
                    || !automaticPlSql
                    ? DefinitionConfidence.LOW : DefinitionConfidence.HIGH;
            boolean incomplete = ddl == null || ddl.isBlank()
                    || OracleSchemaDefinitionNormalizer.containsProviderStorageClause(ddl);
            state.addDefinition(key, ddl, confidence, incomplete);
            state.register(entry.oracleType(), entry.name(), key);
            if (entry.oracleType().equals("PACKAGE BODY")) {
                state.addDependency(key, state.key(ObjectType.PACKAGE_SPEC, entry.name(), ""));
            } else if (entry.oracleType().equals("TYPE BODY")) {
                state.addDependency(key, state.key(ObjectType.TYPE, entry.name(), "SPEC"));
            } else if (entry.oracleType().equals("TRIGGER")
                    && ("TABLE".equals(entry.baseObjectType())
                    || "VIEW".equals(entry.baseObjectType()))
                    && state.owner.equals(entry.baseObjectOwner())) {
                ObjectKey base = state.singleKey(entry.baseObjectType(), entry.baseObjectName());
                if (base == null) {
                    state.diagnostic(ObjectType.TRIGGER, SnapshotCompleteness.DEPENDENCY_UNRESOLVED);
                } else {
                    state.addDependency(key, base);
                }
            }
        }
    }

    private static boolean isPlSqlDefinition(ObjectKey key) {
        return switch (key.type()) {
            case FUNCTION, PROCEDURE, TRIGGER, PACKAGE_SPEC, PACKAGE_BODY -> true;
            case TYPE -> key.signature().equals("BODY");
            default -> false;
        };
    }

    private String queryDdl(String objectType, String objectName, String owner,
                            SqlExecutionOptions options) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DDL_SQL)) {
            SqlExecutionControl.Activation activation = options.control()
                    .activate(statement, options.queryTimeoutSeconds());
            try {
                statement.setString(1, objectType);
                statement.setString(2, objectName);
                statement.setString(3, owner);
                options.control().ensureNotCancelled(activation);
                String ddl = null;
                try (ResultSet rows = statement.executeQuery()) {
                    boolean first = true;
                    while (rows.next()) {
                        if (first) {
                            ddl = rows.getString("ddl");
                            first = false;
                        }
                    }
                }
                options.control().ensureNotCancelled(activation);
                return ddl;
            } finally {
                options.control().release(activation);
            }
        } catch (SQLException failure) {
            if (isDefinitionUnavailable(failure)) {
                throw new DefinitionUnavailableSQLException(
                        failure.getSQLState(), failure.getErrorCode());
            }
            throw failure;
        }
    }

    private static boolean isDefinitionUnavailable(SQLException failure) {
        return failure.getErrorCode() == 31603
                || failure.getErrorCode() == 4043
                || failure.getErrorCode() == 942;
    }

    private static void readDependencies(ResultSet rows, ReadState state) throws SQLException {
        while (rows.next()) {
            String sourceName = requiredCatalogValue(rows.getString("source_name"));
            String sourceType = requiredCatalogValue(rows.getString("source_type"));
            String referencedOwner = requiredCatalogValue(rows.getString("referenced_owner"));
            String referencedName = requiredCatalogValue(rows.getString("referenced_name"));
            String referencedType = requiredCatalogValue(rows.getString("referenced_type"));
            List<ObjectKey> sources = state.keys(sourceType, sourceName);
            if (sources.isEmpty() || !state.owner.equals(referencedOwner)) continue;
            List<ObjectKey> targets = state.keys(referencedType, referencedName);
            if (targets.size() != 1) {
                if (supportedDependencyType(referencedType)) {
                    sources.forEach(source -> state.diagnostic(
                            source.type(), SnapshotCompleteness.DEPENDENCY_UNRESOLVED));
                }
                continue;
            }
            ObjectKey target = targets.getFirst();
            for (ObjectKey source : sources) {
                if (!source.equals(target)) state.addDependency(source, target);
            }
        }
    }

    private static ObjectKey definitionKey(ReadState state, DefinitionEntry entry)
            throws SQLException {
        return switch (entry.oracleType()) {
            case "VIEW" -> state.key(ObjectType.VIEW, entry.name(), "");
            case "MATERIALIZED VIEW" -> state.key(ObjectType.MATERIALIZED_VIEW, entry.name(), "");
            case "FUNCTION" -> state.key(ObjectType.FUNCTION, entry.name(),
                    routineSignature(state, entry));
            case "PROCEDURE" -> state.key(ObjectType.PROCEDURE, entry.name(),
                    routineSignature(state, entry));
            case "TRIGGER" -> state.key(ObjectType.TRIGGER, entry.name(), "");
            case "PACKAGE" -> state.key(ObjectType.PACKAGE_SPEC, entry.name(), "");
            case "PACKAGE BODY" -> state.key(ObjectType.PACKAGE_BODY, entry.name(), "");
            case "TYPE" -> state.key(ObjectType.TYPE, entry.name(), "SPEC");
            case "TYPE BODY" -> state.key(ObjectType.TYPE, entry.name(), "BODY");
            default -> throw new SQLException("Snapshot metadata failed");
        };
    }

    private static String routineSignature(ReadState state, DefinitionEntry entry) {
        List<RoutineArgument> arguments = new ArrayList<>(state.arguments.getOrDefault(
                new RoutineIdentity(entry.name(), entry.objectId(), entry.subprogramId()), List.of()));
        arguments.sort(Comparator.comparingInt(RoutineArgument::sequence));
        StringBuilder signature = new StringBuilder("oracle-routine-signature-v1\0");
        for (RoutineArgument argument : arguments) {
            signature.append(lengthField(argument.mode())).append(lengthField(argument.type()));
        }
        return signature.toString();
    }

    private static String argumentType(String dataType, Long dataLength,
                                       Integer precision, Integer scale,
                                       String typeOwner, String typeName,
                                       String typeSubname, String plsType) throws SQLException {
        String type;
        if (typeOwner != null && typeName != null) {
            type = OracleSchemaIdentifierNormalizer.quote(typeOwner) + '.'
                    + OracleSchemaIdentifierNormalizer.quote(typeName)
                    + (typeSubname == null ? ""
                    : "." + OracleSchemaIdentifierNormalizer.quote(typeSubname));
        } else if (dataType != null) {
            type = dataType;
        } else if (plsType != null) {
            type = plsType;
        } else {
            throw new SQLException("Snapshot metadata failed");
        }
        if (precision != null) {
            type += "(" + precision + (scale == null ? "" : "," + scale) + ")";
        } else if (dataLength != null && supportsLength(type)) {
            type += "(" + dataLength + ")";
        }
        return type;
    }

    private static boolean supportsLength(String type) {
        String upper = type.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith("CHAR") || upper.endsWith("CHAR2")
                || upper.endsWith("VARCHAR") || upper.endsWith("VARCHAR2")
                || upper.endsWith("RAW");
    }

    private static String lengthField(String value) {
        return value.length() + ":" + value;
    }

    private static Set<ObjectType> definitionScopes(String oracleType) {
        return switch (oracleType) {
            case "VIEW" -> Set.of(ObjectType.VIEW);
            case "MATERIALIZED VIEW" -> Set.of(ObjectType.MATERIALIZED_VIEW);
            case "FUNCTION" -> Set.of(ObjectType.FUNCTION);
            case "PROCEDURE" -> Set.of(ObjectType.PROCEDURE);
            case "TRIGGER" -> Set.of(ObjectType.TRIGGER);
            case "PACKAGE" -> Set.of(ObjectType.PACKAGE_SPEC);
            case "PACKAGE BODY" -> Set.of(ObjectType.PACKAGE_BODY);
            case "TYPE", "TYPE BODY" -> Set.of(ObjectType.TYPE);
            default -> throw new IllegalArgumentException("Unsupported Oracle definition category");
        };
    }

    private static boolean supportedDependencyType(String oracleType) {
        return switch (oracleType) {
            case "TABLE", "VIEW", "MATERIALIZED VIEW", "FUNCTION", "PROCEDURE", "TRIGGER",
                    "PACKAGE", "PACKAGE BODY", "TYPE", "TYPE BODY", "SEQUENCE" -> true;
            default -> false;
        };
    }

    private static CanonicalDataType canonicalType(
            String dataType, Long dataLength, Long characterLength, String characterUsed,
            Integer precision, Integer scale, String typeOwner, String typeModifier,
            boolean identity, String generationType, String identityOptions, boolean defaultOnNull,
            boolean virtual, boolean invisible) {
        SortedMap<String, String> extensions = new TreeMap<>();
        String upperType = dataType.toUpperCase(java.util.Locale.ROOT);
        String baseType = dataType;
        Long length = null;
        boolean withTimeZone = false;

        if (typeOwner != null) {
            baseType = typeOwner + "." + dataType;
            extensions.put("formattedType", OracleSchemaIdentifierNormalizer.quote(typeOwner)
                    + "." + OracleSchemaIdentifierNormalizer.quote(dataType));
            extensions.put("oracle.typeOwner", typeOwner);
        } else if (upperType.startsWith("TIMESTAMP")) {
            baseType = "TIMESTAMP";
            withTimeZone = upperType.contains("TIME ZONE");
            if (upperType.contains("LOCAL TIME ZONE")) {
                extensions.put("oracle.timeZone", "WITH LOCAL TIME ZONE");
            } else if (upperType.contains("WITH TIME ZONE")) {
                extensions.put("oracle.timeZone", "WITH TIME ZONE");
            }
            extensions.put("formattedType", dataType);
        } else if (upperType.startsWith("INTERVAL DAY")) {
            baseType = "INTERVAL DAY TO SECOND";
            extensions.put("formattedType", dataType);
        } else if (upperType.startsWith("INTERVAL YEAR")) {
            baseType = "INTERVAL YEAR TO MONTH";
            extensions.put("formattedType", dataType);
        } else if (upperType.contains("CHAR")) {
            length = characterLength;
        } else if (upperType.equals("RAW") || upperType.equals("UROWID")) {
            length = dataLength;
        }
        if (characterUsed != null) {
            extensions.put("oracle.lengthSemantics", "C".equals(characterUsed) ? "CHAR" : "BYTE");
        }
        if (typeModifier != null) extensions.put("oracle.typeModifier", typeModifier);
        if (identity) {
            extensions.put("oracle.identity", generationType == null ? "UNKNOWN" : generationType);
            String normalizedOptions = normalizeIdentityOptions(identityOptions);
            if (normalizedOptions != null) {
                extensions.put("oracle.identityOptions", normalizedOptions);
            }
        }
        if (defaultOnNull) extensions.put("oracle.defaultOnNull", "true");
        if (virtual) extensions.put("oracle.virtual", "true");
        if (invisible) extensions.put("oracle.invisible", "true");
        return new CanonicalDataType(baseType, length, precision, scale, withTimeZone, 0, extensions);
    }

    private static String normalizeIdentityOptions(String options) {
        if (options == null || options.isBlank()) return null;
        return options.strip().replaceAll("\\s+", " ");
    }

    private static String columnDefault(String expression, boolean identity, String generationType,
                                        boolean defaultOnNull, boolean virtual) {
        String normalized = OracleSchemaDefinitionNormalizer.normalize(expression);
        if (identity) {
            String generation = generationType == null ? "UNKNOWN" : generationType.strip();
            return "GENERATED " + generation + " AS IDENTITY";
        }
        if (virtual) {
            return "GENERATED ALWAYS AS (" + (normalized == null ? "" : normalized) + ") VIRTUAL";
        }
        if (defaultOnNull) {
            return normalized == null ? null : "DEFAULT ON NULL " + normalized;
        }
        return normalized;
    }

    private static ConstraintKind constraintKind(String oracleType) throws SQLException {
        return switch (oracleType) {
            case "P" -> ConstraintKind.PRIMARY_KEY;
            case "U" -> ConstraintKind.UNIQUE;
            case "R" -> ConstraintKind.FOREIGN_KEY;
            case "C" -> ConstraintKind.CHECK;
            default -> throw new SQLException("Snapshot metadata failed");
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

    private static void ensureReadNotCancelled(SqlExecutionOptions options) throws SQLException {
        if (options.control().cancellationRequested()) {
            throw new SQLException("Snapshot metadata cancelled", "57014");
        }
    }

    private static boolean isCancellation(SQLException failure) {
        return "57014".equals(failure.getSQLState()) || failure.getErrorCode() == 1013;
    }

    private static String requiredCatalogValue(String value) throws SQLException {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new SQLException("Snapshot metadata failed");
        }
        return value;
    }

    private static Long nullableLong(ResultSet rows, String label) throws SQLException {
        Object value = rows.getObject(label);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInteger(ResultSet rows, String label) throws SQLException {
        Object value = rows.getObject(label);
        return value == null ? null : ((Number) value).intValue();
    }

    private static String catalogOwner(QualifiedName schema) {
        String prefix = "oracle-schema-v1\0";
        String comparisonKey = schema.comparisonKey();
        if (comparisonKey.startsWith(prefix)) {
            String encoded = comparisonKey.substring(prefix.length());
            int colon = encoded.indexOf(':');
            if (colon > 0) {
                try {
                    int length = Integer.parseInt(encoded.substring(0, colon));
                    String value = encoded.substring(colon + 1);
                    if (value.length() == length) return value;
                } catch (NumberFormatException ignored) {
                    // Fall through to the safely rendered original form.
                }
            }
        }
        String original = schema.original();
        if (original.length() >= 2 && original.charAt(0) == '"'
                && original.charAt(original.length() - 1) == '"') {
            return original.substring(1, original.length() - 1).replace("\"\"", "\"");
        }
        return original;
    }

    @Override
    public String toString() {
        return "OracleSchemaSnapshotReader[redacted]";
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet rows) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlAttempt {
        void run(ReadState state) throws SQLException;
    }

    private static final class ReadState {
        private final String owner;
        private final Map<String, TableBuilder> tables = new LinkedHashMap<>();
        private final SortedMap<ObjectKey, SequenceDefinition> sequences = new TreeMap<>();
        private final List<DefinitionEntry> definitionEntries = new ArrayList<>();
        private final Map<RoutineIdentity, List<RoutineArgument>> arguments = new LinkedHashMap<>();
        private final SortedMap<ObjectKey, DefinitionObject> definitions = new TreeMap<>();
        private final Map<OracleIdentity, List<ObjectKey>> identityKeys = new LinkedHashMap<>();
        private final Map<ObjectKey, Set<ObjectKey>> discoveredDependencies = new LinkedHashMap<>();
        private final SortedMap<ObjectType, String> diagnostics = new TreeMap<>();

        private ReadState(String owner) {
            this.owner = owner;
        }

        private ReadState copy() {
            ReadState copy = new ReadState(owner);
            tables.forEach((name, table) -> copy.tables.put(name, table.copy()));
            copy.sequences.putAll(sequences);
            copy.definitionEntries.addAll(definitionEntries);
            arguments.forEach((identity, values) ->
                    copy.arguments.put(identity, new ArrayList<>(values)));
            copy.definitions.putAll(definitions);
            identityKeys.forEach((identity, keys) ->
                    copy.identityKeys.put(identity, new ArrayList<>(keys)));
            discoveredDependencies.forEach((key, dependencies) ->
                    copy.discoveredDependencies.put(key, new java.util.TreeSet<>(dependencies)));
            copy.diagnostics.putAll(diagnostics);
            return copy;
        }

        private ObjectKey key(ObjectType type, String name, String signature) {
            return new ObjectKey(type, OracleSchemaIdentifierNormalizer.object(owner, name), signature);
        }

        private List<DefinitionEntry> entries(String oracleType) {
            return definitionEntries.stream()
                    .filter(entry -> entry.oracleType().equals(oracleType)).toList();
        }

        private void addDefinition(
                ObjectKey key, String original, DefinitionConfidence confidence,
                boolean incomplete) {
            DefinitionConfidence actual = original == null || original.isBlank()
                    ? DefinitionConfidence.LOW : confidence;
            if (incomplete) {
                diagnostic(key.type(), SnapshotCompleteness.DEFINITION_UNAVAILABLE);
            }
            definitions.put(key, new DefinitionObject(key,
                    OracleSchemaDefinitionNormalizer.normalize(original), original, Set.of(), actual));
        }

        private void register(String oracleType, String name, ObjectKey key) {
            OracleIdentity identity = new OracleIdentity(oracleType, name);
            List<ObjectKey> keys = identityKeys.computeIfAbsent(identity, ignored -> new ArrayList<>());
            if (!keys.contains(key)) {
                keys.add(key);
                keys.sort(ObjectKey::compareTo);
            }
        }

        private List<ObjectKey> keys(String oracleType, String name) {
            return List.copyOf(identityKeys.getOrDefault(new OracleIdentity(oracleType, name), List.of()));
        }

        private ObjectKey singleKey(String oracleType, String name) {
            List<ObjectKey> keys = keys(oracleType, name);
            return keys.size() == 1 ? keys.getFirst() : null;
        }

        private void addDependency(ObjectKey source, ObjectKey target) {
            if (source != null && target != null && !source.equals(target)) {
                discoveredDependencies.computeIfAbsent(source, ignored -> new java.util.TreeSet<>())
                        .add(target);
            }
        }

        private Set<ObjectKey> dependencies(ObjectKey key) {
            return discoveredDependencies.getOrDefault(key, Set.of());
        }

        private Set<ObjectType> knownTypes() {
            Set<ObjectType> types = new java.util.TreeSet<>();
            tables.values().forEach(table -> types.add(ObjectType.TABLE));
            sequences.values().forEach(sequence -> types.add(ObjectType.SEQUENCE));
            definitions.values().forEach(definition -> types.add(definition.key().type()));
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
                case SnapshotCompleteness.NOT_SUPPORTED -> 2;
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
                        .map(ConstraintBuilder::build)
                        .sorted(Comparator.comparing(ConstraintDefinition::key)).toList();
                List<IndexDefinition> indexes = builder.indexes.stream()
                        .map(IndexBuilder::build)
                        .sorted(Comparator.comparing(IndexDefinition::key)).toList();
                Set<ObjectKey> dependencies = new java.util.TreeSet<>(builder.dependencies);
                constraints.forEach(constraint -> dependencies.addAll(constraint.dependencies()));
                indexes.forEach(index -> dependencies.addAll(index.dependencies()));
                TableDefinition table = new TableDefinition(builder.key, builder.columns,
                        constraints, indexes, dependencies);
                objects.put(table.key(), table);
            }
            for (SequenceDefinition sequence : sequences.values()) {
                objects.put(sequence.key(), new SequenceDefinition(sequence.key(), sequence.startValue(),
                        sequence.incrementBy(), sequence.minimumValue(), sequence.maximumValue(),
                        sequence.cycle(), sequence.cacheSize(), dependencies(sequence.key()),
                        sequence.providerExtensions()));
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
        private final Set<ObjectKey> dependencies = new java.util.TreeSet<>();

        private TableBuilder(ObjectKey key) {
            this.key = key;
        }

        private TableBuilder copy() {
            TableBuilder copy = new TableBuilder(key);
            copy.columns.addAll(columns);
            constraints.forEach(value -> copy.constraints.add(value.copy()));
            indexes.forEach(value -> copy.indexes.add(value.copy()));
            copy.dependencies.addAll(dependencies);
            return copy;
        }
    }

    private static final class ConstraintBuilder {
        private final ObjectKey key;
        private final ConstraintKind kind;
        private final List<PositionedName> columns = new ArrayList<>();
        private final List<PositionedName> referencedColumns = new ArrayList<>();
        private final String expression;
        private final String deleteAction;
        private final boolean generated;
        private final Set<ObjectKey> dependencies = new java.util.TreeSet<>();
        private ObjectKey referencedTable;

        private ConstraintBuilder(ObjectKey key, ConstraintKind kind, String expression,
                                  String deleteAction, boolean generated) {
            this.key = key;
            this.kind = kind;
            this.expression = expression;
            this.deleteAction = deleteAction;
            this.generated = generated;
        }

        private ConstraintDefinition build() {
            return new ConstraintDefinition(key, kind, names(columns), referencedTable,
                    names(referencedColumns), expression, null, deleteAction,
                    generated, dependencies);
        }

        private ConstraintBuilder copy() {
            ConstraintBuilder copy = new ConstraintBuilder(key, kind, expression, deleteAction, generated);
            copy.columns.addAll(columns);
            copy.referencedColumns.addAll(referencedColumns);
            copy.dependencies.addAll(dependencies);
            copy.referencedTable = referencedTable;
            return copy;
        }
    }

    private static final class IndexBuilder {
        private final ObjectKey key;
        private final boolean unique;
        private final boolean backingConstraint;
        private final List<PositionedText> expressions = new ArrayList<>();

        private IndexBuilder(ObjectKey key, boolean unique, boolean backingConstraint) {
            this.key = key;
            this.unique = unique;
            this.backingConstraint = backingConstraint;
        }

        private IndexDefinition build() {
            expressions.sort(Comparator.comparingInt(PositionedText::position));
            return new IndexDefinition(key, unique,
                    expressions.stream().map(PositionedText::text).toList(),
                    null, backingConstraint, Set.of());
        }

        private IndexBuilder copy() {
            IndexBuilder copy = new IndexBuilder(key, unique, backingConstraint);
            copy.expressions.addAll(expressions);
            return copy;
        }
    }

    private static List<QualifiedName> names(List<PositionedName> values) {
        return values.stream().sorted(Comparator.comparingInt(PositionedName::position))
                .map(PositionedName::name).toList();
    }

    private record ConstraintIdentity(String table, String constraint) {
    }

    private record IndexIdentity(String table, String index) {
    }

    private record PositionedName(int position, QualifiedName name) {
    }

    private record PositionedText(int position, String text) {
    }

    private record DefinitionEntry(
            String name,
            String oracleType,
            long objectId,
            int subprogramId,
            String baseObjectOwner,
            String baseObjectType,
            String baseObjectName) {
        private String ddlType() {
            return switch (oracleType) {
                case "VIEW" -> "VIEW";
                case "MATERIALIZED VIEW" -> "MATERIALIZED_VIEW";
                case "FUNCTION" -> "FUNCTION";
                case "PROCEDURE" -> "PROCEDURE";
                case "TRIGGER" -> "TRIGGER";
                case "PACKAGE" -> "PACKAGE_SPEC";
                case "PACKAGE BODY" -> "PACKAGE_BODY";
                case "TYPE" -> "TYPE_SPEC";
                case "TYPE BODY" -> "TYPE_BODY";
                default -> throw new IllegalArgumentException("Unsupported Oracle definition category");
            };
        }
    }

    private record RoutineIdentity(String name, long objectId, int subprogramId) {
    }

    private record RoutineArgument(int sequence, String mode, String type) {
    }

    private record OracleIdentity(String oracleType, String name) {
    }

    private static final class DefinitionUnavailableSQLException extends SQLException {
        private DefinitionUnavailableSQLException(String sqlState, int errorCode) {
            super("Definition unavailable", sqlState, errorCode);
        }
    }
}
