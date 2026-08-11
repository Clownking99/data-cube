package com.datacube.provider.oracle;

import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshotReader;

import java.sql.Connection;
import java.util.Objects;
import java.util.Set;

/** Immutable Oracle Schema Diff capability facade. */
public final class OracleSchemaDiffCapability implements SchemaDiffCapability {
    private static final SchemaChangeRenderer RENDERER = new OracleSchemaChangeRenderer();
    private static final Set<ObjectType> SUPPORTED_TYPES = Set.of(
            ObjectType.TABLE,
            ObjectType.SEQUENCE,
            ObjectType.VIEW,
            ObjectType.MATERIALIZED_VIEW,
            ObjectType.FUNCTION,
            ObjectType.PROCEDURE,
            ObjectType.TRIGGER,
            ObjectType.PACKAGE_SPEC,
            ObjectType.PACKAGE_BODY,
            ObjectType.TYPE);

    @Override
    public SchemaSnapshotReader snapshotReader(Connection connection) {
        return new OracleSchemaSnapshotReader(Objects.requireNonNull(connection, "connection"));
    }

    @Override
    public SchemaChangeRenderer changeRenderer() {
        return RENDERER;
    }

    @Override
    public Set<ObjectType> supportedObjectTypes() {
        return SUPPORTED_TYPES;
    }
}
