package io.github.hectorvent.floci.services.ram.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A RAM managed permission: either an AWS managed entry from the built-in catalog or a customer
 * managed permission created through {@code CreatePermission}. Every version is kept, including
 * deleted ones, because {@code ListPermissionVersions} keeps reporting DELETED versions.
 *
 * @param permissionType {@code AWS_MANAGED} or {@code CUSTOMER_MANAGED}
 * @param defaultVersion the version number new resource shares attach
 */
@RegisterForReflection
public record RamPermission(
        String arn,
        String name,
        String resourceType,
        String permissionType,
        boolean resourceTypeDefault,
        String ownerAccountId,
        int defaultVersion,
        List<PermissionVersion> versions,
        Instant creationTime,
        Instant lastUpdatedTime,
        Map<String, String> tags) {

    public RamPermission {
        versions = versions == null ? List.of() : List.copyOf(versions);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** One version of a permission's policy template. */
    @RegisterForReflection
    public record PermissionVersion(
            int version,
            String policyTemplate,
            boolean deleted,
            Instant creationTime,
            Instant lastUpdatedTime) {

        public PermissionVersion markDeleted(Instant at) {
            return new PermissionVersion(version, policyTemplate, true, creationTime, at);
        }
    }

    @JsonIgnore
    public boolean isCustomerManaged() {
        return "CUSTOMER_MANAGED".equals(permissionType);
    }

    public Optional<PermissionVersion> findVersion(int number) {
        return versions.stream().filter(v -> v.version() == number).findFirst();
    }

    public PermissionVersion defaultVersionEntry() {
        return findVersion(defaultVersion).orElseThrow();
    }

    /**
     * Only the default version can be attached to a new resource share, so every other live
     * version is UNATTACHABLE.
     */
    public String statusOf(PermissionVersion version) {
        if (version.deleted()) {
            return "DELETED";
        }
        return version.version() == defaultVersion ? "ATTACHABLE" : "UNATTACHABLE";
    }

    public RamPermission withVersions(List<PermissionVersion> newVersions, int newDefault, Instant at) {
        return new RamPermission(arn, name, resourceType, permissionType, resourceTypeDefault, ownerAccountId,
                newDefault, newVersions, creationTime, at, tags);
    }

    public RamPermission withTags(Map<String, String> newTags) {
        return new RamPermission(arn, name, resourceType, permissionType, resourceTypeDefault, ownerAccountId,
                defaultVersion, versions, creationTime, lastUpdatedTime, newTags);
    }
}
