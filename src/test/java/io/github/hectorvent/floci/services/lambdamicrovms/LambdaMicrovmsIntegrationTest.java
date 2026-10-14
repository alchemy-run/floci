package io.github.hectorvent.floci.services.lambdamicrovms;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmBuildService;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmRuntimeService;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmStore;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmRecord;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaMicrovmsIntegrationTest {

    private static final String MICROVMS = "/2025-09-09";
    private static final String CORE = "/2026-04-04";
    private static final String TAGS = "/2017-03-31";
    private static final String IMAGE = "microvm-it-image";

    private static String microvmId;
    private static String connectorId;
    private static String imageArn;

    @InjectMock
    MicrovmBuildService buildService;

    @InjectMock
    ContainerLifecycleManager containers;

    @Inject
    MicrovmRuntimeService runtime;

    @Inject
    MicrovmStore vmStore;

    private DockerClient docker;

    @BeforeEach
    void mockDockerInfrastructure() {
        when(buildService.buildForAccount(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            String artifact = call.getArgument(2);
            return artifact.startsWith("docker://") ? artifact.substring("docker://".length()) : call.getArgument(3);
        });
        when(containers.createAndStart(any())).thenAnswer(call ->
                new ContainerLifecycleManager.ContainerInfo("container-" + UUID.randomUUID(), Map.of()));
        docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(containers.getDockerClient()).thenReturn(docker);
    }

    @Test
    @Order(1)
    void createImage() {
        imageArn = given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "baseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                          "buildRoleArn": "arn:aws:iam::000000000000:role/microvm-build",
                          "codeArtifact": { "uri": "s3://bucket/code.zip" }
                        }
                        """.formatted(IMAGE))
                .when()
                .post(MICROVMS + "/microvm-images")
                .then()
                .statusCode(201)
                .body("name", equalTo(IMAGE))
                .body("state", equalTo("CREATING"))
                .body("imageVersion", equalTo("1.0"))
                .extract().path("imageArn");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .get(MICROVMS + "/microvm-images/" + IMAGE).then()
                .body("state", equalTo("CREATED")));
        verify(buildService).buildForAccount(eq("000000000000"), eq("us-east-1"),
                eq("s3://bucket/code.zip"), anyString());
    }

    @Test
    @Order(2)
    void imageSettlesToCreated() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(200)
                .body("state", equalTo("CREATED"))
                .body("latestActiveImageVersion", equalTo("1.0")));

    }

    @Test
    @Order(3)
    void versionAndBuildConverge() {
        String buildId = given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0/builds")
                .then()
                .statusCode(200)
                .body("items[0].buildState", equalTo("SUCCESSFUL"))
                .extract().path("items[0].buildId");

        given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0/builds/" + buildId)
                .then()
                .statusCode(200)
                .body("buildState", equalTo("SUCCESSFUL"))
                .body("architecture", equalTo(hostArchitecture()));

        given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0")
                .then()
                .statusCode(200)
                .body("state", equalTo("SUCCESSFUL"))
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    @Order(4)
    void badImageNameIsRejected() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "bad name!",
                          "baseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                          "buildRoleArn": "arn:aws:iam::000000000000:role/microvm-build",
                          "codeArtifact": { "uri": "s3://bucket/code.zip" }
                        }
                        """)
                .when()
                .post(MICROVMS + "/microvm-images")
                .then()
                .statusCode(400);
    }

    @Test
    @Order(5)
    void managedCatalogListsBaseImage() {
        given()
                .when()
                .get(MICROVMS + "/managed-microvm-images")
                .then()
                .statusCode(200)
                .body("items[0].imageArn", notNullValue());
    }

    @Test
    @Order(6)
    void runMicrovm() {
        microvmId = given()
                .contentType("application/json")
                .body("{\"imageIdentifier\": \"" + IMAGE + "\"}")
                .when()
                .post(MICROVMS + "/microvms")
                .then()
                .statusCode(200)
                .body("state", equalTo("RUNNING"))
                .body("microvmId", startsWith("mvm-"))
                .body("maximumDurationInSeconds", equalTo(28800))
                .extract().path("microvmId");

        given()
                .when()
                .get(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(200)
                .body("state", equalTo("RUNNING"));
    }

    @Test
    @Order(7)
    void runWithMissingImageIsNotFound() {
        given()
                .contentType("application/json")
                .body("{\"imageIdentifier\": \"no-such-image\"}")
                .when()
                .post(MICROVMS + "/microvms")
                .then()
                .statusCode(404);
    }

    @Test
    @Order(8)
    void imageInUseRefusesDelete() {
        // Recorded live: 400 with "Cannot delete microvm image with running microvms."
        given()
                .when()
                .delete(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(400);
    }

    @Test
    @Order(9)
    void tagImageArn() {
        given()
                .contentType("application/json")
                .body("{\"Tags\": {\"team\": \"conformance\"}}")
                .when()
                .post(TAGS + "/tags/" + imageArn)
                .then()
                .statusCode(204);

        given()
                .when()
                .get(TAGS + "/tags/" + imageArn)
                .then()
                .statusCode(200)
                .body("Tags.team", equalTo("conformance"));

        given()
                .queryParam("tagKeys", "team")
                .when()
                .delete(TAGS + "/tags/" + imageArn)
                .then()
                .statusCode(204);
    }

    @Test
    @Order(10)
    void terminateMicrovm() {
        given()
                .when()
                .delete(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(200);

        given()
                .when()
                .get(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(200)
                .body("state", equalTo("TERMINATED"));

        // Recorded live: terminal-state mutations are 400 ValidationException.
        given()
                .when()
                .delete(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(400);
    }

    @Test
    @Order(11)
    void connectorLifecycle() {
        connectorId = given()
                .contentType("application/json")
                .body("""
                        {
                          "Name": "microvm-it-connector",
                          "ClientToken": "microvm-it-token-1",
                          "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator",
                          "Configuration": { "VpcEgressConfiguration": {
                            "AssociatedComputeResourceTypes": ["MicroVm"],
                            "NetworkProtocol": "IPv4",
                            "SubnetIds": ["subnet-0000000000000it01"],
                            "SecurityGroupIds": ["sg-0000000000000it01"]
                          } }
                        }
                        """)
                .when()
                .post(CORE + "/network-connectors")
                .then()
                .statusCode(202)
                .body("State", equalTo("PENDING"))
                .extract().path("Id");

        given()
                .when()
                .get(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(200)
                .body("State", equalTo("ACTIVE"))
                .body("Name", equalTo("microvm-it-connector"));

        given()
                .when()
                .delete(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(202);

        given()
                .when()
                .get(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(11)
    void connectorUpdatePersistsNetworkProtocolAndConfiguration() {
        String id = given()
                .contentType("application/json")
                .body("""
                        {
                          "Name": "microvm-it-connector-update",
                          "ClientToken": "microvm-it-token-update",
                          "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator",
                          "Configuration": { "VpcEgressConfiguration": {
                            "AssociatedComputeResourceTypes": ["MicroVm"],
                            "NetworkProtocol": "IPv4",
                            "SubnetIds": ["subnet-0000000000000up01"],
                            "SecurityGroupIds": ["sg-0000000000000up01"]
                          } }
                        }
                        """)
                .when()
                .post(CORE + "/network-connectors")
                .then()
                .statusCode(202)
                .body("Configuration.VpcEgressConfiguration.NetworkProtocol", equalTo("IPv4"))
                .extract().path("Id");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator-2",
                          "Configuration": { "VpcEgressConfiguration": {
                            "AssociatedComputeResourceTypes": ["MicroVm"],
                            "NetworkProtocol": "DualStack",
                            "SubnetIds": ["subnet-0000000000000up02"],
                            "SecurityGroupIds": ["sg-0000000000000up02"]
                          } }
                        }
                        """)
                .when()
                .put(CORE + "/network-connectors/" + id)
                .then()
                .statusCode(202)
                .body("LastUpdateStatus", equalTo("Successful"))
                .body("Configuration.VpcEgressConfiguration.NetworkProtocol", equalTo("DualStack"));

        given()
                .when()
                .get(CORE + "/network-connectors/" + id)
                .then()
                .statusCode(200)
                .body("OperatorRole", equalTo("arn:aws:iam::000000000000:role/microvm-connector-operator-2"))
                .body("Configuration.VpcEgressConfiguration.NetworkProtocol", equalTo("DualStack"))
                .body("Configuration.VpcEgressConfiguration.SubnetIds[0]", equalTo("subnet-0000000000000up02"))
                .body("Configuration.VpcEgressConfiguration.SecurityGroupIds[0]", equalTo("sg-0000000000000up02"))
                .body("Configuration.VpcEgressConfiguration.AssociatedComputeResourceTypes[0]", equalTo("MicroVm"));

        given()
                .when()
                .get(CORE + "/network-connectors")
                .then()
                .statusCode(200)
                .body("NetworkConnectors.Id", hasItem(id));

        given()
                .when()
                .delete(CORE + "/network-connectors/" + id)
                .then()
                .statusCode(202)
                .body("State", equalTo("DELETING"));

        given()
                .when()
                .get(CORE + "/network-connectors/" + id)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(12)
    void connectorSubnetLimit() {
        StringBuilder subnets = new StringBuilder();
        for (int i = 1; i <= 17; i++) {
            if (i > 1) subnets.append(",");
            subnets.append("\"subnet-00000000000000").append(String.format("%03d", i)).append("\"");
        }
        given()
                .contentType("application/json")
                .body("""
                        {
                          "Name": "microvm-it-too-many",
                          "ClientToken": "microvm-it-token-2",
                          "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator",
                          "Configuration": { "VpcEgressConfiguration": {
                            "AssociatedComputeResourceTypes": ["MicroVm"],
                            "NetworkProtocol": "IPv4",
                            "SubnetIds": [%s]
                          } }
                        }
                        """.formatted(subnets))
                .when()
                .post(CORE + "/network-connectors")
                .then()
                .statusCode(400);
    }

    @Test
    @Order(13)
    void cleanupImage() {
        given()
                .when()
                .delete(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0")
                .then()
                .statusCode(200);

        given()
                .when()
                .delete(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(200)
                .body("state", equalTo("DELETING"))
                .body("imageIdentifier", equalTo(imageArn));

        given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(14)
    void connectorRequiredMembersAreEnforced() {
        // Recorded live: all three are required despite being modeled optional.
        String base = """
                {
                  "Name": "microvm-it-missing-%s"%s,
                  "Configuration": { "VpcEgressConfiguration": {
                    %s
                    "SubnetIds": ["subnet-0000000000000it09"]
                  } }
                }
                """;
        given().contentType("application/json")
                .body(base.formatted("token", ", \"OperatorRole\": \"arn:aws:iam::000000000000:role/op\"", "\"AssociatedComputeResourceTypes\": [\"MicroVm\"], \"NetworkProtocol\": \"IPv4\","))
                .when().post(CORE + "/network-connectors")
                .then().statusCode(400);
        given().contentType("application/json")
                .body(base.formatted("acrt", ", \"ClientToken\": \"t1\", \"OperatorRole\": \"arn:aws:iam::000000000000:role/op\"", "\"NetworkProtocol\": \"IPv4\","))
                .when().post(CORE + "/network-connectors")
                .then().statusCode(400);
        given().contentType("application/json")
                .body(base.formatted("proto", ", \"ClientToken\": \"t2\", \"OperatorRole\": \"arn:aws:iam::000000000000:role/op\"", "\"AssociatedComputeResourceTypes\": [\"MicroVm\"],"))
                .when().post(CORE + "/network-connectors")
                .then().statusCode(400);
    }

    @Test
    @Order(16)
    void sharedRoutesLaunchDockerAndRetainTokensIdempotencyAndIdlePolicies() {
        String name = "microvm-shared-runtime";
        String arn = given().contentType("application/json").body("""
                {
                  "name": "%s",
                  "baseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                  "buildRoleArn": "arn:aws:iam::000000000000:role/build",
                  "codeArtifact": {"uri": "docker://local/microvm:test"},
                  "resources": [{"minimumMemoryInMiB": 256}],
                  "hooks": {"port": 8081},
                  "environmentVariables": {"COMBINED_RUNTIME": "yes"}
                }
                """.formatted(name))
                .post(MICROVMS + "/microvm-images").then().statusCode(201)
                .body("resources[0].minimumMemoryInMiB", equalTo(256))
                .extract().path("imageArn");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .get(MICROVMS + "/microvm-images/" + name).then().body("state", equalTo("CREATED")));
        given().get(MICROVMS + "/microvm-images/" + arn.replace(":microvm-image:", ":microvm-image/"))
                .then().statusCode(200).body("imageArn", equalTo(arn));
        String request = """
                {"imageIdentifier":"%s", "clientToken":"same-vm", "maximumDurationInSeconds":120,
                 "idlePolicy":{"maxIdleDurationSeconds":10,"suspendedDurationSeconds":20,"autoResumeEnabled":true}}
                """.formatted(arn);
        String id = given().contentType("application/json").body(request)
                .post(MICROVMS + "/microvms").then().statusCode(200)
                .body("maximumDurationInSeconds", equalTo(120))
                .body("idlePolicy.maxIdleDurationSeconds", equalTo(10))
                .extract().path("microvmId");
        given().contentType("application/json").body(request).post(MICROVMS + "/microvms")
                .then().statusCode(200).body("microvmId", equalTo(id));
        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(containers, times(1)).createAndStart(spec.capture());
        assertEquals("local/microvm:test", spec.getValue().image());
        assertEquals(256L * 1024 * 1024, spec.getValue().memoryBytes().longValue());
        assertTrue(spec.getValue().env().contains("COMBINED_RUNTIME=yes"));
        assertTrue(spec.getValue().env().contains("PORT=8081"));
        MicrovmRecord vm = runtime.requireMicrovm("us-east-1", id);
        String containerId = vm.getContainerId();
        given().post(MICROVMS + "/microvms/" + id + "/suspend").then().statusCode(200);
        verify(docker).pauseContainerCmd(containerId);
        given().contentType("application/json").body(request).post(MICROVMS + "/microvms")
                .then().statusCode(200).body("microvmId", equalTo(id)).body("state", equalTo("RUNNING"));
        verify(docker).unpauseContainerCmd(containerId);
        given().post(MICROVMS + "/microvms/" + id + "/resume").then().statusCode(200);
        given().contentType("application/json")
                .body("{\"expirationInMinutes\":5,\"allowedPorts\":[{\"port\":8081}]}")
                .post(MICROVMS + "/microvms/" + id + "/auth-token")
                .then().statusCode(200).body("authToken", notNullValue());
        given().contentType("application/json").body("{\"expirationInMinutes\":5}")
                .post(MICROVMS + "/microvms/" + id + "/shell-auth-token")
                .then().statusCode(200).body("authToken", notNullValue());
        vm = runtime.requireMicrovm("us-east-1", id);
        vm.setStartedAt(System.currentTimeMillis() - 30_000);
        vm.setLastActivityAt(System.currentTimeMillis() - 30_000);
        vmStore.save(vm);
        runtime.sweepIdlePolicies();
        given().get(MICROVMS + "/microvms/" + id).then().body("state", equalTo("SUSPENDED"));
        vm = runtime.requireMicrovm("us-east-1", id);
        vm.setSuspendedAt(System.currentTimeMillis() - 30_000);
        vmStore.save(vm);
        runtime.sweepIdlePolicies();
        given().get(MICROVMS + "/microvms/" + id).then().body("state", equalTo("TERMINATED"));
        verify(containers).stopAndRemove(eq(containerId), isNull());
        given().delete(MICROVMS + "/microvm-images/" + name).then().statusCode(200);
    }

    @Test
    @Order(17)
    void sharedImageRoutesRetainVersionSnapshotsRebuildsAndBuildFailures() {
        String name = "microvm-versioned-runtime";
        String request = """
                {"name":"%s", "baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::000000000000:role/build",
                 "codeArtifact":{"uri":"docker://local/microvm:%s"},
                 "environmentVariables":{"REVISION":"%s"}}
                """;
        given().contentType("application/json").body(request.formatted(name, "one", "one"))
                .post(MICROVMS + "/microvm-images").then().statusCode(201);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .get(MICROVMS + "/microvm-images/" + name).then()
                .body("latestActiveImageVersion", equalTo("1.0")));
        given().contentType("application/json").body(request.formatted(name, "two", "two"))
                .put(MICROVMS + "/microvm-images/" + name).then().statusCode(200)
                .body("state", equalTo("UPDATING"))
                .body("latestActiveImageVersion", equalTo("1.0"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .get(MICROVMS + "/microvm-images/" + name).then()
                .body("latestActiveImageVersion", equalTo("2.0")));
        given().get(MICROVMS + "/microvm-images/" + name + "/versions/1.0").then()
                .body("environmentVariables.REVISION", equalTo("one"))
                .body("codeArtifact.uri", equalTo("docker://local/microvm:one"));
        given().contentType("application/json").body(request.formatted(name, "three", "three"))
                .post(MICROVMS + "/microvm-images").then().statusCode(201)
                .body("imageVersion", equalTo("3.0"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .get(MICROVMS + "/microvm-images/" + name).then()
                .body("latestActiveImageVersion", equalTo("3.0")));
        when(buildService.buildForAccount(anyString(), anyString(), eq("docker://local/microvm:broken"), anyString()))
                .thenThrow(new IllegalStateException("Docker build failed"));
        given().contentType("application/json").body(request.formatted(name, "broken", "broken"))
                .put(MICROVMS + "/microvm-images/" + name).then().statusCode(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .get(MICROVMS + "/microvm-images/" + name).then()
                .body("state", equalTo("UPDATE_FAILED"))
                .body("latestActiveImageVersion", equalTo("3.0"))
                .body("latestFailedImageVersion", equalTo("4.0")));
        given().get(MICROVMS + "/microvm-images/" + name + "/versions/4.0/builds").then()
                .body("items.size()", equalTo(2))
                .body("items[0].buildState", equalTo("FAILED"))
                .body("items[1].stateReason", equalTo("Docker build failed"));
        given().contentType("application/json")
                .body("{\"imageIdentifier\":\"" + name + "\",\"imageVersion\":\"4.0\"}")
                .post(MICROVMS + "/microvms").then().statusCode(400);
        given().contentType("application/json").body("{\"status\":\"INACTIVE\"}")
                .patch(MICROVMS + "/microvm-images/" + name + "/versions/3.0").then()
                .statusCode(200).body("status", equalTo("INACTIVE"));
        given().get(MICROVMS + "/microvm-images/" + name).then()
                .body("latestActiveImageVersion", equalTo("2.0"));
        given().queryParam("nameFilter", name).get(MICROVMS + "/microvm-images").then()
                .body("items.size()", equalTo(1));
        given().delete(MICROVMS + "/microvm-images/" + name).then().statusCode(200);
    }

    private static String hostArchitecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm") ? "ARM_64" : "X86_64";
    }

    private static String credentialFor(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260801/us-east-1/lambda/aws4_request, SignedHeaders=host, Signature=x";
    }

    @Test
    @Order(15)
    void resourcesAreScopedToTheCallersAccount() {
        String accountA = "111122223333";
        String accountB = "444455556666";
        String name = "microvm-it-tenant-image";

        given()
                .header("Authorization", credentialFor(accountA))
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "baseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                          "buildRoleArn": "arn:aws:iam::111122223333:role/microvm-build",
                          "codeArtifact": { "uri": "s3://bucket/code.zip" }
                        }
                        """.formatted(name))
                .when()
                .post(MICROVMS + "/microvm-images")
                .then()
                .statusCode(201)
                .body("imageArn", startsWith("arn:aws:lambda:us-east-1:" + accountA + ":"));

        // The ARN names account A, so account B must not reach the image by
        // identifier or see it in a list.
        given()
                .header("Authorization", credentialFor(accountB))
                .when()
                .get(MICROVMS + "/microvm-images/" + name)
                .then()
                .statusCode(404);

        given()
                .header("Authorization", credentialFor(accountB))
                .when()
                .get(MICROVMS + "/microvm-images")
                .then()
                .statusCode(200)
                .body("items.name", not(hasItem(name)));

        given()
                .header("Authorization", credentialFor(accountA))
                .when()
                .get(MICROVMS + "/microvm-images/" + name)
                .then()
                .statusCode(200)
                .body("name", equalTo(name));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                .header("Authorization", credentialFor(accountA))
                .get(MICROVMS + "/microvm-images/" + name).then()
                .body("state", equalTo("CREATED")));
        String id = given().header("Authorization", credentialFor(accountA))
                .contentType("application/json")
                .body("""
                        {"imageIdentifier":"%s", "maximumDurationInSeconds":120,
                         "idlePolicy":{"maxIdleDurationSeconds":10,"suspendedDurationSeconds":20}}
                        """.formatted(name))
                .post(MICROVMS + "/microvms").then().statusCode(200).extract().path("microvmId");
        given().header("Authorization", credentialFor(accountB))
                .get(MICROVMS + "/microvms/" + id).then().statusCode(404);
        MicrovmRecord vm = runtime.findById(id).orElseThrow();
        vm.setStartedAt(System.currentTimeMillis() - 30_000);
        vm.setLastActivityAt(System.currentTimeMillis() - 30_000);
        vmStore.save(vm);
        runtime.sweepIdlePolicies();
        given().header("Authorization", credentialFor(accountA))
                .get(MICROVMS + "/microvms/" + id).then().body("state", equalTo("SUSPENDED"));
        given().header("Authorization", credentialFor(accountA))
                .delete(MICROVMS + "/microvms/" + id).then().statusCode(200);
        given().header("Authorization", credentialFor(accountA))
                .delete(MICROVMS + "/microvm-images/" + name).then().statusCode(200);
    }
}
