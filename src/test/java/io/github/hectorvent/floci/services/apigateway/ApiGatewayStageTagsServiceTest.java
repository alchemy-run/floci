package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayStageTagsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ARN_PREFIX = "arn:aws:apigateway:us-east-1::";
    private static final Map<String, String> API_TAGS = Map.of("owner::id", "api");
    private static final Map<String, String> SIBLING_TAGS = Map.of("owner::id", "sibling");

    @Test
    void stageTagsPersistIndependentlyAcrossUpdatesDeletionAndRecreation(@TempDir Path dir) {
        ApiGatewayService service = newService(dir);
        String apiId = service.createRestApi(REGION, Map.of("name", "stage-tags", "tags", API_TAGS)).getId();
        String otherId = service.createRestApi(REGION, Map.of("name", "other-api")).getId();
        String deploymentId = service.createDeployment(REGION, apiId, Map.of()).id();
        Map<String, String> createTags = new HashMap<>(Map.of("owner::id", "stage", "keep", "value"));
        service.createStage(REGION, apiId, Map.of("stageName", "blue", "deploymentId", deploymentId,
                "tags", createTags));
        service.createStage(REGION, apiId, Map.of("stageName", "green", "deploymentId", deploymentId,
                "tags", SIBLING_TAGS));
        service.createStage(REGION, otherId, Map.of("stageName", "blue", "deploymentId",
                service.createDeployment(REGION, otherId, Map.of()).id(), "tags", SIBLING_TAGS));
        createTags.put("owner::id", "caller-mutation");
        String stageArn = ARN_PREFIX + "/restapis/" + apiId + "/stages/blue";

        service = newService(dir);
        ApiGatewayTagHandler handler = new ApiGatewayTagHandler(service);
        assertEquals(Map.of("owner::id", "stage", "keep", "value"), handler.listTags(REGION, stageArn));
        handler.listTags(REGION, stageArn).clear();
        assertEquals("stage", handler.listTags(REGION, stageArn).get("owner::id"));
        handler.tagResource(REGION, stageArn, Map.of("owner::id", "updated-stage", "new", "value"));

        service = newService(dir);
        handler = new ApiGatewayTagHandler(service);
        Map<String, String> updated = Map.of("owner::id", "updated-stage", "keep", "value", "new", "value");
        assertEquals(updated, handler.listTags(REGION, stageArn));
        assertEquals(updated, service.updateStage(REGION, apiId, "blue", List.of(
                Map.of("op", "replace", "path", "/description", "value", "updated"))).getTags());
        service.createDeployment(REGION, apiId, Map.of("stageName", "blue"));
        assertEquals(updated, service.getStage(REGION, apiId, "blue").getTags());
        assertEquals(updated, service.getStages(REGION, apiId).stream()
                .filter(stage -> "blue".equals(stage.getStageName())).findFirst().orElseThrow().getTags());
        assertIsolation(service, apiId, otherId);
        handler.untagResource(REGION, stageArn, List.of("owner::id", "new", "absent"));
        handler.untagResource(REGION, stageArn, List.of("owner::id", "new", "absent"));

        service = newService(dir);
        assertEquals(Map.of("keep", "value"), service.getStageTags(REGION, apiId, "blue"));
        assertIsolation(service, apiId, otherId);
        service.deleteStage(REGION, apiId, "blue");

        service = newService(dir);
        assertTargetFailure(new ApiGatewayTagHandler(service), stageArn, "NotFoundException", 404);
        service.createStage(REGION, apiId, Map.of("stageName", "blue", "deploymentId", deploymentId));
        service = newService(dir);
        assertEquals(Map.of(), service.getStageTags(REGION, apiId, "blue"));
        assertIsolation(service, apiId, otherId);
        service.tagStage(REGION, apiId, "blue", Map.of("fresh", "value"));
        assertEquals(Map.of("fresh", "value"), newService(dir).getStageTags(REGION, apiId, "blue"));
    }

    @Test
    void malformedAndMissingTargetsNeverFallBackToTheParent(@TempDir Path dir) {
        ApiGatewayService service = newService(dir);
        String apiId = service.createRestApi(REGION, Map.of("name", "invalid-tags", "tags", API_TAGS)).getId();
        service.createStage(REGION, apiId, Map.of("stageName", "blue", "deploymentId",
                service.createDeployment(REGION, apiId, Map.of()).id(), "tags", SIBLING_TAGS));
        ApiGatewayTagHandler handler = new ApiGatewayTagHandler(service);
        String apiArn = ARN_PREFIX + "/restapis/" + apiId;
        for (String suffix : List.of("/", "/stages", "/stages/", "/stages/blue/", "/stages/blue/extra",
                "/resources/root", "/deployments/deployment", "/stages/blue?query", "/stages/blue#fragment")) {
            assertTargetFailure(handler, apiArn + suffix, "BadRequestException", 400);
        }
        for (String resource : List.of("/restapis/", "/restapis//stages/blue", "/domainnames/",
                "/domainnames/example.com/basepathmappings/test",
                "/domainnames/example.com/restapis/" + apiId,
                "/unsupported/restapis/" + apiId)) {
            assertTargetFailure(handler, ARN_PREFIX + resource, "BadRequestException", 400);
        }
        for (String arn : List.of("not-an-arn/restapis/" + apiId,
                "arn:aws:apigateway:us-east-1:123456789012:/restapis/" + apiId,
                "arn:aws:s3:us-east-1::/restapis/" + apiId)) {
            assertTargetFailure(handler, arn, "BadRequestException", 400);
        }
        assertTargetFailure(handler, apiArn + "/stages/missing", "NotFoundException", 404);
        assertTargetFailure(handler, ARN_PREFIX + "/restapis/missing/stages/blue", "NotFoundException", 404);
        assertTargetFailure(handler, ARN_PREFIX + "/restapis/missing", "NotFoundException", 404);
        assertTargetFailure(handler, ARN_PREFIX + "/domainnames/missing.example.com", "NotFoundException", 404);
        assertEquals(API_TAGS, handler.listTags(REGION, apiArn));
        assertEquals(SIBLING_TAGS, handler.listTags(REGION, apiArn + "/stages/blue"));
        handler.tagResource(REGION, apiArn, Map.of("api-only", "value"));
        assertEquals("value", handler.listTags(REGION, apiArn).get("api-only"));
        assertEquals(SIBLING_TAGS, service.getStageTags(REGION, apiId, "blue"));
        handler.untagResource(REGION, apiArn, List.of("api-only"));
        assertEquals(API_TAGS, handler.listTags(REGION, apiArn));
    }

    @Test
    void legacyStagesWithoutTagsDeserializeWithAnEmptyMutableMap() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String json : List.of("{\"stageName\":\"legacy\"}", "{\"stageName\":\"legacy\",\"tags\":null}")) {
            Stage stage = mapper.readValue(json, Stage.class);
            assertEquals(Map.of(), stage.getTags());
            stage.getTags().put("added", "value");
            Stage restored = mapper.readValue(mapper.writeValueAsString(stage), Stage.class);
            assertEquals(Map.of("added", "value"), restored.getTags());
        }
    }

    private static void assertIsolation(ApiGatewayService service, String apiId, String otherId) {
        assertEquals(API_TAGS, service.getTags(REGION, apiId));
        assertEquals(API_TAGS, service.getRestApis(REGION).stream()
                .filter(api -> apiId.equals(api.getId())).findFirst().orElseThrow().getTags());
        assertEquals(SIBLING_TAGS, service.getStageTags(REGION, apiId, "green"));
        assertEquals(SIBLING_TAGS, service.getStageTags(REGION, otherId, "blue"));
    }

    private static void assertTargetFailure(ApiGatewayTagHandler handler, String arn, String code, int status) {
        List<Runnable> operations = List.of(
                () -> handler.listTags(REGION, arn),
                () -> handler.tagResource(REGION, arn, Map.of("owner::id", "wrong-target")),
                () -> handler.untagResource(REGION, arn, List.of("owner::id")));
        for (Runnable operation : operations) {
            AwsException error = assertThrows(AwsException.class, operation::run, arn);
            assertEquals(code, error.getErrorCode(), arn);
            assertEquals(status, error.getHttpStatus(), arn);
        }
    }

    private static ApiGatewayService newService(Path dir) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.persistentPath()).thenReturn(dir.toString());
        when(config.defaultAccountId()).thenReturn("000000000000");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("apigateway")).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, access);
        return new ApiGatewayService(factory, config, mock(TlsCertificateManager.class));
    }
}
