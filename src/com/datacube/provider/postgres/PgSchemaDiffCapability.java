package com.datacube.provider.postgres;

import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaComparisonProjector;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshotReader;

import java.sql.Connection;
import java.util.Objects;
import java.util.Set;

/** Immutable PostgreSQL Schema Diff capability facade. */
public final class PgSchemaDiffCapability implements SchemaDiffCapability {
    private static final SchemaChangeRenderer RENDERER = new PgSchemaChangeRenderer();
    private static final SchemaComparisonProjector COMPARISON_PROJECTOR =
            new PgSchemaComparisonProjector();
    private static final Set<ObjectType> SUPPORTED_TYPES = Set.of(
            ObjectType.TABLE,
            ObjectType.SEQUENCE,
            ObjectType.VIEW,
            ObjectType.MATERIALIZED_VIEW,
            ObjectType.FUNCTION,
            ObjectType.PROCEDURE,
            ObjectType.TRIGGER,
            ObjectType.TYPE);

    @Override
    public SchemaSnapshotReader snapshotReader(Connection connection) {
        return new PgSchemaSnapshotReader(Objects.requireNonNull(connection, "connection"));
    }

    @Override
    public SchemaChangeRenderer changeRenderer() {
        return RENDERER;
    }

    @Override
    public SchemaComparisonProjector comparisonProjector() {
        return COMPARISON_PROJECTOR;
    }

    @Override
    public Set<ObjectType> supportedObjectTypes() {
        return SUPPORTED_TYPES;
    }
}
