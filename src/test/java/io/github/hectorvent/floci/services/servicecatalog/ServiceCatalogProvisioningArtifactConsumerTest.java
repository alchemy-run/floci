package io.github.hectorvent.floci.services.servicecatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-level tests for {@code CreateProvisioningArtifact},
 * {@code UpdateProvisioningArtifact}, {@code DeleteProvisioningArtifact} and
 * {@code ListProvisioningArtifactsForServiceAction}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). {@code ListProvisioningArtifactsForServiceAction} always
 * returns an empty list, no association between service actions and provisioning
 * artifacts is tracked for this specific listing (documented as a limitation, not a
 * bug: unlike {@code ListServiceActions} before this session's fix, nothing else in
 * the codebase persists data this operation could read).
 */
@QuarkusTest
class ServiceCatalogProvisioningArtifactConsumerTest {

    @Inject
    ServiceCatalogService service;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void legacyArtifactStorageRetainsIdentityAndTemplateMetadata() throws Exception {
        ObjectNode product = (ObjectNode) objectMapper.readTree("""
                {"Id":"prod-existing","CreatedTime":123,
                 "ProvisioningArtifactIds":["pa-existing"],"ProvisioningArtifactNames":["renamed-version"],
                 "ProvisioningArtifactParameters":{"Name":"initial-version","Description":"existing description",
                   "Info":{"LoadTemplateFromURL":"https://templates.s3.amazonaws.com/existing.json"}}}
                """);
        ObjectNode artifact = service.provisioningArtifacts(product).getFirst();
        assertEquals("pa-existing", artifact.path("Id").asText());
        assertEquals("renamed-version", artifact.path("Name").asText());
        assertEquals("existing description", artifact.path("Description").asText());
        assertEquals("https://templates.s3.amazonaws.com/existing.json", artifact.path("Info").path("LoadTemplateFromURL").asText());
        assertEquals(123, artifact.path("CreatedTime").asInt());
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWS242ServiceCatalogService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    private static String createProduct(String name) {
        return call("CreateProduct", "{\"Name\":\"" + name + "\",\"Owner\":\"floci-test\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
    }

    private static String firstArtifactId(String productId) {
        return call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProvisioningArtifactDetails[0].Id");
    }

    // ---------- CreateProvisioningArtifact ----------

    @Test
    void createProvisioningArtifact_addsSecondArtifact() {
        String productId = createProduct("ab-artifact-create");

        call("CreateProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"Parameters\":{\"Name\":\"v2\"},\"IdempotencyToken\":\"tok-create-artifact\"}")
        .then()
            .statusCode(200)
            .body("ProvisioningArtifactDetail.Name", equalTo("v2"))
            .body("Status", equalTo("AVAILABLE"));

        call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisioningArtifactDetails.Name", hasItem("v2"))
            .body("ProvisioningArtifactDetails.size()", equalTo(2));
    }

    @Test
    void createProvisioningArtifact_unknownProduct_returnsResourceNotFound() {
        call("CreateProvisioningArtifact", "{\"ProductId\":\"prod-doesnotexist\","
                + "\"Parameters\":{\"Name\":\"v2\"},\"IdempotencyToken\":\"tok-unknown-product\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- UpdateProvisioningArtifact ----------

    @Test
    void updateProvisioningArtifact_changesName() {
        String productId = createProduct("ab-artifact-update");
        String artifactId = firstArtifactId(productId);

        call("UpdateProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"Name\":\"v1-renamed\"}")
        .then()
            .statusCode(200)
            .body("ProvisioningArtifactDetail.Name", equalTo("v1-renamed"));

        call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisioningArtifactDetails.Name", hasItem("v1-renamed"));
    }

    @Test
    void artifactMetadataPersistsAcrossDescribeListAndPartialUpdates() {
        String productId = createProduct("artifact-metadata-lifecycle");
        String artifactId = call("CreateProvisioningArtifact", """
                {"ProductId":"%s","IdempotencyToken":"artifact-metadata-lifecycle",
                 "Parameters":{"Name":"release-2","Description":"second version",
                   "Type":"CLOUD_FORMATION_TEMPLATE","Info":{"LoadTemplateFromURL":"https://templates.s3.amazonaws.com/v2.json"}}}
                """.formatted(productId)).then().statusCode(200)
                .extract().path("ProvisioningArtifactDetail.Id");
        String selector = "\"ProductId\":\"" + productId + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\"";
        call("UpdateProvisioningArtifact", "{" + selector
                + ",\"Description\":\"revised\",\"Active\":false,\"Guidance\":\"DEPRECATED\"}")
                .then().statusCode(200)
                .body("ProvisioningArtifactDetail.Description", equalTo("revised"));
        call("UpdateProvisioningArtifact", "{" + selector + ",\"Name\":\"release-renamed\"}")
                .then().statusCode(200);
        call("DescribeProvisioningArtifact", "{" + selector + "}")
                .then().statusCode(200)
                .body("ProvisioningArtifactDetail.Name", equalTo("release-renamed"))
                .body("ProvisioningArtifactDetail.Description", equalTo("revised"))
                .body("ProvisioningArtifactDetail.Active", equalTo(false))
                .body("ProvisioningArtifactDetail.Guidance", equalTo("DEPRECATED"))
                .body("Info.LoadTemplateFromURL", equalTo("https://templates.s3.amazonaws.com/v2.json"));
        call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .body("ProvisioningArtifactDetails.find { it.Id == '" + artifactId + "' }.Description", equalTo("revised"))
                .body("ProvisioningArtifactDetails.find { it.Id == '" + artifactId + "' }.Active", equalTo(false));
        call("UpdateProvisioningArtifact", "{" + selector + ",\"Description\":\"\"}")
                .then().statusCode(200);
        call("DescribeProductAsAdmin", "{\"Id\":\"" + productId + "\"}")
                .then().statusCode(200)
                .body("ProvisioningArtifactSummaries.find { it.Id == '" + artifactId + "' }.Description", equalTo(""));
        call("DeleteProvisioningArtifact", "{" + selector + "}").then().statusCode(200);
        call("DescribeProvisioningArtifact", "{" + selector + "}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("DeleteProduct", "{\"Id\":\"" + productId + "\"}").then().statusCode(200);
    }

    // ---------- DeleteProvisioningArtifact ----------

    @Test
    void deleteProvisioningArtifact_removesArtifact() {
        String productId = createProduct("ab-artifact-delete");
        call("CreateProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"Parameters\":{\"Name\":\"v2-to-delete\"},\"IdempotencyToken\":\"tok-delete-artifact\"}")
        .then().statusCode(200);
        String artifactId = call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProvisioningArtifactDetails.find { it.Name == 'v2-to-delete' }.Id");

        call("DeleteProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\"}")
        .then()
            .statusCode(200);

        call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisioningArtifactDetails.Name", org.hamcrest.Matchers.not(hasItem("v2-to-delete")));
    }

    // ---------- ListProvisioningArtifactsForServiceAction ----------

    @Test
    void listProvisioningArtifactsForServiceAction_returnsEmptyList() {
        call("ListProvisioningArtifactsForServiceAction", "{\"ServiceActionId\":\"sa-anything\"}")
        .then()
            .statusCode(200)
            .body("ProvisioningArtifactViews.size()", equalTo(0));
    }

    @Test
    void listProvisioningArtifactsForServiceAction_missingServiceActionId_returnsInvalidParameters() {
        call("ListProvisioningArtifactsForServiceAction", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }
}
