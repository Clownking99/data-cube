package com.datacube.fx;
import java.util.concurrent.*;

import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic JDBC only. Trace lists are copy-on-write; per-statement bindings use synchronized maps. */
public final class ShellGridJdbcProbe {
    public final AtomicInteger opens=new AtomicInteger(), executes=new AtomicInteger(), commits=new AtomicInteger(),
            rollbacks=new AtomicInteger(), closes=new AtomicInteger();
    public final List<String> sql=new CopyOnWriteArrayList<>();
    public final List<Map<Integer,Object>> bindings=new CopyOnWriteArrayList<>();
    public final List<Boolean> autoModes=new CopyOnWriteArrayList<>();
    public Runnable onExecute=()->{};
    public final List<String> trace=new CopyOnWriteArrayList<>();
    public final List<Integer> executedRows=new CopyOnWriteArrayList<>(),committedRows=new CopyOnWriteArrayList<>(),rolledBackRows=new CopyOnWriteArrayList<>();
    public final List<AtomicInteger> connectionCloses=new CopyOnWriteArrayList<>();
    public List<EditableColumn> columns=List.of(
            new EditableColumn("id",Types.INTEGER,"int",false,true,false,true,null),
            new EditableColumn("name",Types.VARCHAR,"varchar",true,false,false,true,null));
    public final SqlDialect dialect=proxy(SqlDialect.class,(p,m,a)->switch(m.getName()){
        case "quoteIdentifier" -> "\""+a[0].toString().replace("\"","\"\"")+"\"";
        case "columnComments" -> Map.of();
        default -> throw new AssertionError(m.getName());
    });
    public Connection open() {
        int connectionId=opens.incrementAndGet(); AtomicInteger closeCount=new AtomicInteger(); connectionCloses.add(closeCount); trace.add("open:"+connectionId); int[] row={0};
        boolean[] state={true,false};
        return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit" -> state[0];
            case "setAutoCommit" -> {autoModes.add((boolean)a[0]);state[0]=(boolean)a[0];yield null;}
            case "isClosed" -> state[1];
            case "isValid" -> !state[1];
            case "setReadOnly" -> null;
            case "close" -> {if(!state[1]) {state[1]=true;closes.incrementAndGet();} closeCount.incrementAndGet();trace.add("close:"+connectionId+":row="+row[0]);yield null;}
            case "commit" -> {commits.incrementAndGet();committedRows.add(row[0]);trace.add("commit:"+row[0]);yield null;}
            case "rollback" -> {rollbacks.incrementAndGet();rolledBackRows.add(row[0]);trace.add("rollback:"+row[0]);yield null;}
            case "getMetaData" -> proxy(DatabaseMetaData.class,(dp,dm,da)-> {
                if(!dm.getName().equals("getPrimaryKeys"))throw new AssertionError(dm.getName());
                List<String> names=columns.stream().filter(EditableColumn::primaryKey).map(EditableColumn::name).toList();int[] index={-1};
                return proxy(ResultSet.class,(rp,rm,ra)->switch(rm.getName()){
                    case "next" -> ++index[0]<names.size();case "getString" -> names.get(index[0]);case "close" -> null;
                    default -> throw new AssertionError(rm.getName());
                });
            });
            case "createStatement" -> proxy(Statement.class,(sp,sm,sa)->switch(sm.getName()){
                case "executeQuery" -> metadata();case "close" -> null;default -> throw new AssertionError(sm.getName());
            });
            case "prepareStatement" -> {
                sql.add((String)a[0]);Map<Integer,Object> values=Collections.synchronizedMap(new LinkedHashMap<>());bindings.add(values);
                yield proxy(PreparedStatement.class,(sp,sm,sa)->switch(sm.getName()){
                    case "setObject" -> {values.put((int)sa[0],sa[1]);yield null;}
                    case "setNull" -> {values.put((int)sa[0],null);yield null;}
                    case "executeUpdate" -> {row[0]=((Number)values.get(2)).intValue();executedRows.add(row[0]);trace.add("execute:"+row[0]);executes.incrementAndGet();onExecute.run();yield 1;}
                    case "close" -> null;default -> throw new AssertionError(sm.getName());
                });
            }
            default -> throw new AssertionError(m.getName());
        });
    }
    private ResultSet metadata() {
        return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "close" -> null;
            case "getMetaData" -> proxy(ResultSetMetaData.class,(mp,mm,ma)->{
                if(mm.getName().equals("getColumnCount"))return columns.size();
                EditableColumn c=columns.get((int)ma[0]-1);
                return switch(mm.getName()){
                    case "getColumnLabel" -> c.name();case "getColumnType" -> c.jdbcType();case "getColumnTypeName" -> c.typeName();
                    case "isNullable" -> c.nullable()?ResultSetMetaData.columnNullable:ResultSetMetaData.columnNoNulls;
                    case "isAutoIncrement" -> c.autoIncrement();case "isReadOnly" -> !c.editable();
                    default -> throw new AssertionError(mm.getName());
                };
            });
            default -> throw new AssertionError(m.getName());
        });
    }
    public static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class)return switch(m.getName()){
                case "toString" -> "SyntheticJdbc";case "hashCode" -> System.identityHashCode(p);case "equals" -> p==a[0];default -> null;
            };
            return handler.invoke(p,m,a);
        }));
    }
}
