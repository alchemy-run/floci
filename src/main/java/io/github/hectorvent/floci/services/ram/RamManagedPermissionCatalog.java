package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.services.ram.model.RamPermission;
import io.github.hectorvent.floci.services.ram.model.RamPermission.PermissionVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The AWS managed permissions floci serves. Every account sees them from ListPermissions and
 * GetPermission under the account-less {@code arn:aws:ram::aws:permission/<name>} ARN.
 *
 * <p>Only permissions whose policy template AWS publishes are included, so the actions returned
 * by GetPermission match real AWS: the {@code ec2:Subnet} default (RAM user guide, "Types of
 * managed permissions") and the AppSync source API permissions (AppSync developer guide,
 * "Merged APIs"). AWS publishes neither version numbers nor creation dates for these, so
 * every entry is version 1 with a fixed timestamp.
 */
final class RamManagedPermissionCatalog {

    private static final Instant PUBLISHED = Instant.parse("2023-05-01T00:00:00Z");

    private static final List<RamPermission> PERMISSIONS = List.of(
            managed("AWSRAMDefaultPermissionSubnet", "ec2:Subnet", true,
                    "{\"Effect\":\"Allow\",\"Action\":[\"ec2:RunInstances\","
                            + "\"ec2:CreateNetworkInterface\",\"ec2:DescribeSubnets\"]}"),
            managed("AWSRAMPermissionAppSyncSourceApiOperationAccess", "appsync:Apis", true,
                    "{\"Effect\":\"Allow\",\"Action\":[\"appsync:AssociateMergedGraphqlApi\","
                            + "\"appsync:SourceGraphQL\"]}"),
            managed("AWSRAMPermissionAppSyncAllowSourceGraphQLAccess", "appsync:Apis", false,
                    "{\"Effect\":\"Allow\",\"Action\":[\"appsync:SourceGraphQL\"]}"));

    private RamManagedPermissionCatalog() {}

    static List<RamPermission> all() {
        return PERMISSIONS;
    }

    static Optional<RamPermission> find(String arn) {
        return PERMISSIONS.stream().filter(p -> p.arn().equals(arn)).findFirst();
    }

    /** The permission RAM attaches for {@code resourceType} when a share names none. */
    static Optional<RamPermission> defaultFor(String resourceType) {
        return PERMISSIONS.stream()
                .filter(RamPermission::resourceTypeDefault)
                .filter(p -> p.resourceType().equalsIgnoreCase(resourceType))
                .findFirst();
    }

    private static RamPermission managed(String name, String resourceType, boolean resourceTypeDefault,
                                         String policyTemplate) {
        return new RamPermission("arn:aws:ram::aws:permission/" + name, name, resourceType, "AWS_MANAGED",
                resourceTypeDefault, null, 1,
                List.of(new PermissionVersion(1, policyTemplate, false, PUBLISHED, PUBLISHED)),
                PUBLISHED, PUBLISHED, Map.of());
    }
}
