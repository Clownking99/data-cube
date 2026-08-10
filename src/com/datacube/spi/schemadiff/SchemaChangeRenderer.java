package com.datacube.spi.schemadiff;

import java.util.List;

public interface SchemaChangeRenderer {
    List<RenderedStatement> render(SchemaChange change, RenderContext context);
}
