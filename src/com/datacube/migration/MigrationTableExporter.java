package com.datacube.migration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Exports one table from a source-only read transaction, including empty tables. */
public final class MigrationTableExporter {
    public record Exported(long rows,long bytes,MigrationExportEvidence evidence) { }
    private final MigrationCancellation cancellation;
    public MigrationTableExporter(MigrationCancellation cancellation) { this.cancellation=Objects.requireNonNull(cancellation); }
    public Exported export(Connection connection,String owner,String table,Path directory) throws SQLException,IOException {
        if(!MigrationPreflight.simpleName(owner) || !MigrationPreflight.simpleName(table))throw new IOException("Table name requires manual mapping");
        cancellation.checkCancelled(); Instant began=Instant.now();
        connection.setReadOnly(true); connection.setAutoCommit(false);
        Path temporary=null,marker=null;boolean markerOwned=false,published=false;
        try {
            try(Statement setup=connection.createStatement()){setup.setQueryTimeout(30);setup.execute("SET TRANSACTION READ ONLY");}
            String sourceIdentity=MigrationPreflight.sourceIdentity(connection,cancellation);
            List<MigrationPlan.Column> columns=new ArrayList<>();
            for(var row:MigrationPreflight.rows(connection,"SELECT COLUMN_NAME, DATA_TYPE, NULLABLE FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",cancellation,owner,table)) {
                String type=switch(row.get(1)){case "NUMBER","INTEGER" -> "NUMERIC";case "VARCHAR2","NVARCHAR2","CHAR","NCHAR" -> "TEXT";default -> "";};
                if(type.isEmpty() || !MigrationPreflight.simpleName(row.getFirst()))throw new IOException("Column type or name requires manual mapping");
                columns.add(new MigrationPlan.Column(row.getFirst(),row.getFirst().toLowerCase(Locale.ROOT),row.get(1),type,"Y".equals(row.get(2))));
            }
            if(columns.isEmpty() || columns.size()>1600)throw new IOException("Unsupported column scope");
            Path dataDirectory=directory.toAbsolutePath().normalize().resolve("data");
            MigrationFiles.checkParents(dataDirectory); Files.createDirectories(dataDirectory);
            String targetName=table.toLowerCase(Locale.ROOT); Path destination=dataDirectory.resolve(targetName+".sql");
            marker=MigrationExportEvidence.pending(destination);
            if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS))throw new IOException("Previous export publication incomplete; use a new export directory");
            MigrationFiles.Snapshot old=Files.exists(destination,LinkOption.NOFOLLOW_LINKS)?MigrationFiles.inspect(directory,targetName,cancellation):null;
            temporary=Files.createTempFile(dataDirectory,".export-",".part");
            long rows=0,bytes=0;
            String projection=columns.stream().map(c -> MigrationPreflight.quote(c.sourceName())).collect(java.util.stream.Collectors.joining(","));
            try(Statement statement=connection.createStatement();BufferedWriter writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)) {
                statement.setFetchSize(2000);statement.setQueryTimeout(600);
                try(ResultSet result=statement.executeQuery("SELECT "+projection+" FROM "+MigrationPreflight.quote(owner)+"."+MigrationPreflight.quote(table))) {
                    while(result.next()) {
                        cancellation.checkCancelled(); String line=MigrationDataFile.format(targetName,columns,MigrationDataFile.jdbcRow(result,columns));
                        bytes+=line.getBytes(StandardCharsets.UTF_8).length+1;
                        if(bytes>MigrationFiles.MAX_FILE_BYTES)throw new IOException("Export exceeds per-table limit");
                        writer.write(line);writer.write('\n');rows++;
                    }
                }
            }
            Instant finished=Instant.now(); cancellation.checkCancelled();
            // Persist before either file is published, so a missing proof can never look like a legacy export.
            Files.writeString(marker,"Export publication in progress",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);markerOwned=true;
            try(var channel=java.nio.channels.FileChannel.open(marker,StandardOpenOption.WRITE)){channel.force(true);}
            if(old!=null)MigrationFiles.verify(old,cancellation);
            else if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Export destination appeared during read");
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            MigrationFiles.checkParents(destination);
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); temporary=null;published=true;
            MigrationFiles.Snapshot snapshot=MigrationFiles.inspect(directory,targetName,cancellation);
            var evidence=new MigrationExportEvidence(MigrationExportEvidence.scope(sourceIdentity,owner),MigrationExportEvidence.columns(columns),snapshot.sha256(),rows,began,finished);
            evidence.write(destination);
            Files.delete(marker);markerOwned=false;
            return new Exported(rows,bytes,evidence);
        } finally {
            try { if(temporary!=null)Files.deleteIfExists(temporary);if(markerOwned && !published)Files.deleteIfExists(marker); }
            finally { connection.rollback(); }
        }
    }
}
