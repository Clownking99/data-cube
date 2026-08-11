package com.datacube.service;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.schemadiff.QualifiedName;

import java.util.Objects;

/** Immutable source/target admission snapshot for one same-provider schema comparison. */
public record SchemaDiffRequest(
        ConnConfig sourceConfig,
        QualifiedName sourceSchema,
        ConnConfig targetConfig,
        QualifiedName targetSchema) {

    public SchemaDiffRequest {
        sourceConfig = copy(Objects.requireNonNull(sourceConfig, "sourceConfig"));
        sourceSchema = Objects.requireNonNull(sourceSchema, "sourceSchema");
        targetConfig = copy(Objects.requireNonNull(targetConfig, "targetConfig"));
        targetSchema = Objects.requireNonNull(targetSchema, "targetSchema");
    }

    private static ConnConfig copy(ConnConfig config) {
        return new ConnConfig(config.id(), config.name(), config.type(), config.host(), config.port(),
                config.database(), config.username(), config.encryptedPassword(), config.props());
    }

    @Override
    public String toString() {
        return "SchemaDiffRequest[sourceType=" + sourceConfig.type()
                + ", targetType=" + targetConfig.type() + "]";
    }
}
