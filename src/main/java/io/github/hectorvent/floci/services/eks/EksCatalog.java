package io.github.hectorvent.floci.services.eks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS-managed EKS catalogs: access policies, Kubernetes versions, and add-on
 * versions. These are account-scoped reads (no cluster required).
 */
public final class EksCatalog {

    static final String ACCESS_POLICY_ARN_PREFIX = "arn:aws:eks::aws:cluster-access-policy/";

    private static final List<String> ACCESS_POLICY_NAMES = List.of(
            "AmazonEKSClusterAdminPolicy",
            "AmazonEKSAdminPolicy",
            "AmazonEKSAdminViewPolicy",
            "AmazonEKSEditPolicy",
            "AmazonEKSViewPolicy",
            "AmazonEKSAutoNodePolicy",
            "AmazonEKSBlockStoragePolicy",
            "AmazonEKSComputePolicy",
            "AmazonEKSLoadBalancingPolicy",
            "AmazonEKSNetworkingPolicy",
            "AmazonEKSHybridPolicy");

    private static final String VPC_CNI_SCHEMA = """
            {"$schema":"https://json-schema.org/draft-06/schema#","description":"Configuration for Amazon VPC CNI","type":"object","additionalProperties":false,"properties":{"enableNetworkPolicy":{"type":"string","enum":["true","false"]},"env":{"type":"object","additionalProperties":{"type":"string"}}}}""";

    private static final String GENERIC_ADDON_SCHEMA = """
            {"$schema":"https://json-schema.org/draft-06/schema#","type":"object","additionalProperties":false,"properties":{"replicaCount":{"type":"integer","minimum":1}}}""";

    private EksCatalog() {
    }

    public static List<Map<String, Object>> accessPolicies() {
        List<Map<String, Object>> policies = new ArrayList<>();
        for (String name : ACCESS_POLICY_NAMES) {
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("name", name);
            policy.put("arn", ACCESS_POLICY_ARN_PREFIX + name);
            policies.add(policy);
        }
        return policies;
    }

    public static List<Map<String, Object>> clusterVersions() {
        return List.of(
                clusterVersion("1.33", false, "standard-support"),
                clusterVersion("1.32", false, "standard-support"),
                clusterVersion("1.31", true, "standard-support"),
                clusterVersion("1.30", false, "standard-support"),
                clusterVersion("1.29", false, "extended-support"),
                clusterVersion("1.28", false, "unsupported"));
    }

    public static List<Map<String, Object>> addons() {
        return List.of(
                addon("vpc-cni", "networking", "v1.19.2-eksbuild.1", "v1.18.5-eksbuild.1"),
                addon("coredns", "coredns", "v1.11.4-eksbuild.2", "v1.11.3-eksbuild.1"),
                addon("kube-proxy", "kube-proxy", "v1.31.3-eksbuild.2", "v1.30.9-eksbuild.3"),
                addon("aws-ebs-csi-driver", "storage", "v1.38.1-eksbuild.1", "v1.37.0-eksbuild.1"));
    }

    public static String configurationSchema(String addonName, String addonVersion) {
        for (Map<String, Object> addon : addons()) {
            if (!addonName.equals(addon.get("addonName"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> versions = (List<Map<String, Object>>) addon.get("addonVersions");
            for (Map<String, Object> version : versions) {
                if (addonVersion.equals(version.get("addonVersion"))) {
                    return "vpc-cni".equals(addonName) ? VPC_CNI_SCHEMA : GENERIC_ADDON_SCHEMA;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> clusterVersion(String version, boolean defaultVersion, String status) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("clusterVersion", version);
        info.put("clusterType", "eks");
        info.put("defaultPlatformVersion", "eks.1");
        info.put("defaultVersion", defaultVersion);
        info.put("status", status);
        info.put("versionStatus", switch (status) {
            case "extended-support" -> "EXTENDED_SUPPORT";
            case "unsupported" -> "UNSUPPORTED";
            default -> "STANDARD_SUPPORT";
        });
        info.put("kubernetesPatchVersion", version + ".0");
        return info;
    }

    private static Map<String, Object> addon(String name, String type, String... versions) {
        Map<String, Object> addon = new LinkedHashMap<>();
        addon.put("addonName", name);
        addon.put("type", type);
        addon.put("publisher", "eks");
        addon.put("owner", "amazon");
        addon.put("defaultNamespace", "kube-system");
        List<Map<String, Object>> addonVersions = new ArrayList<>();
        for (int i = 0; i < versions.length; i++) {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("addonVersion", versions[i]);
            version.put("architecture", List.of("amd64", "arm64"));
            version.put("compatibilities", List.of(
                    compatibility("1.33", i == 0),
                    compatibility("1.32", i == 0),
                    compatibility("1.31", i == 0)));
            version.put("requiresConfiguration", false);
            version.put("requiresIamPermissions", "vpc-cni".equals(name) || "aws-ebs-csi-driver".equals(name));
            addonVersions.add(version);
        }
        addon.put("addonVersions", addonVersions);
        return addon;
    }

    private static Map<String, Object> compatibility(String clusterVersion, boolean defaultVersion) {
        Map<String, Object> compatibility = new LinkedHashMap<>();
        compatibility.put("clusterVersion", clusterVersion);
        compatibility.put("platformVersions", List.of("*"));
        compatibility.put("defaultVersion", defaultVersion);
        return compatibility;
    }
}
