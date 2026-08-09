package com.datacube.spi.model;

import java.util.Locale;

public enum ConnectionEnvironment {
    DEVELOPMENT("开发"),
    TEST("测试"),
    PRODUCTION("生产");

    private final String label;

    ConnectionEnvironment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ConnectionEnvironment parse(String value) {
        if (value == null || value.isBlank()) return DEVELOPMENT;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return DEVELOPMENT;
        }
    }
}
