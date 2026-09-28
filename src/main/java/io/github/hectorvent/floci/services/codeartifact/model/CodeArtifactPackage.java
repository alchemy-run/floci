package io.github.hectorvent.floci.services.codeartifact.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public record CodeArtifactPackage(String format, String namespace, String name,
                                  Map<String, String> restrictions, Map<String, Version> versions) {
    @RegisterForReflection
    public record Version(String version, String revision, String status, long publishedTime,
                          String originRepository, Map<String, byte[]> assets) {}
}
