package com.datacube.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Acknowledges this exact staged launch only after the main JavaFX window is ready. */
public final class UpdateStartup {
    private static final String PREFIX = "--datacube-update=";
    private UpdateStartup() { }
    public static boolean acknowledge(List<String> arguments, String version, Path appDir) {
        try {
            List<String> values = arguments.stream().filter(arg -> arg.startsWith(PREFIX)).toList();
            if (values.size() != 1 || appDir == null) return false;
            String encoded = values.getFirst().substring(PREFIX.length());
            if (encoded.length() > 8192) return false;
            Path planFile = Path.of(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
            Path target = UpdatePaths.image(appDir);
            Path workspace = UpdatePaths.noLinks(planFile).getParent();
            if (!planFile.getFileName().toString().equals("plan.json") || workspace == null
                    || !workspace.getFileName().toString().startsWith(".datacube-update-")
                    || !workspace.getParent().equals(target.getParent()) || Files.size(planFile) > 16384) return false;
            Object parsed = MiniJson.parse(Files.readString(planFile));
            if (!(parsed instanceof Map<?, ?> plan) || !version.equals(plan.get("version"))
                    || !"PORTABLE".equals(plan.get("mode")) || !target.toString().equals(plan.get("appDir"))
                    || !workspace.toString().equals(plan.get("workspace"))
                    || !(plan.get("token") instanceof String token) || !token.matches("[a-f0-9]{32}")) return false;
            Path owner = UpdatePaths.noLinks(workspace.resolve("owner"));
            if (Files.size(owner) != 32 || !Files.readString(owner).equals(token)) return false;
            String ack = "{\"token\":" + quote(token) + ",\"version\":" + quote(version)
                    + ",\"appDir\":" + quote(target.toString()) + ",\"pid\":" + ProcessHandle.current().pid() + "}";
            Files.writeString(workspace.resolve("startup-ack.json"), ack,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (Exception rejected) { return false; }
    }
    static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') json.append('\\').append(c);
            else if (c < 32) json.append(String.format("\\u%04x", (int)c));
            else json.append(c);
        }
        return json.append('"').toString();
    }
}
