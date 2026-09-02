package com.datacube.fx;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import javafx.application.Platform;

/** FX-owned canonical-path bindings for SQL file tabs and transactional Save As claims. */
final class SqlFileTabRegistry implements AutoCloseable {

    enum Claim { CLAIMED, COLLISION }

    /** Stable identity token. Callers cannot inspect or manufacture registry ownership. */
    static final class Owner {
        private final SqlFileTabRegistry registry;
        private final Runnable selector;

        private Owner(SqlFileTabRegistry registry, Runnable selector) {
            this.registry = registry;
            this.selector = selector;
        }
    }

    private final Map<Path, Owner> committed = new HashMap<>();
    private final Map<Path, Owner> provisional = new HashMap<>();
    private final Map<Owner, Path> committedByOwner = new IdentityHashMap<>();
    private final Map<Owner, Path> provisionalByOwner = new IdentityHashMap<>();
    private boolean closed;

    Owner createOwner(Runnable selector) {
        requireFx("createOwner");
        if (closed) throw new IllegalStateException("SQL file tab registry is closed");
        return new Owner(this, Objects.requireNonNull(selector, "selector"));
    }

    boolean install(Owner owner, Path path) {
        requireFx("install");
        requireOwner(owner);
        if (closed) return false;
        Path key = key(path);
        Owner collision = occupant(key, owner);
        if (collision != null) {
            collision.selector.run();
            return false;
        }
        Path current = committedByOwner.get(owner);
        if (current != null && !current.equals(key)) {
            throw new IllegalStateException("SQL file tab owner is already installed");
        }
        committed.put(key, owner);
        committedByOwner.put(owner, key);
        return true;
    }

    boolean select(Path path) {
        requireFx("select");
        if (closed || path == null) return false;
        Owner owner = committed.get(key(path));
        if (owner == null) owner = provisional.get(key(path));
        if (owner == null) return false;
        owner.selector.run();
        return true;
    }

    Claim claim(Owner owner, Path path) {
        requireFx("claim");
        requireOwner(owner);
        if (closed) return Claim.COLLISION;
        Path key = key(path);
        Owner collision = occupant(key, owner);
        if (collision != null) {
            collision.selector.run();
            return Claim.COLLISION;
        }
        Path previous = provisionalByOwner.put(owner, key);
        if (previous != null && !previous.equals(key)) provisional.remove(previous, owner);
        provisional.put(key, owner);
        return Claim.CLAIMED;
    }

    void rollback(Owner owner, Path path) {
        requireFx("rollback");
        requireOwner(owner);
        if (path == null) return;
        Path key = key(path);
        if (key.equals(provisionalByOwner.get(owner))) {
            provisionalByOwner.remove(owner);
            provisional.remove(key, owner);
        }
    }

    void commit(Owner owner, Path path) {
        requireFx("commit");
        requireOwner(owner);
        if (closed) throw new IllegalStateException("SQL file tab registry is closed");
        Path key = key(path);
        Path current = committedByOwner.get(owner);
        if (key.equals(current) && !provisionalByOwner.containsKey(owner)) return;
        if (!key.equals(provisionalByOwner.get(owner)) || provisional.get(key) != owner) {
            throw new IllegalStateException("SQL file tab path was not claimed");
        }
        Owner collision = committed.get(key);
        if (collision != null && collision != owner) {
            throw new IllegalStateException("SQL file tab path is already committed");
        }
        if (current != null) committed.remove(current, owner);
        provisional.remove(key, owner);
        provisionalByOwner.remove(owner);
        committed.put(key, owner);
        committedByOwner.put(owner, key);
    }

    void release(Owner owner) {
        requireFx("release");
        requireOwner(owner);
        Path committedPath = committedByOwner.remove(owner);
        if (committedPath != null) committed.remove(committedPath, owner);
        Path provisionalPath = provisionalByOwner.remove(owner);
        if (provisionalPath != null) provisional.remove(provisionalPath, owner);
    }

    @Override
    public void close() {
        requireFx("close");
        closed = true;
        committed.clear();
        provisional.clear();
        committedByOwner.clear();
        provisionalByOwner.clear();
    }

    private Owner occupant(Path path, Owner requester) {
        Owner owner = committed.get(path);
        if (owner != null && owner != requester) return owner;
        owner = provisional.get(path);
        return owner != null && owner != requester ? owner : null;
    }

    private void requireOwner(Owner owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.registry != this) throw new IllegalArgumentException("owner belongs to another registry");
    }

    private static Path key(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static void requireFx(String operation) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(operation + " must run on the FX Application Thread");
        }
    }
}
