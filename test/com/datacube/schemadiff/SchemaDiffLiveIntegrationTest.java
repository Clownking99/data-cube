package com.datacube.schemadiff;

import com.datacube.config.CredentialCipher;
import com.datacube.provider.oracle.OracleSchemaIdentifierNormalizer;
import com.datacube.provider.postgres.PgSchemaIdentifierNormalizer;
import com.datacube.service.ConnectionManager;
import com.datacube.service.SchemaDeploymentControl;
import com.datacube.service.SchemaDeploymentResult;
import com.datacube.service.SchemaDeploymentService;
import com.datacube.service.SchemaDeploymentState;
import com.datacube.service.SchemaDiffRequest;
import com.datacube.service.SchemaDiffService;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.ProviderRegistry;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffLiveIntegrationTest {

    @Test
    void postgresqlSafeDeploymentConvergesInDisposableSchemas() {
        runIfExplicitlyEnabled(DbType.POSTGRESQL);
    }

    @Test
    void oracleSafeDeploymentConvergesInDisposableSchemas() {
        runIfExplicitlyEnabled(DbType.ORACLE);
    }

    @Test
    void writeGateMustBeExactAndEveryPostgresVariableMustBePresent() {
        Map<String, String> environment = postgresEnvironment();

        assertTrue(SchemaDiffLiveEnvironment.load(DbType.POSTGRESQL, environment).isPresent());
        for (String invalidGate : new String[]{"TRUE", " true", "true ", "1", "yes", ""}) {
            environment.put(SchemaDiffLiveEnvironment.ALLOW_WRITE, invalidGate);
            assertTrue(SchemaDiffLiveEnvironment.load(DbType.POSTGRESQL, environment).isEmpty());
        }
        environment.put(SchemaDiffLiveEnvironment.ALLOW_WRITE, "true");
        for (String required : SchemaDiffLiveEnvironment.requiredKeys(DbType.POSTGRESQL)) {
            Map<String, String> incomplete = new HashMap<>(environment);
            incomplete.remove(required);
            assertTrue(SchemaDiffLiveEnvironment.load(DbType.POSTGRESQL, incomplete).isEmpty());
        }
    }

    @Test
    void providerVariableSetsAreIndependentAndOracleRequiresTablespace() {
        Map<String, String> postgres = postgresEnvironment();
        Map<String, String> oracle = oracleEnvironment();

        assertTrue(SchemaDiffLiveEnvironment.load(DbType.POSTGRESQL, postgres).isPresent());
        assertTrue(SchemaDiffLiveEnvironment.load(DbType.ORACLE, postgres).isEmpty());
        assertTrue(SchemaDiffLiveEnvironment.load(DbType.ORACLE, oracle).isPresent());
        assertTrue(SchemaDiffLiveEnvironment.load(DbType.POSTGRESQL, oracle).isEmpty());

        oracle.remove("DATACUBE_SCHEMA_DIFF_ORACLE_TABLESPACE");
        assertTrue(SchemaDiffLiveEnvironment.load(DbType.ORACLE, oracle).isEmpty());
    }

    @Test
    void runNamesAreRandomProviderSafeAndCleanupUsesOnlyThoseExactNames() {
        SchemaDiffLiveRun first = SchemaDiffLiveRun.create(DbType.POSTGRESQL);
        SchemaDiffLiveRun second = SchemaDiffLiveRun.create(DbType.POSTGRESQL);
        SchemaDiffLiveRun oracle = SchemaDiffLiveRun.create(DbType.ORACLE);

        assertNotEquals(first.prefix(), second.prefix());
        assertTrue(first.sourceSchema().matches("dcsd_[0-9a-f]{20}_src"));
        assertTrue(first.targetSchema().matches("dcsd_[0-9a-f]{20}_tgt"));
        assertTrue(first.objectName().matches("dcsd_[0-9a-f]{20}_item"));
        assertTrue(oracle.sourceSchema().matches("DCSD_[0-9A-F]{20}_S"));
        assertTrue(oracle.targetSchema().matches("DCSD_[0-9A-F]{20}_T"));
        assertTrue(oracle.objectName().matches("DCSD_[0-9A-F]{20}_I"));
        assertTrue(oracle.sourceSchema().length() <= 30);
        assertTrue(oracle.targetSchema().length() <= 30);
        assertTrue(oracle.objectName().length() <= 30);

        assertEquals(2, first.cleanupStatements().size());
        assertTrue(first.cleanupStatements().contains(
                "DROP SCHEMA " + SchemaDiffLiveRun.quote(first.sourceSchema()) + " CASCADE"));
        assertTrue(first.cleanupStatements().contains(
                "DROP SCHEMA " + SchemaDiffLiveRun.quote(first.targetSchema()) + " CASCADE"));
        assertFalse(first.cleanupStatements().stream().anyMatch(sql -> sql.contains("*")
                || sql.toUpperCase(java.util.Locale.ROOT).contains("DROP DATABASE")));

        assertEquals(2, oracle.cleanupStatements().size());
        assertTrue(oracle.cleanupStatements().contains(
                "DROP USER " + SchemaDiffLiveRun.quote(oracle.sourceSchema()) + " CASCADE"));
        assertTrue(oracle.cleanupStatements().contains(
                "DROP USER " + SchemaDiffLiveRun.quote(oracle.targetSchema()) + " CASCADE"));
        assertFalse(oracle.cleanupStatements().stream().anyMatch(sql -> sql.contains("*")
                || sql.toUpperCase(java.util.Locale.ROOT).contains("DROP DATABASE")));
    }

    @Test
    void oracleUserIsCleanupEligibleBeforeAnyGrantCanFail() {
        Set<String> createdSchemas = new HashSet<>();

        assertThrows(SQLException.class, () -> SchemaDiffLiveSmoke.createOracleUser(
                sql -> {
                    if (sql.startsWith("GRANT ")) throw new SQLException("fixture");
                },
                "DCSD_0123456789ABCDEF0123_S",
                "fixture",
                "USERS",
                createdSchemas));

        assertEquals(Set.of("DCSD_0123456789ABCDEF0123_S"), createdSchemas);
    }

    private static Map<String, String> postgresEnvironment() {
        return environment("POSTGRES", false);
    }

    private static Map<String, String> oracleEnvironment() {
        return environment("ORACLE", true);
    }

    private static Map<String, String> environment(String provider, boolean tablespace) {
        Map<String, String> values = new HashMap<>();
        values.put(SchemaDiffLiveEnvironment.ALLOW_WRITE, "true");
        values.put("DATACUBE_SCHEMA_DIFF_" + provider + "_HOST", "x");
        values.put("DATACUBE_SCHEMA_DIFF_" + provider + "_PORT", "5432");
        values.put("DATACUBE_SCHEMA_DIFF_" + provider + "_DATABASE", "x");
        values.put("DATACUBE_SCHEMA_DIFF_" + provider + "_USERNAME", "x");
        values.put("DATACUBE_SCHEMA_DIFF_" + provider + "_PASSWORD", "x");
        if (tablespace) {
            values.put("DATACUBE_SCHEMA_DIFF_ORACLE_TABLESPACE", "USERS");
        }
        return values;
    }

    private static void runIfExplicitlyEnabled(DbType type) {
        Optional<SchemaDiffLiveEnvironment> loaded =
                SchemaDiffLiveEnvironment.load(type, System.getenv());
        Assumptions.assumeTrue(loaded.isPresent(),
                "Schema Diff relational live smoke requires the explicit write gate "
                        + "and the complete provider environment set");
        try {
            new SchemaDiffLiveSmoke().run(loaded.orElseThrow());
        } catch (Throwable failure) {
            throw new AssertionError(type + " Schema Diff live smoke failed with redacted details");
        }
    }
}

final class SchemaDiffLiveEnvironment {
    static final String ALLOW_WRITE = "DATACUBE_SCHEMA_DIFF_TEST_ALLOW_WRITE";
    private static final String PREFIX = "DATACUBE_SCHEMA_DIFF_";

    private final DbType type;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String tablespace;

    private SchemaDiffLiveEnvironment(
            DbType type, String host, int port, String database,
            String username, String password, String tablespace) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.tablespace = tablespace;
    }

    static Optional<SchemaDiffLiveEnvironment> load(
            DbType type, Map<String, String> environment) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(environment, "environment");
        if (!"true".equals(environment.get(ALLOW_WRITE))) return Optional.empty();
        List<String> required = requiredKeys(type);
        if (required.stream().anyMatch(key -> missing(environment.get(key)))) {
            return Optional.empty();
        }
        String provider = provider(type);
        int port;
        try {
            port = Integer.parseInt(environment.get(key(provider, "PORT")));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Schema Diff live port is invalid");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Schema Diff live port is invalid");
        }
        return Optional.of(new SchemaDiffLiveEnvironment(
                type,
                environment.get(key(provider, "HOST")),
                port,
                environment.get(key(provider, "DATABASE")),
                environment.get(key(provider, "USERNAME")),
                environment.get(key(provider, "PASSWORD")),
                type == DbType.ORACLE
                        ? environment.get(key(provider, "TABLESPACE")) : null));
    }

    static List<String> requiredKeys(DbType type) {
        String provider = provider(type);
        List<String> common = List.of(
                key(provider, "HOST"),
                key(provider, "PORT"),
                key(provider, "DATABASE"),
                key(provider, "USERNAME"),
                key(provider, "PASSWORD"));
        if (type == DbType.POSTGRESQL) return common;
        java.util.ArrayList<String> oracle = new java.util.ArrayList<>(common);
        oracle.add(key(provider, "TABLESPACE"));
        return List.copyOf(oracle);
    }

    DbType type() {
        return type;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String database() {
        return database;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    String tablespace() {
        return tablespace;
    }

    @Override
    public String toString() {
        return "SchemaDiffLiveEnvironment[type=" + type + "]";
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static String provider(DbType type) {
        return switch (type) {
            case POSTGRESQL -> "POSTGRES";
            case ORACLE -> "ORACLE";
            case REDIS -> throw new IllegalArgumentException(
                    "Schema Diff live smoke supports relational providers only");
        };
    }

    private static String key(String provider, String suffix) {
        return PREFIX + provider + "_" + suffix;
    }
}

record SchemaDiffLiveRun(
        DbType type, String prefix, String sourceSchema,
        String targetSchema, String objectName, List<String> cleanupStatements) {
    private static final SecureRandom RANDOM = new SecureRandom();

    SchemaDiffLiveRun {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(sourceSchema, "sourceSchema");
        Objects.requireNonNull(targetSchema, "targetSchema");
        Objects.requireNonNull(objectName, "objectName");
        cleanupStatements = List.copyOf(cleanupStatements);
    }

    static SchemaDiffLiveRun create(DbType type) {
        byte[] entropy = new byte[10];
        RANDOM.nextBytes(entropy);
        String random = HexFormat.of().formatHex(entropy);
        if (type == DbType.POSTGRESQL) {
            String prefix = "dcsd_" + random;
            String source = prefix + "_src";
            String target = prefix + "_tgt";
            return new SchemaDiffLiveRun(type, prefix, source, target, prefix + "_item", List.of(
                    "DROP SCHEMA " + quote(target) + " CASCADE",
                    "DROP SCHEMA " + quote(source) + " CASCADE"));
        }
        if (type == DbType.ORACLE) {
            String prefix = "DCSD_" + random.toUpperCase(java.util.Locale.ROOT);
            String source = prefix + "_S";
            String target = prefix + "_T";
            return new SchemaDiffLiveRun(type, prefix, source, target, prefix + "_I", List.of(
                    "DROP USER " + quote(target) + " CASCADE",
                    "DROP USER " + quote(source) + " CASCADE"));
        }
        throw new IllegalArgumentException("Schema Diff live smoke supports relational providers only");
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String toString() {
        return "SchemaDiffLiveRun[type=" + type + "]";
    }
}

final class SchemaDiffLiveSmoke {
    private static final ConnectionSafetyOptions TEST_SAFETY =
            new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 60);

    void run(SchemaDiffLiveEnvironment environment) throws SQLException {
        SchemaDiffLiveRun run = SchemaDiffLiveRun.create(environment.type());
        CredentialCipher cipher = new CredentialCipher();
        String encryptedPassword = cipher.encrypt(environment.password());
        ConnConfig sourceConfig = config(environment, encryptedPassword, run.prefix() + "-source");
        ConnConfig targetConfig = config(environment, encryptedPassword, run.prefix() + "-target");
        ConnectionManager connections = new ConnectionManager(cipher);
        SchemaDeploymentService deployment = new SchemaDeploymentService(connections);
        Set<String> createdSchemas = new HashSet<>();
        Throwable operationFailure = null;
        try {
            createFixture(connections, sourceConfig, environment, run, createdSchemas);
            compareDeployAndVerify(connections, deployment, sourceConfig, targetConfig, run);
        } catch (Throwable failure) {
            operationFailure = failure;
        } finally {
            boolean cleanupFailed = false;
            try {
                deployment.closeRetainedSessionsStrict();
            } catch (Throwable failure) {
                cleanupFailed = true;
            }
            if (!createdSchemas.isEmpty()) {
                cleanupFailed |= !dropCreatedSchemas(
                        connections, sourceConfig, run, createdSchemas);
            }
            connections.closeAll();
            if (operationFailure != null || cleanupFailed) {
                throw new SQLException("Schema Diff live smoke or exact cleanup failed");
            }
        }
    }

    private static void createFixture(
            ConnectionManager connections,
            ConnConfig adminConfig,
            SchemaDiffLiveEnvironment environment,
            SchemaDiffLiveRun run,
            Set<String> createdSchemas) throws SQLException {
        try (Connection connection = connections.openDedicated(adminConfig);
             Statement statement = connection.createStatement()) {
            if (environment.type() == DbType.POSTGRESQL) {
                statement.execute("CREATE SCHEMA " + SchemaDiffLiveRun.quote(run.sourceSchema()));
                createdSchemas.add(run.sourceSchema());
                statement.execute("CREATE SCHEMA " + SchemaDiffLiveRun.quote(run.targetSchema()));
                createdSchemas.add(run.targetSchema());
                statement.execute("CREATE TABLE "
                        + qualified(run.sourceSchema(), run.objectName())
                        + " (\"id\" BIGINT NOT NULL)");
            } else {
                String userPassword = randomOraclePassword();
                createOracleUser(statement::execute, run.sourceSchema(), userPassword,
                        environment.tablespace(), createdSchemas);
                createOracleUser(statement::execute, run.targetSchema(), userPassword,
                        environment.tablespace(), createdSchemas);
                statement.execute("CREATE TABLE "
                        + qualified(run.sourceSchema(), run.objectName())
                        + " (\"ID\" NUMBER(19) NOT NULL)");
            }
        }
    }

    static void createOracleUser(
            SqlExecutor executor,
            String user,
            String password,
            String tablespace,
            Set<String> createdSchemas) throws SQLException {
        String quotedUser = SchemaDiffLiveRun.quote(user);
        String quotedTablespace = SchemaDiffLiveRun.quote(tablespace);
        executor.execute("CREATE USER " + quotedUser
                + " IDENTIFIED BY " + SchemaDiffLiveRun.quote(password)
                + " DEFAULT TABLESPACE " + quotedTablespace
                + " QUOTA 10M ON " + quotedTablespace);
        createdSchemas.add(user);
        executor.execute("GRANT CREATE SESSION, CREATE TABLE TO " + quotedUser);
    }

    private static void compareDeployAndVerify(
            ConnectionManager connections,
            SchemaDeploymentService deployment,
            ConnConfig sourceConfig,
            ConnConfig targetConfig,
            SchemaDiffLiveRun run) {
        QualifiedName sourceSchema = schema(sourceConfig.type(), run.sourceSchema());
        QualifiedName targetSchema = schema(targetConfig.type(), run.targetSchema());
        SchemaDiffRequest request = new SchemaDiffRequest(
                sourceConfig, sourceSchema, targetConfig, targetSchema);
        SchemaDiffService comparison = new SchemaDiffService(connections);
        SchemaDiffResult initial = comparison.compare(request, new SchemaDeploymentControl())
                .toCompletableFuture().join();
        if (!initial.source().completeness().complete()
                || !initial.target().completeness().complete()) {
            throw new IllegalStateException("Schema Diff live snapshot was incomplete");
        }

        SchemaChangePlan plan = new SchemaChangePlanner().plan(initial);
        if (plan.selectedChangeIds().isEmpty() || !plan.blockedChangeIds().isEmpty()) {
            throw new IllegalStateException("Schema Diff live plan was not safely executable");
        }
        List<SchemaChange> selected = plan.changes().stream()
                .filter(change -> plan.selectedChangeIds().contains(change.id()))
                .toList();
        if (selected.stream().anyMatch(
                change -> change.automation() != AutomationLevel.SAFE_AUTOMATIC)) {
            throw new IllegalStateException("Schema Diff live plan selected a non-safe change");
        }

        DatabaseProvider provider = ProviderRegistry.forType(sourceConfig.type());
        SchemaChangeRenderer renderer = provider.schemaDiffCapability().orElseThrow().changeRenderer();
        RenderContext context = new RenderContext(
                sourceConfig.type(), initial.source().schema(), initial.target().schema(), false);
        List<RenderedStatement> statements = new ArrayList<>();
        for (SchemaChange change : selected) {
            statements.addAll(renderer.render(change, context));
        }
        if (statements.isEmpty() || statements.stream().anyMatch(RenderedStatement::destructive)) {
            throw new IllegalStateException("Schema Diff live renderer did not produce a safe plan");
        }

        SchemaDeploymentResult deployed = deployment.deploy(
                        request, initial.target(), statements, new SchemaDeploymentControl())
                .toCompletableFuture().join();
        if (!deployed.successful()
                || deployed.steps().stream().anyMatch(
                        step -> step.state() != SchemaDeploymentState.SUCCEEDED)) {
            throw new IllegalStateException("Schema Diff live deployment did not succeed");
        }

        SchemaDiffResult converged = comparison.compare(request, new SchemaDeploymentControl())
                .toCompletableFuture().join();
        if (!converged.source().completeness().complete()
                || !converged.target().completeness().complete()
                || !new SchemaChangePlanner().plan(converged).changes().isEmpty()) {
            throw new IllegalStateException("Schema Diff live deployment did not converge");
        }
    }

    private static boolean dropCreatedSchemas(
            ConnectionManager connections,
            ConnConfig adminConfig,
            SchemaDiffLiveRun run,
            Set<String> createdSchemas) {
        boolean success = true;
        try (Connection connection = connections.openDedicated(adminConfig);
             Statement statement = connection.createStatement()) {
            for (String cleanup : run.cleanupStatements()) {
                String exactName = cleanup.contains(SchemaDiffLiveRun.quote(run.targetSchema()))
                        ? run.targetSchema() : run.sourceSchema();
                if (!createdSchemas.contains(exactName)) continue;
                try {
                    statement.execute(cleanup);
                } catch (SQLException failure) {
                    success = false;
                }
            }
        } catch (SQLException failure) {
            return false;
        }
        return success;
    }

    private static ConnConfig config(
            SchemaDiffLiveEnvironment environment, String encryptedPassword, String id) {
        return new ConnConfig(
                id,
                "Schema Diff disposable live endpoint",
                environment.type(),
                environment.host(),
                environment.port(),
                environment.database(),
                environment.username(),
                encryptedPassword,
                TEST_SAFETY.toPersistentProps());
    }

    private static QualifiedName schema(DbType type, String name) {
        return switch (type) {
            case POSTGRESQL -> PgSchemaIdentifierNormalizer.schema(name);
            case ORACLE -> OracleSchemaIdentifierNormalizer.schema(name);
            case REDIS -> throw new IllegalArgumentException(
                    "Schema Diff live smoke supports relational providers only");
        };
    }

    private static String qualified(String schema, String object) {
        return SchemaDiffLiveRun.quote(schema) + "." + SchemaDiffLiveRun.quote(object);
    }

    private static String randomOraclePassword() {
        byte[] entropy = new byte[12];
        new SecureRandom().nextBytes(entropy);
        return "A" + HexFormat.of().formatHex(entropy) + "9";
    }

    @FunctionalInterface
    interface SqlExecutor {
        void execute(String sql) throws SQLException;
    }
}
