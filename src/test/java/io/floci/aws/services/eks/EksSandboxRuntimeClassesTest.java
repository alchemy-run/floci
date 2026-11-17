package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import io.floci.aws.services.eks.model.Cluster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EKS Auto Mode provides the Nitro Sandbox RuntimeClasses without setup, so Floci installs them on
 * an Auto Mode cluster's k3s, backed by its runc handler.
 */
class EksSandboxRuntimeClassesTest {

    @Test
    void theManifestDeclaresBothSandboxRuntimeClassesOnRunc() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        List<JsonNode> documents = new ArrayList<>();
        try (YAMLParser parser = new YAMLFactory().createParser(EksClusterManager.SANDBOX_RUNTIME_CLASSES_MANIFEST)) {
            mapper.readValues(parser, JsonNode.class).forEachRemaining(documents::add);
        }
        assertEquals(2, documents.size());
        assertEquals(List.of("isolated-sandbox", "confidential-sandbox"),
                documents.stream().map(d -> d.path("metadata").path("name").asText()).toList());
        for (JsonNode document : documents) {
            assertEquals("node.k8s.io/v1", document.path("apiVersion").asText());
            assertEquals("RuntimeClass", document.path("kind").asText());
            assertEquals("runc", document.path("handler").asText());
        }
        assertTrue(EksClusterManager.SANDBOX_RUNTIME_CLASSES_TAR_ENTRY.startsWith("server/manifests/"));
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
