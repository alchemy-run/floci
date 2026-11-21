package io.floci.aws.services.grafana;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Amazon Managed Grafana over REST, without Docker: workspaces and their authentication, configuration, Identity
 * Center permissions, licenses, service accounts and tokens, API keys, versions and tags.
 */
@QuarkusTest
class GrafanaIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification grafana() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/"
                + "grafana/aws4_request").contentType("application/json");
    }

    private static String create(String body) {
        return grafana().body(body).post("/workspaces").then().statusCode(202).extract().path("workspace.id");
    }

    @Test
    void workspacesTheirSettingsAndServiceAccounts() {
        grafana().get("/workspaces/g-0000000000").then().statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException").body("resourceType", equalTo("WORKSPACE"));
        grafana().body("{\"accountAccessType\":\"CURRENT_ACCOUNT\",\"authenticationProviders\":[\"LDAP\"],"
                + "\"permissionType\":\"SERVICE_MANAGED\"}").post("/workspaces").then().statusCode(400)
                .header("x-amzn-ErrorType", "ValidationException");
        grafana().body("{\"accountAccessType\":\"CURRENT_ACCOUNT\",\"authenticationProviders\":[\"SAML\"],"
                + "\"permissionType\":\"SERVICE_MANAGED\",\"grafanaVersion\":\"8.4\"}").post("/workspaces").then()
                .statusCode(400).body("message", containsString("not supported"));
        grafana().body("{\"accountAccessType\":\"ORGANIZATION\",\"authenticationProviders\":[\"SAML\"],"
                + "\"permissionType\":\"SERVICE_MANAGED\"}").post("/workspaces").then().statusCode(400)
                .body("message", containsString("organizational units"));

        String body = "{\"accountAccessType\":\"CURRENT_ACCOUNT\",\"authenticationProviders\":[\"AWS_SSO\",\"SAML\"],"
                + "\"permissionType\":\"SERVICE_MANAGED\",\"workspaceName\":\"ops\",\"workspaceDescription\":\"first\","
                + "\"workspaceDataSources\":[\"CLOUDWATCH\"],\"grafanaVersion\":\"10.4\",\"clientToken\":\"once\","
                + "\"tags\":{\"team\":\"ops\"}}";
        String id = create(body);
        grafana().body(body).post("/workspaces").then().statusCode(202).body("workspace.id", equalTo(id));
        grafana().get("/workspaces/" + id).then().statusCode(200).body("workspace.status", equalTo("ACTIVE"))
                .body("workspace.id", matchesPattern("g-[0-9a-f]{10}"))
                .body("workspace.endpoint", containsString(id + ".grafana-workspace.us-east-1."))
                .body("workspace.grafanaVersion", equalTo("10.4")).body("workspace.tags.team", equalTo("ops"))
                .body("workspace.authentication.providers", hasItem("AWS_SSO"))
                .body("workspace.authentication.samlConfigurationStatus", equalTo("NOT_CONFIGURED"));
        grafana().get("/workspaces").then().statusCode(200).body("workspaces.id", hasItem(id));

        grafana().body("{\"workspaceDescription\":\"second\"}").put("/workspaces/" + id).then().statusCode(202)
                .body("workspace.description", equalTo("second"));

        grafana().get("/workspaces/" + id + "/authentication").then().statusCode(200)
                .body("authentication.awsSso.ssoClientId", not(nullValue()))
                .body("authentication.saml.status", equalTo("NOT_CONFIGURED"));
        grafana().body("{\"authenticationProviders\":[\"SAML\"],\"samlConfiguration\":{\"idpMetadata\":{\"url\":"
                + "\"https://idp.example.com/metadata\"},\"assertionAttributes\":{\"role\":\"role\"},\"roleValues\":"
                + "{\"admin\":[\"admins\"]}}}").post("/workspaces/" + id + "/authentication").then().statusCode(200)
                .body("authentication.saml.status", equalTo("CONFIGURED"))
                .body("authentication.saml.configuration.loginValidityDuration", equalTo(120));
        grafana().get("/workspaces/" + id + "/permissions").then().statusCode(400)
                .body("message", containsString("Identity Center"));
        grafana().body("{\"authenticationProviders\":[\"AWS_SSO\",\"SAML\"],\"samlConfiguration\":{\"idpMetadata\":"
                + "{\"url\":\"https://idp.example.com/metadata\"}}}").post("/workspaces/" + id + "/authentication")
                .then().statusCode(200);

        grafana().body("{\"updateInstructionBatch\":[{\"action\":\"ADD\",\"role\":\"ADMIN\",\"users\":[{\"id\":"
                + "\"u-1\",\"type\":\"SSO_USER\"},{\"id\":\"g-1\",\"type\":\"SSO_GROUP\"}]},{\"action\":\"ADD\","
                + "\"role\":\"OWNER\",\"users\":[{\"id\":\"u-2\",\"type\":\"SSO_USER\"}]}]}")
                .patch("/workspaces/" + id + "/permissions").then().statusCode(200).body("errors", hasSize(1));
        grafana().queryParam("userType", "SSO_GROUP").get("/workspaces/" + id + "/permissions").then()
                .statusCode(200).body("permissions", hasSize(1)).body("permissions[0].user.id", equalTo("g-1"));

        grafana().get("/workspaces/" + id + "/configuration").then().statusCode(200)
                .body("grafanaVersion", equalTo("10.4")).body("configuration", containsString("unifiedAlerting"));
        grafana().body("{\"configuration\":\"{\\\"plugins\\\":{\\\"pluginAdminEnabled\\\":true}}\","
                + "\"grafanaVersion\":\"9.4\"}").put("/workspaces/" + id + "/configuration").then().statusCode(400)
                .body("message", containsString("upgraded"));
        grafana().body("{\"configuration\":\"{\\\"plugins\\\":{\\\"pluginAdminEnabled\\\":true}}\","
                + "\"grafanaVersion\":\"12.4\"}").put("/workspaces/" + id + "/configuration").then().statusCode(202);
        grafana().get("/workspaces/" + id + "/configuration").then().body("grafanaVersion", equalTo("12.4"))
                .body("configuration", containsString("\"pluginAdminEnabled\":true"));
        grafana().queryParam("workspace-id", id).get("/versions").then().statusCode(200)
                .body("grafanaVersions", equalTo(List.of("13.2")));
        grafana().get("/versions").then().statusCode(200).body("grafanaVersions", hasItem("9.4"));

        grafana().post("/workspaces/" + id + "/licenses/ENTERPRISE").then().statusCode(400)
                .body("message", containsString("Grafana-Token"));
        grafana().post("/workspaces/" + id + "/licenses/ENTERPRISE_FREE_TRIAL").then().statusCode(202)
                .body("workspace.licenseType", equalTo("ENTERPRISE_FREE_TRIAL"))
                .body("workspace.freeTrialConsumed", equalTo(true));
        grafana().delete("/workspaces/" + id + "/licenses/ENTERPRISE_FREE_TRIAL").then().statusCode(202)
                .body("workspace.licenseType", nullValue());
        grafana().post("/workspaces/" + id + "/licenses/ENTERPRISE_FREE_TRIAL").then().statusCode(400)
                .body("message", containsString("already used"));

        String account = grafana().body("{\"name\":\"ci\",\"grafanaRole\":\"EDITOR\"}")
                .post("/workspaces/" + id + "/serviceaccounts").then().statusCode(200)
                .body("grafanaRole", equalTo("EDITOR")).extract().path("id");
        grafana().body("{\"name\":\"ci\",\"grafanaRole\":\"EDITOR\"}").post("/workspaces/" + id + "/serviceaccounts")
                .then().statusCode(409).header("x-amzn-ErrorType", "ConflictException");
        grafana().body("{\"name\":\"deploy\",\"secondsToLive\":3600}")
                .post("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens").then().statusCode(200)
                .body("serviceAccountToken.key", matchesPattern("glsa_[A-Za-z0-9]{32}_[0-9a-f]{8}"));
        grafana().body("{\"name\":\"forever\",\"secondsToLive\":99999999}")
                .post("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens").then().statusCode(400);
        String tokenId = grafana().get("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens").then()
                .statusCode(200).body("serviceAccountTokens", hasSize(1)).extract().path("serviceAccountTokens[0].id");
        grafana().get("/workspaces/" + id + "/serviceaccounts").then().statusCode(200).body("serviceAccounts",
                hasSize(1));
        grafana().delete("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens/" + tokenId).then()
                .statusCode(200);
        grafana().delete("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens/" + tokenId).then()
                .statusCode(404);
        grafana().delete("/workspaces/" + id + "/serviceaccounts/" + account).then().statusCode(200);
        grafana().get("/workspaces/" + id + "/serviceaccounts").then().body("serviceAccounts", hasSize(0));

        grafana().body("{\"keyName\":\"legacy\",\"keyRole\":\"VIEWER\",\"secondsToLive\":60}")
                .post("/workspaces/" + id + "/apikeys").then().statusCode(200).body("key", containsString("glsa_"));
        grafana().get("/workspaces/" + id + "/serviceaccounts").then().body("serviceAccounts", hasSize(0));
        grafana().delete("/workspaces/" + id + "/apikeys/legacy").then().statusCode(200);
        grafana().delete("/workspaces/" + id + "/apikeys/legacy").then().statusCode(404);

        String arn = URLEncoder.encode("arn:aws:grafana:us-east-1:000000000000:/workspaces/" + id,
                StandardCharsets.UTF_8);
        grafana().body("{\"tags\":{\"stage\":\"one\"}}").post("/tags/" + arn).then().statusCode(200);
        grafana().queryParam("tagKeys", "team").delete("/tags/" + arn).then().statusCode(200);
        grafana().get("/tags/" + arn).then().statusCode(200).body("tags.stage", equalTo("one"))
                .body("tags.team", nullValue());

        grafana().delete("/workspaces/" + id).then().statusCode(202).body("workspace.status", equalTo("DELETING"));
        grafana().get("/workspaces/" + id).then().statusCode(404);
    }
}
