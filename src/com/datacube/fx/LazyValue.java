package com.datacube.fx;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 线程安全的惰性值：首次访问时创建，并可在不触发创建的前提下执行清理。 */
final class LazyValue<T> {

    private Supplier<? extends T> supplier;
    private T value;

    LazyValue(Supplier<? extends T> supplier) {
        this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    synchronized T get() {
        if (value == null) {
            value = Objects.requireNonNull(supplier.get(), "supplier returned null");
            supplier = null;
        }
        return value;
    }

    synchronized Optional<T> peek() {
        return Optional.ofNullable(value);
    }

    synchronized void ifInitialized(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        if (value != null) action.accept(value);
    }
}
