package com.datacube.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Blocking local favorites; callers run off FX, and no operation opens a database. */
public final class SqlFavoriteStore implements AutoCloseable {
    public static final int MAX_FAVORITES=100, MAX_SQL_BYTES=SqlFavoriteCodec.MAX_SQL_BYTES;
    public static final long MAX_TOTAL_BYTES=16L*1024*1024;
    public enum Code { CAPACITY, CHANGED, PROTECTED, UNAVAILABLE, INVALID }
    public static final class Failure extends IOException {
        private final Code code;
        Failure(Code code) { super("SQL favorites failed: " + code); this.code=code; }
        public Code code() { return code; }
    }
    public record Snapshot(List<SqlFavorite> favorites,List<SqlFavorite> recoverable,int protectedCount,boolean writable) {
        public Snapshot { favorites=List.copyOf(favorites); recoverable=List.copyOf(recoverable); }
    }
    private record Inspection(Snapshot snapshot,Map<UUID,SqlFavorite> current,Set<UUID> occupied,Set<UUID> protectedIds,Map<String,Integer> lengths,long bytes) {}
    private final SqlDraftDirectory directory;
    SqlFavoriteStore(SqlDraftDirectory directory) { this.directory=directory; }
    public static SqlFavoriteStore open(Path root) throws IOException { return new SqlFavoriteStore(SqlDraftDirectory.open(root)); }
    public synchronized Snapshot snapshot() throws IOException { return inspect().snapshot(); }
    public synchronized void save(SqlFavorite value,SqlFavorite expected) throws IOException {
        byte[] encoded;
        try { encoded=SqlFavoriteCodec.encode(value); } catch (IOException invalid) { throw new Failure(Code.INVALID); }
        Inspection state=inspect();
        if (!state.snapshot().writable()) throw new Failure(Code.UNAVAILABLE);
        SqlFavorite previous=state.current().get(value.id());
        if (state.protectedIds().contains(value.id()) || state.occupied().contains(value.id()) && previous==null) throw new Failure(Code.PROTECTED);
        if (!Objects.equals(expected,previous)) throw new Failure(Code.CHANGED);
        String primary=file(value.id()),backup=backup(value.id());
        byte[] old=previous==null ? null : SqlFavoriteCodec.encode(previous);
        long total=state.bytes()-state.lengths().getOrDefault(primary,0)-state.lengths().getOrDefault(backup,0)
                +encoded.length+(old==null ? 0 : old.length);
        if (previous==null && state.occupied().size()>=MAX_FAVORITES || total>MAX_TOTAL_BYTES) throw new Failure(Code.CAPACITY);
        // Publish a verified previous version before replacing current; either failure preserves current.
        if (old!=null) directory.publish(backup,old);
        directory.publish(primary,encoded);
    }
    public synchronized void delete(SqlFavorite expected) throws IOException {
        Inspection state=inspect();
        if (state.protectedIds().contains(expected.id())) throw new Failure(Code.PROTECTED);
        if (!Objects.equals(expected,state.current().get(expected.id()))) throw new Failure(Code.CHANGED);
        // Backup first: an interrupted delete can leave current visible, but cannot resurrect deleted SQL.
        directory.delete(backup(expected.id())); directory.delete(file(expected.id()));
    }
    /** Explicit copy recovery protects damaged/unknown bytes at their original identity. */
    public synchronized SqlFavorite recover(UUID id,long now) throws IOException {
        SqlFavorite old=inspect().snapshot().recoverable().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> new Failure(Code.PROTECTED));
        SqlFavorite restored=new SqlFavorite(UUID.randomUUID(),old.name(),old.group(),old.sql(),now);
        save(restored,null); return restored;
    }
    @Override public synchronized void close() throws IOException { directory.close(); }
    private Inspection inspect() throws IOException {
        Set<UUID> ids=new HashSet<>();
        for (String name:directory.entries()) { UUID id=id(name); if (id!=null) ids.add(id); }
        if (ids.size()>MAX_FAVORITES) throw new Failure(Code.CAPACITY);
        var current=new HashMap<UUID,SqlFavorite>(); var recoverable=new ArrayList<SqlFavorite>(); var lengths=new HashMap<String,Integer>();
        Set<UUID> protectedIds=new HashSet<>(); long total=0; boolean writable=true;
        for (UUID id:ids) {
            SqlFavorite primary=null,previous=null; boolean damaged=false;
            for (String name:List.of(file(id),backup(id))) {
                byte[] bytes;
                try { bytes=directory.read(name,SqlFavoriteCodec.MAX_FILE_BYTES); }
                catch (IOException unreadable) { writable=false; damaged=true; continue; }
                if (bytes==null) continue;
                lengths.put(name,bytes.length); total+=bytes.length;
                if (total>MAX_TOTAL_BYTES) throw new Failure(Code.CAPACITY);
                try {
                    SqlFavorite value=SqlFavoriteCodec.decode(bytes);
                    if (!value.id().equals(id)) { damaged=true; continue; }
                    if (name.equals(file(id))) primary=value; else previous=value;
                } catch (IOException corrupt) { damaged=true; }
            }
            if (primary!=null) current.put(id,primary);
            if (primary==null || damaged) {
                protectedIds.add(id); SqlFavorite valid=primary!=null ? primary : previous;
                if (valid!=null) recoverable.add(valid);
            }
        }
        Comparator<SqlFavorite> order=Comparator.comparingLong(SqlFavorite::modifiedAt).reversed().thenComparing(v -> v.id().toString());
        var favorites=new ArrayList<>(current.values()); favorites.sort(order); recoverable.sort(order);
        return new Inspection(new Snapshot(favorites,recoverable,protectedIds.size(),writable),Map.copyOf(current),Set.copyOf(ids),Set.copyOf(protectedIds),Map.copyOf(lengths),total);
    }
    private static String file(UUID id) { return id+".favorite"; }
    private static String backup(UUID id) { return id+".favorite-backup"; }
    private static UUID id(String name) {
        String suffix=name.endsWith(".favorite-backup") ? ".favorite-backup" : name.endsWith(".favorite") ? ".favorite" : null;
        if (suffix==null) return null;
        String value=name.substring(0,name.length()-suffix.length());
        try { UUID id=UUID.fromString(value); return id.toString().equals(value) ? id : null; } catch (IllegalArgumentException invalid) { return null; }
    }
}
