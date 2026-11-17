package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import io.floci.aws.services.eks.model.Cluster;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EKS Auto Mode provides the Nitro Sandbox RuntimeClasses without setup, so Floci installs them on
 * an Auto Mode cluster's k3s, on gVisor's runsc or on runc.
 */
class EksSandboxRuntimeClassesTest {

    @Test
    void theManifestDeclaresBothSandboxRuntimeClassesOnItsHandler() throws Exception {
        for (String handler : List.of("runc", EksGvisorRuntime.HANDLER)) {
            List<JsonNode> documents = parse(EksClusterManager.sandboxRuntimeClassesManifest(handler));
            assertEquals(2, documents.size());
            assertEquals(List.of("isolated-sandbox", "confidential-sandbox"),
                    documents.stream().map(d -> d.path("metadata").path("name").asText()).toList());
            for (JsonNode document : documents) {
                assertEquals("node.k8s.io/v1", document.path("apiVersion").asText());
                assertEquals("RuntimeClass", document.path("kind").asText());
                assertEquals(handler, document.path("handler").asText());
            }
        }
        assertTrue(EksClusterManager.SANDBOX_RUNTIME_CLASSES_TAR_ENTRY.startsWith("server/manifests/"));
    }

    @Test
    void theContainerdTemplatesExtendTheBaseConfigWithRunsc() {
        assertTrue(EksGvisorRuntime.CONTAINERD_V3_TEMPLATE.startsWith("{{ template \"base\" . }}"));
        assertTrue(EksGvisorRuntime.CONTAINERD_V3_TEMPLATE.contains(
                "[plugins.\"io.containerd.cri.v1.runtime\".containerd.runtimes.runsc]"));
        assertTrue(EksGvisorRuntime.CONTAINERD_V2_TEMPLATE.contains(
                "[plugins.\"io.containerd.grpc.v1.cri\".containerd.runtimes.runsc]"));
        assertTrue(EksGvisorRuntime.CONTAINERD_V3_TEMPLATE.contains("runtime_type = \"io.containerd.runsc.v1\""));
    }

    @Test
    void gvisorReleasesAreNamedByTheirArchitecture() throws Exception {
        assertEquals("aarch64", EksGvisorRuntime.releaseArch("aarch64"));
        assertEquals("aarch64", EksGvisorRuntime.releaseArch("arm64"));
        assertEquals("x86_64", EksGvisorRuntime.releaseArch("x86_64"));
        assertEquals("x86_64", EksGvisorRuntime.releaseArch("amd64"));
        assertThrows(IllegalArgumentException.class, () -> EksGvisorRuntime.releaseArch("s390x"));
        String hex = "ab".repeat(64);
        assertEquals(hex, EksGvisorRuntime.expectedSha512(hex + "  gvisor.tar.bz2\n"));
        assertThrows(IOException.class, () -> EksGvisorRuntime.expectedSha512("not-a-hash"));
    }

    private static List<JsonNode> parse(String yaml) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        List<JsonNode> documents = new ArrayList<>();
        try (YAMLParser parser = new YAMLFactory().createParser(yaml)) {
            mapper.readValues(parser, JsonNode.class).forEachRemaining(documents::add);
        }
        return documents;
    }

    @Test
    void onlyAnAutoModeClusterGetsThem() {
        Cluster auto = new Cluster();
        auto.setComputeConfig(Map.of("enabled", true, "nodePools", List.of("general-purpose")));
        assertTrue(EksClusterManager.isAutoMode(auto));

        Cluster disabled = new Cluster();
        disabled.setComputeConfig(Map.of("enabled", false));
        assertFalse(EksClusterManager.isAutoMode(disabled));
        assertFalse(EksClusterManager.isAutoMode(new Cluster()));
    }
}
