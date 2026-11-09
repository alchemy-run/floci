package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * UpdateClusterConfig: each member is one update category, and a request carries exactly one.
 * The Auto Mode trio (computeConfig, storageConfig, kubernetesNetworkConfig) counts as one.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateClusterConfigRequest(
        ResourcesVpcConfig resourcesVpcConfig,
        Logging logging,
        AccessConfig accessConfig,
        Map<String, Object> upgradePolicy,
        Map<String, Object> zonalShiftConfig,
        Map<String, Object> computeConfig,
        Map<String, Object> storageConfig,
        Map<String, Object> kubernetesNetworkConfig,
        Map<String, Object> remoteNetworkConfig,
        Boolean deletionProtection,
        String clientRequestToken
) {}
