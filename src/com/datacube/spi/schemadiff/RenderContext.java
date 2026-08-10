package com.datacube.spi.schemadiff;

import com.datacube.spi.model.DbType;

import java.util.Objects;

public record RenderContext(
        DbType databaseType, QualifiedName sourceSchema,
        QualifiedName targetSchema, boolean destructiveApproved) {
    public RenderContext {
        databaseType = Objects.requireNonNull(databaseType, "databaseType");
        sourceSchema = Objects.requireNonNull(sourceSchema, "sourceSchema");
        targetSchema = Objects.requireNonNull(targetSchema, "targetSchema");
    }

    @Override
    public String toString() {
        return "RenderContext[databaseType=" + databaseType
                + ", destructiveApproved=" + destructiveApproved + "]";
    }
}
