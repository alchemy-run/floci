package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * SageMaker Model JSON 1.1 coverage used by Alchemy Model: typed
 * describe-missing (ValidationException "Could not find model"), create with
 * primary-container environment and tags, list, already-exists, replace
 * (create a second model then delete the first), and delete-missing.
 */
@QuarkusTest
class SageMakerModelIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sagemaker/aws4_request";
    private static final String TARGET = "SageMaker.";
    private static final String UNKNOWN = "alchemy-nonexistent-sagemaker-model-probe";
    private static final String IMAGE =
            "683313688378.dkr.ecr.us-east-1.amazonaws.com/sagemaker-scikit-learn:1.2-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/SageMakerModelRole";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeModel_unknownName_validationExceptionCouldNotFindModel() {
        invoke("DescribeModel", "{\"ModelName\":\"" + UNKNOWN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find model \"" + UNKNOWN + "\""));
    }

    @Test
    void createDescribeTagReplaceAndDeleteModel() {
        String name = "floci-sagemaker-model";
        String replacement = "floci-sagemaker-model-v2";
        Response created = invoke("CreateModel", """
                {
                  "ModelName": "%s",
                  "ExecutionRoleArn": "%s",
                  "PrimaryContainer": {
                    "Image": "%s",
                    "Environment": {"MODEL_VERSION": "1"}
                  },
                  "Tags": [
                    {"Key": "alchemy::id", "Value": "TestModel"},
                    {"Key": "purpose", "Value": "alchemy-test"}
                  ]
                }
                """.formatted(name, ROLE, IMAGE));
        created.then()
                .statusCode(200)
                .body("ModelArn", startsWith("arn:aws:sagemaker:"))
                .body("ModelArn", containsString(":model/"));
        String arn = created.jsonPath().getString("ModelArn");

        invoke("DescribeModel", "{\"ModelName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("ModelName", equalTo(name))
                .body("ModelArn", equalTo(arn))
                .body("ExecutionRoleArn", equalTo(ROLE))
                .body("PrimaryContainer.Image", equalTo(IMAGE))
                .body("PrimaryContainer.Environment.MODEL_VERSION", equalTo("1"));

        invoke("ListModels", "{}")
                .then()
                .statusCode(200)
                .body("Models.ModelName", hasItem(name));

        invoke("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'alchemy::id' }.Value", equalTo("TestModel"))
                .body("Tags.find { it.Key == 'purpose' }.Value", equalTo("alchemy-test"));

        invoke("AddTags", """
                {"ResourceArn":"%s","Tags":[{"Key":"env","Value":"local"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("CreateModel", """
                {
                  "ModelName": "%s",
                  "ExecutionRoleArn": "%s",
                  "PrimaryContainer": {"Image": "%s"}
                }
                """.formatted(name, ROLE, IMAGE))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Cannot create already existing model"));

        Response replaced = invoke("CreateModel", """
                {
                  "ModelName": "%s",
                  "ExecutionRoleArn": "%s",
                  "PrimaryContainer": {
                    "Image": "%s",
                    "Environment": {"MODEL_VERSION": "2"}
                  },
                  "Tags": [{"Key": "alchemy::id", "Value": "TestModel"}]
                }
                """.formatted(replacement, ROLE, IMAGE));
        replaced.then()
                .statusCode(200)
                .body("ModelArn", containsString(":model/"));
        String replacementArn = replaced.jsonPath().getString("ModelArn");

        invoke("DescribeModel", "{\"ModelName\":\"" + replacement + "\"}")
                .then()
                .statusCode(200)
                .body("ModelArn", equalTo(replacementArn))
                .body("PrimaryContainer.Environment.MODEL_VERSION", equalTo("2"));

        invoke("DeleteModel", "{\"ModelName\":\"" + name + "\"}")
                .then()
                .statusCode(200);
        invoke("DescribeModel", "{\"ModelName\":\"" + name + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find model \"" + name + "\""));

        invoke("DeleteModel", "{\"ModelName\":\"" + replacement + "\"}")
                .then()
                .statusCode(200);
        invoke("DescribeModel", "{\"ModelName\":\"" + replacement + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find model \"" + replacement + "\""));

        invoke("DeleteModel", "{\"ModelName\":\"" + replacement + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Could not find model \"" + replacement + "\""));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
