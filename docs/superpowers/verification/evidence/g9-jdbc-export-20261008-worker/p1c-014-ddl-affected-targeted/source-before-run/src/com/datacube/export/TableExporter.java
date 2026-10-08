package com.datacube.export;

import com.datacube.service.ConnectionManager;
import com.datacube.service.ExportTarget;
import com.datacube.spi.model.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pinned table export, dedicated single cursor, strict complete values and protected publication. */
public final class TableExporter {
    private TableExporter(){}
    public record Request(String connId,TableRef table,ExportContent content,ExportFormat format,
                          SafeResultFilePublisher.Target target,ExportTarget source){
        public Request(String connId,TableRef table,ExportContent content,ExportFormat format,SafeResultFilePublisher.Target target){
            this(connId,table,content,format,target,null);
        }
        public Request{Objects.requireNonNull(connId);Objects.requireNonNull(table);Objects.requireNonNull(content);Objects.requireNonNull(format);Objects.requireNonNull(target);}
        @Override public String toString(){return "TableExportRequest["+format+"]";}
    }
    /** Owns the selection-time subscription, even when no background producer is eventually submitted. */
    public static final class Selection implements AutoCloseable {
        private final ExportTarget snapshot;
        private final ResultExportOperation operation;
        private final AutoCloseable listener;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Selection(ConnectionManager manager,String id){
            snapshot=manager.captureExport(id);operation=new ResultExportOperation();
            listener=snapshot.whenChanged(operation::invalidateTarget);
            operation.requireBeforeClaim(this::validate);
        }
        public ExportTarget snapshot(){return snapshot;}
        public ResultExportOperation operation(){return operation;}
        public void validate(){operation.check();if(!snapshot.current())operation.invalidateTarget();operation.check();}
        public void close(){if(closed.compareAndSet(false,true))try{listener.close();}catch(Exception failure){throw new IllegalStateException("Export listener release failed");}}
        @Override public String toString(){return "TableExportSelection[redacted]";}
    }
    public static Selection capture(ConnectionManager manager,String id){return new Selection(manager,id);}
    @FunctionalInterface interface DumpJob{void run(ConnConfig cfg,String password,TableRef table,ExportContent content,File temporary)throws Exception;}
    @FunctionalInterface interface OutputFactory{OutputStream open(Path temporary)throws Exception;}
    public static void export(ConnectionManager manager,String id,TableRef table,ExportContent content,ExportFormat format,File out)throws Exception{
        export(manager,new Request(id,table,content,format,SafeResultFilePublisher.capture(out.toPath())),new ResultExportOperation());
    }
    public static Path export(ConnectionManager manager,Request request,ResultExportOperation operation)throws Exception{
        return export(manager,request,operation,new SafeResultFilePublisher(),
                (cfg,password,table,content,out)->PgDumpRunner.run(cfg,password,table,content,out,operation),Files::newOutputStream);
    }
    static Path export(ConnectionManager manager,Request request,ResultExportOperation operation,SafeResultFilePublisher publisher,DumpJob dump,OutputFactory output)throws Exception{
        return export(manager,request,operation,publisher,dump,output,TableExportJdbcJob.Policy.defaults());
    }
    static Path export(ConnectionManager manager,Request request,ResultExportOperation operation,SafeResultFilePublisher publisher,DumpJob dump,OutputFactory output,TableExportJdbcJob.Policy policy)throws Exception{
        operation.check();
        ExportTarget snapshot=request.source()==null?manager.captureExport(request.connId()):request.source();
        if(!snapshot.belongsTo(manager)||!snapshot.config().id().equals(request.connId()))
            throw new TableExportFailure(TableExportFailure.Kind.TARGET_CHANGED);
        AutoCloseable listener=snapshot.whenChanged(operation::invalidateTarget);
        try{
            Runnable validate=()->{operation.check();if(!snapshot.current())operation.invalidateTarget();operation.check();};
            operation.requireBeforeClaim(validate);validate.run();
            TableRef table=request.table();
            if(table.schema()==null||table.schema().isEmpty()||table.name()==null||table.name().isEmpty()
                    ||table.schema().indexOf('\0')>=0||table.name().indexOf('\0')>=0)throw new TableExportFailure(TableExportFailure.Kind.TARGET_CHANGED);
            return publisher.publish(request.target(),operation,(temporary,token)->{
                validate.run();
                if(request.format()==ExportFormat.PG_DUMP){
                    ConnConfig cfg=snapshot.config();String password=manager.cipher().decrypt(cfg.encryptedPassword());validate.run();
                    dump.run(cfg,password,table,request.content(),temporary.toFile());validate.run();return;
                }
                var job=new TableExportJdbcJob(snapshot,operation,policy);
                job.run(manager,()->{
                    var dialect=snapshot.provider().dialect();job.check();
                    String ddl=request.format()==ExportFormat.SQL&&request.content().includesStructure()?StrictTableDdl.read(job,table,dialect):null;
                    boolean data=request.format()==ExportFormat.XLSX||request.content().includesData();
                    if(!data){job.check();OutputStream stream=job.output(output.open(temporary));job.check();SqlScriptExporter.write(stream,table,request.content(),ddl,List.of(),sink->{},dialect);return null;}
                    String sql="SELECT * FROM "+StrictTableDdl.qualified(table,dialect);
                    try(var cursor=job.openCursor(sql,null)){
                        job.check();ResultSetMetaData metadata=cursor.rows.getMetaData();job.check();
                        List<String> columns=TableExportValues.columns(metadata,request.format(),job);
                        RowFeed feed=sink->{long count=0;while(StrictTableDdl.next(cursor.rows,job)){
                            TableExportValues.rowNumber(++count,request.format());
                            List<Object> values=TableExportValues.row(cursor.rows,metadata,columns.size(),request.format(),snapshot.config().type(),dialect,job);
                            job.check();sink.row(values);job.check();
                        }};
                        job.check();OutputStream stream=job.output(output.open(temporary));job.check();
                        if(request.format()==ExportFormat.XLSX)XlsxWriter.write(stream,columns,feed);
                        else SqlScriptExporter.write(stream,table,request.content(),ddl,columns,feed,dialect);
                    }
                    return null;
                });
                validate.run();
            });
        }catch(CancellationException cancelled){operation.check();throw cancelled;}
        finally{listener.close();}
    }
}
