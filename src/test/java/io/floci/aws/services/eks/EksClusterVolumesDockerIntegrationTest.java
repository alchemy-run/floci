package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.eks.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stopping a cluster removes its container's anonymous volumes. k3s's image declares {@code /var/lib/kubelet},
 * {@code /var/lib/cni} and {@code /var/log} as volumes; the cluster's own state is in its named volume, so the
 * anonymous ones would be left behind unused. A container from another image that declares a volume stands in for
 * k3s here.
 */
@QuarkusTest
class EksClusterVolumesDockerIntegrationTest {

    private static final String IMAGE_WITH_VOLUME = "registry:2";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EksClusterManager eksClusterManager;

    private String containerId;

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available");
    }

    @AfterEach
    void tearDown() {
        if (containerId != null) {
            lifecycleManager.removeIfExists(containerId, true);
        }
    }

    @Test
    void stoppingAClusterReclaimsItsContainersAnonymousVolumes() {
        String name = "volumes-it-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/" + name);
        ContainerSpec spec = containerBuilder.newContainer(IMAGE_WITH_VOLUME).withName("floci-eks-volumes-" + name)
                .build();
        containerId = lifecycleManager.createAndStart(spec).containerId();
        cluster.setContainerId(containerId);
        List<String> volumes = dockerClient.inspectContainerCmd(containerId).exec().getMounts().stream()
                .map(InspectContainerResponse.Mount::getName).filter(volume -> volume != null && !volume.isBlank())
                .toList();
        assertFalse(volumes.isEmpty(), "The image declares a volume");

        eksClusterManager.stopCluster(cluster);

        for (String volume : volumes) {
            assertThrows(NotFoundException.class, () -> dockerClient.inspectVolumeCmd(volume).exec(),
                    "The anonymous volume " + volume + " outlived the cluster's container");
        }
    }
}
