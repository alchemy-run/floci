package io.github.hectorvent.floci.services.cloudhsmv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudhsmv2.model.CloudHsm;
import io.github.hectorvent.floci.services.cloudhsmv2.model.CloudHsmCluster;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CloudHSM V2 (JSON 1.1, target prefix {@code BaldrApiService.}).
 *
 * <p>Clusters settle immediately to {@code UNINITIALIZED} and HSMs to {@code ACTIVE}
 * so local reconcilers do not wait on the live 10–20 minute HSM provisioner.
 */
@ApplicationScoped
public class CloudHsmV2Service {

    static final String SERVICE = "cloudhsmv2";
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HEX = "0123456789abcdef";
    private static final String DEFAULT_BACKUP_DAYS = "90";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final String CLUSTER_CSR = """
            -----BEGIN CERTIFICATE REQUEST-----
            MIICXjCCAUYCAQAwGjEYMBYGA1UEAwwPQ2xvdWRIU00gQ2x1c3RlcjCCASIwDQYJ
            KoZIhvcNAQEBBQADggEPADCCAQoCggEBALDUMMYCSRFORFLOCIEMULATORONLYAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AwEAAaAAMA0GCSqGSIb3DQEBCwUAA4IBAQDummyCloudHsmClusterCsrForTests
            -----END CERTIFICATE REQUEST-----
            """;

    private final StorageBackend<String, CloudHsmCluster> store;
    private final RegionResolver regionResolver;
    private final Ec2Service ec2Service;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public CloudHsmV2Service(StorageFactory storageFactory, RegionResolver regionResolver, Ec2Service ec2Service) {
        this(storageFactory.create(SERVICE, "cloudhsmv2-clusters.json",
                new TypeReference<Map<String, CloudHsmCluster>>() {
                }), regionResolver, ec2Service);
    }

    CloudHsmV2Service(StorageBackend<String, CloudHsmCluster> store, RegionResolver regionResolver) {
        this(store, regionResolver, null);
    }

    CloudHsmV2Service(StorageBackend<String, CloudHsmCluster> store, RegionResolver regionResolver,
                      Ec2Service ec2Service) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.ec2Service = ec2Service;
    }

    public synchronized CloudHsmCluster createCluster(String region, String hsmType, List<String> subnetIds,
                                                      String sourceBackupId, String networkType, String mode,
                                                      String backupRetentionType, String backupRetentionValue,
                                                      Map<String, String> tags) {
        if (hsmType == null || hsmType.isBlank()) {
            throw invalid("HsmType is a required parameter.");
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw invalid("SubnetIds is a required parameter.");
        }

        Map<String, String> subnetMapping = new LinkedHashMap<>();
        String vpcId = null;
        int azIndex = 0;
        for (String subnetId : subnetIds) {
            if (subnetId == null || subnetId.isBlank()) {
                throw invalid("SubnetIds contains an invalid subnet id.");
            }
            Subnet subnet = findSubnet(region, subnetId);
            String az;
            String subnetVpc;
            if (subnet != null) {
                az = subnet.getAvailabilityZone();
                subnetVpc = subnet.getVpcId();
            } else {
                az = region + (char) ('a' + Math.min(azIndex, 25));
                subnetVpc = "vpc-00000000";
            }
            if (subnetMapping.containsKey(az)) {
                throw invalid("Each subnet must be in a different Availability Zone.");
            }
            if (vpcId == null) {
                vpcId = subnetVpc;
            } else if (!vpcId.equals(subnetVpc)) {
                throw invalid("All subnets must belong to the same VPC.");
            }
            subnetMapping.put(az, subnetId);
            azIndex++;
        }

        String clusterId = newId("cluster-", 11);
        CloudHsmCluster cluster = new CloudHsmCluster();
        cluster.setClusterId(clusterId);
        cluster.setRegion(region);
        cluster.setHsmType(hsmType);
        cluster.setState("UNINITIALIZED");
        cluster.setVpcId(vpcId);
        cluster.setSecurityGroup(createClusterSecurityGroup(region, vpcId, clusterId));
        cluster.setSourceBackupId(blankToNull(sourceBackupId));
        cluster.setNetworkType(networkType == null || networkType.isBlank() ? "IPV4" : networkType);
        cluster.setMode(mode == null || mode.isBlank() ? "FIPS" : mode);
        cluster.setBackupPolicy("DEFAULT");
        cluster.setBackupRetentionType(
                backupRetentionType == null || backupRetentionType.isBlank() ? "DAYS" : backupRetentionType);
        cluster.setBackupRetentionValue(
                backupRetentionValue == null || backupRetentionValue.isBlank()
                        ? DEFAULT_BACKUP_DAYS
                        : backupRetentionValue);
        cluster.setClusterCsr(CLUSTER_CSR);
        cluster.setCreateTimestamp(Instant.now().getEpochSecond());
        cluster.setSubnetMapping(subnetMapping);
        if (tags != null) {
            cluster.getTags().putAll(tags);
        }
        store.put(storageKey(region, clusterId), cluster);
        return cluster;
    }

    public List<CloudHsmCluster> describeClusters(String region, Map<String, List<String>> filters,
                                                  String nextToken, Integer maxResults) {
        List<CloudHsmCluster> clusters = store.scan(key -> key.startsWith(region + "::"));
        clusters.sort(Comparator.comparing(CloudHsmCluster::getClusterId));
        if (filters != null && !filters.isEmpty()) {
            clusters.removeIf(cluster -> !matchesFilters(cluster, filters));
        }
        int start = parseToken(nextToken);
        if (start < 0 || start > clusters.size()) {
            throw invalid("NextToken is invalid.");
        }
        int limit = maxResults == null || maxResults <= 0 ? DEFAULT_MAX_RESULTS : Math.min(maxResults, MAX_RESULTS);
        int end = Math.min(clusters.size(), start + limit);
        return new ArrayList<>(clusters.subList(start, end));
    }

    public String nextToken(String region, Map<String, List<String>> filters, String nextToken, Integer maxResults) {
        List<CloudHsmCluster> clusters = store.scan(key -> key.startsWith(region + "::"));
        if (filters != null && !filters.isEmpty()) {
            clusters.removeIf(cluster -> !matchesFilters(cluster, filters));
        }
        int start = parseToken(nextToken);
        int limit = maxResults == null || maxResults <= 0 ? DEFAULT_MAX_RESULTS : Math.min(maxResults, MAX_RESULTS);
        int end = Math.min(clusters.size(), start + limit);
        return end < clusters.size() ? Integer.toString(end) : null;
    }

    public synchronized CloudHsmCluster deleteCluster(String region, String clusterId) {
        CloudHsmCluster cluster = requireCluster(region, clusterId);
        boolean hasHsms = cluster.getHsms().stream().anyMatch(hsm -> !"DELETED".equals(hsm.getState()));
        if (hasHsms) {
            throw invalid("The cluster still contains HSMs. Delete all HSMs before deleting the cluster.");
        }
        store.delete(storageKey(region, cluster.getClusterId()));
        cluster.setState("DELETED");
        cluster.setHsms(List.of());
        return cluster;
    }

    public synchronized CloudHsmCluster modifyCluster(String region, String clusterId, String hsmType,
                                                      String backupRetentionType, String backupRetentionValue) {
        CloudHsmCluster cluster = requireCluster(region, clusterId);
        if (hsmType != null && !hsmType.isBlank()) {
            cluster.setHsmType(hsmType);
        }
        if (backupRetentionType != null && !backupRetentionType.isBlank()) {
            cluster.setBackupRetentionType(backupRetentionType);
        }
        if (backupRetentionValue != null && !backupRetentionValue.isBlank()) {
            cluster.setBackupRetentionValue(backupRetentionValue);
        }
        store.put(storageKey(region, cluster.getClusterId()), cluster);
        return cluster;
    }

    public synchronized CloudHsm createHsm(String region, String clusterId, String availabilityZone, String ipAddress) {
        CloudHsmCluster cluster = requireCluster(region, clusterId);
        if (availabilityZone == null || availabilityZone.isBlank()) {
            throw invalid("AvailabilityZone is a required parameter.");
        }
        String subnetId = cluster.getSubnetMapping().get(availabilityZone);
        if (subnetId == null) {
            throw invalid("AvailabilityZone is not covered by the cluster's subnets.");
        }
        CloudHsm hsm = new CloudHsm();
        hsm.setHsmId(newId("hsm-", 11));
        hsm.setClusterId(cluster.getClusterId());
        hsm.setAvailabilityZone(availabilityZone);
        hsm.setSubnetId(subnetId);
        hsm.setEniId("eni-" + randomHex(17));
        hsm.setEniIp(ipAddress == null || ipAddress.isBlank()
                ? "10.0." + Math.abs(availabilityZone.hashCode() % 200) + "." + (10 + cluster.getHsms().size())
                : ipAddress);
        hsm.setHsmType(cluster.getHsmType());
        hsm.setState("ACTIVE");
        cluster.getHsms().add(hsm);
        store.put(storageKey(region, cluster.getClusterId()), cluster);
        return hsm;
    }

    public synchronized String deleteHsm(String region, String clusterId, String hsmId, String eniId, String eniIp) {
        CloudHsmCluster cluster = requireCluster(region, clusterId);
        CloudHsm match = null;
        for (CloudHsm hsm : cluster.getHsms()) {
            if (hsmId != null && hsmId.equals(hsm.getHsmId())) {
                match = hsm;
                break;
            }
            if (eniId != null && eniId.equals(hsm.getEniId())) {
                match = hsm;
                break;
            }
            if (eniIp != null && eniIp.equals(hsm.getEniIp())) {
                match = hsm;
                break;
            }
        }
        if (match == null) {
            throw notFound("HSM not found in cluster " + cluster.getClusterId() + ".");
        }
        cluster.getHsms().remove(match);
        store.put(storageKey(region, cluster.getClusterId()), cluster);
        return match.getHsmId();
    }

    public Map<String, String> listTags(String region, String resourceId) {
        return new LinkedHashMap<>(requireCluster(region, resourceId).getTags());
    }

    public synchronized void tagResource(String region, String resourceId, Map<String, String> tags) {
        CloudHsmCluster cluster = requireCluster(region, resourceId);
        if (tags != null) {
            cluster.getTags().putAll(tags);
        }
        store.put(storageKey(region, cluster.getClusterId()), cluster);
    }

    public synchronized void untagResource(String region, String resourceId, List<String> tagKeys) {
        CloudHsmCluster cluster = requireCluster(region, resourceId);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                cluster.getTags().remove(key);
            }
        }
        store.put(storageKey(region, cluster.getClusterId()), cluster);
    }

    private CloudHsmCluster requireCluster(String region, String clusterId) {
        if (clusterId == null || clusterId.isBlank()) {
            throw invalid("ClusterId is a required parameter.");
        }
        return store.get(storageKey(region, clusterId)).orElseThrow(
                () -> notFound("CloudHSM cluster " + clusterId + " not found."));
    }

    private boolean matchesFilters(CloudHsmCluster cluster, Map<String, List<String>> filters) {
        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            Set<String> wanted = Set.copyOf(values);
            boolean match = switch (entry.getKey()) {
                case "clusterIds" -> wanted.contains(cluster.getClusterId());
                case "vpcIds" -> wanted.contains(cluster.getVpcId());
                case "states" -> wanted.contains(cluster.getState());
                case "hsmType" -> wanted.contains(cluster.getHsmType());
                default -> true;
            };
            if (!match) {
                return false;
            }
        }
        return true;
    }

    private Subnet findSubnet(String region, String subnetId) {
        if (ec2Service == null) {
            return null;
        }
        try {
            Optional<Subnet> found = ec2Service.findSubnetById(region, subnetId);
            return found.orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String createClusterSecurityGroup(String region, String vpcId, String clusterId) {
        if (ec2Service != null && vpcId != null) {
            try {
                SecurityGroup sg = ec2Service.createSecurityGroup(
                        region, "cloudhsm-cluster-" + clusterId, "CloudHSM cluster " + clusterId, vpcId);
                return sg.getGroupId();
            } catch (RuntimeException ignored) {
                // Fall through to a synthetic group id — cluster create must still succeed.
            }
        }
        return "sg-" + randomHex(17);
    }

    private static String storageKey(String region, String clusterId) {
        return region + "::" + clusterId;
    }

    private String newId(String prefix, int length) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HEX.charAt(random.nextInt(HEX.length())));
        }
        return sb.toString();
    }

    private static int parseToken(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(nextToken);
        } catch (NumberFormatException e) {
            throw invalid("NextToken is invalid.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static AwsException invalid(String message) {
        return new AwsException("CloudHsmInvalidRequestException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("CloudHsmResourceNotFoundException", message, 400);
    }
}
