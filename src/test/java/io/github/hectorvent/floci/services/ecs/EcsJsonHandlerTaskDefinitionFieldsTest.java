package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Register/DescribeTaskDefinition must round-trip the control-plane fields
 * Alchemy's Task / TaskDefinition suite asserts: {@code logConfiguration},
 * {@code command}, {@code dependsOn}, {@code environmentFiles},
 * {@code runtimePlatform}, {@code ephemeralStorage}, and top-level {@code tags}
 * when {@code include} contains {@code TAGS}.
 */
class EcsJsonHandlerTaskDefinitionFieldsTest {

    private EcsService service;
    private ObjectMapper objectMapper;
    private EcsJsonHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = mock(EcsService.class);
        when(service.registerTaskDefinition(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    TaskDefinition td = new TaskDefinition();
                    td.setFamily(inv.getArgument(0));
                    td.setRevision(1);
                    td.setStatus("ACTIVE");
                    td.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/"
                            + inv.getArgument(0) + ":1");
                    td.setContainerDefinitions(inv.getArgument(1, List.class));
                    td.setTags(inv.getArgument(8));
                    return td;
                });
        handler = new EcsJsonHandler(service, objectMapper);
    }

    @Test
    void registerTaskDefinitionRoundTripsLogConfigurationCommandAndDependsOn() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "family": "fields-family",
                  "runtimePlatform": {"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
                  "ephemeralStorage": {"sizeInGiB": 25},
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "nginx:stable",
                      "logConfiguration": {
                        "logDriver": "awslogs",
                        "options": {
                          "awslogs-group": "/ecs/fields-family",
                          "awslogs-region": "us-east-1",
                          "awslogs-stream-prefix": "fields-family"
                        }
                      },
                      "dependsOn": [{"containerName": "sidecar", "condition": "START"}],
                      "environmentFiles": [{"value": "arn:aws:s3:::bucket/app.env", "type": "s3"}]
                    },
                    {
                      "name": "sidecar",
                      "image": "busybox:latest",
                      "essential": false,
                      "command": ["sh", "-c", "sleep 30"]
                    }
                  ]
                }
                """);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        assertEquals("ARM64", td.path("runtimePlatform").path("cpuArchitecture").asText());
        assertEquals("LINUX", td.path("runtimePlatform").path("operatingSystemFamily").asText());
        assertEquals(25, td.path("ephemeralStorage").path("sizeInGiB").asInt());

        JsonNode app = td.path("containerDefinitions").get(0);
        assertEquals("awslogs", app.path("logConfiguration").path("logDriver").asText());
        assertEquals("/ecs/fields-family",
                app.path("logConfiguration").path("options").path("awslogs-group").asText());
        assertEquals("sidecar", app.path("dependsOn").get(0).path("containerName").asText());
        assertEquals("START", app.path("dependsOn").get(0).path("condition").asText());
        assertEquals("arn:aws:s3:::bucket/app.env",
                app.path("environmentFiles").get(0).path("value").asText());
        assertEquals("s3", app.path("environmentFiles").get(0).path("type").asText());

        JsonNode sidecar = td.path("containerDefinitions").get(1);
        assertEquals("sidecar", sidecar.path("name").asText());
        assertEquals(3, sidecar.path("command").size());
        assertEquals("sleep 30", sidecar.path("command").get(2).asText());
    }

    @Test
    void describeTaskDefinitionReturnsTopLevelTagsWhenIncluded() throws Exception {
        TaskDefinition td = new TaskDefinition();
        td.setFamily("tagged");
        td.setRevision(1);
        td.setStatus("ACTIVE");
        td.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/tagged:1");
        td.setContainerDefinitions(List.of());
        td.getTags().put("env", "test");
        td.getTags().put("alchemy::id", "LifecycleTaskDef");
        when(service.describeTaskDefinition(anyString(), anyString())).thenReturn(td);

        JsonNode request = objectMapper.readTree("""
                {"taskDefinition": "tagged:1", "include": ["TAGS"]}
                """);
        Response response = handler.handle("DescribeTaskDefinition", request, "us-east-1");
        JsonNode body = objectMapper.valueToTree(response.getEntity());

        assertTrue(body.path("tags").isArray());
        assertEquals(2, body.path("tags").size());
        String env = null;
        for (JsonNode tag : body.path("tags")) {
            if ("env".equals(tag.path("key").asText())) {
                env = tag.path("value").asText();
            }
        }
        assertEquals("test", env);
    }
}
