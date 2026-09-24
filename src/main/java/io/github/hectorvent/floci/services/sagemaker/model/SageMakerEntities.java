package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public final class SageMakerEntities {
    private SageMakerEntities() {
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelResource {
        public String modelName;
        public String modelArn;
        public Map<String, Object> primaryContainer = new LinkedHashMap<>();
        public List<Map<String, Object>> containers = new ArrayList<>();
        public String executionRoleArn;
        public long creationTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointConfigResource {
        public String endpointConfigName;
        public String endpointConfigArn;
        public List<Map<String, Object>> productionVariants = new ArrayList<>();
        public long creationTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointResource {
        public String endpointName;
        public String endpointArn;
        public String endpointConfigName;
        public String endpointStatus;
        public String failureReason;
        public long creationTime;
        public long lastModifiedTime;
        public String region;
        public String accountId;
        public String containerId;
        public String invokeHost;
        public int invokePort;
        // Bumped every time a start (create or update) is kicked off; a start worker only
        // persists its result while it is still the current one for this endpoint, so a
        // superseding update or a delete does not get overwritten by a stale worker finishing
        // late.
        public long generation;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TrainingJobResource {
        public String trainingJobName;
        public String trainingJobArn;
        public String trainingJobStatus;
        public String secondaryStatus;
        public String failureReason;
        public long creationTime;
        public long trainingStartTime;
        public long trainingEndTime;
        public String region;
        public String accountId;
        public Map<String, Object> algorithmSpecification = new LinkedHashMap<>();
        public List<Map<String, Object>> inputDataConfig = new ArrayList<>();
        public Map<String, Object> outputDataConfig = new LinkedHashMap<>();
        public Map<String, Object> resourceConfig = new LinkedHashMap<>();
        public Map<String, Object> stoppingCondition = new LinkedHashMap<>();
        public Map<String, String> hyperParameters = new LinkedHashMap<>();
        public String modelArtifactsS3ModelArtifacts;
        public String containerId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeatureGroupResource {
        public String featureGroupName;
        public String featureGroupArn;
        public String recordIdentifierFeatureName;
        public String eventTimeFeatureName;
        public List<Map<String, Object>> featureDefinitions = new ArrayList<>();
        public Map<String, Object> onlineStoreConfig;
        public Map<String, Object> offlineStoreConfig;
        public Map<String, Object> throughputConfig;
        public String roleArn;
        public String description;
        public String featureGroupStatus;
        public String offlineStoreStatus;
        public String offlineStoreBlockedReason;
        public String lastUpdateStatus;
        public String failureReason;
        public long creationTime;
        public long lastModifiedTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    /** One record identifier's latest online-store state within a feature group. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeatureRecordResource {
        public String featureGroupArn;
        public long featureGroupCreationTime;
        public String recordIdentifier;
        /** Event time of the latest write or soft delete, in epoch milliseconds. */
        public long eventTimeMillis;
        /** Feature name to its ValueAsString (String) or ValueAsStringList (List of String). */
        public Map<String, Object> values = new LinkedHashMap<>();
        public boolean deleted;
        /** Epoch milliseconds after which the record has expired, or null when it has no TTL. */
        public Long expiresAtMillis;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClusterResource {
        public String clusterName;
        public String clusterArn;
        public String clusterStatus;
        public String failureMessage;
        public List<Map<String, Object>> instanceGroups = new ArrayList<>();
        public List<Map<String, Object>> restrictedInstanceGroups = new ArrayList<>();
        public Map<String, Object> vpcConfig;
        public Map<String, Object> orchestrator;
        public String nodeRecovery;
        public long creationTime;
        public long lastModifiedTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClusterSchedulerConfigResource {
        public String id;
        public String arn;
        public String name;
        public String clusterArn;
        public int version;
        public String status;
        public Map<String, Object> schedulerConfig;
        public String description;
        public long creationTime;
        public long lastModifiedTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ComputeQuotaResource {
        public String id;
        public String arn;
        public String name;
        public String clusterArn;
        public int version;
        public String status;
        public Map<String, Object> computeQuotaConfig;
        public Map<String, Object> computeQuotaTarget;
        public String activationState;
        public String description;
        public long creationTime;
        public long lastModifiedTime;
        public String region;
        public String accountId;
        public Map<String, String> tags = new LinkedHashMap<>();
    }
}
