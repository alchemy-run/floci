package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudControlServiceTest {

    @Test
    void emitsOnlyAwsShapedTagsForMalformedPersistedData() throws Exception {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-test");
        vpc.setTags(List.of(
                new Tag(null, "ignored-null"),
                new Tag("", "ignored-empty"),
                new Tag("  ", "ignored-blank"),
                new Tag("Name", null)));
        when(ec2Service.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), ec2Service, mock(IamService.class), mock(SsmService.class), mapper);

        String properties = service.listResources("us-east-1", "AWS::EC2::VPC").getFirst().properties();
        JsonNode tags = mapper.readTree(properties).path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertTrue(tags.get(0).path("Key").isTextual());
        assertEquals("Name", tags.get(0).path("Key").asText());
        assertTrue(tags.get(0).path("Value").isTextual());
        assertEquals("", tags.get(0).path("Value").asText());
        assertFalse(properties.contains("ignored-null"));
        assertFalse(properties.contains("ignored-empty"));
        assertFalse(properties.contains("ignored-blank"));
    }

    @Test
    void createUpdateDeleteSsmParameterAndPollRequestStatus() throws Exception {
        CloudControlService service = ssmCloudControl();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode desired = mapper.createObjectNode();
        desired.put("Name", "/alchemy-test/cloudcontrol/greeting");
        desired.put("Type", "String");
        desired.put("Value", "hello");

        CloudControlService.ProgressEvent created = service.createResource(
                "us-east-1", "AWS::SSM::Parameter", desired, "client-create");
        assertEquals("SUCCESS", created.operationStatus());
        assertEquals("CREATE", created.operation());
        assertEquals("/alchemy-test/cloudcontrol/greeting", created.identifier());
        assertEquals("SUCCESS", service.getResourceRequestStatus(created.requestToken()).operationStatus());
        assertEquals("hello", mapper.readTree(created.resourceModel()).path("Value").asText());

        CloudControlService.ResourceDescription described = service.getResource(
                "us-east-1", "AWS::SSM::Parameter", created.identifier());
        assertEquals("hello", mapper.readTree(described.properties()).path("Value").asText());
        assertEquals(1, service.listResources("us-east-1", "AWS::SSM::Parameter").size());

        ArrayNode patch = mapper.createArrayNode();
        patch.addObject().put("op", "replace").put("path", "/Value").put("value", "world");
        CloudControlService.ProgressEvent updated = service.updateResource(
                "us-east-1", "AWS::SSM::Parameter", created.identifier(), patch, "client-update");
        assertEquals("SUCCESS", updated.operationStatus());
        assertEquals("world", mapper.readTree(
                service.getResource("us-east-1", "AWS::SSM::Parameter", created.identifier()).properties())
                .path("Value").asText());

        CloudControlService.ProgressEvent deleted = service.deleteResource(
                "us-east-1", "AWS::SSM::Parameter", created.identifier(), "client-delete");
        assertEquals("SUCCESS", deleted.operationStatus());
        assertTrue(service.listResourceRequests(List.of(), List.of()).size() >= 3);

        AwsException missing = assertThrows(AwsException.class,
                () -> service.getResource("us-east-1", "AWS::SSM::Parameter", created.identifier()));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void createSsmParameterAlreadyExists() {
        CloudControlService service = ssmCloudControl();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode desired = mapper.createObjectNode();
        desired.put("Name", "/alchemy-test/cloudcontrol/exists");
        desired.put("Type", "String");
        desired.put("Value", "one");
        service.createResource("us-east-1", "AWS::SSM::Parameter", desired, "token-a");

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createResource("us-east-1", "AWS::SSM::Parameter", desired, "token-b"));
        assertEquals("AlreadyExistsException", duplicate.getErrorCode());
        assertEquals(400, duplicate.getHttpStatus());
    }

    @Test
    void unknownRequestTokenIsNotFound() {
        CloudControlService service = ssmCloudControl();
        AwsException status = assertThrows(AwsException.class,
                () -> service.getResourceRequestStatus("00000000-0000-0000-0000-000000000000"));
        assertEquals("RequestTokenNotFoundException", status.getErrorCode());
        assertEquals(404, status.getHttpStatus());

        AwsException cancel = assertThrows(AwsException.class,
                () -> service.cancelResourceRequest("00000000-0000-0000-0000-000000000000"));
        assertEquals("RequestTokenNotFoundException", cancel.getErrorCode());
        assertEquals(404, cancel.getHttpStatus());
    }

    @Test
    void jsonPatchReplacesValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode target = mapper.readTree("{\"Name\":\"n\",\"Type\":\"String\",\"Value\":\"hello\"}");
        JsonNode patch = mapper.readTree("[{\"op\":\"replace\",\"path\":\"/Value\",\"value\":\"world\"}]");
        JsonNode result = CloudControlJsonPatch.apply(target, patch);
        assertEquals("world", result.path("Value").asText());
        assertEquals("n", result.path("Name").asText());
    }

    private static CloudControlService ssmCloudControl() {
        SsmService ssm = mock(SsmService.class);
        Map<String, Parameter> store = new ConcurrentHashMap<>();
        when(ssm.describeParameters(any())).thenAnswer(invocation -> List.copyOf(store.values()));
        when(ssm.getParameter(any(), any())).thenAnswer(invocation -> {
            Parameter parameter = store.get(invocation.getArgument(0));
            if (parameter == null) {
                throw new AwsException("ParameterNotFound", "Parameter not found.", 400);
            }
            return parameter;
        });
        when(ssm.putParameter(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    boolean overwrite = invocation.getArgument(4);
                    if (store.containsKey(name) && !overwrite) {
                        throw new AwsException("ParameterAlreadyExists", "The parameter already exists.", 400);
                    }
                    Parameter parameter = new Parameter(name, invocation.getArgument(1), invocation.getArgument(2));
                    parameter.setDescription(invocation.getArgument(3));
                    store.put(name, parameter);
                    return 1L;
                });
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (store.remove(name) == null) {
                throw new AwsException("ParameterNotFound", "Parameter not found.", 400);
            }
            return null;
        }).when(ssm).deleteParameter(any(), any());
        return new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), ssm, new ObjectMapper());
    }
}
