package com.datacube.export;

import com.datacube.spi.SqlDialect;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;
import java.sql.*;
import java.util.*;

/** Strict table-only structure reader; legacy display/migration generators keep their own semantics. */
final class StrictTableDdl {
    static final String COLUMNS="SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, column_default, "
            +"numeric_precision_radix, is_identity, identity_generation, is_generated, generation_expression, "
            +"domain_catalog, domain_schema, domain_name, collation_catalog, collation_schema, collation_name, "
            +"datetime_precision, interval_type, interval_precision "
            +"FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
    private static final Set<String> SIMPLE_TYPES=Set.of("boolean","smallint","integer","bigint","real","double precision",
            "numeric","character","character varying","text","bytea","date","uuid","json","jsonb");
    static final String PRIMARY_KEY="SELECT tc.constraint_name, kcu.column_name FROM information_schema.table_constraints tc "
            +"JOIN information_schema.key_column_usage kcu ON tc.constraint_catalog = kcu.constraint_catalog "
            +"AND tc.constraint_schema = kcu.constraint_schema AND tc.constraint_name = kcu.constraint_name "
            +"AND tc.table_catalog = kcu.table_catalog AND tc.table_schema = kcu.table_schema AND tc.table_name = kcu.table_name "
            +"WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ? AND tc.table_name = ? ORDER BY kcu.ordinal_position";
    private StrictTableDdl(){}
    static String read(TableExportJdbcJob job,TableRef table,SqlDialect dialect)throws Exception{
        try{
            String ddl;
            if(job.source.config().type()==DbType.ORACLE){
                ddl=job.query("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL",List.of("TABLE",table.name(),table.schema()),rows->{
                    job.check();if(!rows.next())throw structure();job.check();
                    Clob lob=rows.getClob(1);if(lob==null)throw structure();
                    var lease=job.ownValue("ddl-clob",()->{lob.free();return null;});
                    try{job.check();String result=TableExportValues.text(lob.getCharacterStream(),TableExportValues.DDL_BYTES,TableExportValues.DDL_BYTES,job);
                        job.check();if(rows.next())throw structure();job.check();return result;}
                    catch(Error error){job.fatal.compareAndSet(null,error);throw error;}
                    finally{job.closeValue(lease);}
                });
            }else if(job.source.config().type()==DbType.POSTGRESQL){
                var budget=new TableExportValues.TextBudget(TableExportValues.DDL_BYTES,TableExportValues.DDL_BYTES);
                var out=new StringBuilder();append(out,budget,"CREATE TABLE "+qualified(table,dialect)+" (\n");
                int[] columns={0};
                job.query(COLUMNS,List.of(table.schema(),table.name()),rows->{
                    while(next(rows,job)){
                        if(++columns[0]>TableExportValues.XLSX_COLUMNS)throw TableExportValues.limit();
                        if(columns[0]>1)append(out,budget,",\n");
                        String name=text(rows,"column_name",job),type=text(rows,"data_type",job);
                        if(name==null||name.isEmpty()||name.indexOf('\0')>=0||type==null||!SIMPLE_TYPES.contains(type))throw structure();
                        String definition=simpleType(rows,type,job);
                        String nullable=text(rows,"is_nullable",job);
                        if(!"YES".equals(nullable)&&!"NO".equals(nullable))throw structure();
                        String defaultValue=text(rows,"column_default",job);
                        if(defaultValue!=null&&defaultValue.isBlank())throw structure();
                        append(out,budget,"    "+dialect.quoteIdentifier(name)+" "+definition);
                        if("NO".equals(nullable))append(out,budget," NOT NULL");
                        if(defaultValue!=null)append(out,budget," DEFAULT "+defaultValue);
                    }return null;
                });
                if(columns[0]==0)throw structure();
                String[] constraint={null};int[] keys={0};var pk=new StringBuilder();
                var pkBudget=new TableExportValues.TextBudget(TableExportValues.DDL_BYTES,TableExportValues.DDL_BYTES);
                job.query(PRIMARY_KEY,List.of(table.schema(),table.name()),rows->{
                    while(next(rows,job)){
                        String name=text(rows,"constraint_name",job),column=text(rows,"column_name",job);
                        if(name==null||column==null||++keys[0]>columns[0]||constraint[0]!=null&&!constraint[0].equals(name))throw structure();
                        constraint[0]=name;if(keys[0]>1)append(pk,pkBudget,", ");append(pk,pkBudget,dialect.quoteIdentifier(column));
                    }return null;
                });
                if(keys[0]>0)append(out,budget,",\n    CONSTRAINT "+dialect.quoteIdentifier(constraint[0])+" PRIMARY KEY ("+pk+")");
                append(out,budget,"\n);");budget.finish();ddl=out.toString();
            }else throw structure();
            if(ddl==null||ddl.isBlank()||ddl.stripLeading().startsWith("--"))throw structure();
            TableExportValues.textBytes(ddl,TableExportValues.DDL_BYTES,TableExportValues.DDL_BYTES);job.check();return ddl;
        }catch(TableExportFailure safe){throw safe;}
        catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(SQLException|RuntimeException error){throw structure();}
    }
    static TableExportFailure structure(){return new TableExportFailure(TableExportFailure.Kind.STRUCTURE);}
    /** Unsupported column semantics are refused, never flattened into the underlying built-in type. */
    private static String simpleType(ResultSet rows,String type,TableExportJdbcJob job)throws Exception{
        if(!"NO".equals(text(rows,"is_identity",job))||!"NEVER".equals(text(rows,"is_generated",job)))throw structure();
        for(String field:List.of("identity_generation","generation_expression","domain_catalog","domain_schema","domain_name",
                "collation_catalog","collation_schema","collation_name","interval_type")){
            if(text(rows,field,job)!=null)throw structure();
        }
        if(number(rows,"interval_precision",job)!=null)throw structure();
        Long datetime=number(rows,"datetime_precision",job);
        if(type.equals("date")?!Long.valueOf(0).equals(datetime):datetime!=null)throw structure();
        Long length=number(rows,"character_maximum_length",job),precision=number(rows,"numeric_precision",job),
                scale=number(rows,"numeric_scale",job),radix=number(rows,"numeric_precision_radix",job);
        if(type.equals("character")||type.equals("character varying")){
            if(length==null){if(type.equals("character"))throw structure();return type;}
            if(length<1||length>10_485_760)throw structure();
            return type+"("+length+")";
        }
        if(length!=null)throw structure();
        if(type.equals("numeric")){
            if(!Long.valueOf(10).equals(radix))throw structure();
            if(precision==null){if(scale!=null)throw structure();return type;}
            if(precision<1||precision>1000||scale==null||scale<-1000||scale>1000)throw structure();
            return type+"("+precision+","+scale+")";
        }
        return type; // Other admitted types have no configurable typmod in this contract.
    }
    static String qualified(TableRef table,SqlDialect dialect){return dialect.quoteIdentifier(table.schema())+"."+dialect.quoteIdentifier(table.name());}
    static boolean next(ResultSet rows,TableExportJdbcJob job)throws Exception{job.check();boolean next=rows.next();job.check();return next;}
    static String text(ResultSet rows,String label,TableExportJdbcJob job)throws Exception{
        job.check();String value=rows.getString(label);job.check();if(value!=null)TableExportValues.textBytes(value,TableExportValues.VALUE_BYTES,TableExportValues.VALUE_BYTES);return value;
    }
    static Long number(ResultSet rows,String label,TableExportJdbcJob job)throws Exception{
        job.check();long value=rows.getLong(label);job.check();boolean absent=rows.wasNull();job.check();return absent?null:value;
    }
    static void append(StringBuilder out,TableExportValues.TextBudget budget,String text){
        for(int i=0;i<text.length();i++)budget.add(text.charAt(i));out.append(text);
    }
}
