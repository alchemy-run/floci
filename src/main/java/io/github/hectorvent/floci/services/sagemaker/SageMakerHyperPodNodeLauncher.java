package io.github.hectorvent.floci.services.sagemaker;

/**
 * Provisions HyperPod nodes. Provisioning a node means running its instance group's lifecycle
 * scripts ({@code LifeCycleConfig.OnCreate} from {@code LifeCycleConfig.SourceS3Uri}); the node is
 * {@code Running} once they exit successfully.
 */
public interface SageMakerHyperPodNodeLauncher {

    /**
     * Starts provisioning one node asynchronously. {@code outcome} is reported exactly once, unless
     * {@link #cancel} is called first. {@link #inFlight} is true from the moment this returns until
     * after the outcome has been reported.
     */
    void launch(NodeLaunch launch, NodeOutcome outcome);

    /** Stops provisioning a node (the node was removed or its cluster deleted); its outcome is dropped. */
    void cancel(String instanceId);

    /** Whether the node's provisioning is queued or running in this process. */
    boolean inFlight(String instanceId);

    /** Receives the result of provisioning one node. */
    @FunctionalInterface
    interface NodeOutcome {
        void completed(boolean succeeded, String message);
    }

    /**
     * Everything needed to provision one node.
     *
     * @param clusterId the id segment of the cluster ARN
     * @param resourceConfigJson the {@code /opt/ml/config/resource_config.json} document describing the cluster
     */
    record NodeLaunch(String accountId, String region, String clusterArn, String clusterId, String clusterName,
                      String instanceGroupName, String instanceId, String instanceType, String sourceS3Uri,
                      String onCreate, String resourceConfigJson) {
    }
}
