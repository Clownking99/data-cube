package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import javafx.scene.Group;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffManagedTabFactoryTest {

    @Test
    void cachedConnectionsAreLoadedOnlyInsideTheLeasedFactoryAndCleanupIsBoundBeforePublish() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger constructions = new AtomicInteger();
        List<String> order = new ArrayList<>();
        FakeContent content = new FakeContent(new Group(), order, false);

        ContentTabPane.ManagedTabFactory factory = SchemaDiffManagedTabFactory.factory(
                () -> {
                    loads.incrementAndGet();
                    order.add("load");
                    return List.of(config("source"));
                }, connections -> {
                    constructions.incrementAndGet();
                    order.add("construct");
                    assertEquals(List.of("source"),
                            connections.stream().map(ConnConfig::id).toList());
                    return content;
                }, ignored -> {});

        assertEquals(0, loads.get(), "factory creation occurs before reservation callback");
        assertEquals(0, constructions.get());
        ContentTabPane.AbortBinding binding = new ContentTabPane.AbortBinding();
        ContentTabPane.ManagedTabSpec spec = factory.create(binding);

        assertEquals(1, loads.get());
        assertEquals(1, constructions.get());
        assertEquals(List.of("load", "construct", "content"), order);
        assertTrue(binding.isBound());
        assertSame(content.node, spec.content());
        spec.mandatoryAbortCleanup().run();
        assertEquals(1, content.cleanups.get());
    }

    @Test
    void failureAfterConstructionRunsOwnedCleanupAndDoesNotPublishASpec() {
        FakeContent content = new FakeContent(new Group(), new ArrayList<>(), true);
        ContentTabPane.ManagedTabFactory factory = SchemaDiffManagedTabFactory.factory(
                () -> List.of(config("source")), ignored -> content, ignored -> {});

        SafeConstructionFailure failure = assertThrows(SafeConstructionFailure.class,
                () -> factory.create(new ContentTabPane.AbortBinding()));

        assertFalse(failure.getMessage() != null && failure.getMessage().contains("secret"));
        assertTrue(failure.requiresMandatoryAbort());
        assertEquals(0, content.cleanups.get());
        failure.mandatoryAbortCleanup().run();
        assertEquals(1, content.cleanups.get());
    }

    private static ConnConfig config(String id) {
        return new ConnConfig(id, id, DbType.POSTGRESQL, "host", 5432,
                "database", "user", "encrypted", Map.of());
    }

    private static final class FakeContent implements SchemaDiffManagedTabFactory.ManagedContent {
        private final Node node;
        private final List<String> order;
        private final boolean failContent;
        private final AtomicInteger cleanups = new AtomicInteger();

        private FakeContent(Node node, List<String> order, boolean failContent) {
            this.node = node;
            this.order = order;
            this.failContent = failContent;
        }

        @Override
        public Node content() {
            order.add("content");
            if (failContent) throw new IllegalStateException("secret-provider-detail");
            return node;
        }

        @Override
        public CompletionStage<CloseGuardOutcome> requestClose() {
            return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
        }

        @Override
        public CompletionStage<CloseGuardOutcome> requestMandatoryClose() {
            return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
        }

        @Override public void finalizeCloseOnFx() {}
        @Override public void closeResources() { cleanups.incrementAndGet(); }
    }
}
