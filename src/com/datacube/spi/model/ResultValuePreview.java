package com.datacube.spi.model;

import java.util.Objects;

/** A display-only prefix or omission; never a complete scalar for SQL export or re-query. */
public record ResultValuePreview(String text) {
    public ResultValuePreview { Objects.requireNonNull(text); }
    @Override public String toString() { return text + " [仅预览，值未完整保留]"; }
}
