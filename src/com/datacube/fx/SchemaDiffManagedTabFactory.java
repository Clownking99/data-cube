package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import javafx.scene.Node;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Reservation-scoped Schema Diff construction with mandatory-abort ownership. */
final class SchemaDiffManagedTabFactory {
    private SchemaDiffManagedTabFactory() {}

    static ContentTabPane.ManagedTabFactory factory(
            Supplier<List<ConnConfig>> cachedConnections,
            Function<List<ConnConfig>, ? extends ManagedContent> contentFactory,
            Consumer<? super Throwable> reporter) {
        Objects.requireNonNull(cachedConnections, "cachedConnections");
        Objects.requireNonNull(contentFactory, "contentFactory");
        Objects.requireNonNull(reporter, "reporter");
        return binding -> {
            ConstructionOwner construction = new ConstructionOwner(reporter);
            try {
                List<ConnConfig> connections = List.copyOf(
                        Objects.requireNonNull(cachedConnections.get(), "cached connections"));
                ManagedContent content = Objects.requireNonNull(
                        contentFactory.apply(connections), "managed content");
                construction.ownBlocking(content::closeResources);
                binding.bind(content::closeResources);
                ContentTabPane.ManagedTabSpec spec = new ContentTabPane.ManagedTabSpec(
                        content.content(), content::requestClose, content::requestMandatoryClose,
                        content::finalizeCloseOnFx, content::closeResources);
                construction.commit();
                return spec;
            } catch (SafeConstructionFailure failure) {
                throw failure;
            } catch (Throwable failure) {
                throw construction.close(failure).failure();
            }
        };
    }

    interface ManagedContent {
        Node content();

        CompletionStage<CloseGuardOutcome> requestClose();

        CompletionStage<CloseGuardOutcome> requestMandatoryClose();

        void finalizeCloseOnFx();

        void closeResources();
    }
}
