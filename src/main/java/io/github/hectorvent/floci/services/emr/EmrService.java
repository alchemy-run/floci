package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emr.model.EmrBootstrapAction;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceFleet;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceGroup;
import io.github.hectorvent.floci.services.emr.model.EmrManagedScalingPolicy;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.emr.model.SecurityConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EMR management-plane business logic. Cluster and step lifecycles are simulated
 * through immediate state transitions (no Hadoop runs): clusters land in WAITING and
 * initial steps in COMPLETED. The {@code clusterStartupDelaySeconds} config exists for
 * future deferred transitions; Phase 1 advances synchronously for deterministic tests.
 */
@ApplicationScoped
public class EmrService {

    private static final String UPPER_ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MIN_IDLE_TIMEOUT_SECONDS = 60L;
    private static final long MAX_IDLE_TIMEOUT_SECONDS = 604_800L;
    // AWS applies a one-hour idle timeout when a policy is attached without IdleTimeout.
    static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 3_600L;

    private final StorageBackend<String, EmrCluster> clusterStore;
    private final StorageBackend<String, SecurityConfiguration> secConfigStore;
    private final RegionResolver regionResolver;
    private final String defaultReleaseLabel;

    @Inject
    public EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.clusterStore = storageFactory.create("emr", "emr-clusters.json",
                new TypeReference<Map<String, EmrCluster>>() {});
        this.secConfigStore = storageFactory.create("emr", "emr-security-configs.json",
                new TypeReference<Map<String, SecurityConfiguration>>() {});
        this.regionResolver = regionResolver;
        this.defaultReleaseLabel = config.services().emr().defaultReleaseLabel();
    }

    // ──────────────────────────── Cluster lifecycle ────────────────────────────

    public EmrCluster runJobFlow(EmrCluster cluster, String region) {
        // Only a missing Name fails AWS's framework validation: the member is REQUIRED but its
        // constraint is len=[0..256], so an explicit empty string is accepted by real EMR.
        if (cluster.getName() == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'name' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        String id = "j-" + randomId(13);
        cluster.setId(id);
        cluster.setRegion(region);
        cluster.setClusterArn(regionResolver.buildArn("elasticmapreduce", region, "cluster/" + id));
        if (cluster.getReleaseLabel() == null) {
            cluster.setReleaseLabel(defaultReleaseLabel);
        }
        cluster.setInstanceCollectionType(cluster.getInstanceFleets().isEmpty()
                ? "INSTANCE_GROUP" : "INSTANCE_FLEET");
        if (cluster.getAutoTerminationIdleTimeout() != null) {
            validateIdleTimeout(cluster.getAutoTerminationIdleTimeout());
        }
        if (cluster.getManagedScalingPolicy() != null) {
            validateManagedScalingPolicy(cluster, cluster.getManagedScalingPolicy());
        }
        cluster.setAutoTerminate(!cluster.isKeepJobFlowAliveWhenNoSteps());
        cluster.setMasterPublicDnsName("ip-10-0-0-1." + region + ".compute.internal");
        cluster.setCreationDateTime(Instant.now());
        for (EmrInstanceGroup g : cluster.getInstanceGroups()) {
            g.setId("ig-" + randomId(13));
            g.setRunningInstanceCount(g.getRequestedInstanceCount());
            g.setState("RUNNING");
        }
        for (EmrInstanceFleet f : cluster.getInstanceFleets()) {
            f.setId("if-" + randomId(13));
            f.setProvisionedOnDemandCapacity(f.getTargetOnDemandCapacity());
            f.setProvisionedSpotCapacity(f.getTargetSpotCapacity());
            f.setState("RUNNING");
        }
        for (EmrStep step : cluster.getSteps()) {
            initStep(step);
            completeStep(step);
        }
        advanceToWaiting(cluster);
        clusterStore.put(id, cluster);
        return cluster;
    }

    public EmrCluster describeCluster(String id) {
        return requireCluster(id);
    }

    public List<EmrCluster> listClusters(String region, List<String> states) {
        List<EmrCluster> result = new ArrayList<>();
        for (EmrCluster c : clusterStore.scan(k -> true)) {
            if (!region.equals(c.getRegion())) {
                continue;
            }
            if (states != null && !states.isEmpty() && !states.contains(c.getState())) {
                continue;
            }
            result.add(c);
        }
        return result;
    }

    public void terminateJobFlows(List<String> ids) {
        // AWS terminates the unprotected clusters in the request and then fails it with a
        // ValidationException if any were termination protected.
        boolean anyProtected = false;
        for (String id : ids) {
            if (clusterStore.get(id).isEmpty()) {
                throw new AwsException("ValidationException", "Specified job flow ID not valid: " + id, 400);
            }
        }
        for (String id : ids) {
            EmrCluster cluster = clusterStore.get(id).orElseThrow();
            if (cluster.isTerminationProtected()) {
                anyProtected = true;
                continue;
            }
            if ("TERMINATED".equals(cluster.getState())) {
                continue;
            }
            cluster.setState("TERMINATED");
            cluster.setStateChangeReasonCode("USER_REQUEST");
            cluster.setStateChangeMessage("Terminated by user request");
            cluster.setEndDateTime(Instant.now());
            clusterStore.put(id, cluster);
        }
        if (anyProtected) {
            throw new AwsException("ValidationException",
                    "Could not shut down one or more job flows since they are termination protected.", 400);
        }
    }

    public void setTerminationProtection(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setTerminationProtected(value));
    }

    public void setVisibleToAllUsers(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setVisibleToAllUsers(value));
    }

    public void setKeepJobFlowAliveWhenNoSteps(List<String> ids, boolean value) {
        mutateClusters(ids, c -> {
            c.setKeepJobFlowAliveWhenNoSteps(value);
            c.setAutoTerminate(!value);
        });
    }

    public void setUnhealthyNodeReplacement(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setUnhealthyNodeReplacement(value));
    }

    public int modifyCluster(String id, Integer stepConcurrencyLevel) {
        EmrCluster cluster = requireCluster(id);
        if (stepConcurrencyLevel != null) {
            cluster.setStepConcurrencyLevel(stepConcurrencyLevel);
            clusterStore.put(id, cluster);
        }
        return cluster.getStepConcurrencyLevel();
    }

    // ──────────────────────────── Steps ────────────────────────────

    public List<String> addJobFlowSteps(String clusterId, List<EmrStep> steps) {
        EmrCluster cluster = clusterStore.get(clusterId).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Cluster id '" + clusterId + "' is not valid.", 400));
        List<String> ids = new ArrayList<>();
        for (EmrStep step : steps) {
            initStep(step);
            completeStep(step);
            cluster.getSteps().add(step);
            ids.add(step.getId());
        }
        clusterStore.put(clusterId, cluster);
        return ids;
    }

    public EmrStep describeStep(String clusterId, String stepId) {
        EmrCluster cluster = requireCluster(clusterId);
        return cluster.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "Step " + stepId + " does not exist.", 400));
    }

    public List<EmrStep> listSteps(String clusterId, List<String> states, List<String> stepIds) {
        EmrCluster cluster = requireCluster(clusterId);
        List<EmrStep> result = new ArrayList<>();
        // AWS returns steps in reverse submission order (newest first).
        List<EmrStep> steps = cluster.getSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            EmrStep step = steps.get(i);
            if (states != null && !states.isEmpty() && !states.contains(step.getState())) {
                continue;
            }
            if (stepIds != null && !stepIds.isEmpty() && !stepIds.contains(step.getId())) {
                continue;
            }
            result.add(step);
        }
        return result;
    }

    public record CancelStepInfo(String stepId, String status, String reason) {}

    public List<CancelStepInfo> cancelSteps(String clusterId, List<String> stepIds) {
        EmrCluster cluster = requireCluster(clusterId);
        List<CancelStepInfo> result = new ArrayList<>();
        for (String stepId : stepIds) {
            EmrStep step = cluster.getSteps().stream()
                    .filter(s -> s.getId().equals(stepId)).findFirst().orElse(null);
            if (step == null) {
                result.add(new CancelStepInfo(stepId, "FAILED", "Step does not exist."));
            } else if ("PENDING".equals(step.getState())) {
                step.setState("CANCELLED");
                step.setEndDateTime(Instant.now());
                result.add(new CancelStepInfo(stepId, "SUBMITTED", null));
            } else {
                result.add(new CancelStepInfo(stepId, "FAILED",
                        "Step is not in a cancellable state: " + step.getState()));
            }
        }
        clusterStore.put(clusterId, cluster);
        return result;
    }

    // ──────────────────────────── Instance groups / fleets ────────────────────────────

    public List<String> addInstanceGroups(String clusterId, List<EmrInstanceGroup> groups) {
        EmrCluster cluster = requireCluster(clusterId);
        List<String> ids = new ArrayList<>();
        for (EmrInstanceGroup group : groups) {
            group.setId("ig-" + randomId(13));
            group.setRunningInstanceCount(group.getRequestedInstanceCount());
            group.setState("RUNNING");
            cluster.getInstanceGroups().add(group);
            ids.add(group.getId());
        }
        clusterStore.put(clusterId, cluster);
        return ids;
    }

    public List<EmrInstanceGroup> listInstanceGroups(String clusterId) {
        return requireCluster(clusterId).getInstanceGroups();
    }

    public String addInstanceFleet(String clusterId, EmrInstanceFleet fleet) {
        EmrCluster cluster = requireCluster(clusterId);
        fleet.setId("if-" + randomId(13));
        fleet.setProvisionedOnDemandCapacity(fleet.getTargetOnDemandCapacity());
        fleet.setProvisionedSpotCapacity(fleet.getTargetSpotCapacity());
        fleet.setState("RUNNING");
        cluster.getInstanceFleets().add(fleet);
        clusterStore.put(clusterId, cluster);
        return fleet.getId();
    }

    public List<EmrInstanceFleet> listInstanceFleets(String clusterId) {
        return requireCluster(clusterId).getInstanceFleets();
    }

    public EmrCluster getClusterForInstances(String clusterId) {
        return requireCluster(clusterId);
    }

    public record InstanceGroupModification(String instanceGroupId, Integer instanceCount) {}

    public void modifyInstanceGroups(String clusterId, List<InstanceGroupModification> modifications) {
        // ClusterId is optional on the wire; instance group ids are globally unique.
        List<EmrCluster> candidates = clusterId != null
                ? List.of(requireCluster(clusterId))
                : new ArrayList<>(clusterStore.scan(k -> true));
        List<EmrCluster> touched = new ArrayList<>();
        List<Runnable> updates = new ArrayList<>();
        // Validate every modification before applying any: the call succeeds or fails atomically.
        for (InstanceGroupModification mod : modifications) {
            if (mod.instanceCount() != null && mod.instanceCount() < 0) {
                throw new AwsException("ValidationException",
                        "Instance count for instance group " + mod.instanceGroupId() + " must not be negative.", 400);
            }
            EmrCluster owner = null;
            EmrInstanceGroup target = null;
            for (EmrCluster c : candidates) {
                for (EmrInstanceGroup g : c.getInstanceGroups()) {
                    if (g.getId().equals(mod.instanceGroupId())) {
                        owner = c;
                        target = g;
                    }
                }
            }
            if (target == null) {
                throw new AwsException("InvalidRequestException",
                        "Instance group id '" + mod.instanceGroupId() + "' is not valid.", 400);
            }
            if (mod.instanceCount() != null) {
                if ("MASTER".equals(target.getInstanceGroupType())
                        && mod.instanceCount() != target.getRequestedInstanceCount()) {
                    throw new AwsException("ValidationException",
                            "The instance count of the master instance group cannot be modified.", 400);
                }
                EmrInstanceGroup group = target;
                int count = mod.instanceCount();
                updates.add(() -> {
                    group.setRequestedInstanceCount(count);
                    group.setRunningInstanceCount(count);
                });
            }
            if (!touched.contains(owner)) {
                touched.add(owner);
            }
        }
        updates.forEach(Runnable::run);
        for (EmrCluster c : touched) {
            clusterStore.put(c.getId(), c);
        }
    }

    public void modifyInstanceFleet(String clusterId, String instanceFleetId,
                                    Integer targetOnDemandCapacity, Integer targetSpotCapacity) {
        EmrCluster cluster = requireCluster(clusterId);
        EmrInstanceFleet fleet = cluster.getInstanceFleets().stream()
                .filter(f -> f.getId().equals(instanceFleetId))
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "Instance fleet id '" + instanceFleetId + "' is not valid.", 400));
        if ((targetOnDemandCapacity != null && targetOnDemandCapacity < 0)
                || (targetSpotCapacity != null && targetSpotCapacity < 0)) {
            throw new AwsException("ValidationException",
                    "Target capacity for instance fleet " + instanceFleetId + " must not be negative.", 400);
        }
        if (targetOnDemandCapacity != null) {
            fleet.setTargetOnDemandCapacity(targetOnDemandCapacity);
            fleet.setProvisionedOnDemandCapacity(targetOnDemandCapacity);
        }
        if (targetSpotCapacity != null) {
            fleet.setTargetSpotCapacity(targetSpotCapacity);
            fleet.setProvisionedSpotCapacity(targetSpotCapacity);
        }
        clusterStore.put(cluster.getId(), cluster);
    }

    public List<EmrBootstrapAction> listBootstrapActions(String clusterId) {
        return requireCluster(clusterId).getBootstrapActions();
    }

    // ──────────────────────────── Auto-termination policy ────────────────────────────

    public void putAutoTerminationPolicy(String clusterId, Long idleTimeout) {
        EmrCluster cluster = requireCluster(clusterId);
        long timeout = idleTimeout == null ? DEFAULT_IDLE_TIMEOUT_SECONDS : idleTimeout;
        validateIdleTimeout(timeout);
        cluster.setAutoTerminationIdleTimeout(timeout);
        clusterStore.put(cluster.getId(), cluster);
    }

    public Long getAutoTerminationIdleTimeout(String clusterId) {
        return requireCluster(clusterId).getAutoTerminationIdleTimeout();
    }

    public void removeAutoTerminationPolicy(String clusterId) {
        EmrCluster cluster = requireCluster(clusterId);
        cluster.setAutoTerminationIdleTimeout(null);
        clusterStore.put(cluster.getId(), cluster);
    }

    // ──────────────────────────── Managed scaling policy ────────────────────────────

    public void putManagedScalingPolicy(String clusterId, EmrManagedScalingPolicy policy) {
        EmrCluster cluster = requireCluster(clusterId);
        if (policy == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'managedScalingPolicy' failed to satisfy "
                            + "constraint: Member must not be null", 400);
        }
        validateManagedScalingPolicy(cluster, policy);
        cluster.setManagedScalingPolicy(policy);
        clusterStore.put(cluster.getId(), cluster);
    }

    public EmrManagedScalingPolicy getManagedScalingPolicy(String clusterId) {
        return requireCluster(clusterId).getManagedScalingPolicy();
    }

    public void removeManagedScalingPolicy(String clusterId) {
        EmrCluster cluster = requireCluster(clusterId);
        cluster.setManagedScalingPolicy(null);
        clusterStore.put(cluster.getId(), cluster);
    }

    // ──────────────────────────── Security configurations ────────────────────────────

    public SecurityConfiguration createSecurityConfiguration(String name, String json) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequestException", "Name is required.", 400);
        }
        if (secConfigStore.get(name).isPresent()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration already exists: " + name, 400);
        }
        SecurityConfiguration sc = new SecurityConfiguration();
        sc.setName(name);
        sc.setSecurityConfiguration(json);
        sc.setCreationDateTime(Instant.now());
        secConfigStore.put(name, sc);
        return sc;
    }

    public SecurityConfiguration describeSecurityConfiguration(String name) {
        return secConfigStore.get(name).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Security configuration with name " + name + " does not exist", 400));
    }

    public void deleteSecurityConfiguration(String name) {
        if (secConfigStore.get(name).isEmpty()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration with name " + name + " does not exist", 400);
        }
        secConfigStore.delete(name);
    }

    public List<SecurityConfiguration> listSecurityConfigurations() {
        return new ArrayList<>(secConfigStore.scan(k -> true));
    }

    // ──────────────────────────── Tags ────────────────────────────

    public void addTags(String resourceId, Map<String, String> tags) {
        EmrCluster cluster = requireCluster(resourceId);
        cluster.getTags().putAll(tags);
        clusterStore.put(resourceId, cluster);
    }

    public void removeTags(String resourceId, List<String> keys) {
        EmrCluster cluster = requireCluster(resourceId);
        keys.forEach(cluster.getTags()::remove);
        clusterStore.put(resourceId, cluster);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private EmrCluster requireCluster(String id) {
        if (id == null) {
            throw new AwsException("InvalidRequestException", "ClusterId is required.", 400);
        }
        return clusterStore.get(id).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Cluster id '" + id + "' is not valid.", 400));
    }

    private void mutateClusters(List<String> ids, java.util.function.Consumer<EmrCluster> mutation) {
        for (String id : ids) {
            clusterStore.get(id).ifPresent(c -> {
                mutation.accept(c);
                clusterStore.put(id, c);
            });
        }
    }

    private void validateIdleTimeout(long idleTimeout) {
        if (idleTimeout < MIN_IDLE_TIMEOUT_SECONDS || idleTimeout > MAX_IDLE_TIMEOUT_SECONDS) {
            throw new AwsException("ValidationException",
                    "The idle timeout must be between " + MIN_IDLE_TIMEOUT_SECONDS + " and "
                            + MAX_IDLE_TIMEOUT_SECONDS + " seconds.", 400);
        }
    }

    private void validateManagedScalingPolicy(EmrCluster cluster, EmrManagedScalingPolicy policy) {
        requireComputeLimit(policy.getUnitType(), "unitType");
        requireComputeLimit(policy.getMinimumCapacityUnits(), "minimumCapacityUnits");
        requireComputeLimit(policy.getMaximumCapacityUnits(), "maximumCapacityUnits");
        String unitType = policy.getUnitType();
        if (!List.of("InstanceFleetUnits", "Instances", "VCPU").contains(unitType)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + unitType + "' at "
                            + "'managedScalingPolicy.computeLimits.unitType' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [InstanceFleetUnits, Instances, VCPU]", 400);
        }
        boolean fleets = "INSTANCE_FLEET".equals(cluster.getInstanceCollectionType());
        if (fleets && "Instances".equals(unitType)) {
            throw new AwsException("ValidationException",
                    "The unit type Instances is not supported for clusters with instance fleets.", 400);
        }
        if (!fleets && "InstanceFleetUnits".equals(unitType)) {
            throw new AwsException("ValidationException",
                    "The unit type InstanceFleetUnits is not supported for clusters with instance groups.", 400);
        }
        int min = policy.getMinimumCapacityUnits();
        int max = policy.getMaximumCapacityUnits();
        if (min < 0) {
            throw new AwsException("ValidationException",
                    "MinimumCapacityUnits must not be negative.", 400);
        }
        if (min > max) {
            throw new AwsException("ValidationException",
                    "MinimumCapacityUnits must be less than or equal to MaximumCapacityUnits.", 400);
        }
        Integer onDemand = policy.getMaximumOnDemandCapacityUnits();
        if (onDemand != null && (onDemand < 0 || onDemand > max)) {
            throw new AwsException("ValidationException",
                    "MaximumOnDemandCapacityUnits must be between 0 and MaximumCapacityUnits.", 400);
        }
        Integer core = policy.getMaximumCoreCapacityUnits();
        if (core != null && (core < 0 || core > max)) {
            throw new AwsException("ValidationException",
                    "MaximumCoreCapacityUnits must be between 0 and MaximumCapacityUnits.", 400);
        }
        String strategy = policy.getScalingStrategy();
        if (strategy != null && !List.of("DEFAULT", "ADVANCED").contains(strategy)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + strategy + "' at "
                            + "'managedScalingPolicy.scalingStrategy' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [DEFAULT, ADVANCED]", 400);
        }
    }

    private void requireComputeLimit(Object value, String member) {
        if (value == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'managedScalingPolicy.computeLimits."
                            + member + "' failed to satisfy constraint: Member must not be null", 400);
        }
    }

    private void advanceToWaiting(EmrCluster cluster) {
        cluster.setState("WAITING");
        cluster.setStateChangeReasonCode(cluster.getSteps().isEmpty() ? null : "ALL_STEPS_COMPLETED");
        cluster.setStateChangeMessage("Cluster ready to run steps.");
        cluster.setReadyDateTime(Instant.now());
    }

    private void initStep(EmrStep step) {
        step.setId("s-" + randomId(13));
        step.setState("PENDING");
        step.setCreationDateTime(Instant.now());
        if (step.getActionOnFailure() == null) {
            step.setActionOnFailure("TERMINATE_CLUSTER");
        }
    }

    private void completeStep(EmrStep step) {
        Instant now = Instant.now();
        step.setStartDateTime(now);
        step.setEndDateTime(now);
        step.setState("COMPLETED");
    }

    private String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(UPPER_ALNUM.charAt(RANDOM.nextInt(UPPER_ALNUM.length())));
        }
        return sb.toString();
    }
}
