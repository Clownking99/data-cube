package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConstructionOwnerTest {

    @Test
    void failureClosesAllOwnedResourcesInReverseOrderAndReportsAggregate() {
        List<String> closed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        ConstructionOwner owner = new ConstructionOwner(failures::add);
        owner.own(() -> closed.add("scope"));
        owner.own(() -> {
            closed.add("queue");
            throw new IllegalStateException("queue close");
        });
        owner.own(() -> closed.add("socket"));

        assertThrows(PartialCloseException.class, owner::close);

        assertEquals(List.of("socket", "queue", "scope"), closed);
        assertEquals(1, failures.size());
    }

    @Test
    void commitTransfersOwnershipWithoutClosingResources() {
        List<String> closed = new ArrayList<>();
        ConstructionOwner owner = new ConstructionOwner(ignored -> {});
        owner.own(() -> closed.add("scope"));

        owner.commit();
        owner.close();

        assertEquals(List.of(), closed);
    }

    @Test
    void sqlEditorStyleBuildFailureClosesQueueThenScope() {
        List<String> closed = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> {
            try (ConstructionOwner construction = new ConstructionOwner(ignored -> {})) {
                construction.own(() -> closed.add("scope"));
                construction.own(() -> closed.add("metadata-queue"));
                throw new IllegalStateException("build failed");
            }
        });

        assertEquals(List.of("metadata-queue", "scope"), closed);
    }
}
