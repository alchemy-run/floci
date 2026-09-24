package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Persistence-across-restart coverage: writes a fully-populated agent runtime through
 * {@link PersistentStorage} to disk and reloads it into a fresh instance (a simulated
 * restart), verifying that JsonNode config blobs, {@code Instant} timestamps, version
 * snapshots, endpoints, tags, and env vars all survive serialization. This is the path
 * exercised by {@code persistent}/{@code hybrid}/{@code wal} storage modes and native image.
 */
class BedrockAgentCorePersistenceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void agentRuntimeSurvivesPersistentStorageRoundTrip(@TempDir Path dir) {
        BedrockAgentCoreControlService svc = new BedrockAgentCoreControlService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

        ObjectNode artifact = mapper.createObjectNode();
        artifact.putObject("containerConfiguration").put("containerUri", "x:latest");
        ObjectNode network = mapper.createObjectNode();
        network.put("networkMode", "PUBLIC");
        ObjectNode authorizer = mapper.createObjectNode();
        authorizer.putObject("customJWTAuthorizer").put("discoveryUrl", "https://issuer/.well-known");

        AgentRuntime created = svc.createAgentRuntime("persistAgent", artifact, network,
                "arn:aws:iam::000000000000:role/agent", "v1", Map.of("K", "V"),
                authorizer, null, null, REGION);
        String id = created.getAgentRuntimeId();
        svc.updateAgentRuntime(id, artifact, network,
                "arn:aws:iam::000000000000:role/agent", "v2", null, authorizer, null, REGION);
        svc.createEndpoint(id, "prod", "1", "prod ep", null, REGION);
        AgentRuntime before = svc.getAgentRuntime(id, REGION);
        svc.tagByArn(REGION, svc.arn(before, "1", REGION), Map.of("env", "prod"));
        before = svc.getAgentRuntime(id, REGION);

        // Write to disk, then reload into a brand-new backend (simulated restart).
        Path file = dir.resolve("bedrock-agentcore-runtimes.json");
        TypeReference<Map<String, AgentRuntime>> ref = new TypeReference<>() {};
        PersistentStorage<String, AgentRuntime> writer = new PersistentStorage<>(file, ref);
        writer.put("runtime:" + REGION + ":" + id, before);
        writer.flush();

        PersistentStorage<String, AgentRuntime> reader = new PersistentStorage<>(file, ref);
        reader.load();
        AgentRuntime loaded = reader.get("runtime:" + REGION + ":" + id).orElseThrow();

        assertEquals("persistAgent", loaded.getAgentRuntimeName());
        assertEquals(2, loaded.getLatestVersion());
        assertEquals(2, loaded.getVersions().size());
        assertNotNull(loaded.getCreatedAt());
        // JsonNode config blobs survive.
        assertEquals("x:latest", loaded.getAgentRuntimeArtifact()
                .path("containerConfiguration").path("containerUri").asText());
        assertEquals("https://issuer/.well-known", loaded.getAuthorizerConfiguration()
                .path("customJWTAuthorizer").path("discoveryUrl").asText());
        // Per-version snapshot JsonNode survives.
        assertNotNull(loaded.getVersions().get(0).getAgentRuntimeArtifact());
        // Env vars + tags + endpoints (DEFAULT + prod) survive.
        assertEquals("V", loaded.getEnvironmentVariables().get("K"));
        assertEquals("prod", loaded.getTags().get("env"));
        assertEquals(2, loaded.getEndpoints().size());
        assertTrue(loaded.getEndpoints().stream().anyMatch(e -> "prod".equals(e.getName())));
        assertTrue(loaded.getEndpoints().stream().anyMatch(e -> "DEFAULT".equals(e.getName())));
    }

    @Test
    void memorySurvivesPersistentStorageRoundTrip(@TempDir Path dir) {
        BedrockAgentCoreMemoryService svc = new BedrockAgentCoreMemoryService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

        Memory created = svc.create("persistMem", 45, "a memory",
                "arn:aws:kms:us-east-1:000000000000:key/mem-key",
                "arn:aws:iam::000000000000:role/mem-role",
                Map.of("team", "core"), null, REGION);
        String id = created.getMemoryId();
        svc.tagByArn(REGION, svc.arn(created, REGION), Map.of("env", "prod"));
        Memory before = svc.get(id, REGION);

        Path file = dir.resolve("bedrock-agentcore-memories.json");
        TypeReference<Map<String, Memory>> ref = new TypeReference<>() {};
        PersistentStorage<String, Memory> writer = new PersistentStorage<>(file, ref);
        writer.put("memory:" + REGION + ":" + id, before);
        writer.flush();

        PersistentStorage<String, Memory> reader = new PersistentStorage<>(file, ref);
        reader.load();
        Memory loaded = reader.get("memory:" + REGION + ":" + id).orElseThrow();

        assertEquals("persistMem", loaded.getName());
        assertEquals(45, loaded.getEventExpiryDuration());
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/mem-key", loaded.getEncryptionKeyArn());
        assertEquals("arn:aws:iam::000000000000:role/mem-role", loaded.getMemoryExecutionRoleArn());
        assertEquals("core", loaded.getTags().get("team"));
        assertEquals("prod", loaded.getTags().get("env"));
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"browser", "browser-profile", "code-interpreter"})
    void toolTagsSurvivePersistentStorageRestart(String family, @TempDir Path dir) {
        Path file = dir.resolve("bedrock-agentcore-tools.json");
        TypeReference<Map<String, ObjectNode>> ref = new TypeReference<>() {};
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        PersistentStorage<String, ObjectNode> writer = new PersistentStorage<>(file, ref);
        BedrockAgentCoreToolsService service = new BedrockAgentCoreToolsService(writer, resolver);
        String arn = createTool(service, family);
        service.tagByArn(REGION, arn, Map.of("env", "prod", "remove", "value"));
        service.untagByArn(REGION, arn, List.of("remove", "missing"));
        writer.flush();

        PersistentStorage<String, ObjectNode> reader = new PersistentStorage<>(file, ref);
        reader.load();
        BedrockAgentCoreToolsService reloaded = new BedrockAgentCoreToolsService(reader, resolver);
        assertEquals(Map.of("env", "prod", "alchemy::id", "Tool"), reloaded.getTagsByArn(REGION, arn));
        reloaded.getTagsByArn(REGION, arn).clear();
        assertEquals(Map.of("env", "prod", "alchemy::id", "Tool"), reloaded.getTagsByArn(REGION, arn));
        reloaded.untagByArn(REGION, arn, List.of("env", "alchemy::id"));
        reader.flush();

        PersistentStorage<String, ObjectNode> emptyReader = new PersistentStorage<>(file, ref);
        emptyReader.load();
        assertEquals(Map.of(), new BedrockAgentCoreToolsService(emptyReader, resolver).getTagsByArn(REGION, arn));
    }

    @ParameterizedTest
    @ValueSource(strings = {"browser", "browser-profile", "code-interpreter"})
    void toolTagsRequireStoredOwnerAndCallerIdentity(String family) {
        InMemoryStorage<String, ObjectNode> storage = new InMemoryStorage<>();
        RegionResolver owner = spy(new RegionResolver(REGION, "000000000000"));
        doReturn("111111111111").when(owner).getAccountId();
        BedrockAgentCoreToolsService service = new BedrockAgentCoreToolsService(storage, owner);
        String arn = createTool(service, family);
        BedrockAgentCoreTagHandler ownerHandler = toolTagHandler(service, owner);
        assertEquals(Map.of("env", "test", "alchemy::id", "Tool"), ownerHandler.listTags(REGION, arn));

        RegionResolver foreign = new RegionResolver(REGION, "000000000000");
        BedrockAgentCoreToolsService foreignService = new BedrockAgentCoreToolsService(storage, foreign);
        BedrockAgentCoreTagHandler foreignHandler = toolTagHandler(foreignService, foreign);
        for (String candidate : List.of(arn, arn.replace(":111111111111:", ":000000000000:"))) {
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> foreignHandler.listTags(REGION, candidate)).getErrorCode());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> foreignHandler.tagResource(REGION, candidate, Map.of("env", "stolen"))).getErrorCode());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> foreignHandler.untagResource(REGION, candidate, List.of("env"))).getErrorCode());
        }
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> ownerHandler.listTags("eu-west-1", arn)).getErrorCode());
        assertEquals(Map.of("env", "test", "alchemy::id", "Tool"), ownerHandler.listTags(REGION, arn));
    }

    @Test
    void tagHandlerRetainsLegacyConfiguredAccountBoundary() {
        RegionResolver resolver = spy(new RegionResolver(REGION, "000000000000"));
        doReturn("111111111111").when(resolver).getAccountId();
        BedrockAgentCoreTagHandler handler = toolTagHandler(mock(BedrockAgentCoreToolsService.class), resolver);
        for (String resource : List.of("agent/agentId", "gateway/gatewayId", "memory/memoryId")) {
            String arn = resolver.buildArn("bedrock-agentcore", REGION, resource);
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> handler.listTags(REGION, arn)).getErrorCode());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> handler.tagResource(REGION, arn, Map.of("env", "stolen"))).getErrorCode());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> handler.untagResource(REGION, arn, List.of("env"))).getErrorCode());
        }
    }

    private BedrockAgentCoreTagHandler toolTagHandler(BedrockAgentCoreToolsService service, RegionResolver resolver) {
        return new BedrockAgentCoreTagHandler(mock(BedrockAgentCoreControlService.class),
                mock(BedrockAgentCoreGatewayService.class), mock(BedrockAgentCoreMemoryService.class), service, resolver);
    }

    private String createTool(BedrockAgentCoreToolsService service, String family) {
        ObjectNode request = mapper.createObjectNode();
        request.put("name", "persistTool");
        request.putObject("tags").put("env", "test").put("alchemy::id", "Tool");
        request.putObject("networkConfiguration").put("networkMode", "PUBLIC");
        return switch (family) {
            case "browser" -> service.createBrowser(request, REGION).path("browserArn").asText();
            case "browser-profile" -> service.createBrowserProfile(request, REGION).path("profileArn").asText();
            case "code-interpreter" -> service.createCodeInterpreter(request, REGION).path("codeInterpreterArn").asText();
            default -> throw new IllegalArgumentException("Unsupported tool family: " + family);
        };
    }

    @Test
    void toolDeleteIdempotencySurvivesPersistentStorageRestart(@TempDir Path dir) {
        Path file = dir.resolve("bedrock-agentcore-tools.json");
        TypeReference<Map<String, ObjectNode>> ref = new TypeReference<>() {};
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");

        PersistentStorage<String, ObjectNode> writer = new PersistentStorage<>(file, ref);
        BedrockAgentCoreToolsService beforeRestart = new BedrockAgentCoreToolsService(writer, regionResolver);

        ObjectNode request = mapper.createObjectNode();
        request.put("name", "persistBrowser");
        request.putObject("networkConfiguration").put("networkMode", "PUBLIC");
        String browserId = beforeRestart.createBrowser(request, REGION).path("browserId").asText();
        String clientToken = "persist-delete-browser-token-00000001";

        ObjectNode firstDelete = beforeRestart.deleteBrowser(browserId, clientToken, REGION);
        assertEquals("DELETING", firstDelete.path("status").asText());
        writer.flush();

        PersistentStorage<String, ObjectNode> reader = new PersistentStorage<>(file, ref);
        reader.load();
        BedrockAgentCoreToolsService afterRestart = new BedrockAgentCoreToolsService(reader, regionResolver);

        ObjectNode replayedDelete = afterRestart.deleteBrowser(browserId, clientToken, REGION);
        assertEquals("DELETING", replayedDelete.path("status").asText());
        assertEquals(browserId, replayedDelete.path("browserId").asText());
    }
}
