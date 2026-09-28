package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApsScraperSourcesTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER_ARN = "arn:aws:eks:us-east-1:000000000000:cluster/scraped";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EksService eks = mock(EksService.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final IamService iam = mock(IamService.class);
    private final ApsScraperSources sources = new ApsScraperSources(eks, ec2, iam);

    private static ObjectNode eksSource(String clusterArn, List<String> subnets) {
        return JSON.valueToTree(Map.of("eksConfiguration", Map.of("clusterArn", clusterArn, "subnetIds", subnets)));
    }

    private void givenCluster() {
        Cluster cluster = new Cluster();
        cluster.setName("scraped");
        cluster.setArn(CLUSTER_ARN);
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setVpcId("vpc-cluster");
        cluster.setResourcesVpcConfig(vpcConfig);
        when(eks.describeCluster("scraped")).thenReturn(cluster);
    }

    private void givenSubnet(String subnetId, String vpcId) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        when(ec2.requireSubnet(REGION, subnetId)).thenReturn(subnet);
    }

    @Test
    void eksSourceInTheClusterVpcIsAccepted() {
        givenCluster();
        givenSubnet("subnet-a", "vpc-cluster");
        givenSubnet("subnet-b", "vpc-cluster");
        ObjectNode source = eksSource(CLUSTER_ARN, List.of("subnet-a", "subnet-b"));
        assertEquals(source, sources.validateSource(REGION, ACCOUNT, source));
    }

    @Test
    void missingClusterIsResourceNotFound() {
        when(eks.describeCluster("scraped")).thenThrow(
                new AwsException("ResourceNotFoundException", "No cluster found for name: scraped", 404));
        AwsException error = assertThrows(AwsException.class,
                () -> sources.validateSource(REGION, ACCOUNT, eksSource(CLUSTER_ARN, List.of("subnet-a"))));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void subnetsOutsideTheClusterVpcOrMissingAreRejected() {
        givenCluster();
        givenSubnet("subnet-other", "vpc-other");
        when(ec2.requireSubnet(REGION, "subnet-missing")).thenThrow(
                new AwsException("InvalidSubnetID.NotFound", "missing", 400));
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> sources.validateSource(
                REGION, ACCOUNT, eksSource(CLUSTER_ARN, List.of("subnet-other")))).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> sources.validateSource(
                REGION, ACCOUNT, eksSource(CLUSTER_ARN, List.of("subnet-missing")))).getErrorCode());
    }

    @Test
    void clusterArnMustBeAnEksClusterInTheScraperAccountAndRegion() {
        for (String arn : List.of("not-an-arn", "arn:aws:eks:eu-west-1:000000000000:cluster/scraped",
                "arn:aws:eks:us-east-1:111111111111:cluster/scraped", "arn:aws:ecs:us-east-1:000000000000:cluster/x")) {
            assertEquals("ValidationException", assertThrows(AwsException.class, () -> sources.validateSource(
                    REGION, ACCOUNT, eksSource(arn, List.of("subnet-a")))).getErrorCode(), arn);
        }
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> sources.validateSource(
                REGION, ACCOUNT, JSON.valueToTree(Map.of()))).getErrorCode());
    }

    @Test
    void scraperRoleIsAServiceLinkedRoleDeletedWithTheScraper() {
        IamRole role = new IamRole();
        role.setArn("arn:aws:iam::000000000000:role/aws-service-role/scraper.aps.amazonaws.com/"
                + "AWSServiceRoleForScraperAps_12345678-1234-1234-1234-123456789012");
        when(iam.createServiceLinkedRole(eq("scraper.aps.amazonaws.com"), eq("12345678-1234-1234-1234-123456789012"),
                anyString())).thenReturn(role);
        assertEquals(role.getArn(), sources.createScraperRole("s-12345678-1234-1234-1234-123456789012"));

        sources.deleteScraperRole(role.getArn());
        verify(iam).deleteServiceLinkedRole("AWSServiceRoleForScraperAps_12345678-1234-1234-1234-123456789012");
        when(iam.deleteServiceLinkedRole(anyString())).thenThrow(new AwsException("NoSuchEntity", "gone", 404));
        assertDoesNotThrow(() -> sources.deleteScraperRole(role.getArn()));
    }
}
