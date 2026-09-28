package io.github.hectorvent.floci.services.memorydb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

public final class MemoryDbMetadata {
    private MemoryDbMetadata() {}

    @RegisterForReflection
    public record ParameterGroup(String name, String family, String description, String arn,
                                 Map<String, String> parameters, Map<String, String> tags) {}

    @RegisterForReflection
    public record SubnetGroup(String name, String description, String vpcId, String arn,
                              List<Subnet> subnets, Map<String, String> tags) {}

    @RegisterForReflection
    public record Subnet(String identifier, String availabilityZone) {}

    @RegisterForReflection
    public record Snapshot(String name, String arn, String clusterName, String nodeType, String engine,
                           String engineVersion, int numShards, double createdAt,
                           byte[] data, Map<String, String> tags) {}

    @RegisterForReflection
    public record Event(String sourceName, String sourceType, String message, double date) {}

    public record EngineVersion(String engine, String version, String family) {}

    public record Parameter(String name, String value, String dataType, String allowedValues) {}
}
