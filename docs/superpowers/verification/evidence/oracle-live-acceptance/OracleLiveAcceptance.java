import com.datacube.config.CredentialCipher;
import com.datacube.provider.oracle.OracleProvider;
import com.datacube.service.*;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** External acceptance probe against the unchanged packaged product. No deletion or business-row access. */
public final class OracleLiveAcceptance {
    static String schema = "SCMTEST", table, column, marker;
    static final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
    static final AtomicInteger executed = new AtomicInteger(), mutations = new AtomicInteger(), denied = new AtomicInteger();
    static volatile CountDownLatch updateEntered;
    static final List<String> checks = new ArrayList<>();
    static int failed;
    static CredentialCipher cipher;
    static DatabaseProvider provider;
    static ConnectionManager manager;
    static String encrypted;

    static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing acceptance environment");
        return value;
    }
    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    static <T> T wrap(Class<T> api, InvocationHandler handler) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler));
    }
    static void require(boolean condition, String code) {
        if (!condition) throw new AssertionError(code);
    }
    static ConnConfig config(String id, boolean readOnly, boolean production, int timeout) {
        return new ConnConfig(id, "Oracle 专用验收", DbType.ORACLE, env("DC_ORACLE_HOST"),
            Integer.parseInt(env("DC_ORACLE_PORT")), env("DC_ORACLE_SERVICE"), env("DC_ORACLE_USER"), encrypted,
            Map.of("readOnly", Boolean.toString(readOnly), "environment", production ? "PRODUCTION" : "TEST",
                "queryTimeoutSeconds", Integer.toString(timeout)));
    }
    static String qualified() { return "\"" + schema + "\".\"" + table + "\""; }
    static TableRef ref() { return new TableRef(schema, table); }
    static void safeSql(String sql) throws SQLException {
        String normalized = sql.strip().toUpperCase(Locale.ROOT);
        // This is a probe boundary, independent of the product's client read-only policy.
        if (normalized.matches("(?s).*\\b(DELETE|TRUNCATE|DROP|GRANT|REVOKE|MERGE)\\b.*")
                || normalized.startsWith("BEGIN") || normalized.startsWith("DECLARE")) {
            denied.incrementAndGet(); throw new SQLException("Acceptance SQL denied");
        }
        if (normalized.startsWith("ALTER SESSION SET CURRENT_SCHEMA") && normalized.contains(schema)) return;
        if (normalized.startsWith("SELECT")) {
            if (normalized.contains(qualified()) || normalized.contains("FROM DUAL")
                    || normalized.contains("FROM ALL_") || normalized.contains("FROM USER_")) return;
        } else if (normalized.contains(qualified()) &&
                (normalized.startsWith("CREATE TABLE") || normalized.startsWith("COMMENT ON")
                    || normalized.startsWith("INSERT INTO") || normalized.startsWith("UPDATE"))) return;
        denied.incrementAndGet(); throw new SQLException("Acceptance SQL outside fixture denied");
    }
    static Statement statement(Statement raw, String prepared, Connection owner) {
        Class<? extends Statement> api = raw instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        Map<Integer, Object> parameters = new HashMap<>();
        return wrap(api, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("getConnection")) return owner;
            if (name.equals("unwrap")) throw new SQLException("Acceptance unwrap denied");
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer i)
                parameters.put(i, args[1]);
            if (name.startsWith("execute") || name.equals("addBatch")) {
                String sql = prepared != null ? prepared : args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                if (sql == null) throw new SQLException("Acceptance batch denied");
                safeSql(sql);
                // Catalog lookups are bounded to the authorized schema and own object where applicable.
                if (sql.contains("ALL_") && !parameters.containsValue(schema))
                    throw new SQLException("Acceptance catalog owner required");
                if (sql.contains("DBMS_METADATA.GET_DDL") && (!parameters.containsValue(table) || !parameters.containsValue(schema)))
                    throw new SQLException("Acceptance DDL target required");
                executed.incrementAndGet();
                String upper = sql.strip().toUpperCase(Locale.ROOT);
                if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("CREATE") || upper.startsWith("COMMENT")) {
                    mutations.incrementAndGet();
                    if (upper.startsWith("UPDATE") && updateEntered != null) updateEntered.countDown();
                }
            }
            return invoke(raw, method, args);
        });
    }
    static Connection connection(Connection raw) {
        opened.incrementAndGet();
        AtomicBoolean ended = new AtomicBoolean();
        return wrap(Connection.class, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("close")) {
                try { return invoke(raw, method, args); }
                finally { if (ended.compareAndSet(false, true)) closed.incrementAndGet(); }
            }
            if (name.equals("unwrap")) throw new SQLException("Acceptance unwrap denied");
            if (name.equals("prepareCall")) throw new SQLException("Acceptance callable denied");
            if (name.equals("prepareStatement")) {
                safeSql((String) args[0]);
                return statement((Statement) invoke(raw, method, args), (String) args[0], (Connection) proxy);
            }
            if (name.equals("createStatement"))
                return statement((Statement) invoke(raw, method, args), null, (Connection) proxy);
            return invoke(raw, method, args);
        });
    }
    static void initialize(Path home, String object) throws Exception {
        require(object.matches("DCA_[0-9A-F]{14}_T"), "FIXTURE_NAME");
        table = object; marker = object.substring(4, 18); column = "DCA_" + marker + "_VAL";
        require(env("DC_ORACLE_USER").equalsIgnoreCase(schema), "AUTHORIZED_USER");
        System.setProperty("user.home", home.toString());
        System.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        System.setProperty("oracle.jdbc.ReadTimeout", "15000");
        DriverManager.setLoginTimeout(10);
        cipher = new CredentialCipher();
        encrypted = cipher.encrypt(env("DC_ORACLE_PASSWORD"));
        OracleProvider real = new OracleProvider();
        ConnectionFactory original = real.connectionFactory();
        ConnectionFactory guarded = new ConnectionFactory() {
            public void ensureDriverLoaded() { original.ensureDriverLoaded(); }
            public Connection open(ConnConfig cfg) throws SQLException {
                require(cfg.type() == DbType.ORACLE && cfg.host().equals(env("DC_ORACLE_HOST"))
                    && cfg.port() == Integer.parseInt(env("DC_ORACLE_PORT"))
                    && cfg.database().equals(env("DC_ORACLE_SERVICE"))
                    && cfg.username().equals(env("DC_ORACLE_USER")), "EXACT_ENDPOINT");
                return connection(original.open(cfg));
            }
            public String test(ConnConfig cfg) { throw new UnsupportedOperationException("Use acceptance checks"); }
        };
        provider = wrap(DatabaseProvider.class, (proxy, method, args) ->
            method.getName().equals("connectionFactory") ? guarded : invoke(real, method, args));
        manager = manager();
    }
    static ConnectionManager manager() throws Exception {
        ConnectionManager result = new ConnectionManager(cipher);
        Field resolver = ConnectionManager.class.getDeclaredField("providerResolver"); resolver.setAccessible(true);
        resolver.set(result, (Function<DbType, DatabaseProvider>) type -> {
            require(type == DbType.ORACLE, "ORACLE_ONLY"); return provider;
        });
        return result;
    }
    @FunctionalInterface interface Work { void run() throws Exception; }
    static void check(String name, Work work) {
        long start = System.nanoTime();
        try {
            work.run();
            checks.add(name);
            System.out.println("{\"check\":\"" + name + "\",\"result\":\"passed\",\"milliseconds\":" + (System.nanoTime()-start)/1_000_000 + "}");
        } catch (Throwable error) {
            failed++;
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            String code = cause instanceof SQLException e ? Integer.toString(e.getErrorCode()) : "0";
            String assertion = cause instanceof AssertionError && String.valueOf(cause.getMessage()).matches("[A-Z][A-Z0-9_]+") ? cause.getMessage() : "REDACTED";
            System.out.println("{\"check\":\"" + name + "\",\"result\":\"failed\",\"type\":\"" + cause.getClass().getSimpleName()
                + "\",\"sqlCode\":" + code + ",\"assertion\":\"" + assertion + "\"}");
        }
    }
    static QueryResult result(JdbcEditorSession.ExecutionBatch batch) {
        require(batch.outcomes().size() == 1, "ONE_OUTCOME"); return batch.outcomes().getFirst().result();
    }
    static QueryResult run(JdbcEditorSession session, String sql) throws SQLException {
        var op = session.prepareScript(sql, schema, 10, null, true);
        return result(op.execute(op.confirmationRequired() ? op.confirm() : null));
    }
    static int count(int id) throws SQLException {
        try (Connection c = manager.openDedicated(config("observe", true, false, 5));
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + qualified() + " WHERE \"ID\" = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { require(rs.next(), "COUNT_ROW"); return rs.getInt(1); }
        }
    }
    static String value(int id) throws SQLException {
        try (Connection c = manager.openDedicated(config("observe", true, false, 5));
             PreparedStatement ps = c.prepareStatement("SELECT \"" + column + "\" FROM " + qualified() + " WHERE \"ID\" = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { require(rs.next(), "VALUE_ROW"); return rs.getString(1); }
        }
    }
    static void rejected(Work operation) throws Exception {
        int beforeOpen = opened.get(), beforeExecute = executed.get();
        try { operation.run(); throw new AssertionError("EXPECTED_REJECTION"); }
        catch (IllegalStateException expected) { }
        require(opened.get() == beforeOpen && executed.get() == beforeExecute, "REJECT_BEFORE_RESOURCE");
    }
    static LinkedHashMap<String,String> values(String... pairs) {
        LinkedHashMap<String,String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i+1]);
        return map;
    }
    static void transactions(int id, boolean commit, boolean close) throws Exception {
        ConnConfig cfg = config("transaction-" + id, false, false, 5); manager.register(cfg);
        JdbcEditorSession session = manager.openEditorSession(cfg);
        try {
            session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
            require(run(session, "INSERT INTO " + qualified() + " (\"ID\",\"" + column + "\") VALUES ("+id+",'transaction')").kind == QueryResult.Kind.UPDATE, "INSERT");
            require(session.snapshot().hasPendingTransaction() && count(id) == 0, "UNCOMMITTED_INVISIBLE");
            if (close) session.closeStrict(); else if (commit) session.commit(); else session.rollback();
            require(count(id) == (commit ? 1 : 0), "TRANSACTION_VISIBILITY");
        } finally { session.closeStrict(); }
    }
    static void blockedUpdate(boolean timeout) throws Exception {
        ConnConfig cfg = config(timeout ? "timeout" : "cancel", false, false, timeout ? 2 : 10); manager.register(cfg);
        try (Connection blocker = manager.openDedicated(config("blocker", false, false, 5));
             PreparedStatement lock = blocker.prepareStatement("UPDATE " + qualified()+" SET \""+column+"\" = ? WHERE \"ID\" = ?");
             JdbcEditorSession session = manager.openEditorSession(cfg);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false); lock.setString(1, "locked"); lock.setInt(2, 1);
            require(lock.executeUpdate() == 1, "LOCK_OWN_ROW");
            updateEntered = new CountDownLatch(1);
            Future<QueryResult> operation = executor.submit(() -> run(session,
                "UPDATE " + qualified() + " SET \"" + column + "\" = 'must_not_commit' WHERE \"ID\" = 1"));
            require(updateEntered.await(5, TimeUnit.SECONDS), "UPDATE_ENTERED");
            try {
                if (!timeout) {
                    try { operation.get(200, TimeUnit.MILLISECONDS); throw new AssertionError("EXPECTED_LOCK_WAIT"); }
                    catch (TimeoutException expected) { }
                    require(session.snapshot().running(), "RUNNING"); session.cancel();
                }
                QueryResult answer = operation.get(12, TimeUnit.SECONDS);
                System.out.println("{\"driverObservation\":\""+(timeout ? "timeout" : "cancel")+"\",\"kind\":\""+answer.kind+"\",\"failureKind\":\""+answer.failureKind+"\",\"elapsedMillis\":"+answer.elapsedMillis+"}");
                require(answer.kind == QueryResult.Kind.ERROR
                    && answer.failureKind == (timeout ? QueryResult.FailureKind.TIMEOUT : QueryResult.FailureKind.CANCELLED), "CANCEL_TIMEOUT_CLASSIFICATION");
                require(!session.snapshot().running(), "FINISHED");
            } finally { updateEntered = null; blocker.rollback(); }
            require(value(1).equals("updated"), "NO_CANCELLED_WRITE_COMMITTED");
            require(run(session, "SELECT \"ID\" FROM " + qualified() + " WHERE \"ID\" = 1").kind == QueryResult.Kind.QUERY, "RECOVER_READ");
        }
    }
    public static void main(String[] args) throws Exception {
        try {
            initialize(Path.of(args[0]), args[1]);
            ConnConfig write = config("write", false, false, 5); manager.register(write);
            check("connect_identity", () -> {
                try (Connection c = manager.openDedicated(write); Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT USER, SYS_CONTEXT('USERENV','SERVICE_NAME') FROM DUAL")) {
                    require(rs.next() && schema.equals(rs.getString(1))
                        && rs.getString(2).equalsIgnoreCase(env("DC_ORACLE_SERVICE")), "DATABASE_IDENTITY");
                    System.out.println("{\"databaseMajor\":" + c.getMetaData().getDatabaseMajorVersion()
                        + ",\"databaseMinor\":" + c.getMetaData().getDatabaseMinorVersion() + "}");
                }
            });
            if (failed > 0) { System.exit(1); }
            if (args.length > 2 && args[2].equals("preflight")) { manager.closeAll(); System.exit(0); }
            if (args.length > 2 && args[2].equals("cancel-diagnostic")) {
                check("real_driver_cancel_diagnostic", () -> blockedUpdate(false));
                manager.closeAll(); if(failed>0)System.exit(1); System.exit(0);
            }
            require(env("DC_ORACLE_ALLOW_FIXTURE_CREATE_WRITE").equals("yes"), "EXPLICIT_FIXTURE_WRITE_AUTHORIZATION");
            check("create_unique_fixture", () -> {
                try (Connection c = manager.openDedicated(write); Statement s = c.createStatement()) {
                    s.setQueryTimeout(10);
                    s.executeUpdate("CREATE TABLE " + qualified() + " (\"ID\" NUMBER(10) PRIMARY KEY, \"" + column + "\" VARCHAR2(80), \"EMPTY_VALUE\" VARCHAR2(20))");
                    s.executeUpdate("COMMENT ON TABLE " + qualified() + " IS 'DataCube acceptance " + marker + "'");
                    s.executeUpdate("COMMENT ON COLUMN " + qualified() + ".\"" + column + "\" IS 'DataCube field acceptance " + marker + "'");
                }
            });
            if (failed > 0) { manager.closeAll(); System.exit(1); }
            DataEditService edits = new DataEditService(manager);
            check("real_insert_update_and_conflict", () -> {
                require(edits.insert(write.id(), ref(), values("ID","1",column,"inserted","EMPTY_VALUE","")) == 1, "INSERT_ONE");
                require(edits.update(write.id(), ref(), values(column,"updated"), new RowKey(List.of("ID",column), List.of(1,"inserted"))) == 1, "UPDATE_ONE");
                try { edits.update(write.id(), ref(), values(column,"must_not_commit"), new RowKey(List.of("ID",column), List.of(1,"stale"))); throw new AssertionError("STALE_UPDATE"); }
                catch (com.datacube.provider.jdbc.RowGuardException expected) { require(expected.affectedRows() == 0, "CONFLICT_ZERO"); }
                require(value(1).equals("updated"), "CONFLICT_UNCHANGED");
            });
            ConnConfig read = config("readonly", true, false, 5); manager.register(read);
            check("readonly_rejects_sql_insert_and_data_update_before_resources", () -> {
                rejected(() -> edits.prepareUpdate(edits.target(read.id()), ref(), values(column,"blocked"), new RowKey(List.of("ID"),List.of(1))).execute(null));
                try (JdbcEditorSession session = manager.openEditorSession(read)) {
                    rejected(() -> session.prepareScript("INSERT INTO " + qualified()+" (\"ID\") VALUES (9)",schema,10,null,true).execute(null));
                }
            });
            check("production_confirmation_bound_to_request_and_config", () -> {
                ConnConfig prod = config("production",false,true,5); manager.register(prod);
                var one = edits.prepareInsert(edits.target(prod.id()),ref(),values("ID","2",column,"production"));
                require(one.production() && one.confirmationRequired(), "PRODUCTION_FLAG");
                rejected(() -> one.execute(null));
                var confirmed = one.confirm();
                var other = edits.prepareInsert(edits.target(prod.id()),ref(),values("ID","3",column,"other"));
                rejected(() -> other.execute(confirmed));
                require(one.execute(confirmed) == 1 && count(2) == 1, "CONFIRMED_ONE");
                rejected(() -> one.execute(confirmed));
                var stale = edits.prepareUpdate(edits.target(prod.id()),ref(),values(column,"stale"),new RowKey(List.of("ID"),List.of(2)));
                var staleConfirmation = stale.confirm(); manager.register(config(prod.id(),true,true,5));
                rejected(() -> stale.execute(staleConfirmation));
            });
            check("manual_rollback_visibility", () -> transactions(11,false,false));
            check("manual_commit_visibility", () -> transactions(12,true,false));
            check("pending_close_rolls_back", () -> transactions(13,false,true));
            check("schema_name_catalog", () -> {
                List<TableInfo> names = new SchemaObjectCatalog(manager).load(read,schema);
                require(names.stream().anyMatch(n -> n.name().equals(table) && n.kind() == TableInfo.Kind.TABLE), "OWN_TABLE_FOUND");
            });
            for (SchemaMetadataSearch.Mode mode : SchemaMetadataSearch.Mode.values()) check("metadata_" + mode.name().toLowerCase(Locale.ROOT), () -> {
                var search = SchemaMetadataSearch.search(new SchemaMetadataSearch.Request(read,schema,mode,marker), manager::openDedicated,new SqlExecutionControl());
                require(!search.truncated() && search.hits().size() == 1 && search.hits().getFirst().object().name().equals(table), "EXACT_FIXTURE_HIT");
                if(mode != SchemaMetadataSearch.Mode.OBJECT_COMMENT) require(search.hits().getFirst().column().equals(column), "OWN_COLUMN");
            });
            check("readonly_data_page_and_oracle_empty_string", () -> {
                var page = new DataBrowseService(manager).page(read.id(),ref(),0,10,List.of(),null);
                require(page.rows().size() == 3 && page.columns().contains(column), "OWN_PAGE");
                int empty = page.columns().indexOf("EMPTY_VALUE");
                require(empty >= 0 && page.rows().stream().allMatch(row -> row.get(empty) == null), "ORACLE_EMPTY_IS_NULL");
            });
            check("ddl_preview_only", () -> {
                int before = mutations.get();
                String ddl = new DdlService(manager).tableDdl(read.id(),ref());
                require(ddl.contains("CREATE TABLE") && ddl.contains(table) && mutations.get() == before, "DDL_PREVIEW");
            });
            check("real_driver_cancel_lock_wait_and_recover", () -> blockedUpdate(false));
            check("real_driver_timeout_lock_wait_and_recover", () -> blockedUpdate(true));
            manager.closeAll();
            check("connections_closed_and_fixture_retained", () -> {
                require(opened.get() == closed.get(), "BALANCED_CONNECTIONS");
                require(count(1) == 1 && count(2) == 1 && count(12) == 1 && count(9) == 0 && count(13) == 0, "RETAINED_ROWS");
                require(opened.get() == closed.get(), "FINAL_BALANCE");
            });
            System.out.println("{\"summary\":true,\"passed\":"+checks.size()+",\"failed\":"+failed
                +",\"opened\":"+opened+",\"closed\":"+closed+",\"executed\":"+executed+",\"mutations\":"+mutations
                +",\"deletionStatementsExecuted\":0,\"fixture\":\""+table+"\",\"retained\":true}");
        } catch (Throwable error) {
            Throwable cause=error; while(cause.getCause()!=null)cause=cause.getCause();
            System.out.println("{\"fatal\":true,\"type\":\""+cause.getClass().getSimpleName()+"\",\"sqlCode\":"+(cause instanceof SQLException e ? e.getErrorCode() : 0)+"}");
            failed++;
        } finally { if(manager!=null)manager.closeAll(); }
        if(failed>0)System.exit(1);
    }
}
