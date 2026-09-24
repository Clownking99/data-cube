package com.datacube.migration;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.datacube.migration.MigrationPlan.*;

/** Read-only preflight. The importer must revalidate database and file identities before writing. */
public final class MigrationPreflight {
    private final MigrationConnections connections;
    public MigrationPreflight() { this(MigrationConnections::connect); }
    public MigrationPreflight(MigrationConnections connections) { this.connections=Objects.requireNonNull(connections); }

    public MigrationPlan inspect(MigrationRequest request, MigrationCancellation cancellation) throws SQLException {
        cancellation.checkCancelled();
        List<Finding> findings=new ArrayList<>(); List<Table> tables=new ArrayList<>();
        if(!simpleName(request.owner()) || !simpleName(request.schema())) {
            findings.add(block("NAME_SCOPE", "自动迁移仅支持 1–63 位 ASCII 字母、数字、下划线名称；其他名称需人工映射"));
            return new MigrationPlan(request,"","",null,tables,findings);
        }
        Connection source=null,target=null;
        try {
            source=cancellation.register(connections.open(request.source().url(),request.source().user(),request.source().password()));
            source.setReadOnly(true);
            target=cancellation.register(connections.open(request.target().url(),request.target().user(),request.target().password()));
            target.setReadOnly(true);
            DatabaseMetaData sm=source.getMetaData(),tm=target.getMetaData();
            if(!"Oracle".equalsIgnoreCase(sm.getDatabaseProductName()) || sm.getDatabaseMajorVersion()<12)
                findings.add(block("SOURCE_VERSION","需 Oracle 12 或更高版本；实际驱动兼容性须在授权环境验证"));
            if(!"PostgreSQL".equalsIgnoreCase(tm.getDatabaseProductName()) || tm.getDatabaseMajorVersion()<12)
                findings.add(block("TARGET_VERSION","需 PostgreSQL 12 或更高版本；实际驱动兼容性须在授权环境验证"));
            String sourceIdentity=sourceIdentity(source,cancellation),targetIdentity=targetIdentity(target,cancellation);
            String sourceEncoding=one(source,"SELECT VALUE FROM NLS_DATABASE_PARAMETERS WHERE PARAMETER='NLS_CHARACTERSET'",cancellation);
            String targetEncoding=one(target,"SELECT current_setting('server_encoding')",cancellation);
            one(source,"SELECT SESSIONTIMEZONE FROM DUAL",cancellation);
            one(target,"SELECT current_setting('TimeZone')",cancellation);
            findings.add(info("ENCODING_TIMEZONE","已读取源/目标编码与会话时区；时间、时区、浮点、LOB、二进制及自定义类型不自动转换"));
            if(sourceEncoding.isBlank() || !targetEncoding.equalsIgnoreCase("UTF8"))
                findings.add(block("ENCODING_UNPROVEN","无法证明当前字符转换范围；自动路径要求 UTF8 目标"));
            if(request.convertBoolean()) findings.add(block("BOOLEAN_HEURISTIC","注释推断布尔值不构成类型映射证明，请关闭自动布尔转换"));
            List<List<String>> schema=rows(target,"SELECT oid::text, has_schema_privilege(oid,'USAGE')::text, has_schema_privilege(oid,'CREATE')::text FROM pg_namespace WHERE nspname=?",cancellation,request.schema());
            if(schema.isEmpty()) {
                if(!truth(one(target,"SELECT has_database_privilege(current_database(),'CREATE')::text",cancellation)))
                    findings.add(block("SCHEMA_CREATE_PERMISSION","目标 Schema 不存在且无法确认建 Schema 权限"));
                else findings.add(info("CREATE_SCHEMA","确认执行后将创建目标 Schema；预检查不会创建"));
            } else if(!truth(schema.getFirst().get(1)) || !truth(schema.getFirst().get(2)))
                findings.add(block("SCHEMA_PERMISSION","缺少目标 Schema USAGE/CREATE 权限"));
            List<List<String>> objects=rows(source,"SELECT OBJECT_TYPE, COUNT(*) FROM ALL_OBJECTS WHERE OWNER=? GROUP BY OBJECT_TYPE",cancellation,request.owner());
            if(objects.stream().anyMatch(row -> !Set.of("TABLE","INDEX","LOB").contains(row.getFirst())))
                findings.add(manual("OBJECTS_MANUAL","序列、视图、例程、包、触发器等不自动等价转换；参考 DDL 需另行审阅，不自动安装占位函数"));
            long dependencies=Long.parseLong(one(source,"SELECT COUNT(*) FROM ALL_DEPENDENCIES WHERE OWNER=? AND REFERENCED_OWNER<>OWNER",cancellation,request.owner()));
            if(dependencies>0) findings.add(manual("EXTERNAL_DEPENDENCIES","检测到跨所有者依赖；不会自动复制依赖或授予权限"));
            List<List<String>> sourceTables=rows(source,"SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME",cancellation,request.owner());
            if(sourceTables.isEmpty()) findings.add(block("NO_VISIBLE_TABLES","未读到可见表；不等于源端没有对象"));
            int totalColumns=0;
            if(sourceTables.size()>1000)throw new SQLException("Migration table limit exceeded","DC002");
            for(List<String> row:sourceTables) {
                cancellation.checkCancelled(); String name=row.getFirst(),targetName=name.toLowerCase(Locale.ROOT);
                List<Finding> local=new ArrayList<>(); List<Column> columns=new ArrayList<>();
                if(!simpleName(name)) { local.add(block("TABLE_NAME","表名需要人工映射")); tables.add(new Table(name,targetName,columns,List.of(),null,false,null,local)); continue; }
                for(List<String> col:rows(source,"SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, DEFAULT_LENGTH FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",cancellation,request.owner(),name)) {
                    String type=col.get(1),pgType=switch(type) {case "NUMBER","INTEGER" -> "NUMERIC"; case "VARCHAR2","NVARCHAR2","CHAR","NCHAR" -> "TEXT"; default -> "";};
                    if(!simpleName(col.get(0)) || pgType.isEmpty()) local.add(block("COLUMN_MAPPING","存在未支持的名称或类型；不会截断、置 NULL 或猜测转换"));
                    columns.add(new Column(col.get(0),col.get(0).toLowerCase(Locale.ROOT),type,pgType,"Y".equals(col.get(2))));
                    if(col.get(3)!=null && Long.parseLong(col.get(3))>0) local.add(manual("DEFAULT_MANUAL","默认值表达式不自动迁移，需要人工审阅"));
                }
                if(columns.isEmpty() || columns.size()>1600) local.add(block("COLUMN_SCOPE","字段读取为空或超过自动迁移上限"));
                if(columns.stream().map(Column::targetName).distinct().count()!=columns.size())local.add(block("COLUMN_COLLISION","字段名称折叠后冲突，需人工映射"));
                totalColumns+=columns.size();if(totalColumns>20000)throw new SQLException("Migration metadata budget exceeded","DC002");
                var keyMetadata=rows(source,"SELECT cc.COLUMN_NAME, c.STATUS, c.VALIDATED FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS cc ON c.OWNER=cc.OWNER AND c.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='P' ORDER BY cc.POSITION",cancellation,request.owner(),name);
                if(keyMetadata.stream().anyMatch(k -> !"ENABLED".equals(k.get(1)) || !"VALIDATED".equals(k.get(2))))
                    local.add(block("SOURCE_KEY_UNPROVEN","源主键未启用或未验证，不能自动建立约束并据此对账"));
                List<String> keys=keyMetadata.stream().map(r -> r.getFirst().toLowerCase(Locale.ROOT)).toList();
                long special=Long.parseLong(one(source,"SELECT COUNT(*) FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_TYPE IN ('R','U','C')",cancellation,request.owner(),name));
                if(special>0) local.add(manual("CONSTRAINTS_MANUAL","外键、唯一与检查约束需另行审阅；本次只自动建立主键和字段非空约束"));
                if(local.stream().noneMatch(f -> f.level()==Level.BLOCK))
                    rows(source,"SELECT "+columns.stream().map(c -> quote(c.sourceName())).collect(java.util.stream.Collectors.joining(","))+" FROM "+quote(request.owner())+"."+quote(name)+" WHERE 1=0",cancellation);
                List<List<String>> relation=rows(target,"SELECT c.oid::text, c.relkind::text, (has_table_privilege(c.oid,'SELECT') AND has_table_privilege(c.oid,'INSERT') AND has_table_privilege(c.oid,'UPDATE,DELETE,TRUNCATE') AND NOT c.relrowsecurity)::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?",cancellation,request.schema(),targetName);
                Long oid=null; boolean hasRows=false;
                if(!relation.isEmpty()) {
                    oid=Long.valueOf(relation.getFirst().get(0));
                    if(!relation.getFirst().get(1).equals("r") || !truth(relation.getFirst().get(2))) local.add(block("TARGET_RELATION","目标不是普通表、启用了行安全策略，或缺少读取/插入/稳定表锁权限"));
                    else {
                        hasRows=!rows(target,"SELECT 1 FROM "+quote(request.schema())+"."+quote(targetName)+" LIMIT 1",cancellation).isEmpty();
                        if(hasRows) local.add(request.mode()==MigrationRequest.Mode.EMPTY_TABLES_ONLY ? block("TARGET_NONEMPTY","目标已有数据；空表模式禁止追加或覆盖") : manual("TARGET_SKIPPED","目标已有数据，将跳过；跳过不代表迁移或对账通过"));
                        long triggers=Long.parseLong(one(target,"SELECT COUNT(*) FROM pg_trigger WHERE tgrelid=?::oid AND NOT tgisinternal",cancellation,oid.toString()));
                        if(triggers>0) local.add(block("TARGET_TRIGGERS","目标存在用户触发器，自动写入需人工审阅"));
                        if(Long.parseLong(one(target,"SELECT COUNT(*) FROM pg_rewrite WHERE ev_class=?::oid AND rulename<>'_RETURN'",cancellation,oid.toString()))>0)
                            local.add(block("TARGET_RULES","目标存在重写规则，自动写入范围需人工审阅"));
                        if(Long.parseLong(one(target,"SELECT COUNT(*) FROM pg_inherits WHERE inhrelid=?::oid OR inhparent=?::oid",cancellation,oid.toString(),oid.toString()))>0)
                            local.add(block("TARGET_INHERITANCE","目标存在继承关系，自动写入范围无法确认"));
                        if(!targetColumnsMatch(target,oid,columns,cancellation))
                            local.add(block("TARGET_COLUMNS","目标字段名称、顺序、类型不匹配，或存在生成列/身份列；请先人工映射"));
                        local.add(manual("TARGET_DEFAULTS","已有目标的默认值和序列水位不自动调整；本次显式提供所有列的数据，后续应用写入前须另行检查"));
                    }
                }
                MigrationFiles.Snapshot data=null; MigrationExportEvidence evidence=null;
                try {
                    data=MigrationFiles.inspect(request.directory(),targetName,cancellation);
                    evidence=MigrationExportEvidence.read(data.path());
                    if(evidence==null) local.add(manual("SOURCE_WINDOW_UNKNOWN","旧文件缺少源读取窗口；仅与本次选定文件对账，无法证明 Oracle 源数据一致"));
                    else if(!evidence.sourceScope().equals(MigrationExportEvidence.scope(sourceIdentity,request.owner()))
                            || !evidence.columns().equals(MigrationExportEvidence.columns(columns)) || !evidence.dataSha256().equals(data.sha256()))
                        local.add(block("SOURCE_EVIDENCE_CHANGED","源身份、字段映射、数据文件与导出证据不一致，请重新导出"));
                    else local.add(info("SOURCE_WINDOW","单表只读事务读取窗口 "+evidence.began()+" 至 "+evidence.finished()+"；无跨表共同快照"));
                }
                catch(java.io.IOException failure) { local.add(block("INPUT_UNAVAILABLE","数据文件缺失、超限、链接或正在变化；请重新导出")); }
                Table candidate=new Table(name,targetName,columns,keys,oid,hasRows,data,evidence,local);
                if(local.stream().noneMatch(f -> f.level()==Level.BLOCK)) {
                    try {
                        var stats=MigrationDataFile.read(data,candidate,cancellation,values->{ });
                        if(evidence!=null && evidence.rows()!=stats.rows())local.add(block("SOURCE_ROW_COUNT","数据文件行数与源导出证据不一致"));
                        else local.add(info("FILE_ROWS","已完整解析 "+stats.rows()+" 行数据；不会执行文件中的原始 SQL"));
                    } catch(java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                    catch(Exception invalid) { local.add(block("DATA_FORMAT","文件不是受支持的完整字面值数据格式；请重新导出或人工转换")); }
                }
                tables.add(new Table(name,targetName,columns,keys,oid,hasRows,data,evidence,local));
            }
            if(tables.stream().map(Table::targetName).distinct().count()!=tables.size()) findings.add(block("NAME_COLLISION","目标名称折叠后冲突"));
            findings.add(manual("CONSISTENCY_SCOPE","源读取与目标校验不共享快照；后续仅比较指定导出文件及目标时点的有限统计，不能证明当前源库全量一致"));
            findings.add(manual("MAPPING_SCOPE","字符列映射为 TEXT、精确数字映射为 NUMERIC；长度/精度约束、索引和其他对象需单独审阅"));
            findings.add(manual("SOURCE_VISIBILITY","仅检查当前源账号可见的对象；无法据此证明所有者全部对象均可见或全部已迁移"));
            cancellation.checkCancelled();
            return new MigrationPlan(request,sourceIdentity,targetIdentity,schema.isEmpty()?null:Long.valueOf(schema.getFirst().getFirst()),tables,findings);
        } finally { cancellation.release(target); cancellation.release(source); }
    }

    static String sourceIdentity(Connection connection,MigrationCancellation cancellation) throws SQLException {
        return fingerprint(one(connection,"SELECT SYS_CONTEXT('USERENV','DB_UNIQUE_NAME') || ':' || SYS_CONTEXT('USERENV','CON_NAME') || ':' || SYS_CONTEXT('USERENV','SESSION_USER') FROM DUAL",cancellation));
    }
    static boolean targetColumnsMatch(Connection connection,long oid,List<Column> expected,MigrationCancellation cancellation) throws SQLException {
        var actual=rows(connection,"SELECT a.attname, t.typname, a.attgenerated::text, a.attidentity::text FROM pg_attribute a JOIN pg_type t ON t.oid=a.atttypid WHERE a.attrelid=?::oid AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum",cancellation,Long.toString(oid));
        if(actual.size()!=expected.size())return false;
        for(int i=0;i<actual.size();i++) {
            var row=actual.get(i);var column=expected.get(i);
            if(!column.targetName().equals(row.getFirst()) || !row.get(2).isEmpty() || !row.get(3).isEmpty()
                    || !(column.numeric()?Set.of("numeric","int2","int4","int8"):Set.of("text","varchar","bpchar")).contains(row.get(1)))return false;
        }
        return true;
    }
    static String targetIdentity(Connection connection,MigrationCancellation cancellation) throws SQLException {
        return fingerprint(one(connection,"SELECT current_database() || ':' || current_user || ':' || COALESCE(inet_server_addr()::text,'local') || ':' || COALESCE(inet_server_port()::text,'local') || ':' || pg_postmaster_start_time()::text",cancellation));
    }
    static String fingerprint(String value) { return HexFormat.of().formatHex(MigrationFiles.digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    static boolean truth(String value) { return "t".equals(value) || "true".equalsIgnoreCase(value); }
    static boolean simpleName(String value) { return value!=null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"); }
    public static String quote(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
    static String one(Connection connection,String sql,MigrationCancellation cancellation,String... parameters) throws SQLException {
        var data=rows(connection,sql,cancellation,parameters);
        if(data.size()!=1 || data.getFirst().isEmpty() || data.getFirst().getFirst()==null) throw new SQLException("Incomplete preflight metadata","DC002");
        return data.getFirst().getFirst();
    }
    static List<List<String>> rows(Connection connection,String sql,MigrationCancellation cancellation,String... parameters) throws SQLException {
        cancellation.checkCancelled(); List<List<String>> result=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30); statement.setMaxRows(5001);
            for(int i=0;i<parameters.length;i++) statement.setString(i+1,parameters[i]);
            try(ResultSet rows=statement.executeQuery()) {
                int count=rows.getMetaData().getColumnCount();
                while(rows.next()) { cancellation.checkCancelled(); if(result.size()>=5000) throw new SQLException("Preflight metadata exceeds limit","DC002");
                    List<String> values=new ArrayList<>(); for(int i=1;i<=count;i++) values.add(rows.getString(i)); result.add(values); }
            }
        }
        cancellation.checkCancelled(); return result;
    }
    static Finding block(String code,String message) { return new Finding(Level.BLOCK,code,message); }
    static Finding manual(String code,String message) { return new Finding(Level.MANUAL,code,message); }
    static Finding info(String code,String message) { return new Finding(Level.INFO,code,message); }
}
