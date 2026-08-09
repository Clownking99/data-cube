package com.datacube.fx;

/** Installation failed; retained ownership means rollback could not be confirmed. */
final class ManagedTabInstallException extends RuntimeException {
    private final boolean ownershipRetained;

    ManagedTabInstallException(Throwable cause, boolean ownershipRetained) {
        super("managed tab installation failed", cause);
        this.ownershipRetained = ownershipRetained;
    }

    boolean ownershipRetained() { return ownershipRetained; }
}
