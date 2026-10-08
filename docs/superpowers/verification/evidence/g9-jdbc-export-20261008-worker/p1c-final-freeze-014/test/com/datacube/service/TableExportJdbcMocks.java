package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import com.datacube.provider.postgres.*;
import com.datacube.provider.oracle.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic JDBC only; every connection and statement belongs to this fixture. */
public final class TableExportJdbcMocks {
    public final ConnectionManager manager;
    public final List<Owner> owners = new CopyOnWriteArrayList<>();
    public final List<String> queries = new CopyOnWriteArrayList<>();
    public final AtomicInteger pages = new AtomicInteger(), executions = new AtomicInteger(), getters = new AtomicInteger();
    public volatile int rows = 501;
    public volatile Object value = "complete value";
    public volatile int sqlType = Types.VARCHAR;
    public volatile String label = "value", typeName = "synthetic";
    public volatile int columnCount=1;
    public volatile List<Map<String,Object>> ddlColumns=List.of(basicColumn("text"));
    public volatile List<Map<String,Object>> ddlKeys=List.of();
    public volatile String failure = "";
    public volatile Runnable beforeOpen=()->{}, beforeExecute=()->{}, beforeNext=()->{}, beforeGet=()->{}, beforeClose=()->{}, beforeRollback=()->{};
    public volatile Runnable beforeCancel=()->{}, beforeStatementClose=()->{}, beforeResultClose=()->{}, beforeSetup=()->{};
    public volatile java.util.function.Consumer<String> beforeStatement=ignored->{};
    public volatile java.util.function.Consumer<String> beforeCatalogGet=ignored->{};
    public volatile String catalogFailureLabel="";
    public final DbType type;
    public volatile DatabaseProvider provider;
    public TableExportJdbcMocks() { this(DbType.POSTGRESQL); }
    public TableExportJdbcMocks(DbType type) {
        this.type=type;
        ConnectionFactory factory=new ConnectionFactory(){
            public void ensureDriverLoaded(){}
            public Connection open(ConnConfig config) throws SQLException {
                beforeOpen.run(); fail("open"); var owner=new Owner(config); owners.add(owner); return owner.connection;
            }
            public String test(ConnConfig config){throw new AssertionError("No database probe");}
        };
        provider=(DatabaseProvider)Proxy.newProxyInstance(DatabaseProvider.class.getClassLoader(),new Class<?>[]{DatabaseProvider.class},(proxy,method,args)->switch(method.getName()){
            case "type" -> type;
            case "connectionFactory" -> factory;
            case "dialect" -> type==DbType.ORACLE ? new OracleSqlDialect() : new PgSqlDialect();
            case "ddlGenerator" -> type==DbType.ORACLE ? new OracleDdlGenerator((Connection)args[0]) : new PgDdlGenerator((Connection)args[0]);
            case "dataAccessor" -> new DataAccessor(){
                public PagedResult page(TableRef table,long offset,int limit,List<SortKey> sorts,String filter){
                    pages.incrementAndGet(); List<List<Object>> result=new ArrayList<>();
                    for(long i=offset;i<Math.min(rows,offset+limit);i++)result.add(Collections.singletonList(value));
                    return new PagedResult(List.of("value"),result,offset+limit<rows);
                }
                public long count(TableRef table,String filter){throw new AssertionError("No count");}
            };
            default -> throw new UnsupportedOperationException(method.getName());
        });
        manager=new ConnectionManager(new CredentialCipher(),ignored->provider);
        manager.register(config("synthetic"));
    }
    public ConnConfig config(String database){return new ConnConfig("synthetic","synthetic",type,"synthetic.invalid",1,database,"synthetic","",Map.of("readOnly","true","environment","production"));}
    public static Map<String,Object> basicColumn(String type){
        var column=new HashMap<String,Object>();column.put("column_name","value");column.put("data_type",type);
        column.put("is_nullable","YES");column.put("is_identity","NO");column.put("is_generated","NEVER");
        if(type.equals("numeric"))column.put("numeric_precision_radix",10L);
        if(type.equals("date"))column.put("datetime_precision",0L);
        if(type.equals("character"))column.put("character_maximum_length",1L);
        return column;
    }
    public int exportSubscriptions()throws Exception{
        var field=ConnectionManager.class.getDeclaredField("exportListeners");field.setAccessible(true);
        synchronized(manager){return ((Map<?,? extends Set<?>>)field.get(manager)).values().stream().mapToInt(Set::size).sum();}
    }
    void fail(String stage)throws SQLException{if(failure.equals(stage))throw new SQLException("SYNTHETIC_PRIVATE_JDBC_"+stage);}
    public final class Owner {
        public final ConnConfig opened;
        public final Connection connection;
        public final List<Cursor> cursors=new CopyOnWriteArrayList<>();
        public final List<Stmt> statements=new CopyOnWriteArrayList<>();
        public final AtomicInteger closes=new AtomicInteger(),rollbacks=new AtomicInteger();
        public boolean readOnly,autoCommit=true,closed;
        public int isolation;
        Owner(ConnConfig config){
            opened=config;
            connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->switch(method.getName()){
                case "setReadOnly" -> {beforeSetup.run();fail("setup");readOnly=(boolean)args[0];yield null;}
                case "isReadOnly" -> readOnly;
                case "setAutoCommit" -> {fail("setup");autoCommit=(boolean)args[0];yield null;}
                case "getAutoCommit" -> autoCommit;
                case "setTransactionIsolation" -> {fail("setup");isolation=(int)args[0];yield null;}
                case "getTransactionIsolation" -> isolation;
                case "createStatement","prepareStatement" -> {
                    if(method.getName().equals("prepareStatement")&&failure.equals("ddl"))fail("ddl");
                    String query=method.getName().equals("prepareStatement")?(String)args[0]:null;
                    int start=query==null?0:1;
                    int mode=args!=null&&args.length>start?(int)args[start]:ResultSet.TYPE_FORWARD_ONLY;
                    int concurrency=args!=null&&args.length>start+1?(int)args[start+1]:ResultSet.CONCUR_READ_ONLY;
                    var statement=new Stmt(this,query,mode,concurrency);statements.add(statement);yield statement.statement;
                }
                case "rollback" -> {rollbacks.incrementAndGet();beforeRollback.run();fail("rollback");yield null;}
                case "close" -> {
                    closes.incrementAndGet();beforeClose.run();fail("conn-close");closed=true;
                    statements.forEach(s->{s.closed=true;if(s.cursor!=null)s.cursor.closed=true;});yield null;
                }
                case "isClosed" -> closed;
                case "isValid" -> !closed;
                case "toString" -> "SyntheticOwnedConnection";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }
    public final class Stmt {
        public final Owner owner;
        public final Statement statement;
        public final String prepared;
        public final int mode,concurrency;
        public int fetchSize,timeout;
        public boolean closed;
        public Cursor cursor;
        public final AtomicInteger closes=new AtomicInteger(),cancels=new AtomicInteger();
        public final Map<Integer,String> bindings=new LinkedHashMap<>();
        Stmt(Owner owner,String query,int mode,int concurrency){
            this.owner=owner;prepared=query;this.mode=mode;this.concurrency=concurrency;
            Class<?> api=query==null?Statement.class:PreparedStatement.class;
            statement=(Statement)Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->switch(method.getName()){
                case "setFetchSize" -> {fetchSize=(int)args[0];yield null;}
                case "setQueryTimeout" -> {timeout=(int)args[0];yield null;}
                case "setString" -> {bindings.put((int)args[0],(String)args[1]);yield null;}
                case "executeQuery" -> {
                    beforeExecute.run();fail("execute");String sql=prepared==null?(String)args[0]:prepared;
                    queries.add(sql);executions.incrementAndGet();int snapshotRows=rows;Object snapshotValue=value;beforeStatement.accept(sql);
                    List<Map<String,Object>> catalog=sql.contains("information_schema.columns")?ddlColumns:sql.contains("table_constraints")?ddlKeys:null;
                    cursor=new Cursor(owner,catalog==null?snapshotRows:catalog.size(),snapshotValue,catalog);owner.cursors.add(cursor);yield cursor.result;
                }
                case "execute" -> {beforeExecute.run();fail("setup");queries.add((String)args[0]);yield false;}
                case "cancel" -> {cancels.incrementAndGet();beforeCancel.run();fail("cancel");yield null;}
                case "close" -> {closes.incrementAndGet();beforeStatementClose.run();fail("stmt-close");closed=true;if(cursor!=null)cursor.closed=true;yield null;}
                case "isClosed" -> closed;
                case "getConnection" -> owner.connection;
                case "toString" -> "SyntheticOwnedStatement";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }
    public final class Cursor {
        public final ResultSet result;
        public final Owner owner;
        public final int count;
        public final Object captured;
        public int position; boolean lastNull;
        public boolean closed;
        public final AtomicInteger closes=new AtomicInteger();
        Cursor(Owner owner,int count,Object captured,List<Map<String,Object>> catalog){
            this.owner=owner;this.count=count;this.captured=captured;
            var metadata=(ResultSetMetaData)Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),new Class<?>[]{ResultSetMetaData.class},(p,m,a)->{
                fail("metadata");return switch(m.getName()){
                    case "getColumnCount"->columnCount;case "getColumnLabel","getColumnName"->label;
                    case "getColumnType"->sqlType;case "getColumnTypeName"->typeName;
                    default->throw new UnsupportedOperationException(m.getName());};
            });
            result=(ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args)->switch(method.getName()){
                case "next" -> {beforeNext.run();fail("next");yield ++position<=count;}
                case "getMetaData" -> metadata;
                case "getObject","getClob","getNClob","getBlob","getSQLXML","getCharacterStream","getBinaryStream" -> {beforeGet.run();fail("getter");getters.incrementAndGet();yield captured;}
                case "getString","getLong" -> {
                    beforeGet.run();fail("getter");String field=(String)args[0];beforeCatalogGet.accept(field);
                    if(field.equals(catalogFailureLabel))throw new SQLException("SYNTHETIC_PRIVATE_CATALOG");
                    Object v=catalog.get(position-1).get(field);lastNull=v==null;
                    yield method.getName().equals("getString")?v:v==null?0L:((Number)v).longValue();
                }
                case "wasNull" -> lastNull;
                case "close" -> {closes.incrementAndGet();beforeResultClose.run();fail("rs-close");closed=true;yield null;}
                case "isClosed" -> closed;
                case "toString" -> "SyntheticOwnedCursor";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }
}
