package com.datacube.spi.schemadiff;

import java.util.Set;

public sealed interface SchemaObject
        permits TableDefinition, SequenceDefinition, DefinitionObject {
    ObjectKey key();

    Set<ObjectKey> dependencies();
}
