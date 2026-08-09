package com.datacube.fx;

import java.util.function.Consumer;

@FunctionalInterface
public interface AsyncTabCloseGuard {
    void requestClose(Consumer<Boolean> completion);
}
