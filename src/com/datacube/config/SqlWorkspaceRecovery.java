package com.datacube.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure layout-to-checkpoint resolution. No files, database calls or UI construction. */
public final class SqlWorkspaceRecovery {
    private SqlWorkspaceRecovery() { }

    public record ResolvedTab(SqlDraft draft, int anchor, int caret) { }
    public record Resolution(List<ResolvedTab> tabs, UUID selectedDraftId, List<UUID> missingDraftIds) {
        public Resolution {
            tabs = List.copyOf(tabs);
            missingDraftIds = List.copyOf(missingDraftIds);
        }
    }

    public static Resolution resolve(SqlWorkspace workspace, List<SqlDraft> drafts) {
        if (workspace == null || drafts == null || drafts.size() > SqlWorkspace.MAX_ENTRIES) throw invalid();
        Map<UUID, SqlDraft> byId = new HashMap<>();
        for (SqlDraft draft : drafts) {
            if (draft == null || byId.putIfAbsent(draft.id(), draft) != null) throw invalid();
        }
        List<ResolvedTab> tabs = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        UUID selected = null;
        for (SqlWorkspace.Entry entry : workspace.entries()) {
            SqlDraft draft = byId.get(entry.draftId());
            if (draft == null) {
                missing.add(entry.draftId());
            } else {
                tabs.add(new ResolvedTab(draft, entry.anchor(), entry.caret()));
                if (entry.draftId().equals(workspace.selectedDraftId())) selected = entry.draftId();
            }
        }
        if (selected == null && workspace.selectedDraftId() != null && !tabs.isEmpty()) {
            selected = tabs.getFirst().draft().id();
        }
        return new Resolution(tabs, selected, missing);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid SQL workspace draft snapshot");
    }
}

