package com.datacube.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/** Blocking workspace storage owned and serialized by SqlDraftStore; never opens a second lock. */
public final class SqlWorkspaceStore {
    public enum Status { ABSENT, AVAILABLE, CORRUPT, UNSUPPORTED_VERSION, UNREADABLE }
    public enum FailureCode { DISABLED, INVALID_WORKSPACE, PROTECTED_WORKSPACE, PREFERENCE_CORRUPT, DRAFT_PROTECTION_UNAVAILABLE }
    public record Snapshot(SqlWorkspace workspace, Status status, boolean recordingEnabled, boolean preferenceValid) { }
    public static final class Failure extends IOException {
        private final FailureCode code;
        Failure(FailureCode code) { super("SQL workspace store failed: " + code); this.code = code; }
        public FailureCode code() { return code; }
    }
    private record Preference(boolean valid, boolean enabled) { }
    private record Manifest(SqlWorkspace value, Status status) { }
    private static final String WORKSPACE = "workspace.bin";
    private static final String PREFERENCE = "workspace-preferences.bin";
    private static final int PREFERENCE_MAGIC = 0x44435750;
    private static final SqlWorkspace CLEARED = new SqlWorkspace(0, List.of(), null);
    private final SqlDraftDirectory directory;

    SqlWorkspaceStore(SqlDraftDirectory directory) { this.directory = Objects.requireNonNull(directory); }

    Snapshot snapshot() throws IOException {
        Preference preference = preference();
        Manifest manifest = manifest();
        return new Snapshot(manifest.value(), manifest.status(), preference.enabled(), preference.valid());
    }

    void save(SqlWorkspace workspace) throws IOException {
        byte[] bytes;
        try { bytes = SqlWorkspaceCodec.encode(workspace); }
        catch (IOException invalid) { throw new Failure(FailureCode.INVALID_WORKSPACE); }
        Preference preference = preference();
        if (!preference.valid()) throw new Failure(FailureCode.PREFERENCE_CORRUPT);
        if (!preference.enabled()) throw new Failure(FailureCode.DISABLED);
        requireMutable(manifest());
        directory.publish(WORKSPACE, bytes);
    }

    void setEnabled(boolean enabled) throws IOException {
        if (!preference().valid()) throw new Failure(FailureCode.PREFERENCE_CORRUPT);
        directory.publish(PREFERENCE, ByteBuffer.allocate(9).putInt(PREFERENCE_MAGIC)
                .putInt(1).put((byte) (enabled ? 1 : 0)).array());
    }

    boolean clear() throws IOException {
        Manifest manifest = manifest();
        requireMutable(manifest);
        if (manifest.status() == Status.ABSENT || CLEARED.equals(manifest.value())) return false;
        directory.publish(WORKSPACE, SqlWorkspaceCodec.encode(CLEARED));
        return true;
    }

    private Manifest manifest() throws IOException {
        byte[] bytes;
        try { bytes = directory.read(WORKSPACE, SqlWorkspaceCodec.MAX_FILE_BYTES); }
        catch (SqlDraftDirectory.Failure failure) {
            if (failure.stage() != SqlDraftDirectory.Stage.READ) throw failure;
            return new Manifest(null, Status.UNREADABLE);
        }
        if (bytes == null) return new Manifest(null, Status.ABSENT);
        try { return new Manifest(SqlWorkspaceCodec.decode(bytes), Status.AVAILABLE); }
        catch (SqlWorkspaceCodec.Failure invalid) {
            return new Manifest(null, invalid.code() == SqlWorkspaceCodec.Code.UNSUPPORTED_VERSION
                    ? Status.UNSUPPORTED_VERSION : Status.CORRUPT);
        }
    }

    private Preference preference() throws IOException {
        byte[] bytes;
        try { bytes = directory.read(PREFERENCE, 9); }
        catch (SqlDraftDirectory.Failure failure) {
            if (failure.stage() != SqlDraftDirectory.Stage.READ) throw failure;
            return new Preference(false, false);
        }
        if (bytes == null) return new Preference(true, true);
        if (bytes.length != 9) return new Preference(false, false);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != PREFERENCE_MAGIC || buffer.getInt() != 1) return new Preference(false, false);
        byte enabled = buffer.get();
        return enabled == 0 || enabled == 1 ? new Preference(true, enabled == 1) : new Preference(false, false);
    }

    private static void requireMutable(Manifest manifest) throws Failure {
        if (manifest.status() != Status.ABSENT && manifest.status() != Status.AVAILABLE)
            throw new Failure(FailureCode.PROTECTED_WORKSPACE);
    }
}
