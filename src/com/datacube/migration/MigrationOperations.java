package com.datacube.migration;

import com.datacube.core.MigrationLogger;
import java.sql.*;
import java.util.Objects;

/** Shared GUI/CLI orchestration. Preflight and exports never create target objects. */
public class MigrationOperations {
    protected final MigrationLogger logger;
    protected final MigrationConnections connections;
    public MigrationOperations(MigrationLogger logger) { this(logger,MigrationConnections::connect); }
    public MigrationOperations(MigrationLogger logger,MigrationConnections connections) {
        this.logger=Objects.requireNonNull(logger);this.connections=Objects.requireNonNull(connections);
    }
    public MigrationPlan prepare(MigrationRequest request,MigrationCancellation cancellation) throws Exception {
        return new MigrationPreflight(connections).inspect(request,cancellation);
    }
    public void test(MigrationRequest request,MigrationCancellation cancellation) throws Exception {
        for(var endpoint:new MigrationRequest.Endpoint[]{request.source(),request.target()}) {
            Connection connection=null;
            try { cancellation.checkCancelled();connection=cancellation.register(connections.open(endpoint.url(),endpoint.user(),endpoint.password()));connection.setReadOnly(true); }
            finally { cancellation.release(connection); }
        }
        logger.logInfo("连接检查完成；没有创建 Schema 或写入数据");
    }
    public void export(MigrationRequest request,MigrationCancellation cancellation,int concurrency,boolean data) throws Exception {
        if(!request.directory().getFileName().toString().equals(request.schema()))throw new IllegalArgumentException("Export directory must match reviewed schema");
        Connection source=null;
        try {
            cancellation.checkCancelled();source=cancellation.register(connections.open(request.source().url(),request.source().user(),request.source().password()));source.setReadOnly(true);
            var exporter=new OracleExporter(logger,cancellation,request.directory().getParent(),connections);
            exporter.setMaxConcurrency(concurrency);exporter.setConvertBool(request.convertBoolean());
            if(data)exporter.exportData(source,request.source().url(),request.source().user(),request.source().password(),request.owner(),request.schema());
            else exporter.exportDDL(source,request.owner(),request.schema());
            cancellation.checkCancelled();
        } finally { cancellation.release(source); }
    }
    public PgVerifier.Statistics statistics(MigrationRequest request,MigrationCancellation cancellation) throws Exception {
        return new PgVerifier(logger,cancellation,connections).verify(request.target().url(),request.target().user(),request.target().password(),request.schema());
    }
    public MigrationRun execute(MigrationPlan plan,MigrationPlan.Approval approval,MigrationRequest request,MigrationCancellation cancellation) throws Exception {
        return new PgImporter(logger,cancellation,connections,run -> MigrationReport.of(run).checkpoint(MigrationReport.defaultDirectory())).importPrepared(plan,approval,request);
    }
}
