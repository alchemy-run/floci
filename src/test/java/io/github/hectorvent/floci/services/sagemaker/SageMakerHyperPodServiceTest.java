package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sagemaker.SageMakerHyperPodService.EksClusterState;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.Scheduler;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.StateEvents;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SageMakerHyperPodServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/hyperpod";
    private static final String EKS = "arn:aws:eks:us-east-1:000000000000:cluster/governance";
    private static final String CONFIG_MAP_EKS = "arn:aws:eks:us-east-1:000000000000:cluster/config-map";

    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final FakeLauncher launcher = new FakeLauncher();
    private final SageMakerHyperPodService service = new SageMakerHyperPodService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"), mapper, clock,
            Set.of("lifecycle-bucket")::contains, Set.of(ROLE)::contains,
            arn -> switch (arn) {
                case EKS -> Optional.of(new EksClusterState("ACTIVE", "API"));
                case CONFIG_MAP_EKS -> Optional.of(new EksClusterState("ACTIVE", "CONFIG_MAP"));
                default -> Optional.empty();
            },
            StateEvents.NONE, Scheduler.NONE, launcher);

    @Test
    void describeMissingHyperPodResourcesIsResourceNotFound() {
        assertNotFound(() -> service.describeCluster(json("{\"ClusterName\":\"alchemy-nonexistent-hyperpod-cluster-probe\"}"), REGION));
        assertNotFound(() -> service.describeClusterSchedulerConfig(json("{\"ClusterSchedulerConfigId\":\"abcdef012345\"}"), REGION));
        assertNotFound(() -> service.describeComputeQuota(json("{\"ComputeQuotaId\":\"abcdef012345\"}"), REGION));
        assertNotFound(() -> service.deleteCluster(json("{\"ClusterName\":\"missing\"}"), REGION));
        assertNotFound(() -> service.updateCluster(json("{\"ClusterName\":\"missing\"}"), REGION));
        assertNotFound(() -> service.deleteClusterSchedulerConfig(json("{\"ClusterSchedulerConfigId\":\"abcdef012345\"}"), REGION));
        assertEquals(0, service.listClusters(json("{}"), REGION).path("ClusterSummaries").size());
        AwsException badId = assertThrows(AwsException.class,
                () -> service.describeComputeQuota(json("{\"ComputeQuotaId\":\"NOT-AN-ID\"}"), REGION));
        assertEquals("ValidationException", badId.getErrorCode());
    }

    @Test
    void clusterNodesRunTheirLifecycleScriptsBeforeTheClusterIsInService() {
        String arn = service.createCluster(json(slurmCluster(1)), REGION).path("ClusterArn").asText();
        assertEquals("Creating", describeCluster("slurm").path("ClusterStatus").asText());
        JsonNode nodes = listNodes("slurm");
        assertEquals(1, nodes.size());
        assertEquals("Pending", nodes.get(0).path("InstanceStatus").path("Status").asText());
        String instanceId = nodes.get(0).path("InstanceId").asText();
        assertTrue(instanceId.matches("i-[0-9a-f]{17}"));

        SageMakerHyperPodNodeLauncher.NodeLaunch launch = launcher.launches.get(instanceId);
        assertEquals("s3://lifecycle-bucket/lifecycle", launch.sourceS3Uri());
        assertEquals("on_create.sh", launch.onCreate());
        assertEquals("controller", launch.instanceGroupName());
        assertEquals(arn.substring(arn.lastIndexOf('/') + 1), launch.clusterId());
        JsonNode resourceConfig = json(launch.resourceConfigJson());
        assertEquals("slurm", resourceConfig.path("ClusterConfig").path("ClusterName").asText());
        assertEquals(instanceId, resourceConfig.path("InstanceGroups").get(0).path("Instances").get(0)
                .path("InstanceId").asText());

        // The transition time alone does not settle a cluster whose nodes are still provisioning.
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertEquals("Creating", describeCluster("slurm").path("ClusterStatus").asText());

        launcher.completeAll(true, null);
        JsonNode described = describeCluster(arn);
        assertEquals("InService", described.path("ClusterStatus").asText());
        JsonNode group = described.path("InstanceGroups").get(0);
        assertEquals("controller", group.path("InstanceGroupName").asText());
        assertEquals(1, group.path("CurrentCount").asInt());
        assertEquals(1, group.path("TargetCount").asInt());
        assertEquals("InService", group.path("Status").asText());
        assertFalse(group.has("InstanceCount"));
        assertEquals("Running", listNodes("slurm").get(0).path("InstanceStatus").path("Status").asText());
        JsonNode node = service.describeClusterNode(json(
                "{\"ClusterName\":\"slurm\",\"NodeId\":\"" + instanceId + "\"}"), REGION).path("NodeDetails");
        assertEquals("on_create.sh", node.path("LifeCycleConfig").path("OnCreate").asText());
        assertEquals("ml.t3.medium", node.path("InstanceType").asText());

        service.deleteCluster(json("{\"ClusterName\":\"slurm\"}"), REGION);
        assertEquals("Deleting", describeCluster("slurm").path("ClusterStatus").asText());
        assertEquals("ShuttingDown", listNodes("slurm").get(0).path("InstanceStatus").path("Status").asText());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertNotFound(() -> describeCluster("slurm"));
    }

    @Test
    void failedLifecycleScriptFailsClusterCreation() {
        service.createCluster(json(slurmCluster(2)), REGION);
        launcher.completeAll(false, "Lifecycle script on_create.sh exited with code 3.");
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);

        JsonNode described = describeCluster("slurm");
        assertEquals("Failed", described.path("ClusterStatus").asText());
        assertTrue(described.path("FailureMessage").asText().contains("exited with code 3"));
        assertTrue(described.path("FailureMessage").asText().contains("/aws/sagemaker/Clusters/slurm/"));
        assertEquals("Failed", described.path("InstanceGroups").get(0).path("Status").asText());
        assertEquals(0, listNodes("slurm").size());

        service.deleteCluster(json("{\"ClusterName\":\"slurm\"}"), REGION);
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertNotFound(() -> describeCluster("slurm"));
    }

    @Test
    void creatingClusterCannotBeUpdatedOrDeleted() {
        service.createCluster(json(slurmCluster(1)), REGION);
        AwsException update = assertThrows(AwsException.class, () -> service.updateCluster(json(
                "{\"ClusterName\":\"slurm\",\"NodeRecovery\":\"None\"}"), REGION));
        assertEquals("ConflictException", update.getErrorCode());
        AwsException delete = assertThrows(AwsException.class,
                () -> service.deleteCluster(json("{\"ClusterName\":\"slurm\"}"), REGION));
        assertEquals("ConflictException", delete.getErrorCode());
    }

    @Test
    void updateClusterScalesGroupsAddsAndDeletesGroupsAndChangesNodeRecovery() {
        inServiceSlurmCluster(1);
        String workers = group("workers", 2);
        JsonNode updated = service.updateCluster(json("""
                {"ClusterName":"slurm","NodeRecovery":"None",
                 "InstanceGroups":[%s,%s]}
                """.formatted(group("controller", 1), workers)), REGION);
        assertTrue(updated.path("ClusterArn").asText().contains(":cluster/"));

        JsonNode updating = describeCluster("slurm");
        assertEquals("Updating", updating.path("ClusterStatus").asText());
        assertEquals("None", updating.path("NodeRecovery").asText());
        assertEquals("InService", groupNamed(updating, "controller").path("Status").asText());
        assertEquals("Creating", groupNamed(updating, "workers").path("Status").asText());
        assertEquals(2, groupNamed(updating, "workers").path("TargetCount").asInt());
        assertEquals(0, groupNamed(updating, "workers").path("CurrentCount").asInt());
        assertEquals(2, launcher.outcomes.size());

        launcher.completeAll(true, null);
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        JsonNode inService = describeCluster("slurm");
        assertEquals("InService", inService.path("ClusterStatus").asText());
        assertEquals(2, groupNamed(inService, "workers").path("CurrentCount").asInt());
        assertEquals(3, listNodes("slurm").size());

        // Scale workers down by one and delete the controller group in the same update.
        service.updateCluster(json("""
                {"ClusterName":"slurm","InstanceGroups":[%s],"InstanceGroupsToDelete":["controller"]}
                """.formatted(group("workers", 1))), REGION);
        assertEquals("Updating", groupNamed(describeCluster("slurm"), "workers").path("Status").asText());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        JsonNode shrunk = describeCluster("slurm");
        assertEquals("InService", shrunk.path("ClusterStatus").asText());
        assertEquals(1, shrunk.path("InstanceGroups").size());
        assertEquals(1, groupNamed(shrunk, "workers").path("CurrentCount").asInt());
        JsonNode remaining = listNodes("slurm");
        assertEquals(1, remaining.size());
        assertEquals("workers", remaining.get(0).path("InstanceGroupName").asText());
        assertEquals(2, launcher.cancelled.size());
    }

    @Test
    void updateClusterValidatesTheRequest() {
        inServiceSlurmCluster(1);
        assertValidation(() -> service.updateCluster(json("""
                {"ClusterName":"slurm","InstanceGroups":[%s]}
                """.formatted(group("controller", 1).replace("ml.t3.medium", "ml.c5.xlarge"))), REGION));
        assertValidation(() -> service.updateCluster(json(
                "{\"ClusterName\":\"slurm\",\"InstanceGroupsToDelete\":[\"missing\"]}"), REGION));
        assertValidation(() -> service.updateCluster(json(
                "{\"ClusterName\":\"slurm\",\"InstanceGroupsToDelete\":[\"controller\"]}"), REGION));
        assertValidation(() -> service.updateCluster(json(
                "{\"ClusterName\":\"slurm\",\"NodeRecovery\":\"Sometimes\"}"), REGION));
        assertValidation(() -> service.updateCluster(json("""
                {"ClusterName":"slurm","InstanceGroups":[%s],"InstanceGroupsToDelete":["controller"]}
                """.formatted(group("controller", 2))), REGION));
        assertValidation(() -> service.updateCluster(json(
                "{\"ClusterName\":\"slurm\",\"Orchestrator\":{\"Eks\":{\"ClusterArn\":\"" + EKS + "\"}}}"), REGION));
        assertEquals("InService", describeCluster("slurm").path("ClusterStatus").asText());
    }

    @Test
    void failedScaleUpRollsBackToTheRunningNodes() {
        inServiceSlurmCluster(1);
        service.updateCluster(json("""
                {"ClusterName":"slurm","InstanceGroups":[%s]}
                """.formatted(group("controller", 3))), REGION);
        launcher.completeAll(false, "Lifecycle script on_create.sh exited with code 1.");
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);

        JsonNode described = describeCluster("slurm");
        assertEquals("InService", described.path("ClusterStatus").asText());
        assertTrue(described.path("FailureMessage").asText().contains("exited with code 1"));
        assertEquals(1, groupNamed(described, "controller").path("TargetCount").asInt());
        assertEquals(1, groupNamed(described, "controller").path("CurrentCount").asInt());
        assertEquals(1, listNodes("slurm").size());
    }

    @Test
    void pendingNodeNoLongerInFlightFailsInsteadOfBlockingTheCluster() {
        service.createCluster(json(slurmCluster(1)), REGION);
        // Provisioning state is process-local: after a restart nothing is in flight any more.
        launcher.outcomes.clear();
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        JsonNode described = describeCluster("slurm");
        assertEquals("Failed", described.path("ClusterStatus").asText());
        assertTrue(described.path("FailureMessage").asText().contains("interrupted"));
    }

    @Test
    void deletingAnUpdatingClusterCancelsItsProvisioningNodes() {
        inServiceSlurmCluster(1);
        service.updateCluster(json("""
                {"ClusterName":"slurm","InstanceGroups":[%s]}
                """.formatted(group("controller", 2))), REGION);
        assertEquals(1, launcher.outcomes.size());
        service.deleteCluster(json("{\"ClusterName\":\"slurm\"}"), REGION);
        assertEquals(2, launcher.cancelled.size());
        assertTrue(launcher.outcomes.isEmpty());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertNotFound(() -> describeCluster("slurm"));
    }

    @Test
    void createClusterValidatesRoleBucketAndEksOrchestrator() {
        AwsException role = assertThrows(AwsException.class, () -> service.createCluster(json(
                slurmCluster(0).replace(ROLE, "arn:aws:iam::000000000000:role/missing")), REGION));
        assertEquals("ValidationException", role.getErrorCode());
        AwsException bucket = assertThrows(AwsException.class, () -> service.createCluster(json(
                slurmCluster(0).replace("lifecycle-bucket", "missing-bucket")), REGION));
        assertEquals("ValidationException", bucket.getErrorCode());
        AwsException onCreate = assertThrows(AwsException.class, () -> service.createCluster(json(
                slurmCluster(0).replace(",\"OnCreate\":\"on_create.sh\"", "")), REGION));
        assertEquals("ValidationException", onCreate.getErrorCode());
        AwsException eks = assertThrows(AwsException.class, () -> service.createCluster(json(
                eksCluster().replace(EKS, EKS + "-missing")), REGION));
        assertEquals("ValidationException", eks.getErrorCode());
        AwsException configMap = assertThrows(AwsException.class, () -> service.createCluster(json(
                eksCluster().replace(EKS, CONFIG_MAP_EKS)), REGION));
        assertEquals("ValidationException", configMap.getErrorCode());
        assertTrue(configMap.getMessage().contains("API_AND_CONFIG_MAP"));
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
        assertEquals(0, listNodes("slurm").size());
        assertTrue(launcher.launches.isEmpty());

        service.deleteCluster(json("{\"ClusterName\":\"slurm\"}"), REGION);
        assertEquals("Deleting", describeCluster("slurm").path("ClusterStatus").asText());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertNotFound(() -> describeCluster("slurm"));
    }

    @Test
    void eksNodesWithoutLifecycleScriptsJoinImmediately() {
        String arn = service.createCluster(json(eksCluster().replace("\"InstanceCount\":0", "\"InstanceCount\":2")), REGION)
                .path("ClusterArn").asText();
        assertTrue(launcher.launches.isEmpty());
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        JsonNode described = describeCluster(arn);
        assertEquals("InService", described.path("ClusterStatus").asText());
        assertEquals(2, described.path("InstanceGroups").get(0).path("CurrentCount").asInt());
        assertEquals(EKS, described.path("Orchestrator").path("Eks").path("ClusterArn").asText());
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

    private void inServiceSlurmCluster(int count) {
        service.createCluster(json(slurmCluster(count)), REGION);
        launcher.completeAll(true, null);
        clock.advance(SageMakerHyperPodService.TRANSITION_DURATION);
        assertEquals("InService", describeCluster("slurm").path("ClusterStatus").asText());
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
        return "{\"ClusterName\":\"slurm\",\"InstanceGroups\":[" + group("controller", count) + "]}";
    }

    private static String group(String name, int count) {
        return """
                {"InstanceGroupName":"%s","InstanceType":"ml.t3.medium","InstanceCount":%d,"ExecutionRole":"%s",\
                "LifeCycleConfig":{"SourceS3Uri":"s3://lifecycle-bucket/lifecycle","OnCreate":"on_create.sh"}}"""
                .formatted(name, count, ROLE);
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

    private JsonNode listNodes(String name) {
        return service.listClusterNodes(json("{\"ClusterName\":\"" + name + "\"}"), REGION).path("ClusterNodeSummaries");
    }

    private static JsonNode groupNamed(JsonNode described, String name) {
        for (JsonNode group : described.path("InstanceGroups")) {
            if (name.equals(group.path("InstanceGroupName").asText())) {
                return group;
            }
        }
        throw new AssertionError("No instance group " + name + " in " + described);
    }

    private JsonNode describePolicy(String id) {
        return service.describeClusterSchedulerConfig(json("{\"ClusterSchedulerConfigId\":\"" + id + "\"}"), REGION);
    }

    private static void assertNotFound(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("ResourceNotFound", e.getErrorCode());
    }

    private static void assertValidation(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("ValidationException", e.getErrorCode());
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Records launches and lets the test decide when, and how, each node's provisioning ends. */
    private static final class FakeLauncher implements SageMakerHyperPodNodeLauncher {
        private final Map<String, NodeLaunch> launches = new LinkedHashMap<>();
        private final Map<String, NodeOutcome> outcomes = new LinkedHashMap<>();
        private final List<String> cancelled = new ArrayList<>();

        @Override
        public void launch(NodeLaunch launch, NodeOutcome outcome) {
            launches.put(launch.instanceId(), launch);
            outcomes.put(launch.instanceId(), outcome);
        }

        @Override
        public void cancel(String instanceId) {
            cancelled.add(instanceId);
            outcomes.remove(instanceId);
        }

        @Override
        public boolean inFlight(String instanceId) {
            return outcomes.containsKey(instanceId);
        }

        void completeAll(boolean succeeded, String message) {
            for (String instanceId : List.copyOf(outcomes.keySet())) {
                outcomes.remove(instanceId).completed(succeeded, message);
            }
        }
    }
}
