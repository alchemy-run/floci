package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only AWS catalog snapshots, independent of the configured k3s runtime. */
@ApplicationScoped
public class EksCatalogService {
    private static final String INVALID = "InvalidParameterException";
    private static final String VPC_CNI_VERSION = "v1.12.0-eksbuild.1";
    private static final List<String> ACCESS_POLICIES = List.of(
            "AmazonEKSAdminPolicy", "AmazonEKSClusterAdminPolicy", "AmazonEKSEditPolicy", "AmazonEKSViewPolicy");
    private static final List<String> VERSION_STATUSES = List.of("STANDARD_SUPPORT", "EXTENDED_SUPPORT", "UNSUPPORTED");

    // Bounded release-calendar snapshot; these are not k3s runtime versions.
    private static final List<Map<String, Object>> CLUSTER_VERSIONS = List.of(
            clusterVersion("1.34", true, "2025-10-02", "2026-12-02", "2027-12-02", "STANDARD_SUPPORT"),
            clusterVersion("1.33", false, "2025-05-29", "2026-07-29", "2027-07-29", "EXTENDED_SUPPORT"),
            clusterVersion("1.32", false, "2025-01-23", "2026-03-23", "2027-03-23", "EXTENDED_SUPPORT"),
            clusterVersion("1.31", false, "2024-09-26", "2025-11-26", "2026-11-26", "EXTENDED_SUPPORT"),
            clusterVersion("1.30", false, "2024-05-23", "2025-07-23", "2026-07-23", "UNSUPPORTED"));

    // Historical VPC CNI configuration published in the EKS advanced-configuration example.
    private static final String VPC_CNI_SCHEMA = """
            {
              "$ref": "#/definitions/VpcCni",
              "$schema": "http://json-schema.org/draft-06/schema#",
              "definitions": {
                "Env": {
                  "additionalProperties": false,
                  "properties": {
                    "AWS_VPC_K8S_CNI_CUSTOM_NETWORK_CFG": {"format": "boolean", "type": "string"},
                    "AWS_VPC_K8S_CNI_EXTERNALSNAT": {"format": "boolean", "type": "string"},
                    "ENABLE_POD_ENI": {"format": "boolean", "type": "string"},
                    "ENABLE_PREFIX_DELEGATION": {"format": "boolean", "type": "string"},
                    "WARM_ENI_TARGET": {"format": "integer", "type": "string"},
                    "WARM_PREFIX_TARGET": {"format": "integer", "type": "string"}
                  },
                  "title": "Env",
                  "type": "object"
                },
                "Limits": {
                  "additionalProperties": false,
                  "properties": {"cpu": {"type": "string"}, "memory": {"type": "string"}},
                  "title": "Limits",
                  "type": "object"
                },
                "Resources": {
                  "additionalProperties": false,
                  "properties": {
                    "limits": {"$ref": "#/definitions/Limits"},
                    "requests": {"$ref": "#/definitions/Limits"}
                  },
                  "title": "Resources",
                  "type": "object"
                },
                "VpcCni": {
                  "additionalProperties": false,
                  "properties": {
                    "env": {"$ref": "#/definitions/Env"},
                    "resources": {"$ref": "#/definitions/Resources"}
                  },
                  "title": "VpcCni",
                  "type": "object"
                }
              }
            }
            """;
    private static final List<Map<String, Object>> VPC_CNI_COMPATIBILITIES = List.of(
            Map.of("clusterVersion", "1.23", "platformVersions", List.of("*"), "defaultVersion", false),
            Map.of("clusterVersion", "1.24", "platformVersions", List.of("*"), "defaultVersion", false));

    private final RegionResolver regionResolver;

    @Inject
    public EksCatalogService(RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
    }

    public Map<String, Object> listAccessPolicies(String maxResults, String nextToken) {
        String partition = AwsRegions.partitionFor(regionResolver.getRegion());
        List<Map<String, Object>> policies = ACCESS_POLICIES.stream()
                .map(name -> Map.<String, Object>of("name", name,
                        "arn", "arn:" + partition + ":eks::aws:cluster-access-policy/" + name))
                .toList();
        return page("accessPolicies", policies, "name", maxResults, nextToken);
    }

    public Map<String, Object> describeClusterVersions(String clusterType, String maxResults, String nextToken,
                                                       String defaultOnly, String includeAll, List<String> versions,
                                                       String status, String versionStatus) {
        boolean onlyDefault = booleanParameter(defaultOnly, "defaultOnly");
        boolean all = booleanParameter(includeAll, "includeAll");
        String legacyStatus = status == null ? null : status.toUpperCase(Locale.ROOT).replace('-', '_');
        if (status != null && (!VERSION_STATUSES.contains(legacyStatus)
                || !status.equals(legacyStatus.toLowerCase(Locale.ROOT).replace('_', '-')))) {
            throw new AwsException(INVALID, "Invalid status", 400);
        }
        if (versionStatus != null && !VERSION_STATUSES.contains(versionStatus)) {
            throw new AwsException(INVALID, "Invalid versionStatus", 400);
        }
        if (legacyStatus != null && versionStatus != null && !legacyStatus.equals(versionStatus)) {
            throw new AwsException(INVALID, "status and versionStatus must agree", 400);
        }
        String requestedStatus = versionStatus != null ? versionStatus : legacyStatus;
        List<Map<String, Object>> matching = CLUSTER_VERSIONS.stream()
                .filter(version -> clusterType == null || clusterType.equals(version.get("clusterType")))
                .filter(version -> !onlyDefault || Boolean.TRUE.equals(version.get("defaultVersion")))
                .filter(version -> all || !"UNSUPPORTED".equals(version.get("versionStatus")))
                .filter(version -> matches(versions, (String) version.get("clusterVersion")))
                .filter(version -> requestedStatus == null || requestedStatus.equals(version.get("versionStatus")))
                .toList();
        return page("clusterVersions", matching, "clusterVersion", maxResults, nextToken);
    }

    public Map<String, Object> describeAddonVersions(String addonName, String kubernetesVersion,
                                                    List<String> types, List<String> publishers, List<String> owners,
                                                    String maxResults, String nextToken) {
        List<Map<String, Object>> compatibilities = VPC_CNI_COMPATIBILITIES.stream()
                .filter(value -> kubernetesVersion == null || kubernetesVersion.equals(value.get("clusterVersion")))
                .toList();
        List<Map<String, Object>> addons = List.of();
        if ((addonName == null || "vpc-cni".equals(addonName)) && matches(types, "networking")
                && matches(publishers, "eks") && matches(owners, "aws") && !compatibilities.isEmpty()) {
            Map<String, Object> version = Map.of("addonVersion", VPC_CNI_VERSION,
                    "architecture", List.of("amd64", "arm64"), "compatibilities", compatibilities,
                    "requiresConfiguration", false, "requiresIamPermissions", true);
            addons = List.of(Map.of("addonName", "vpc-cni", "type", "networking", "publisher", "eks",
                    "owner", "aws", "addonVersions", List.of(version)));
        }
        return page("addons", addons, "addonName", maxResults, nextToken);
    }

    public Map<String, Object> describeAddonConfiguration(String addonName, String addonVersion) {
        if (addonName == null || addonName.isBlank() || addonVersion == null || addonVersion.isBlank()) {
            throw new AwsException(INVALID, "addonName and addonVersion are required", 400);
        }
        if (!"vpc-cni".equals(addonName) || !VPC_CNI_VERSION.equals(addonVersion)) {
            throw new AwsException("ResourceNotFoundException", "No configuration found for add-on "
                    + addonName + " version " + addonVersion, 404);
        }
        return Map.of("addonName", addonName, "addonVersion", addonVersion,
                "configurationSchema", VPC_CNI_SCHEMA);
    }

    private static Map<String, Object> clusterVersion(String version, boolean isDefault, String release,
                                                      String standardEnd, String extendedEnd, String status) {
        return Map.of("clusterVersion", version, "clusterType", "eks", "defaultVersion", isDefault,
                "releaseDate", epoch(release), "endOfStandardSupportDate", epoch(standardEnd),
                "endOfExtendedSupportDate", epoch(extendedEnd), "versionStatus", status,
                "status", status.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private static long epoch(String date) {
        return Instant.parse(date + "T00:00:00Z").getEpochSecond();
    }

    private static boolean booleanParameter(String value, String name) {
        if (value == null || "false".equals(value)) {
            return false;
        }
        if ("true".equals(value)) {
            return true;
        }
        throw new AwsException(INVALID, name + " must be true or false", 400);
    }

    private static boolean matches(List<String> filter, String value) {
        return filter == null || filter.isEmpty() || filter.contains(value);
    }

    private static Map<String, Object> page(String resultKey, List<Map<String, Object>> values, String sortKey,
                                            String maxResults, String nextToken) {
        PaginatedResult<Map<String, Object>> result = Pagination.paginate(values,
                value -> (String) value.get(sortKey), Pagination.parseMaxResults(maxResults, INVALID),
                nextToken, 100, INVALID);
        return result.nextToken() == null ? Map.of(resultKey, result.items())
                : Map.of(resultKey, result.items(), "nextToken", result.nextToken());
    }
}
