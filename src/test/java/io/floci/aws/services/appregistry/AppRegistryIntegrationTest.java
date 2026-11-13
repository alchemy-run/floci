package io.floci.aws.services.appregistry;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AppRegistry: applications, attribute groups, stack and tag associations, and sync. */
@QuarkusTest
class AppRegistryIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/%s/aws4_request";

    private static RequestSpecification appRegistry() {
        return given().contentType("application/json").header("Authorization", String.format(AUTH, "servicecatalog"));
    }

    private static String stack(String name) {
        given().header("Authorization", String.format(AUTH, "cloudformation"))
                .formParam("Action", "CreateStack").formParam("StackName", name)
                .formParam("TemplateBody", "{\"Resources\":{\"Noop\":{\"Type\":\"AWS::CloudFormation::WaitConditionHandle\"}}}")
                .post("/").then().statusCode(200);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> given()
                .header("Authorization", String.format(AUTH, "cloudformation"))
                .formParam("Action", "DescribeStacks").formParam("StackName", name).post("/").then().extract()
                .asString().contains("CREATE_COMPLETE"));
        return name;
    }

    private static String stackTags(String name) {
        return given().header("Authorization", String.format(AUTH, "cloudformation"))
                .formParam("Action", "DescribeStacks").formParam("StackName", name).post("/").then().statusCode(200)
                .extract().asString();
    }

    @Test
    void applicationsAttributeGroupsAndStackAssociations() {
        String name = "app-" + UUID.randomUUID().toString().substring(0, 8);
        String appId = appRegistry().body("{\"name\":\"" + name + "\",\"description\":\"orders\",\"tags\":{\"team\":"
                        + "\"core\"},\"clientToken\":\"" + UUID.randomUUID() + "\"}")
                .post("/applications").then().statusCode(201)
                .body("application.arn", containsString(":servicecatalog:us-east-1:"))
                .body("application.arn", containsString(":/applications/"))
                .body("application.applicationTag.awsApplication", startsWith("arn:"))
                .extract().path("application.id");
        String appArn = appRegistry().get("/applications/" + name).then().statusCode(200)
                .body("id", equalTo(appId)).body("tags.team", equalTo("core"))
                .body("integrations.resourceGroup.state", equalTo("CREATE_COMPLETE"))
                .body("associatedResourceCount", equalTo(0)).extract().path("arn");
        appRegistry().body("{\"name\":\"" + name + "\"}").post("/applications").then().statusCode(409);
        appRegistry().body("{\"description\":\"orders service\"}").patch("/applications/" + appId).then()
                .statusCode(200).body("application.description", equalTo("orders service"));

        String groupId = appRegistry().body("{\"name\":\"owners-" + name + "\",\"attributes\":\"{\\\"owner\\\":"
                + "\\\"core\\\"}\"}").post("/attribute-groups").then().statusCode(201).extract().path("attributeGroup.id");
        appRegistry().body("{\"name\":\"bad-" + name + "\",\"attributes\":\"not json\"}").post("/attribute-groups")
                .then().statusCode(400);
        appRegistry().put("/applications/" + appId + "/attribute-groups/" + groupId).then().statusCode(200)
                .body("applicationArn", equalTo(appArn));
        appRegistry().get("/applications/" + appId + "/attribute-groups").then().statusCode(200)
                .body("attributeGroups", hasItem(groupId));
        appRegistry().get("/applications/" + appId + "/attribute-group-details").then().statusCode(200)
                .body("attributeGroupsDetails[0].name", equalTo("owners-" + name));
        appRegistry().get("/attribute-groups/" + groupId).then().statusCode(200)
                .body("attributes", containsString("owner"));

        String stack = stack("appreg-" + UUID.randomUUID().toString().substring(0, 8));
        appRegistry().body("{}").put("/applications/" + appId + "/resources/CFN_STACK/" + stack).then().statusCode(200)
                .body("resourceArn", containsString(":cloudformation:"))
                .body("options", hasItem("APPLY_APPLICATION_TAG"));
        assertTrue(stackTags(stack).contains(appArn),
                "the stack carries the awsApplication tag");
        appRegistry().get("/applications/" + appId + "/resources/CFN_STACK/" + stack).then().statusCode(200)
                .body("resource.name", equalTo(stack)).body("applicationTagResult.applicationTagStatus",
                        equalTo("SUCCESS"));
        appRegistry().get("/applications/" + appId + "/resources").then().statusCode(200)
                .body("resources", hasSize(1)).body("resources[0].resourceType", equalTo("CFN_STACK"));
        appRegistry().delete("/applications/" + appId).then().statusCode(400);

        // Another application can't take a stack tagged for this one.
        String other = appRegistry().body("{\"name\":\"other-" + name + "\"}").post("/applications").then()
                .statusCode(201).extract().path("application.id");
        appRegistry().body("{}").put("/applications/" + other + "/resources/CFN_STACK/" + stack).then()
                .statusCode(409);

        appRegistry().delete("/applications/" + appId + "/resources/CFN_STACK/" + stack).then().statusCode(200);
        assertTrue(!stackTags(stack).contains(appArn), "the tag is removed");
        appRegistry().delete("/applications/" + appId + "/attribute-groups/" + groupId).then().statusCode(200);
        appRegistry().delete("/attribute-groups/" + groupId).then().statusCode(200);
        appRegistry().delete("/applications/" + appId).then().statusCode(200);
        appRegistry().get("/applications/" + appId).then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
        appRegistry().get("/applications").then().statusCode(200)
                .body("applications.id", not(hasItem(appId)));
    }

    @Test
    void syncAssociatesATaggedStackAndTagsAndConfiguration() {
        String name = "sync-" + UUID.randomUUID().toString().substring(0, 8);
        String appId = appRegistry().body("{\"name\":\"" + name + "\"}").post("/applications").then().statusCode(201)
                .extract().path("application.id");
        String appArn = appRegistry().get("/applications/" + appId).then().extract().path("arn");
        String stack = stack("appreg-sync-" + UUID.randomUUID().toString().substring(0, 6));
        appRegistry().post("/sync/CFN_STACK/" + stack).then().statusCode(200).body("actionTaken", equalTo("NO_ACTION"));

        // Associate with the tag skipped, disassociate, then tag the stack out of band and sync.
        appRegistry().body("{\"options\":[\"SKIP_APPLICATION_TAG\"]}")
                .put("/applications/" + appId + "/resources/CFN_STACK/" + stack).then().statusCode(200);
        assertTrue(!stackTags(stack).contains(appArn));
        appRegistry().delete("/applications/" + appId + "/resources/CFN_STACK/" + stack).then().statusCode(200);
        given().header("Authorization", String.format(AUTH, "cloudformation")).formParam("Action", "UpdateStack")
                .formParam("StackName", stack).formParam("UsePreviousTemplate", "true")
                .formParam("Tags.member.1.Key", "awsApplication").formParam("Tags.member.1.Value", appArn)
                .post("/").then().statusCode(200);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> stackTags(stack).contains(appArn)
                && stackTags(stack).contains("UPDATE_COMPLETE"));
        appRegistry().post("/sync/CFN_STACK/" + stack).then().statusCode(200).body("actionTaken", equalTo("START_SYNC"))
                .body("applicationArn", equalTo(appArn));
        appRegistry().get("/applications/" + appId + "/resources").then().body("resources", hasSize(1));

        appRegistry().get("/tags/" + appArn).then().statusCode(200);
        appRegistry().body("{\"tags\":{\"env\":\"dev\"}}").post("/tags/" + appArn).then().statusCode(200);
        appRegistry().get("/tags/" + appArn).then().body("tags.env", equalTo("dev"));
        appRegistry().get("/configuration").then().statusCode(200)
                .body("configuration.tagQueryConfiguration.tagKey", equalTo("awsApplication"));
    }
}
