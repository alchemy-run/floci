package io.floci.aws.services.ecs;

import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Launches and terminates the EC2 instances behind an ECS Managed Instances capacity provider.
 *
 * <p>AWS launches instances for a Managed Instances provider from its {@code instanceLaunchTemplate}
 * when a task needs capacity, and terminates them when the provider is deleted. Floci launches one
 * instance per provider through EC2, so DescribeInstances answers for the {@code ec2InstanceId} the
 * container instance reports, with {@code nitroEnclavesEnabled} carried onto the instance's
 * {@code EnclaveOptions}. The task itself still runs as a local container.
 */
@ApplicationScoped
public class EcsManagedInstances {

    private static final Logger LOG = Logger.getLogger(EcsManagedInstances.class);

    /** The ECS-optimized AMI parameter Floci's image catalog publishes. */
    static final String ECS_OPTIMIZED_AMI_PARAMETER =
            "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id";
    /** Intel and AMD instances need 4 vCPUs for Nitro Enclaves; m6i.xlarge has 4. */
    static final String DEFAULT_ENCLAVE_INSTANCE_TYPE = "m6i.xlarge";
    static final String DEFAULT_INSTANCE_TYPE = "m6i.large";

    private final Ec2Service ec2Service;
    private final Ec2ImageCatalog imageCatalog;

    @Inject
    public EcsManagedInstances(Ec2Service ec2Service, Ec2ImageCatalog imageCatalog) {
        this.ec2Service = ec2Service;
        this.imageCatalog = imageCatalog;
    }

    /** Launches one instance for {@code launchTemplate} and returns its id. */
    String launch(String region, String clusterName, String providerName, Map<String, Object> launchTemplate) {
        Map<String, Object> network = map(launchTemplate.get("networkConfiguration"));
        List<String> subnets = strings(network.get("subnets"));
        List<String> securityGroups = strings(network.get("securityGroups"));
        boolean enclaves = Boolean.TRUE.equals(launchTemplate.get("nitroEnclavesEnabled"));
        String imageId = imageCatalog.findByPublicParameterName(ECS_OPTIMIZED_AMI_PARAMETER)
                .map(image -> image.imageId)
                .orElse(null);
        Reservation reservation = ec2Service.runInstances(region, imageId,
                instanceType(launchTemplate, enclaves), 1, 1, null, securityGroups,
                subnets.isEmpty() ? null : subnets.getFirst(),
                "ecs-mi-" + UUID.randomUUID(),
                List.of(new Tag("AmazonECSManaged", "true"),
                        new Tag("ecs-managed-instances/cluster", clusterName),
                        new Tag("ecs-managed-instances/capacity-provider", providerName)),
                null, (String) launchTemplate.get("ec2InstanceProfileArn"));
        Instance instance = reservation.getInstances().getFirst();
        if (enclaves) {
            ec2Service.setLaunchEnclaveOptions(region, instance.getInstanceId(), true);
        }
        LOG.infov("Launched managed instance {0} for capacity provider {1} in cluster {2}",
                instance.getInstanceId(), providerName, clusterName);
        return instance.getInstanceId();
    }

    void terminate(String region, List<String> instanceIds) {
        if (!instanceIds.isEmpty()) {
            ec2Service.terminateInstances(region, instanceIds);
        }
    }

    /**
     * The first allowed instance type, else one that meets the vCPU minimum, else a type that can
     * run Nitro Enclaves when the template enables them.
     */
    static String instanceType(Map<String, Object> launchTemplate, boolean enclaves) {
        Map<String, Object> requirements = map(launchTemplate.get("instanceRequirements"));
        List<String> allowed = strings(requirements.get("allowedInstanceTypes"));
        String first = allowed.stream().filter(type -> !type.contains("*")).findFirst().orElse(null);
        if (first != null) {
            return first;
        }
        Object minVcpus = map(requirements.get("vCpuCount")).get("min");
        boolean needsFour = minVcpus instanceof Number n && n.intValue() > 2;
        return enclaves || needsFour ? DEFAULT_ENCLAVE_INSTANCE_TYPE : DEFAULT_INSTANCE_TYPE;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }
}
