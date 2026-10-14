package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;
import software.amazon.awssdk.services.apigateway.model.Authorizer;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.apigateway.model.CreateResourceResponse;
import software.amazon.awssdk.services.apigateway.model.CreateRestApiResponse;
import software.amazon.awssdk.services.apigateway.model.GetAuthorizerResponse;
import software.amazon.awssdk.services.apigateway.model.EndpointType;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("API Gateway REST lifecycle discovery")
class ApiGatewayRestLifecycleCompatibilityTest {

    @Test
    @DisplayName("Authorizer TTL removal restores the local default idempotently")
    void authorizerTtlRemovalRestoresDefault() {
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            CreateRestApiResponse api = client.createRestApi(request -> request
                    .name(TestFixtures.uniqueName("authorizer-lifecycle")));
            try {
                CreateAuthorizerResponse authorizer = client.createAuthorizer(request -> request
                        .restApiId(api.id()).name("token-authorizer").type("TOKEN")
                        .authorizerUri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                                + "arn:aws:lambda:us-east-1:000000000000:function:authorizer/invocations")
                        .identitySource("method.request.header.Authorization"));
                assertThat(authorizer.authorizerResultTtlInSeconds()).isEqualTo(300);
                assertThat(client.updateAuthorizer(request -> request.restApiId(api.id())
                        .authorizerId(authorizer.id()).patchOperations(PatchOperation.builder().op("remove")
                                .path("/authorizerResultTtlInSeconds").build())).authorizerResultTtlInSeconds())
                        .isEqualTo(300);
                assertThat(client.getAuthorizer(request -> request.restApiId(api.id())
                        .authorizerId(authorizer.id())).authorizerResultTtlInSeconds()).isEqualTo(300);
                client.updateAuthorizer(request -> request.restApiId(api.id()).authorizerId(authorizer.id())
                        .patchOperations(PatchOperation.builder().op("replace")
                                .path("/authorizerResultTtlInSeconds").value("120").build()));
                GetAuthorizerResponse observed = client.getAuthorizer(request -> request
                        .restApiId(api.id()).authorizerId(authorizer.id()));
                assertThat(observed.authorizerResultTtlInSeconds()).isEqualTo(120);
                List<Authorizer> listed = client.getAuthorizers(request -> request.restApiId(api.id())).items();
                assertThat(listed).anySatisfy(item -> {
                    assertThat(item.id()).isEqualTo(authorizer.id());
                    assertThat(item.authorizerResultTtlInSeconds()).isEqualTo(120);
                });
                for (int attempt = 0; attempt < 2; attempt++) {
                    assertThat(client.updateAuthorizer(request -> request.restApiId(api.id())
                            .authorizerId(authorizer.id()).patchOperations(PatchOperation.builder().op("remove")
                                    .path("/authorizerResultTtlInSeconds").build())).authorizerResultTtlInSeconds())
                            .isEqualTo(300);
                }
                assertThat(client.getAuthorizer(request -> request.restApiId(api.id())
                        .authorizerId(authorizer.id())).authorizerResultTtlInSeconds()).isEqualTo(300);
            } finally {
                client.deleteRestApi(request -> request.restApiId(api.id()));
            }
        }
    }

    @Test
    @DisplayName("Ownership tags survive REST route deployment, updates, listing, and deletion")
    void deployedRestApiIsDiscoverableByExactTags() {
        Map<String, String> tags = Map.of("owner::stack", TestFixtures.uniqueName("rest-discovery"),
                "owner::stage", "development", "owner::id", "rest-api");
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            CreateRestApiResponse api = client.createRestApi(request -> request
                    .name(TestFixtures.uniqueName("rest-routes")).tags(tags)
                    .endpointConfiguration(configuration -> configuration.types(EndpointType.REGIONAL)));
            try {
                assertThat(api.tags()).isEqualTo(tags);
                for (String part : List.of("items", "echo")) {
                    CreateResourceResponse resource = client.createResource(request -> request
                            .restApiId(api.id()).parentId(api.rootResourceId()).pathPart(part));
                    String method = "items".equals(part) ? "GET" : "POST";
                    client.putMethod(request -> request.restApiId(api.id()).resourceId(resource.id())
                            .httpMethod(method).authorizationType("NONE"));
                    client.putIntegration(request -> request.restApiId(api.id()).resourceId(resource.id())
                            .httpMethod(method).type("AWS_PROXY").integrationHttpMethod("POST")
                            .uri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                                    + "arn:aws:lambda:us-east-1:000000000000:function:route-handler/invocations"));
                }
                String deploymentId = client.createDeployment(request -> request.restApiId(api.id())).id();
                client.createStage(request -> request.restApiId(api.id()).stageName("test")
                        .deploymentId(deploymentId));
                client.updateRestApi(request -> request.restApiId(api.id()).patchOperations(
                        PatchOperation.builder().op("replace").path("/description").value("deployed").build()));
                assertThat(client.getRestApi(request -> request.restApiId(api.id())).tags()).isEqualTo(tags);
                assertThat(findByTags(client, tags)).singleElement().satisfies(observed -> {
                    assertThat(observed.id()).isEqualTo(api.id());
                    assertThat(observed.rootResourceId()).isEqualTo(api.rootResourceId());
                    assertThat(observed.tags()).isEqualTo(tags);
                });
                String arn = "arn:aws:apigateway:us-east-1::/restapis/" + api.id();
                client.tagResource(request -> request.resourceArn(arn).tags(Map.of("environment", "updated")));
                assertThat(findByTags(client, tags)).singleElement().satisfies(observed ->
                        assertThat(observed.tags()).containsEntry("environment", "updated"));
                client.untagResource(request -> request.resourceArn(arn).tagKeys("environment"));
                assertThat(findByTags(client, tags)).singleElement().satisfies(observed ->
                        assertThat(observed.tags()).isEqualTo(tags));
            } finally {
                client.deleteRestApi(request -> request.restApiId(api.id()));
            }
            assertThat(findByTags(client, tags)).isEmpty();
        }
    }

    @Test
    @DisplayName("Stage tag lifecycle preserves parent ownership discovery and sibling tags")
    void stageTagsDoNotCorruptRestApiDiscovery() {
        Map<String, String> apiTags = Map.of("alchemy::stack", TestFixtures.uniqueName("stage-discovery"),
                "alchemy::stage", "development", "alchemy::id", "AgEsApi");
        Map<String, String> stageTags = Map.of("alchemy::id", "AgEsStage", "keep", "stage-value");
        Map<String, String> siblingTags = Map.of("alchemy::id", "SiblingStage", "keep", "sibling-value");
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            CreateRestApiResponse api = client.createRestApi(request -> request
                    .name(TestFixtures.uniqueName("stage-tag-lifecycle")).tags(apiTags));
            String apiArn = "arn:aws:apigateway:us-east-1::/restapis/" + api.id();
            String stageArn = apiArn + "/stages/blue";
            try {
                client.putMethod(request -> request.restApiId(api.id()).resourceId(api.rootResourceId())
                        .httpMethod("GET").authorizationType("NONE"));
                client.putIntegration(request -> request.restApiId(api.id()).resourceId(api.rootResourceId())
                        .httpMethod("GET").type("MOCK"));
                String deploymentId = client.createDeployment(request -> request.restApiId(api.id())).id();
                assertThat(client.createStage(request -> request.restApiId(api.id()).stageName("blue")
                        .deploymentId(deploymentId).tags(stageTags)).tags()).isEqualTo(stageTags);
                assertThat(client.createStage(request -> request.restApiId(api.id()).stageName("green")
                        .deploymentId(deploymentId).tags(siblingTags)).tags()).isEqualTo(siblingTags);
                assertThat(client.getTags(request -> request.resourceArn(stageArn)).tags()).isEqualTo(stageTags);
                assertStageTagIsolation(client, api.id(), apiTags, siblingTags);

                Map<String, String> updatedTags = Map.of("alchemy::id", "UpdatedStage", "keep", "stage-value",
                        "added", "value");
                client.tagResource(request -> request.resourceArn(stageArn)
                        .tags(Map.of("alchemy::id", "UpdatedStage", "added", "value")));
                assertThat(client.getTags(request -> request.resourceArn(stageArn)).tags()).isEqualTo(updatedTags);
                assertThat(client.updateStage(request -> request.restApiId(api.id()).stageName("blue")
                        .patchOperations(PatchOperation.builder().op("replace").path("/description")
                                .value("updated").build())).tags()).isEqualTo(updatedTags);
                assertThat(client.getStage(request -> request.restApiId(api.id()).stageName("blue")).tags())
                        .isEqualTo(updatedTags);
                assertThat(client.getStages(request -> request.restApiId(api.id())).item())
                        .filteredOn(stage -> "blue".equals(stage.stageName())).singleElement()
                        .satisfies(stage -> assertThat(stage.tags()).isEqualTo(updatedTags));
                client.createDeployment(request -> request.restApiId(api.id()).stageName("blue"));
                assertThat(client.getStage(request -> request.restApiId(api.id()).stageName("blue")).tags())
                        .isEqualTo(updatedTags);
                assertStageTagIsolation(client, api.id(), apiTags, siblingTags);

                for (int attempt = 0; attempt < 2; attempt++) {
                    client.untagResource(request -> request.resourceArn(stageArn)
                            .tagKeys("alchemy::id", "added", "absent"));
                    assertThat(client.getTags(request -> request.resourceArn(stageArn)).tags())
                            .isEqualTo(Map.of("keep", "stage-value"));
                }
                assertThat(client.getStage(request -> request.restApiId(api.id()).stageName("blue")).tags())
                        .isEqualTo(Map.of("keep", "stage-value"));
                assertStageTagIsolation(client, api.id(), apiTags, siblingTags);
                client.deleteStage(request -> request.restApiId(api.id()).stageName("blue"));
                assertSdkTagFailure(client, stageArn, NotFoundException.class, 404);
                assertThat(client.createStage(request -> request.restApiId(api.id()).stageName("blue")
                        .deploymentId(deploymentId)).tags()).isEmpty();
                assertThat(client.getTags(request -> request.resourceArn(stageArn)).tags()).isEmpty();
                client.tagResource(request -> request.resourceArn(stageArn).tags(Map.of("fresh", "value")));
                assertThat(client.getTags(request -> request.resourceArn(stageArn)).tags())
                        .isEqualTo(Map.of("fresh", "value"));
                assertStageTagIsolation(client, api.id(), apiTags, siblingTags);
            } finally {
                client.deleteRestApi(request -> request.restApiId(api.id()));
            }
            assertSdkTagFailure(client, stageArn, NotFoundException.class, 404);
            assertThat(findByTags(client, apiTags)).isEmpty();
        }
    }

    @Test
    @DisplayName("Malformed tag targets fail without mutating parent tags, while missing stages return not found")
    void stageTagTargetsRejectMalformedAndMissingResources() {
        Map<String, String> tags = Map.of("owner::id", TestFixtures.uniqueName("tag-target-validation"));
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            CreateRestApiResponse api = client.createRestApi(request -> request
                    .name(TestFixtures.uniqueName("tag-targets")).tags(tags));
            String arn = "arn:aws:apigateway:us-east-1::/restapis/" + api.id();
            try {
                for (String suffix : List.of("/", "/stages", "/stages/", "/stages/blue/extra",
                        "/resources/" + api.rootResourceId(), "/deployments/missing")) {
                    assertSdkTagFailure(client, arn + suffix, BadRequestException.class, 400);
                }
                assertSdkTagFailure(client, "arn:aws:apigateway:us-east-1::/restapis//stages/blue",
                        BadRequestException.class, 400);
                assertSdkTagFailure(client,
                        "arn:aws:apigateway:us-east-1::/domainnames/example.com/basepathmappings/test",
                        BadRequestException.class, 400);
                assertSdkTagFailure(client, arn + "/stages/missing", NotFoundException.class, 404);
                assertThat(client.getTags(request -> request.resourceArn(arn)).tags()).isEqualTo(tags);
                assertThat(client.getRestApi(request -> request.restApiId(api.id())).tags()).isEqualTo(tags);
                assertThat(findByTags(client, tags)).singleElement().satisfies(item ->
                        assertThat(item.id()).isEqualTo(api.id()));
            } finally {
                client.deleteRestApi(request -> request.restApiId(api.id()));
            }
        }
    }

    private static void assertStageTagIsolation(ApiGatewayClient client, String apiId,
                                               Map<String, String> apiTags, Map<String, String> siblingTags) {
        String arn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;
        assertThat(client.getTags(request -> request.resourceArn(arn)).tags()).isEqualTo(apiTags);
        assertThat(client.getRestApi(request -> request.restApiId(apiId)).tags()).isEqualTo(apiTags);
        assertThat(findByTags(client, apiTags)).singleElement().satisfies(api -> {
            assertThat(api.id()).isEqualTo(apiId);
            assertThat(api.tags()).isEqualTo(apiTags);
        });
        assertThat(client.getTags(request -> request.resourceArn(arn + "/stages/green")).tags())
                .isEqualTo(siblingTags);
        assertThat(client.getStage(request -> request.restApiId(apiId).stageName("green")).tags())
                .isEqualTo(siblingTags);
    }

    private static void assertSdkTagFailure(ApiGatewayClient client, String arn,
                                            Class<? extends ApiGatewayException> type, int status) {
        List<Runnable> operations = List.of(
                () -> client.getTags(request -> request.resourceArn(arn)),
                () -> client.tagResource(request -> request.resourceArn(arn).tags(Map.of("owner::id", "wrong-target"))),
                () -> client.untagResource(request -> request.resourceArn(arn).tagKeys("owner::id")));
        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(type, error -> {
                assertThat(error.statusCode()).isEqualTo(status);
                assertThat(error.awsErrorDetails().errorCode()).isEqualTo(type.getSimpleName());
            });
        }
    }

    private static List<RestApi> findByTags(ApiGatewayClient client, Map<String, String> expected) {
        return client.getRestApisPaginator().items().stream()
                .filter(api -> api.tags().entrySet().containsAll(expected.entrySet()))
                .toList();
    }
}
