package com.datacube.spi.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConnectionSafetyOptions(
        ConnectionEnvironment environment,
        boolean readOnly,
        int queryTimeoutSeconds) {

    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 60;
    public static final int MAX_QUERY_TIMEOUT_SECONDS = 3600;

    public ConnectionSafetyOptions {
        environment = environment == null ? ConnectionEnvironment.DEVELOPMENT : environment;
        if (queryTimeoutSeconds < 0 || queryTimeoutSeconds > MAX_QUERY_TIMEOUT_SECONDS) {
            queryTimeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
    }

    public static ConnectionSafetyOptions from(ConnConfig config) {
        Map<String, String> props = config == null ? Map.of() : config.props();
        ConnectionEnvironment environment = ConnectionEnvironment.parse(props.get("environment"));
        boolean readOnly = Boolean.parseBoolean(props.getOrDefault("readOnly", "false"));
        int timeout = parseTimeout(props.get("queryTimeoutSeconds"));
        return new ConnectionSafetyOptions(environment, readOnly, timeout);
    }

    public Map<String, String> toPersistentProps() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("environment", environment.name());
        props.put("readOnly", Boolean.toString(readOnly));
        props.put("queryTimeoutSeconds", Integer.toString(queryTimeoutSeconds));
        return Map.copyOf(props);
    }

    public ConnConfig applyTo(ConnConfig config) {
        Map<String, String> props = new HashMap<>(config.props());
        props.putAll(toPersistentProps());
        return new ConnConfig(config.id(), config.name(), config.type(), config.host(), config.port(),
                config.database(), config.username(), config.encryptedPassword(), props);
    }

    private static int parseTimeout(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_QUERY_TIMEOUT_SECONDS;
        try {
            int timeout = Integer.parseInt(raw.trim());
            return timeout >= 0 && timeout <= MAX_QUERY_TIMEOUT_SECONDS
                    ? timeout : DEFAULT_QUERY_TIMEOUT_SECONDS;
        } catch (NumberFormatException invalid) {
            return DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
    }
}
