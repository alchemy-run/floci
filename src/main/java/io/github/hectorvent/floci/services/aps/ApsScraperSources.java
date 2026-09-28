package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves an AMP scraper's collection source against the emulator's EKS and EC2 state, and owns
 * the per-scraper service-linked role AMP creates for collection.
 */
@ApplicationScoped
public class ApsScraperSources {

    private static final Logger LOG = Logger.getLogger(ApsScraperSources.class);
    static final String SCRAPER_SERVICE_PRINCIPAL = "scraper.aps.amazonaws.com";
    private static final int MAX_SUBNETS = 5;
    private static final int MAX_SECURITY_GROUPS = 5;

    private final EksService eks;
    private final Ec2Service ec2;
    private final IamService iam;

    @Inject
    public ApsScraperSources(EksService eks, Ec2Service ec2, IamService iam) {
        this.eks = eks;
        this.ec2 = ec2;
        this.iam = iam;
    }

    /** Validates {@code source} and returns a copy safe to persist. */
    public ObjectNode validateSource(String region, String accountId, JsonNode value) {
        ObjectNode source = ApsConfigurationValidator.object(value, "source");
        boolean eksSource = source.has("eksConfiguration");
        if (source.size() != 1 || eksSource == source.has("vpcConfiguration")) {
            throw ApsConfigurationValidator.invalid(
                    "source must contain exactly one of eksConfiguration or vpcConfiguration");
        }
        if (eksSource) {
            validateEksSource(region, accountId,
                    ApsConfigurationValidator.object(source.get("eksConfiguration"), "eksConfiguration"));
        } else {
            ObjectNode vpc = ApsConfigurationValidator.object(source.get("vpcConfiguration"), "vpcConfiguration");
            String vpcId = requireSubnets(region, ids(vpc.get("subnetIds"), "vpcConfiguration.subnetIds", MAX_SUBNETS), null);
            requireSecurityGroups(region,
                    ids(vpc.get("securityGroupIds"), "vpcConfiguration.securityGroupIds", MAX_SECURITY_GROUPS), vpcId);
        }
        return source.deepCopy();
    }

    private void validateEksSource(String region, String accountId, ObjectNode config) {
        String clusterArn = ApsConfigurationValidator.text(config.get("clusterArn"), "eksConfiguration.clusterArn");
        String clusterName = clusterName(clusterArn, region, accountId);
        Cluster cluster;
        try {
            cluster = eks.describeCluster(clusterName);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw clusterNotFound(clusterArn);
            }
            throw e;
        }
        if (!clusterArn.equals(cluster.getArn())) {
            throw clusterNotFound(clusterArn);
        }
        String clusterVpcId = cluster.getResourcesVpcConfig() == null
                || cluster.getResourcesVpcConfig().getVpcId() == null
                || cluster.getResourcesVpcConfig().getVpcId().isBlank()
                ? null : cluster.getResourcesVpcConfig().getVpcId();
        String vpcId = requireSubnets(region,
                ids(config.get("subnetIds"), "eksConfiguration.subnetIds", MAX_SUBNETS), clusterVpcId);
        if (config.has("securityGroupIds")) {
            requireSecurityGroups(region,
                    ids(config.get("securityGroupIds"), "eksConfiguration.securityGroupIds", MAX_SECURITY_GROUPS),
                    vpcId);
        }
    }

    private static String clusterName(String clusterArn, String region, String accountId) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(clusterArn);
        } catch (IllegalArgumentException e) {
            throw ApsConfigurationValidator.invalid("eksConfiguration.clusterArn must be an Amazon EKS cluster ARN");
        }
        if (!"eks".equals(arn.service()) || !arn.resource().startsWith("cluster/")
                || arn.resource().length() == "cluster/".length()) {
            throw ApsConfigurationValidator.invalid("eksConfiguration.clusterArn must be an Amazon EKS cluster ARN");
        }
        if (!region.equals(arn.region()) || !accountId.equals(arn.accountId())) {
            throw ApsConfigurationValidator.invalid(
                    "eksConfiguration.clusterArn must identify a cluster in the scraper's account and Region");
        }
        return arn.resource().substring("cluster/".length());
    }

    private static AwsException clusterNotFound(String clusterArn) {
        return new AwsException("ResourceNotFoundException", "EKS cluster not found: " + clusterArn, 404);
    }

    /** Requires every subnet to exist in one VPC (the expected one when given) and returns it. */
    private String requireSubnets(String region, List<String> subnetIds, String expectedVpcId) {
        Set<String> vpcIds = new LinkedHashSet<>();
        for (String subnetId : subnetIds) {
            Subnet subnet;
            try {
                subnet = ec2.requireSubnet(region, subnetId);
            } catch (AwsException e) {
                throw ApsConfigurationValidator.invalid("Subnet " + subnetId + " does not exist");
            }
            if (expectedVpcId != null && !expectedVpcId.equals(subnet.getVpcId())) {
                throw ApsConfigurationValidator.invalid("Subnet " + subnetId + " is not in the cluster VPC "
                        + expectedVpcId);
            }
            vpcIds.add(subnet.getVpcId());
        }
        if (vpcIds.size() != 1) {
            throw ApsConfigurationValidator.invalid("All subnets must belong to the same VPC");
        }
        return vpcIds.iterator().next();
    }

    private void requireSecurityGroups(String region, List<String> groupIds, String vpcId) {
        List<SecurityGroup> groups;
        try {
            groups = ec2.describeSecurityGroups(region, groupIds, List.of(), Map.of());
        } catch (AwsException e) {
            throw ApsConfigurationValidator.invalid("One or more security groups do not exist: " + e.getMessage());
        }
        for (SecurityGroup group : groups) {
            if (vpcId != null && !vpcId.equals(group.getVpcId())) {
                throw ApsConfigurationValidator.invalid("Security group " + group.getGroupId()
                        + " is not in VPC " + vpcId);
            }
        }
    }

    private static List<String> ids(JsonNode value, String field, int max) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > max) {
            throw ApsConfigurationValidator.invalid(field + " must contain between 1 and " + max + " IDs");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : value) {
            ids.add(ApsConfigurationValidator.text(id, field));
        }
        return ids;
    }

    /** Creates the service-linked role AMP uses to collect metrics for one scraper. */
    public String createScraperRole(String scraperId) {
        return iam.createServiceLinkedRole(SCRAPER_SERVICE_PRINCIPAL, scraperId.substring("s-".length()),
                "Allows Amazon Managed Service for Prometheus scraper " + scraperId + " to collect metrics.")
                .getArn();
    }

    public void deleteScraperRole(String roleArn) {
        if (roleArn == null || roleArn.isBlank()) {
            return;
        }
        String roleName = roleArn.substring(roleArn.lastIndexOf('/') + 1);
        try {
            iam.deleteServiceLinkedRole(roleName);
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Scraper role {0} was already deleted", roleName);
        }
    }
}
