package com.datacube.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Blocking local draft storage; invoke off the JavaFX application thread. */
public final class SqlDraftStore implements AutoCloseable {
    public enum ProblemCode { CORRUPT_DRAFT, UNREADABLE_DRAFT, INVALID_PREFERENCES }
    public enum FailureCode { UNAVAILABLE, DISABLED, CAPACITY, INVALID_DRAFT, PROTECTED_DRAFT, PREFERENCE_CORRUPT }
    public record Problem(UUID draftId, ProblemCode code) { }
    public record Snapshot(List<SqlDraft> drafts, List<Problem> problems, boolean protectionEnabled, boolean writable) {
        public Snapshot { drafts = List.copyOf(drafts); problems = List.copyOf(problems); }
    }
    public static final class Failure extends IOException {
        private final FailureCode code;
        Failure(FailureCode code) { super("SQL draft store failed: " + code); this.code = code; }
        public FailureCode code() { return code; }
    }
    private record Preference(boolean valid, boolean enabled) { }
    private record Inspection(Snapshot snapshot, Map<UUID, Integer> lengths, Set<UUID> rejected, long totalBytes) { }
    private static final int MAX_DRAFTS = 100;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final int PREFERENCE_MAGIC = 0x44434450;
    private static final String PREFERENCE_FILE = "preferences.bin";
    private final SqlDraftDirectory directory;

    SqlDraftStore(SqlDraftDirectory directory) { this.directory = Objects.requireNonNull(directory); }

    public static SqlDraftStore open(Path path) throws IOException {
        return new SqlDraftStore(SqlDraftDirectory.open(path));
    }

    public synchronized Snapshot snapshot() throws IOException { return inspect().snapshot(); }

    public synchronized void save(SqlDraft draft) throws IOException {
        byte[] bytes;
        try { bytes = SqlDraftCodec.encode(draft); }
        catch (IOException invalid) { throw new Failure(FailureCode.INVALID_DRAFT); }
        Inspection inspection = inspect();
        if (!inspection.snapshot().writable()) throw new Failure(FailureCode.UNAVAILABLE);
        if (!inspection.snapshot().protectionEnabled()) throw new Failure(FailureCode.DISABLED);
        if (inspection.rejected().contains(draft.id())) throw new Failure(FailureCode.PROTECTED_DRAFT);
        Integer previous = inspection.lengths().get(draft.id());
        if ((previous == null && inspection.lengths().size() >= MAX_DRAFTS)
                || inspection.totalBytes() - (previous == null ? 0 : previous) + bytes.length > MAX_TOTAL_BYTES) {
            throw new Failure(FailureCode.CAPACITY);
        }
        directory.publish(filename(draft.id()), bytes);
    }

    public synchronized void setEnabled(boolean enabled) throws IOException {
        directory.entries();
        Preference preference = preference();
        if (!preference.valid()) throw new Failure(FailureCode.PREFERENCE_CORRUPT);
        byte[] bytes = ByteBuffer.allocate(9).putInt(PREFERENCE_MAGIC).putInt(1).put((byte) (enabled ? 1 : 0)).array();
        directory.publish(PREFERENCE_FILE, bytes);
    }

    public synchronized void delete(UUID id) throws IOException {
        SqlDraft value = readVerified(Objects.requireNonNull(id));
        if (value != null) directory.delete(filename(id));
    }

    public synchronized int clearRecoverable() throws IOException {
        List<SqlDraft> candidates = inspect().snapshot().drafts();
        int deleted = 0;
        for (SqlDraft candidate : candidates) {
            if (candidate.equals(readVerified(candidate.id()))) {
                directory.delete(filename(candidate.id()));
                deleted++;
            }
        }
        return deleted;
    }

    public synchronized int pruneExpired(long now, Set<UUID> openIds) throws IOException {
        if (now < 0) throw new IllegalArgumentException("Invalid draft retention time");
        Set<UUID> opened = Set.copyOf(openIds);
        if (now < RETENTION_MILLIS) return 0;
        long cutoff = now - RETENTION_MILLIS;
        int deleted = 0;
        for (SqlDraft candidate : inspect().snapshot().drafts()) {
            if (candidate.modifiedAt() <= cutoff && !opened.contains(candidate.id())
                    && candidate.equals(readVerified(candidate.id()))) {
                directory.delete(filename(candidate.id()));
                deleted++;
            }
        }
        return deleted;
    }

    @Override public synchronized void close() throws IOException { directory.close(); }

    private Inspection inspect() throws IOException {
        List<UUID> ids = new ArrayList<>();
        for (String name : directory.entries()) {
            UUID id = idFromName(name);
            if (id != null) ids.add(id);
        }
        if (ids.size() > MAX_DRAFTS) throw new Failure(FailureCode.CAPACITY);
        Preference preference = preference();
        List<Problem> problems = new ArrayList<>();
        if (!preference.valid()) problems.add(new Problem(null, ProblemCode.INVALID_PREFERENCES));
        List<SqlDraft> drafts = new ArrayList<>();
        Map<UUID, Integer> lengths = new HashMap<>();
        Set<UUID> rejected = new HashSet<>();
        long totalBytes = 0;
        boolean sizesKnown = true;
        for (UUID id : ids) {
            byte[] bytes;
            try { bytes = directory.read(filename(id), SqlDraftCodec.MAX_FILE_BYTES); }
            catch (IOException unreadable) {
                problems.add(new Problem(id, ProblemCode.UNREADABLE_DRAFT));
                rejected.add(id); sizesKnown = false;
                continue;
            }
            if (bytes == null) continue;
            lengths.put(id, bytes.length);
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_BYTES) throw new Failure(FailureCode.CAPACITY);
            try {
                SqlDraft value = SqlDraftCodec.decode(bytes);
                if (!id.equals(value.id())) throw new Failure(FailureCode.PROTECTED_DRAFT);
                drafts.add(value);
            } catch (IOException corrupt) {
                rejected.add(id);
                problems.add(new Problem(id, ProblemCode.CORRUPT_DRAFT));
            }
        }
        drafts.sort(Comparator.comparingLong(SqlDraft::modifiedAt).reversed().thenComparing(draft -> draft.id().toString()));
        Snapshot snapshot = new Snapshot(drafts, problems, preference.valid() && preference.enabled(), preference.valid() && sizesKnown);
        return new Inspection(snapshot, Map.copyOf(lengths), Set.copyOf(rejected), totalBytes);
    }

    private Preference preference() throws IOException {
        byte[] bytes;
        try { bytes = directory.read(PREFERENCE_FILE, 9); }
        catch (IOException unreadable) { return new Preference(false, false); }
        if (bytes == null) return new Preference(true, true);
        if (bytes.length != 9) return new Preference(false, false);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != PREFERENCE_MAGIC || buffer.getInt() != 1) return new Preference(false, false);
        byte enabled = buffer.get();
        return enabled == 0 || enabled == 1 ? new Preference(true, enabled == 1) : new Preference(false, false);
    }

    private SqlDraft readVerified(UUID id) throws IOException {
        byte[] bytes = directory.read(filename(id), SqlDraftCodec.MAX_FILE_BYTES);
        if (bytes == null) return null;
        try {
            SqlDraft value = SqlDraftCodec.decode(bytes);
            if (!id.equals(value.id())) throw new Failure(FailureCode.PROTECTED_DRAFT);
            return value;
        } catch (IOException corrupt) { throw new Failure(FailureCode.PROTECTED_DRAFT); }
    }

    private static String filename(UUID id) { return id + ".draft"; }

    private static UUID idFromName(String name) {
        if (!name.endsWith(".draft")) return null;
        String text = name.substring(0, name.length() - 6);
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equals(text) ? id : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
