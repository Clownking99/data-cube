package com.datacube.fx;

import java.util.Objects;
import java.util.function.Consumer;

/** Pure-Java transactional sequence for installing already-registered tab UI. */
final class ManagedTabInstaller {
    private ManagedTabInstaller() {}

    static void install(
            Runnable add,
            Runnable select,
            Runnable installHandlers,
            Runnable removeUnderInternalMutation,
            Runnable unregister,
            Consumer<? super Throwable> retainFatalOwnership) {
        Objects.requireNonNull(add, "add");
        Objects.requireNonNull(select, "select");
        Objects.requireNonNull(installHandlers, "installHandlers");
        Objects.requireNonNull(removeUnderInternalMutation, "removeUnderInternalMutation");
        Objects.requireNonNull(unregister, "unregister");
        Objects.requireNonNull(retainFatalOwnership, "retainFatalOwnership");
        try {
            add.run();
            select.run();
            installHandlers.run();
        } catch (Throwable installFailure) {
            boolean ownershipRetained = true;
            try {
                removeUnderInternalMutation.run();
                unregister.run();
                ownershipRetained = false;
            } catch (Throwable rollbackFailure) {
                installFailure.addSuppressed(rollbackFailure);
                try {
                    retainFatalOwnership.accept(rollbackFailure);
                } catch (Throwable fatalFailure) {
                    installFailure.addSuppressed(fatalFailure);
                }
            }
            throw new ManagedTabInstallException(installFailure, ownershipRetained);
        }
    }
}
