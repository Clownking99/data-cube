package com.datacube.fx;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Deterministic managed-pane sequence: construct, bind abort ownership, initialize, publish spec. */
final class ManagedTabFactorySequence {
    private ManagedTabFactorySequence() {}

    static <P, R> R create(
            Supplier<? extends P> constructor,
            Consumer<? super P> bindAbort,
            Consumer<? super P> initialize,
            Function<? super P, ? extends R> finish) {
        P pane = constructor.get();
        bindAbort.accept(pane);
        initialize.accept(pane);
        return finish.apply(pane);
    }
}
