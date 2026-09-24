package com.datacube.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MigrationPreflightTest {
    @TempDir Path root;
    private MigrationRequest request(MigrationRequest.Mode mode) throws Exception {
        Files.createDirectories(root.resolve("data")); Files.writeString(root.resolve("data/items.sql"),"INSERT INTO items (id,label) VALUES (1,E'x');\n");
        return new MigrationRequest(new MigrationRequest.Endpoint("jdbc:synthetic:source","source-user","source-secret"),new MigrationRequest.Endpoint("jdbc:synthetic:target","target-user","target-secret"),"OWNER","dest",root,mode,false);
    }
    @Test void readsVersionsPermissionsEncodingDependenciesAndFilesWithoutWriting() throws Exception {
        Db source=new Db(true),target=new Db(false); var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);
        var plan=inspect(req,source,target);
        assertTrue(plan.canRun()); assertEquals(1,plan.tables().size()); assertEquals("items",plan.tables().getFirst().targetName());
        assertEquals("NUMERIC",plan.tables().getFirst().columns().getFirst().pgType());
        assertEquals(List.of("id"),plan.tables().getFirst().primaryKey()); assertNotNull(plan.tables().getFirst().data());
        assertTrue(source.readOnly && target.readOnly); assertEquals(1,source.closed); assertEquals(1,target.closed);
        assertEquals(source.statements,source.statementsClosed); assertEquals(target.statements,target.statementsClosed);
        assertTrue(source.parameters.stream().anyMatch(p -> p.equals(List.of("OWNER","ITEMS"))));
        assertTrue(target.parameters.stream().anyMatch(p -> p.equals(List.of("dest"))));
        assertTrue(plan.findings().stream().anyMatch(f -> f.code().equals("CONSISTENCY_SCOPE")));
        assertFalse(plan.toString().contains("secret")); assertFalse(req.toString().contains("user")); assertFalse(req.target().toString().contains("target-secret"));
    }
    @Test void unsupportedMappingsBooleanHeuristicsAndMissingFilesPreventApproval() throws Exception {
        Db source=new Db(true),target=new Db(false); var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);
        source.type="BLOB"; assertFalse(inspect(req,source,target).canRun());
        source=new Db(true); target=new Db(false); Files.delete(root.resolve("data/items.sql"));
        var missing=inspect(req,source,target); assertFalse(missing.canRun()); assertThrows(IllegalStateException.class,() -> missing.approve(req));
        var restored=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);
        var heuristic=new MigrationRequest(restored.source(),restored.target(),restored.owner(),restored.schema(),restored.directory(),restored.mode(),true);
        assertFalse(inspect(heuristic,new Db(true),new Db(false)).canRun());
    }
    @Test void existingDataFailsEmptyModeAndIsExplicitlySkippedInSkipMode() throws Exception {
        var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY); Db target=new Db(false); target.existing=true; target.hasRows=true;
        assertFalse(inspect(req,new Db(true),target).canRun());
        var skip=new MigrationRequest(req.source(),req.target(),req.owner(),req.schema(),req.directory(),MigrationRequest.Mode.SKIP_NONEMPTY,false);
        target=new Db(false); target.existing=true; target.hasRows=true;
        var plan=inspect(skip,new Db(true),target); assertTrue(plan.canRun()); assertTrue(plan.tables().getFirst().targetHasRows());
        assertTrue(plan.tables().getFirst().findings().stream().anyMatch(f -> f.code().equals("TARGET_SKIPPED")));
    }
    @Test void permissionUnknownOrDeniedAndTargetTriggersCannotProduceRunnablePlan() throws Exception {
        var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);
        Db denied=new Db(false); denied.permission=false; assertFalse(inspect(req,new Db(true),denied).canRun());
        Db error=new Db(false); error.fail=true; Db source=new Db(true);
        assertThrows(SQLException.class,() -> inspect(req,source,error)); assertEquals(1,source.closed); assertEquals(1,error.closed);
        Db triggered=new Db(false); triggered.existing=true; triggered.triggers=true;
        assertFalse(inspect(req,new Db(true),triggered).canRun());
        Db rules=new Db(false);rules.existing=true;rules.rules=true;assertFalse(inspect(req,new Db(true),rules).canRun());
    }
    @Test void approvalIsOneShotAndCannotMoveToChangedRequestOrAnotherPlan() throws Exception {
        var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY); var plan=inspect(req,new Db(true),new Db(false));
        var approval=plan.approve(req);
        var changed=new MigrationRequest(req.source(),req.target(),req.owner(),"other",req.directory(),req.mode(),false);
        assertThrows(IllegalStateException.class,() -> approval.consume(plan,changed));
        var other=inspect(req,new Db(true),new Db(false)); assertThrows(IllegalStateException.class,() -> approval.consume(other,req));
        approval.consume(plan,req); assertThrows(IllegalStateException.class,() -> approval.consume(plan,req));
    }
    @Test void invalidScopeAndCancellationDoNotOpenConnections() throws Exception {
        var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY); AtomicInteger opened=new AtomicInteger();
        MigrationPreflight preflight=new MigrationPreflight((u,n,p) -> { opened.incrementAndGet(); throw new SQLException(); });
        var invalid=new MigrationRequest(req.source(),req.target(),"../OWNER",req.schema(),req.directory(),req.mode(),false);
        assertFalse(preflight.inspect(invalid,new MigrationCancellation()).canRun());
        var cancellation=new MigrationCancellation(); cancellation.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,() -> preflight.inspect(req,cancellation));
        assertEquals(0,opened.get());
    }
    @Test void rejectsMalformedDataAndUnvalidatedSourceKeyBeforeApproval() throws Exception {
        var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);
        Files.writeString(root.resolve("data/items.sql"),"INSERT INTO items (id,label) VALUES (nextval('x'), 'bad');");
        assertFalse(inspect(req,new Db(true),new Db(false)).canRun());
        req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);Db source=new Db(true);source.validated=false;
        assertFalse(inspect(req,source,new Db(false)).canRun());
    }
    @Test void rejectsTargetGeneratedColumnsAndWrongTypesButAcceptsExactIntegers() throws Exception {
        var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);Db target=new Db(false);target.existing=true;target.generated="s";
        assertFalse(inspect(req,new Db(true),target).canRun());
        target.generated="";target.identity="a";assertFalse(inspect(req,new Db(true),target).canRun());
        target.identity="";target.targetType="float8";assertFalse(inspect(req,new Db(true),target).canRun());
        target.targetType="int8";assertTrue(inspect(req,new Db(true),target).canRun());
    }
    @Test void sourceEvidenceMustMatchOwnerColumnsFileAndParsedRowCount() throws Exception {
        var req=request(MigrationRequest.Mode.EMPTY_TABLES_ONLY);var initial=inspect(req,new Db(true),new Db(false));
        var table=initial.tables().getFirst();var now=java.time.Instant.now();
        var valid=new MigrationExportEvidence(MigrationExportEvidence.scope(initial.sourceIdentity(),req.owner()),MigrationExportEvidence.columns(table.columns()),table.data().sha256(),1,now,now);
        valid.write(table.data().path());assertTrue(inspect(req,new Db(true),new Db(false)).canRun());
        new MigrationExportEvidence(valid.sourceScope(),valid.columns(),valid.dataSha256(),2,now,now).write(table.data().path());
        assertFalse(inspect(req,new Db(true),new Db(false)).canRun());
        new MigrationExportEvidence(MigrationPreflight.fingerprint("another-owner"),valid.columns(),valid.dataSha256(),1,now,now).write(table.data().path());
        assertFalse(inspect(req,new Db(true),new Db(false)).canRun());
    }
    private MigrationPlan inspect(MigrationRequest request,Db source,Db target) throws SQLException {
        return new MigrationPreflight((url,user,password) -> (url.endsWith("source") ? source : target).connection()).inspect(request,new MigrationCancellation());
    }
    private static final class Db {
        final boolean source; boolean readOnly,existing,hasRows,triggers,rules,fail; boolean permission=true,validated=true; String type="NUMBER",targetType="numeric",generated="",identity="";
        int closed,statements,statementsClosed; final List<List<String>> parameters=new ArrayList<>();
        Db(boolean source) { this.source=source; }
        Connection connection() { return proxy(Connection.class,(method,args) -> switch(method) {
            case "setReadOnly" -> { assertEquals(true,args[0]); readOnly=true; yield null; }
            case "close" -> { closed++; yield null; }
            case "getMetaData" -> proxy(DatabaseMetaData.class,(m,a) -> switch(m) { case "getDatabaseProductName" -> source ? "Oracle" : "PostgreSQL"; case "getDatabaseMajorVersion" -> source ? 21 : 16; default -> throw new AssertionError(m); });
            case "prepareStatement" -> { String sql=(String)args[0]; assertTrue(readOnly); assertTrue(sql.startsWith("SELECT")); statements++; List<String> params=new ArrayList<>(); parameters.add(params);
                yield proxy(PreparedStatement.class,(m,a) -> switch(m) {
                    case "setQueryTimeout" -> { assertEquals(30,a[0]); yield null; }
                    case "setMaxRows" -> { assertEquals(5001,a[0]); yield null; }
                    case "setString" -> { params.add((String)a[1]); yield null; }
                    case "close" -> { statementsClosed++; yield null; }
                    case "executeQuery" -> result(query(sql));
                    default -> throw new AssertionError(m);
                }); }
            default -> throw new AssertionError(method);
        }); }
        List<List<String>> query(String sql) throws SQLException {
            if(fail)throw new SQLException("synthetic-secret");
            if(sql.contains("SYS_CONTEXT"))return data("synthetic-source:user");
            if(sql.contains("pg_postmaster_start_time"))return data("synthetic-target:user:local:time");
            if(sql.contains("NLS_DATABASE_PARAMETERS"))return data("AL32UTF8");
            if(sql.contains("server_encoding"))return data("UTF8");
            if(sql.contains("SESSIONTIMEZONE") || sql.contains("TimeZone"))return data("UTC");
            if(sql.contains("has_schema_privilege"))return List.of(List.of("100",Boolean.toString(permission),Boolean.toString(permission)));
            if(sql.contains("ALL_OBJECTS"))return List.of(List.of("TABLE","1"),List.of("PROCEDURE","1"));
            if(sql.contains("ALL_DEPENDENCIES"))return data("1");
            if(sql.contains("ALL_TABLES"))return data("ITEMS");
            if(sql.contains("ALL_TAB_COLUMNS"))return List.of(List.of("ID",type,"N","0"),List.of("LABEL","VARCHAR2","Y","0"));
            if(sql.contains("ALL_CONS_COLUMNS"))return List.of(List.of("ID","ENABLED",validated?"VALIDATED":"NOT VALIDATED"));
            if(sql.contains("ALL_CONSTRAINTS"))return data("1");
            if(sql.contains("WHERE 1=0"))return List.of();
            if(sql.contains("FROM pg_class"))return existing ? List.of(List.of("200","r",Boolean.toString(permission))) : List.of();
            if(sql.contains("LIMIT 1"))return hasRows ? data("1") : List.of();
            if(sql.contains("pg_trigger"))return data(triggers ? "1" : "0");
            if(sql.contains("pg_rewrite"))return data(rules ? "1" : "0");
            if(sql.contains("pg_inherits"))return data("0");
            if(sql.contains("FROM pg_attribute"))return List.of(List.of("id",targetType,generated,identity),List.of("label","text","",""));
            throw new AssertionError(sql);
        }
        static List<List<String>> data(String value) { return List.of(List.of(value)); }
        static ResultSet result(List<List<String>> values) { int[] row={-1}; return proxy(ResultSet.class,(method,args) -> switch(method) {
            case "getMetaData" -> proxy(ResultSetMetaData.class,(m,a) -> { if(m.equals("getColumnCount"))return values.isEmpty()?1:values.getFirst().size(); throw new AssertionError(m); });
            case "next" -> ++row[0]<values.size();
            case "getString" -> values.get(row[0]).get((int)args[0]-1);
            case "close" -> null;
            default -> throw new AssertionError(method);
        }); }
    }
    @FunctionalInterface private interface Call { Object invoke(String method,Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type,Call call) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a) -> {
        if(m.getName().equals("hashCode"))return System.identityHashCode(p); if(m.getName().equals("equals"))return p==a[0]; return call.invoke(m.getName(),a);
    })); }
}
