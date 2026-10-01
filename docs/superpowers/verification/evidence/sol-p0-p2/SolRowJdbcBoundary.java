

import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic driver: real JdbcDataEditor statements/transactions, never a real connection. */
public final class SolRowJdbcBoundary {
    public final AtomicInteger opens=new AtomicInteger(), executes=new AtomicInteger(), commits=new AtomicInteger(),
            rollbacks=new AtomicInteger(), closes=new AtomicInteger();
    public final List<String> sql=new ArrayList<>();
    public final List<Map<Integer,Object>> bindings=new ArrayList<>();
    public final List<Boolean> autoModes=new ArrayList<>();
    public final Deque<Integer> counts=new ArrayDeque<>();
    public final Deque<String> faults=new ArrayDeque<>();
    public Runnable onExecute=()->{},onCommit=()->{};
    public List<EditableColumn> columns=List.of(
            new EditableColumn("id",Types.INTEGER,"int",false,true,false,true,null),
            new EditableColumn("name",Types.VARCHAR,"varchar",true,false,false,true,null));
    public final SqlDialect dialect=proxy(SqlDialect.class,(p,m,a)->switch(m.getName()){
        case "quoteIdentifier" -> "\""+a[0].toString().replace("\"","\"\"")+"\"";
        case "columnComments" -> Map.of();
        default -> throw new AssertionError(m.getName());
    });
    public Connection open() {
        opens.incrementAndGet();
        String fault=faults.isEmpty()?"":faults.removeFirst();
        int count=counts.isEmpty()?1:counts.removeFirst();
        boolean[] state={true,false};
        return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit" -> state[0];
            case "setAutoCommit" -> {autoModes.add((boolean)a[0]);if((boolean)a[0]&&fault.contains("restore"))throw failure();state[0]=(boolean)a[0];yield null;}
            case "isClosed" -> state[1];
            case "isValid" -> !state[1];
            case "setReadOnly" -> null;
            case "close" -> {state[1]=true;closes.incrementAndGet();if(fault.contains("close"))throw failure();yield null;}
            case "commit" -> {commits.incrementAndGet();onCommit.run();if(fault.contains("commit"))throw failure();yield null;}
            case "rollback" -> {rollbacks.incrementAndGet();if(fault.contains("rollback"))throw failure();yield null;}
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
                sql.add((String)a[0]);Map<Integer,Object> values=new LinkedHashMap<>();bindings.add(values);
                yield proxy(PreparedStatement.class,(sp,sm,sa)->switch(sm.getName()){
                    case "setObject" -> {values.put((int)sa[0],sa[1]);yield null;}
                    case "setNull" -> {values.put((int)sa[0],null);yield null;}
                    case "executeUpdate" -> {executes.incrementAndGet();onExecute.run();if(fault.contains("execute"))throw failure();yield count;}
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
    private static SQLException failure(){return new SQLException("synthetic-secret-value must not appear in save report","23505");}
    public static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class)return switch(m.getName()){
                case "toString" -> "SyntheticJdbc";case "hashCode" -> System.identityHashCode(p);case "equals" -> p==a[0];default -> null;
            };
            return handler.invoke(p,m,a);
        }));
    }
}
