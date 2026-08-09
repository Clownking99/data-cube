package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedTabInstallerTest {

    @Test
    void selectionFailureRemovesUnderInternalMutationThenUnregisters() {
        List<String> calls = new ArrayList<>();

        ManagedTabInstallException failure = assertThrows(ManagedTabInstallException.class, () ->
                ManagedTabInstaller.install(
                        () -> calls.add("add"),
                        () -> { calls.add("select"); throw new IllegalStateException("select"); },
                        () -> calls.add("handlers"),
                        () -> calls.add("remove-internal"),
                        () -> calls.add("unregister"),
                        ignored -> calls.add("fatal")));

        assertEquals(List.of("add", "select", "remove-internal", "unregister"), calls);
        assertFalse(failure.ownershipRetained());
    }

    @Test
    void removeAfterMutationThrowRetainsOwnershipTombstone() {
        List<String> calls = new ArrayList<>();

        ManagedTabInstallException failure = assertThrows(ManagedTabInstallException.class, () ->
                ManagedTabInstaller.install(
                        () -> calls.add("add"),
                        () -> calls.add("select"),
                        () -> { throw new IllegalStateException("handler"); },
                        () -> {
                            calls.add("remove-mutated");
                            throw new IllegalStateException("listener after mutation");
                        },
                        () -> calls.add("unregister"),
                        ignored -> calls.add("fatal")));

        assertEquals(List.of("add", "select", "remove-mutated", "fatal"), calls);
        assertTrue(failure.ownershipRetained());
        assertEquals(1, failure.getCause().getSuppressed().length);
    }
}
