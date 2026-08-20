package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createTenant_withSuppressionAndTags() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\",\"Tags\":[{\"Key\":\"Env\",\"Value\":\"test\"}],"
                        + "\"SuppressionAttributes\":{\"SuppressedReasons\":[\"BOUNCE\"],\"SuppressionScope\":\"TENANT\"}}")
                .when().post("/v2/email/tenants")
                .then().statusCode(200)
                .body("TenantName", equalTo("floci-tenant"))
                .body("SendingStatus", equalTo("ENABLED"));
    }

    @Test
    @Order(2)
    void getAndListTenant() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\"}")
                .when().post("/v2/email/tenants/get")
                .then().statusCode(200)
                .body("Tenant.TenantName", equalTo("floci-tenant"))
                .body("Tenant.SuppressionAttributes.SuppressionScope", equalTo("TENANT"));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{}")
                .when().post("/v2/email/tenants/list")
                .then().statusCode(200)
                .body("Tenants.TenantName", hasItem("floci-tenant"));
    }

    @Test
    @Order(3)
    void associateConfigSet_thenList() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ConfigurationSetName\":\"floci-tenant-cs\"}")
                .when().post("/v2/email/configuration-sets")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\","
                        + "\"ResourceArn\":\"arn:aws:ses:us-east-1:000000000000:configuration-set/floci-tenant-cs\"}")
                .when().post("/v2/email/tenants/resources")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\"}")
                .when().post("/v2/email/tenants/resources/list")
                .then().statusCode(200)
                .body("TenantResources.ResourceType", hasItem("CONFIGURATION_SET"));
    }

    @Test
    @Order(4)
    void putTenantSuppressionAttributes_viaPost() {
        // AWS PutTenantSuppressionAttributes is POST /v2/email/tenant/suppression
        // (not PUT). Alchemy's Tenant reconciler uses this on update.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\",\"SuppressedReasons\":[\"BOUNCE\",\"COMPLAINT\"],"
                        + "\"SuppressionScope\":\"TENANT\"}")
                .when().post("/v2/email/tenant/suppression")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\"}")
                .when().post("/v2/email/tenants/get")
                .then().statusCode(200)
                .body("Tenant.SuppressionAttributes.SuppressedReasons", hasItem("COMPLAINT"))
                .body("Tenant.SuppressionAttributes.SuppressionScope", equalTo("TENANT"));
    }

    @Test
    @Order(5)
    void deleteTenant_removesAssociations() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\"}")
                .when().post("/v2/email/tenants/delete")
                .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\":\"floci-tenant\"}")
                .when().post("/v2/email/tenants/get")
                .then().statusCode(404);

        given().header("Authorization", AUTH)
                .when().delete("/v2/email/configuration-sets/floci-tenant-cs")
                .then().statusCode(200);
    }
}
