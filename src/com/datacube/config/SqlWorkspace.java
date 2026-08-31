package com.datacube.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Layout references only. SQL and connection context remain in their draft checkpoints. */
public record SqlWorkspace(long capturedAt, List<Entry> entries, UUID selectedDraftId) {
    public static final int MAX_ENTRIES = 100;
    public static final int MAX_POSITION = 1024 * 1024;

    public SqlWorkspace {
        if (capturedAt < 0 || entries == null || entries.size() > MAX_ENTRIES) throw invalid();
        Set<UUID> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null || !ids.add(entry.draftId())) throw invalid();
        }
        if (selectedDraftId != null && !ids.contains(selectedDraftId)) throw invalid();
        entries = List.copyOf(entries);
    }

    /** UTF-16 offsets in the editor, not SQL byte offsets; a selection may be reversed. */
    public record Entry(UUID draftId, int anchor, int caret) {
        public Entry {
            if (draftId == null || anchor < 0 || caret < 0
                    || anchor > MAX_POSITION || caret > MAX_POSITION) throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid SQL workspace value");
    }
}

