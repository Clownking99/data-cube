package com.datacube.migration;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Redacted versioned checkpoint. No endpoint, object name, SQL, credential, exception or row value. */
public record MigrationReport(UUID id,Instant began,List<MigrationRun.Outcome> outcomes,boolean persistenceFailed,UUID previousRun,Map<Integer,Integer> previousOrdinals) {
    private static final int MAGIC=0x44434D32,MAX_BYTES=1024*1024;
    public MigrationReport { outcomes=List.copyOf(outcomes);previousOrdinals=Map.copyOf(previousOrdinals); }
    public MigrationReport(UUID id,Instant began,List<MigrationRun.Outcome> outcomes,boolean persistenceFailed) {this(id,began,outcomes,persistenceFailed,null,Map.of());}
    public static MigrationReport of(MigrationRun run) { return new MigrationReport(run.id(),run.began(),run.outcomes(),run.reportFailed(),run.plan().previousRun(),run.plan().previousOrdinals()); }
    public static Path defaultDirectory() { return Path.of(System.getProperty("user.home"),".datacube","migration-reports"); }
    public void checkpoint(Path directory) throws IOException {
        MigrationFiles.checkParents(directory.toAbsolutePath()); Files.createDirectories(directory);
        Path destination=directory.resolve(id+".report");
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid report destination");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeUTF(id.toString()); out.writeUTF(began.toString()); out.writeBoolean(persistenceFailed);out.writeUTF(previousRun==null?"":previousRun.toString()); out.writeInt(outcomes.size());
            for(var row:outcomes) { out.writeInt(row.ordinal()); out.writeUTF(row.state().name()); out.writeUTF(row.phase().name()); out.writeUTF(row.reason().name()); out.writeLong(row.rows()); out.writeUTF(time(row.began())); out.writeUTF(time(row.finished())); out.writeUTF(time(row.sourceBegan())); out.writeUTF(time(row.sourceFinished())); out.writeUTF(row.inputSha256()); out.writeUTF(time(row.comparedAt())); }
            out.writeInt(previousOrdinals.size());for(var entry:new TreeMap<>(previousOrdinals).entrySet()){out.writeInt(entry.getKey());out.writeInt(entry.getValue());}
        }
        byte[] payload=bytes.toByteArray(); if(payload.length>MAX_BYTES)throw new IOException("Report exceeds limit");
        Path temporary=Files.createTempFile(directory,".migration-",".part");
        try {
            try(OutputStream out=Files.newOutputStream(temporary,LinkOption.NOFOLLOW_LINKS)) { out.write(payload); out.write(MigrationFiles.digest().digest(payload)); }
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            MigrationFiles.checkParents(destination);
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Restored RUNNING means outcome unknown. This API deliberately has no resume operation. */
    public static MigrationReport read(Path path) throws IOException {
        MigrationFiles.checkParents(path.toAbsolutePath());
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>MAX_BYTES+32)throw new IOException("Invalid report");
        byte[] bytes; try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(MAX_BYTES+33);}
        if(bytes.length<36 || bytes.length>MAX_BYTES+32)throw new IOException("Invalid report length");
        byte[] payload=Arrays.copyOf(bytes,bytes.length-32),digest=Arrays.copyOfRange(bytes,bytes.length-32,bytes.length);
        if(!java.security.MessageDigest.isEqual(MigrationFiles.digest().digest(payload),digest))throw new IOException("Report checksum mismatch");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(payload))) {
            if(in.readInt()!=MAGIC)throw new IOException("Unsupported report version");
            UUID id=UUID.fromString(in.readUTF()); Instant began=Instant.parse(in.readUTF()); boolean failed=in.readBoolean();String previous=in.readUTF();UUID previousRun=previous.isEmpty()?null:UUID.fromString(previous); int count=in.readInt();
            if(count<0 || count>5000)throw new IOException("Invalid report table count");
            List<MigrationRun.Outcome> rows=new ArrayList<>(); Set<Integer> ordinals=new HashSet<>();
            for(int i=0;i<count;i++) {
                int ordinal=in.readInt(); var state=MigrationRun.State.valueOf(in.readUTF()); var phase=MigrationRun.Phase.valueOf(in.readUTF()); var reason=MigrationRun.Reason.valueOf(in.readUTF()); long rowCount=in.readLong(); String start=in.readUTF(),finish=in.readUTF();
                String sourceStart=in.readUTF(),sourceFinish=in.readUTF(),inputSha256=in.readUTF(),compared=in.readUTF();
                if(ordinal<1 || ordinal>5000 || !ordinals.add(ordinal) || rowCount<0)throw new IOException("Invalid report outcome");
                if(!inputSha256.isEmpty() && !inputSha256.matches("[0-9a-f]{64}"))throw new IOException("Invalid input fingerprint");
                if(sourceStart.isEmpty()!=sourceFinish.isEmpty() || !sourceStart.isEmpty() && Instant.parse(sourceFinish).isBefore(Instant.parse(sourceStart)))throw new IOException("Invalid source window");
                if(state==MigrationRun.State.RUNNING) { state=MigrationRun.State.COMMIT_UNKNOWN; reason=MigrationRun.Reason.COMMIT_UNCERTAIN; }
                rows.add(new MigrationRun.Outcome(ordinal,state,phase,reason,rowCount,instant(start),instant(finish),instant(sourceStart),instant(sourceFinish),inputSha256,instant(compared)));
            }
            int mapCount=in.readInt();if(mapCount<0 || mapCount>count || previousRun==null && mapCount!=0)throw new IOException("Invalid retry mapping");
            Map<Integer,Integer> mapping=new HashMap<>();for(int i=0;i<mapCount;i++){int current=in.readInt(),old=in.readInt();if(!ordinals.contains(current) || old<1 || old>5000 || mapping.put(current,old)!=null)throw new IOException("Invalid retry mapping");}
            if(in.available()!=0)throw new IOException("Unexpected report content");
            return new MigrationReport(id,began,rows,failed,previousRun,mapping);
        } catch(IllegalArgumentException|java.time.DateTimeException failure) { throw new IOException("Invalid report content"); }
    }
    public String display() {
        StringBuilder text=new StringBuilder("迁移报告 "+id+"\n源/目标不共享快照；统计/摘要相符不等于当前源库全量一致。\n恢复报告仅供查看，不授权自动重试。\n");
        if(persistenceFailed)text.append("报告写入曾失败；以当前进程状态为准。\n");
        if(previousRun!=null)text.append("本次仅为部分重试，原报告 ").append(previousRun).append(" 中的其他项仍有效。\n");
        text.append("对账范围：文件与目标的精确行数、逐列空值、主键唯一性（源有主键时）及全行多重集合摘要。数字按精确值规范化；文本不去空格、不作 Unicode 归一化。摘要匹配是概率性校验。\n");
        for(var row:outcomes) {
            text.append(String.format(Locale.ROOT,"T%04d  %s / %s / %s  已校验文件行数=%d%n",row.ordinal(),phase(row.phase()),state(row.state()),row.reason(),row.rows()));
            if(previousOrdinals.containsKey(row.ordinal()))text.append(String.format(Locale.ROOT,"  对应原报告 T%04d%n",previousOrdinals.get(row.ordinal())));
            text.append("  源单表窗口：").append(row.sourceBegan()==null?"未知，仅比较选定文件":row.sourceBegan()+" 至 "+row.sourceFinished()).append('\n');
            if(row.comparedAt()!=null)text.append("  目标事务内对账开始：").append(row.comparedAt()).append("；任务结束：").append(row.finished()).append('\n');
        }
        return text.toString();
    }
    private static String time(Instant value){return value==null?"":value.toString();}
    private static Instant instant(String value){return value.isEmpty()?null:Instant.parse(value);}
    private static String state(MigrationRun.State value){return switch(value){case NOT_STARTED->"未开始";case RUNNING->"进行中";case COMMITTED_VERIFIED->"已提交，文件对账相符";case SKIPPED_NONEMPTY->"已有数据，已跳过";case FAILED_BEFORE_WRITE->"写入前拒绝";case FAILED_ROLLED_BACK->"失败，已回滚";case CANCELLED_ROLLED_BACK->"取消，已回滚";case COMMIT_UNKNOWN->"提交或回滚结果未知，禁止直接重试";};}
    private static String phase(MigrationRun.Phase value){return switch(value){case PRECHECK->"执行前复查";case CREATE->"目标结构/锁";case READ_FILE->"读取文件";case INSERT->"数据写入";case COMPARE->"文件对账";case COMMIT->"提交";case FINISHED->"结束";};}
}
