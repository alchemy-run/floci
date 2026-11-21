package io.floci.aws.services.emrcontainers;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Amazon EMR on EKS over REST: virtual clusters on floci EKS clusters, job templates with parameters, job runs,
 * managed endpoints, security configurations, tags, and the errors the API answers with.
 */
@QuarkusTest
class EmrContainersIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String MISSING = "abcdefabcdefabcdefabcdef01";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static RequestSpecification emr() {
        return given().header("Authorization", auth("emr-containers")).contentType("application/json")
                .urlEncodingEnabled(false);
    }

    private static ValidatableResponse post(String path, String body) {
        return emr().body(body).post(path).then();
    }

    private static ValidatableResponse get(String path) {
        return emr().get(path).then();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"pods.eks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    private static void cluster(String name) {
        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::"
                + ACCOUNT + ":role/eks-role\",\"version\":\"1.29\",\"accessConfig\":{\"authenticationMode\":"
                + "\"API_AND_CONFIG_MAP\"}}").post("/clusters").then().statusCode(200);
    }

    private static String virtualCluster(String name, String cluster, String namespace) {
        return post("/virtualclusters", "{\"name\":\"" + name + "\",\"clientToken\":\"" + suffix() + "\","
                + "\"containerProvider\":{\"type\":\"EKS\",\"id\":\"" + cluster + "\",\"info\":{\"eksInfo\":"
                + "{\"namespace\":\"" + namespace + "\"}}},\"tags\":{\"team\":\"data\"}}").statusCode(200)
                .extract().path("id");
    }

    @Test
    void virtualClustersJobRunsAndEndpoints() {
        String eks = "emr-eks-" + suffix();
        cluster(eks);
        String vc = virtualCluster("analytics", eks, "spark");
        get("/virtualclusters/" + vc).statusCode(200).body("virtualCluster.state", equalTo("RUNNING"))
                .body("virtualCluster.arn", equalTo("arn:aws:emr-containers:us-east-1:" + ACCOUNT
                        + ":/virtualclusters/" + vc))
                .body("virtualCluster.containerProvider.info.eksInfo.namespace", equalTo("spark"));
        given().get("/clusters/" + eks + "/access-entries").then().statusCode(200)
                .body("accessEntries", hasItem("arn:aws:iam::" + ACCOUNT + ":role/AWSServiceRoleForAmazonEMRContainers"));
        post("/virtualclusters", "{\"name\":\"again\",\"clientToken\":\"" + suffix() + "\",\"containerProvider\":"
                + "{\"type\":\"EKS\",\"id\":\"" + eks + "\",\"info\":{\"eksInfo\":{\"namespace\":\"spark\"}}}}")
                .statusCode(400).body("message", containsString("already exists in the given namespace"));
        post("/virtualclusters", "{\"name\":\"nowhere\",\"clientToken\":\"" + suffix() + "\",\"containerProvider\":"
                + "{\"type\":\"EKS\",\"id\":\"missing-" + suffix() + "\"}}").statusCode(400);

        String roleArn = role("emr-job-" + suffix());
        String template = post("/jobtemplates", "{\"name\":\"etl-" + suffix() + "\",\"clientToken\":\"" + suffix()
                + "\",\"jobTemplateData\":{\"executionRoleArn\":\"" + roleArn + "\",\"releaseLabel\":"
                + "\"${release}\",\"jobDriver\":{\"sparkSubmitJobDriver\":{\"entryPoint\":\"s3://bucket/etl.py\","
                + "\"entryPointArguments\":[\"--day\",\"${day}\"]}},\"parameterConfiguration\":{\"release\":"
                + "{\"type\":\"STRING\",\"defaultValue\":\"emr-7.5.0-latest\"},\"day\":{\"type\":\"NUMBER\"}},"
                + "\"jobTags\":{\"origin\":\"template\"}},\"tags\":{\"kind\":\"etl\"}}").statusCode(200)
                .body("arn", containsString(":/jobtemplates/")).extract().path("id");
        post("/virtualclusters/" + vc + "/jobruns", "{\"clientToken\":\"" + suffix() + "\",\"jobTemplateId\":\""
                + template + "\"}").statusCode(400).body("message", containsString("day"));
        String run = post("/virtualclusters/" + vc + "/jobruns", "{\"name\":\"nightly\",\"clientToken\":\""
                + suffix() + "\",\"jobTemplateId\":\"" + template + "\",\"jobTemplateParameters\":{\"day\":\"3\"}}")
                .statusCode(200).extract().path("id");
        get("/virtualclusters/" + vc + "/jobruns/" + run).statusCode(200)
                .body("jobRun.state", equalTo("COMPLETED")).body("jobRun.releaseLabel", equalTo("emr-7.5.0-latest"))
                .body("jobRun.jobDriver.sparkSubmitJobDriver.entryPointArguments[1]", equalTo("3"))
                .body("jobRun.tags.origin", equalTo("template"));
        get("/virtualclusters/" + vc + "/jobruns?states=COMPLETED&name=nightly").statusCode(200)
                .body("jobRuns", hasSize(1));
        emr().delete("/virtualclusters/" + vc + "/jobruns/" + run).then().statusCode(400)
                .body("message", containsString("COMPLETED"));
        post("/virtualclusters/" + vc + "/jobruns", "{\"clientToken\":\"" + suffix() + "\",\"executionRoleArn\":\""
                + roleArn + "\",\"releaseLabel\":\"emr-4.0.0\",\"jobDriver\":{\"sparkSubmitJobDriver\":"
                + "{\"entryPoint\":\"s3://bucket/a.py\"}}}").statusCode(400)
                .body("message", containsString("not supported"));

        String endpoint = post("/virtualclusters/" + vc + "/endpoints", "{\"name\":\"studio\",\"clientToken\":\""
                + suffix() + "\",\"type\":\"JUPYTER_ENTERPRISE_GATEWAY\",\"releaseLabel\":\"emr-6.15.0-latest\","
                + "\"executionRoleArn\":\"" + roleArn + "\"}").statusCode(200).extract().path("id");
        get("/virtualclusters/" + vc + "/endpoints/" + endpoint).statusCode(200)
                .body("endpoint.state", equalTo("ACTIVE")).body("endpoint.serverUrl", startsWith("https://"));
        post("/virtualclusters/" + vc + "/endpoints/" + endpoint + "/credentials", "{\"executionRoleArn\":\""
                + roleArn + "\",\"credentialType\":\"TOKEN\"}").statusCode(200)
                .body("credentials.token", not(equalTo("")));

        String vcArn = "arn:aws:emr-containers:us-east-1:" + ACCOUNT + ":/virtualclusters/" + vc;
        post("/tags/" + vcArn, "{\"tags\":{\"env\":\"dev\"}}").statusCode(200);
        get("/tags/" + vcArn).statusCode(200).body("tags.env", equalTo("dev")).body("tags.team", equalTo("data"));
        post("/tags/arn:aws:emr-containers:us-east-1:" + ACCOUNT + ":/jobtemplates/" + template,
                "{\"tags\":{\"env\":\"dev\"}}").statusCode(400).body("message", containsString("Invalid input resource arn"));

        emr().delete("/virtualclusters/" + vc).then().statusCode(200);
        get("/virtualclusters/" + vc).statusCode(200).body("virtualCluster.state", equalTo("TERMINATED"));
        get("/virtualclusters/" + vc + "/endpoints/" + endpoint).statusCode(200)
                .body("endpoint.state", equalTo("TERMINATED"));
        emr().delete("/virtualclusters/" + vc).then().statusCode(400);

        String arrested = virtualCluster("orphaned", eks, "batch");
        given().delete("/clusters/" + eks).then().statusCode(200);
        get("/virtualclusters/" + arrested).statusCode(200).body("virtualCluster.state", equalTo("ARRESTED"));
        get("/virtualclusters?states=ARRESTED").statusCode(200).body("virtualClusters.id", hasItem(arrested));

        emr().delete("/jobtemplates/" + template).then().statusCode(200);
        get("/jobtemplates/" + template).statusCode(400).body("__type", containsString("ResourceNotFound"));
    }

    @Test
    void missingResourcesAndSecurityConfigurations() {
        get("/virtualclusters/" + MISSING).statusCode(400).body("__type", containsString("ResourceNotFound"));
        emr().delete("/virtualclusters/" + MISSING).then().statusCode(400)
                .body("__type", containsString("ValidationException"));
        get("/jobtemplates/" + MISSING).statusCode(400).body("__type", containsString("ResourceNotFound"));
        emr().delete("/jobtemplates/" + MISSING).then().statusCode(400)
                .body("__type", containsString("ValidationException"));
        get("/virtualclusters/" + MISSING + "/jobruns/abcdefabcdefabcdefa").statusCode(400)
                .body("__type", containsString("ValidationException"));
        emr().delete("/virtualclusters/" + MISSING + "/jobruns/abcdefabcdefabcdefa").then().statusCode(400)
                .body("__type", containsString("ValidationException"));
        get("/virtualclusters/" + MISSING + "/jobruns").statusCode(200).body("jobRuns", hasSize(0));
        get("/jobtemplates?maxResults=10").statusCode(200);

        String configuration = post("/securityconfigurations", "{\"name\":\"lf\",\"clientToken\":\"" + suffix()
                + "\",\"securityConfigurationData\":{\"authorizationConfiguration\":{\"lakeFormationConfiguration\":"
                + "{\"authorizedSessionTagValue\":\"Amazon EMR\"}}}}").statusCode(200)
                .body("arn", containsString(":/securityconfigurations/")).extract().path("id");
        get("/securityconfigurations/" + configuration).statusCode(200)
                .body("securityConfiguration.securityConfigurationData.authorizationConfiguration"
                        + ".lakeFormationConfiguration.authorizedSessionTagValue", equalTo("Amazon EMR"));
        post("/securityconfigurations", "{\"name\":\"tls\",\"clientToken\":\"" + suffix() + "\","
                + "\"securityConfigurationData\":{\"authorizationConfiguration\":{\"encryptionConfiguration\":"
                + "{\"inTransitEncryptionConfiguration\":{\"tlsCertificateConfiguration\":{\"certificateProviderType\":"
                + "\"PEM\",\"publicCertificateSecretArn\":\"arn:aws:secretsmanager:us-east-1:" + ACCOUNT
                + ":secret:missing\"}}}}}}").statusCode(400).body("message", containsString("Secret"));
        emr().delete("/securityconfigurations/" + configuration).then().statusCode(200);
        get("/securityconfigurations/" + configuration).statusCode(400);
    }
}
