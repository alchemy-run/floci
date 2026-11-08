package io.floci.aws.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * A load balancer association on an ECS service ({@code CreateService}'s
 * {@code loadBalancers} block). Links a container port on the service's tasks
 * to an ELBv2 target group so running task containers can be registered as targets.
 */
@RegisterForReflection
public class EcsLoadBalancer {

    private String targetGroupArn;
    private String loadBalancerName;
    private String containerName;
    private Integer containerPort;
    /** {@code advancedConfiguration}: the blue/green alternate target group and listener rules. */
    private Map<String, Object> advancedConfiguration;
    /**
     * The target group serving production traffic under blue/green: null until the first
     * blue/green deployment shifts it, when it becomes {@code alternateTargetGroupArn}, and each
     * later deployment moves it back. DescribeServices keeps reporting {@code targetGroupArn}.
     */
    private String servingTargetGroupArn;

    public EcsLoadBalancer() {}

    public Map<String, Object> getAdvancedConfiguration() { return advancedConfiguration; }
    public void setAdvancedConfiguration(Map<String, Object> advancedConfiguration) {
        this.advancedConfiguration = advancedConfiguration;
    }

    public String getServingTargetGroupArn() {
        return servingTargetGroupArn != null ? servingTargetGroupArn : targetGroupArn;
    }
    public void setServingTargetGroupArn(String servingTargetGroupArn) {
        this.servingTargetGroupArn = servingTargetGroupArn;
    }

    public String getTargetGroupArn() { return targetGroupArn; }
    public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }

    public String getLoadBalancerName() { return loadBalancerName; }
    public void setLoadBalancerName(String loadBalancerName) { this.loadBalancerName = loadBalancerName; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public Integer getContainerPort() { return containerPort; }
    public void setContainerPort(Integer containerPort) { this.containerPort = containerPort; }
}
