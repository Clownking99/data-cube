package com.datacube.spi.schemadiff;

import java.sql.Connection;
import java.util.Set;

public interface SchemaDiffCapability {
    SchemaSnapshotReader snapshotReader(Connection connection);

    SchemaChangeRenderer changeRenderer();

    /** Implementations return an immutable defensive snapshot of their supported types. */
    Set<ObjectType> supportedObjectTypes();
}
