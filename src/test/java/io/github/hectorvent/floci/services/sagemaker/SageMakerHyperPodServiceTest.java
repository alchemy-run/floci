package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.Scheduler;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.StateEvents;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SageMakerHyperPodServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/hyperpod";
    private static final String EKS = "arn:aws:eks:us-east-1:000000000000:cluster/governance";

    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final SageMakerHyperPodService service = new SageMakerHyperPodService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"), mapper, clock,
            Set.of("lifecycle-bucket")::contains, Set.of(ROLE)::contains,
            arn -> EKS.equals(arn) ? Optional.of("ACTIVE") : Optional.empty(),
            StateEvents.NONE, Scheduler.NONE);

    @Test
    void describeMissingHyperPodResourcesIsResourceNotFound() {
        assertNotFound(() -> service.describeCluster(json("{\"ClusterName\":\"alchemy-nonexistent-hyperpod-cluster-probe\"}"), REGION));
        assertNotFound(() -> service.describeClusterSchedulerConfig(json("{\"ClusterSchedulerConfigId\":\"abcdef012345\"}"), REGION));
        assertNotFound(() -> service.describeComputeQuota(json("{\"ComputeQuotaId\":\"abcdef012345\"}"), REGION));
        assertNotFound(() -> service.deleteCluster(json("{\"ClusterName\":\"missing\"}"), REGION));
        assertNotFound(() -> service.deleteClusterSchedulerConfig(json("{\"ClusterSchedulerConfigId\":\"abcdef012345\"}"), REGION));
        assertEquals(0, service.listClusters(json("{}"), REGION).path("ClusterSummaries").size());
        AwsException badId = assertThrows(AwsException.class,
                () -> service.describeComputeQuota(json("{\"ComputeQuotaId\":\"NOT-AN-ID\"}"), REGION));
        assertEquals("ValidationException", badId.getErrorCode());
    }

    @Test
    void clusterRequestingInstancesExceedsTheZeroHyperPodQuota() {
        AwsException quota = assertThrows(AwsException.class, () -> service.createCluster(json(slurmCluster(1)), REGION));
        assertEquals("ResourceLimitExceeded", quota.getErrorCode());
        assertTrue(quota.getMessage().contains("'ml.t3.medium for cluster usage' is 0 Instances"));
        assertNotFound(() -> service.describeCluster(json("{\"ClusterName\":\"slurm\"}"), REGION));
    }

    @Test
    void createClusterValidatesRoleBucketAndEksOrchestrator() {
        AwsException role = assertThrows(AwsException.class, () -> service.createCluster(json(
                slurmCluster(0).replace(ROLE, "arn:aws:iam::000000000000:role/missing")), REGION));
        assertEquals("ValidationException", role.getErrorCode());
        AwsException bucket = assertThrows(AwsException.class, () -> service.createCluster(json(
                slurmCluster(0).replace("lifecycle-bucket", "missing-bucket")), REGION));
        assertEquals("ValidationException", bucket.getErrorCode());
        AwsException eks = assertThrows(AwsException.class, () -> service.createCluster(json(
                eksCluster().replace(EKS, EKS + "-missing")), REGION));
        assertEquals("ValidationException", eks.getErrorCode());
    }

    @Test
    void zeroInstanceClusterLifecycleHasNoNodes() {
        String arn = service.createCluster(json(slurmCluster(0)), REGION).path("ClusterArn").asText();
        assertTrue(arn.matches("arn:aws:sagemaker:us-east-1:000000000000:cluster/[a-z0-9]{12}"));
        assertEquals("Creating", describeCluster("slurm").path("ClusterStatus").asText());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        JsonNode described = describeCluster(arn);
        assertEquals("InService", described.path("ClusterStatus").asText());
        assertEquals(0, described.path("InstanceGroups").get(0).path("CurrentCount").asInt());
        assertEquals(0, service.listClusterNodes(json("{\"ClusterName\":\"slurm\"}"), REGION)
                .path("ClusterNodeSummaries").size());

        service.deleteCluster(json("{\"ClusterName\":\"slurm\"}"), REGION);
        assertEquals("Deleting", describeCluster("slurm").path("ClusterStatus").asText());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertNotFound(() -> describeCluster("slurm"));
    }

    @Test
    void clusterPolicyIsVersionedAndLimitedToOnePerEksCluster() {
        String clusterArn = inServiceEksCluster();
        AwsException slurm = assertThrows(AwsException.class, () -> service.createClusterSchedulerConfig(json(
                policy("p", slurmClusterArn())), REGION));
        assertEquals("ValidationException", slurm.getErrorCode());

        JsonNode created = service.createClusterSchedulerConfig(json(policy("p", clusterArn)), REGION);
        String id = created.path("ClusterSchedulerConfigId").asText();
        assertTrue(created.path("ClusterSchedulerConfigArn").asText().endsWith(":cluster-scheduler-config/" + id));
        assertEquals("Creating", describePolicy(id).path("Status").asText());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertEquals("Created", describePolicy(id).path("Status").asText());

        AwsException second = assertThrows(AwsException.class,
                () -> service.createClusterSchedulerConfig(json(policy("q", clusterArn)), REGION));
        assertEquals("ConflictException", second.getErrorCode());

        AwsException staleVersion = assertThrows(AwsException.class, () -> service.updateClusterSchedulerConfig(json(
                "{\"ClusterSchedulerConfigId\":\"" + id + "\",\"TargetVersion\":7,\"Description\":\"v2\"}"), REGION));
        assertEquals("ConflictException", staleVersion.getErrorCode());
        JsonNode updated = service.updateClusterSchedulerConfig(json(
                "{\"ClusterSchedulerConfigId\":\"" + id + "\",\"TargetVersion\":1,\"Description\":\"v2\"}"), REGION);
        assertEquals(2, updated.path("ClusterSchedulerConfigVersion").asInt());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        JsonNode described = describePolicy(id);
        assertEquals("Updated", described.path("Status").asText());
        assertEquals("v2", described.path("Description").asText());
        assertEquals(75, described.path("SchedulerConfig").path("PriorityClasses").get(0).path("Weight").asInt());

        service.deleteClusterSchedulerConfig(json("{\"ClusterSchedulerConfigId\":\"" + id + "\"}"), REGION);
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertNotFound(() -> describePolicy(id));
    }

    @Test
    void computeQuotaIsUniquePerTeamAndVersioned() {
        String clusterArn = inServiceEksCluster();
        String request = """
                {"Name":"research","ClusterArn":"%s","ComputeQuotaTarget":{"TeamName":"research","FairShareWeight":10},
                 "ComputeQuotaConfig":{"ComputeQuotaResources":[{"InstanceType":"ml.t3.medium","Count":1}]},
                 "Tags":[{"Key":"purpose","Value":"test"}]}
                """.formatted(clusterArn);
        String id = service.createComputeQuota(json(request), REGION).path("ComputeQuotaId").asText();
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        JsonNode described = service.describeComputeQuota(json("{\"ComputeQuotaId\":\"" + id + "\"}"), REGION);
        assertEquals("Created", described.path("Status").asText());
        assertEquals("Enabled", described.path("ActivationState").asText());
        assertEquals("research", described.path("ComputeQuotaTarget").path("TeamName").asText());

        AwsException sameTeam = assertThrows(AwsException.class, () -> service.createComputeQuota(json(request), REGION));
        assertEquals("ConflictException", sameTeam.getErrorCode());

        JsonNode updated = service.updateComputeQuota(json("""
                {"ComputeQuotaId":"%s","TargetVersion":1,"ComputeQuotaTarget":{"TeamName":"research","FairShareWeight":20}}
                """.formatted(id)), REGION);
        assertEquals(2, updated.path("ComputeQuotaVersion").asInt());

        String arn = described.path("ComputeQuotaArn").asText();
        JsonNode tags = service.tags("ListTags", json("{\"ResourceArn\":\"" + arn + "\"}")).orElseThrow().path("Tags");
        assertEquals(Map.of("purpose", "test"), Map.of(tags.get(0).path("Key").asText(), tags.get(0).path("Value").asText()));
        assertEquals(1, service.listComputeQuotas(json("{\"ClusterArn\":\"" + clusterArn + "\"}"), REGION)
                .path("ComputeQuotaSummaries").size());
    }

    private String inServiceEksCluster() {
        String arn = service.createCluster(json(eksCluster()), REGION).path("ClusterArn").asText();
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        return arn;
    }

    private String slurmClusterArn() {
        String arn = service.createCluster(json(slurmCluster(0)), REGION).path("ClusterArn").asText();
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        return arn;
    }

    private static String slurmCluster(int count) {
        return """
                {"ClusterName":"slurm","InstanceGroups":[{"InstanceGroupName":"controller","InstanceType":"ml.t3.medium",
                  "InstanceCount":%d,"ExecutionRole":"%s",
                  "LifeCycleConfig":{"SourceS3Uri":"s3://lifecycle-bucket/lifecycle","OnCreate":"on_create.sh"}}]}
                """.formatted(count, ROLE);
    }

    private static String eksCluster() {
        return """
                {"ClusterName":"governed","Orchestrator":{"Eks":{"ClusterArn":"%s"}},
                 "VpcConfig":{"SecurityGroupIds":["sg-1"],"Subnets":["subnet-1"]},
                 "InstanceGroups":[{"InstanceGroupName":"workers","InstanceType":"ml.g5.xlarge","InstanceCount":0,
                  "ExecutionRole":"%s"}]}
                """.formatted(EKS, ROLE);
    }

    private static String policy(String name, String clusterArn) {
        return """
                {"Name":"%s","ClusterArn":"%s",
                 "SchedulerConfig":{"PriorityClasses":[{"Name":"training","Weight":75}],"FairShare":"Enabled"}}
                """.formatted(name, clusterArn);
    }

    private JsonNode describeCluster(String nameOrArn) {
        return service.describeCluster(json("{\"ClusterName\":\"" + nameOrArn + "\"}"), REGION);
    }

    private JsonNode describePolicy(String id) {
        return service.describeClusterSchedulerConfig(json("{\"ClusterSchedulerConfigId\":\"" + id + "\"}"), REGION);
    }

    private static void assertNotFound(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("ResourceNotFound", e.getErrorCode());
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
