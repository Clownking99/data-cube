package com.datacube.export;

import java.util.List;

public record XlsxLayout(List<Integer> widths) {
    public XlsxLayout {
        widths = List.copyOf(widths);
        if (widths.stream().anyMatch(width -> width < 12 || width > 60)) {
            throw new IllegalArgumentException("XLSX widths must be between 12 and 60");
        }
    }
}
