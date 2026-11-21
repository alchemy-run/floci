package io.floci.aws.services.s3;

import io.floci.aws.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S3 Control's access points, Multi-Region Access Points, Storage Lens, Batch Operations and Access Grants. */
@QuarkusTest
class S3ControlIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String NS = "http://awss3control.amazonaws.com/doc/2018-08-20/";

    private static RequestSpecification control() {
        return given().header("x-amz-account-id", ACCOUNT).contentType("application/xml");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String role(String name) {
        return given().formParam("Action", "CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                        + "\"Allow\",\"Principal\":{\"Service\":\"batchoperations.s3.amazonaws.com\"},\"Action\":"
                        + "\"sts:AssumeRole\"}]}")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/iam/aws4_request")
                .post("/").then().statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult"
                        + ".Role.Arn");
    }

    @Test
    void accessPointsCarryPoliciesScopesAndTags() {
        String bucket = "ap-bucket-" + suffix();
        given().put("/" + bucket).then().statusCode(200);
        String name = "ap-" + suffix();
        String arn = control().body("<CreateAccessPointRequest xmlns=\"" + NS + "\"><Bucket>" + bucket
                        + "</Bucket><Tags><Tag><Key>team</Key><Value>data</Value></Tag></Tags>"
                        + "</CreateAccessPointRequest>")
                .put("/v20180820/accesspoint/" + name).then().statusCode(200)
                .body(containsString("<AccessPointArn>")).extract().xmlPath().getString("CreateAccessPointResult"
                        + ".AccessPointArn");
        assertTrue(arn.endsWith(":accesspoint/" + name), arn);
        control().body("<CreateAccessPointRequest xmlns=\"" + NS + "\"><Bucket>" + bucket + "</Bucket>"
                + "</CreateAccessPointRequest>").put("/v20180820/accesspoint/" + name).then().statusCode(409);

        control().get("/v20180820/accesspoint/" + name).then().statusCode(200)
                .body(containsString("<Bucket>" + bucket + "</Bucket>"))
                .body(containsString("<NetworkOrigin>Internet</NetworkOrigin>"))
                .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"));
        control().get("/v20180820/accesspoint?bucket=" + bucket).then().statusCode(200)
                .body(containsString("<Name>" + name + "</Name>"));

        control().get("/v20180820/accesspoint/" + name + "/policy").then().statusCode(404)
                .body(containsString("NoSuchAccessPointPolicy"));
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"s3:GetObject\",\"Resource\":\"" + arn + "/object/*\"}]}";
        control().body("<PutAccessPointPolicyRequest xmlns=\"" + NS + "\"><Policy>" + policy.replace("\"", "&quot;")
                + "</Policy></PutAccessPointPolicyRequest>").put("/v20180820/accesspoint/" + name + "/policy")
                .then().statusCode(200);
        String read = control().get("/v20180820/accesspoint/" + name + "/policy").then().statusCode(200)
                .extract().xmlPath().getString("GetAccessPointPolicyResult.Policy");
        assertEquals(policy, read);
        control().get("/v20180820/accesspoint/" + name + "/policyStatus").then().statusCode(200)
                .body(containsString("<IsPublic>true</IsPublic>"));

        control().body("<PutAccessPointScopeRequest xmlns=\"" + NS + "\"><Scope><Prefixes><Prefix>logs/</Prefix>"
                        + "</Prefixes><Permissions><Permission>GetObject</Permission></Permissions></Scope>"
                        + "</PutAccessPointScopeRequest>")
                .put("/v20180820/accesspoint/" + name + "/scope").then().statusCode(200);
        control().get("/v20180820/accesspoint/" + name + "/scope").then().statusCode(200)
                .body(containsString("<Prefix>logs/</Prefix>"));

        control().get("/v20180820/tags/" + arn).then().statusCode(200)
                .body(containsString("<Key>team</Key>"));
        control().body("<TagResourceRequest xmlns=\"" + NS + "\"><Tags><Tag><Key>env</Key><Value>dev</Value></Tag>"
                + "</Tags></TagResourceRequest>").post("/v20180820/tags/" + arn).then().statusCode(200);
        control().delete("/v20180820/tags/" + arn + "?tagKeys=team").then().statusCode(200);
        control().get("/v20180820/tags/" + arn).then().statusCode(200)
                .body(containsString("<Key>env</Key>")).body(not(containsString("<Key>team</Key>")));

        control().delete("/v20180820/accesspoint/" + name).then().statusCode(204);
        control().get("/v20180820/accesspoint/" + name).then().statusCode(404)
                .body(containsString("NoSuchAccessPoint"));
    }

    @Test
    void objectLambdaAccessPointsNeedTheirSupportingAccessPoint() {
        String bucket = "olap-bucket-" + suffix();
        given().put("/" + bucket).then().statusCode(200);
        String supporting = "sap-" + suffix();
        String supportingArn = control().body("<CreateAccessPointRequest xmlns=\"" + NS + "\"><Bucket>" + bucket
                        + "</Bucket></CreateAccessPointRequest>").put("/v20180820/accesspoint/" + supporting)
                .then().statusCode(200).extract().xmlPath().getString("CreateAccessPointResult.AccessPointArn");
        String configuration = "<Configuration><SupportingAccessPoint>" + supportingArn
                + "</SupportingAccessPoint><TransformationConfigurations><TransformationConfiguration><Actions>"
                + "<Action>GetObject</Action></Actions><ContentTransformation><AwsLambda><FunctionArn>"
                + "arn:aws:lambda:us-east-1:000000000000:function:transform</FunctionArn></AwsLambda>"
                + "</ContentTransformation></TransformationConfiguration></TransformationConfigurations>"
                + "</Configuration>";
        String name = "olap-" + suffix();
        control().body("<CreateAccessPointForObjectLambdaRequest xmlns=\"" + NS + "\">" + configuration
                        + "</CreateAccessPointForObjectLambdaRequest>")
                .put("/v20180820/accesspointforobjectlambda/" + name).then().statusCode(200)
                .body(containsString("<ObjectLambdaAccessPointArn>"));
        control().get("/v20180820/accesspointforobjectlambda/" + name + "/configuration").then().statusCode(200)
                .body(containsString("<SupportingAccessPoint>" + supportingArn + "</SupportingAccessPoint>"));
        control().get("/v20180820/accesspointforobjectlambda").then().statusCode(200)
                .body(containsString("<Name>" + name + "</Name>"));
        control().delete("/v20180820/accesspoint/" + supporting).then().statusCode(400);
        control().delete("/v20180820/accesspointforobjectlambda/" + name).then().statusCode(204);
        control().delete("/v20180820/accesspoint/" + supporting).then().statusCode(204);
        control().body("<CreateAccessPointForObjectLambdaRequest xmlns=\"" + NS + "\">" + configuration
                        + "</CreateAccessPointForObjectLambdaRequest>")
                .put("/v20180820/accesspointforobjectlambda/" + name).then().statusCode(404);
    }

    @Test
    void multiRegionAccessPointsAreCreatedAsynchronouslyAndRouteTraffic() {
        String east = "mrap-east-" + suffix();
        String west = "mrap-west-" + suffix();
        given().put("/" + east).then().statusCode(200);
        given().body("<CreateBucketConfiguration><LocationConstraint>us-west-2</LocationConstraint>"
                + "</CreateBucketConfiguration>").put("/" + west).then().statusCode(200);
        String name = "mrap-" + suffix();
        String token = control().body("<CreateMultiRegionAccessPointRequest xmlns=\"" + NS + "\"><ClientToken>"
                        + UUID.randomUUID() + "</ClientToken><Details><Name>" + name + "</Name><Regions>"
                        + "<Region><Bucket>" + east + "</Bucket></Region><Region><Bucket>" + west + "</Bucket>"
                        + "</Region></Regions></Details></CreateMultiRegionAccessPointRequest>")
                .post("/v20180820/async-requests/mrap/create").then().statusCode(200).extract().xmlPath()
                .getString("CreateMultiRegionAccessPointResult.RequestTokenARN");
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250)).until(() ->
                control().get("/v20180820/async-requests/mrap/" + token).then().statusCode(200).extract()
                        .asString().contains("<RequestStatus>SUCCEEDED</RequestStatus>"));
        String alias = control().get("/v20180820/mrap/instances/" + name).then().statusCode(200)
                .body(containsString("<Status>READY</Status>")).extract().xmlPath()
                .getString("GetMultiRegionAccessPointResult.AccessPoint.Alias");
        control().get("/v20180820/mrap/instances").then().statusCode(200)
                .body(containsString("<Name>" + name + "</Name>"));
        String routes = control().get("/v20180820/mrap/instances/" + alias + "/routes").then().statusCode(200)
                .extract().asString();
        assertEquals(2, XmlParser.extractAll(routes, "Bucket").size(), routes);
        control().body("<SubmitMultiRegionAccessPointRoutesRequest xmlns=\"" + NS + "\"><RouteUpdates><Route>"
                        + "<Bucket>" + west + "</Bucket><TrafficDialPercentage>0</TrafficDialPercentage></Route>"
                        + "</RouteUpdates></SubmitMultiRegionAccessPointRoutesRequest>")
                .patch("/v20180820/mrap/instances/" + alias + "/routes").then().statusCode(200);
        control().get("/v20180820/mrap/instances/" + alias + "/routes").then().statusCode(200)
                .body(containsString("<TrafficDialPercentage>0</TrafficDialPercentage>"));
        control().get("/v20180820/mrap/instances/" + name + "/policy").then().statusCode(404);
    }

    @Test
    void storageLensConfigurationsAndGroups() {
        String id = "lens-" + suffix();
        control().body("<PutStorageLensConfigurationRequest xmlns=\"" + NS + "\"><StorageLensConfiguration><Id>" + id
                        + "</Id><AccountLevel><BucketLevel/></AccountLevel><IsEnabled>true</IsEnabled>"
                        + "</StorageLensConfiguration><Tags><Tag><Key>k</Key><Value>v</Value></Tag></Tags>"
                        + "</PutStorageLensConfigurationRequest>")
                .put("/v20180820/storagelens/" + id).then().statusCode(200);
        control().get("/v20180820/storagelens/" + id).then().statusCode(200)
                .body(containsString("<StorageLensArn>")).body(containsString("<IsEnabled>true</IsEnabled>"));
        control().get("/v20180820/storagelens/" + id + "/tagging").then().statusCode(200)
                .body(containsString("<Key>k</Key>"));
        control().get("/v20180820/storagelens").then().statusCode(200).body(containsString("<Id>" + id + "</Id>"));
        control().delete("/v20180820/storagelens/" + id).then().statusCode(204);
        control().get("/v20180820/storagelens/" + id).then().statusCode(404);

        String group = "group" + suffix();
        control().body("<CreateStorageLensGroupRequest xmlns=\"" + NS + "\"><StorageLensGroup><Name>" + group
                        + "</Name><Filter><MatchAnyPrefix><Prefix>logs/</Prefix></MatchAnyPrefix></Filter>"
                        + "</StorageLensGroup></CreateStorageLensGroupRequest>")
                .post("/v20180820/storagelensgroup").then().statusCode(200);
        control().get("/v20180820/storagelensgroup/" + group).then().statusCode(200)
                .body(containsString("<Prefix>logs/</Prefix>"));
        control().get("/v20180820/storagelensgroup").then().statusCode(200)
                .body(containsString("<Name>" + group + "</Name>"));
        control().body("<CreateStorageLensGroupRequest xmlns=\"" + NS + "\"><StorageLensGroup><Name>" + group
                        + "</Name><Filter/></StorageLensGroup></CreateStorageLensGroupRequest>")
                .post("/v20180820/storagelensgroup").then().statusCode(409);
        control().delete("/v20180820/storagelensgroup/" + group).then().statusCode(204);
    }

    @Test
    void batchJobsTagEveryManifestObjectAfterConfirmation() {
        String bucket = "batch-" + suffix();
        given().put("/" + bucket).then().statusCode(200);
        given().body("a").put("/" + bucket + "/data/a.txt").then().statusCode(200);
        given().body("b").put("/" + bucket + "/data/b.txt").then().statusCode(200);
        given().body(bucket + ",data/a.txt\n" + bucket + ",data/b.txt\n").put("/" + bucket + "/manifest.csv")
                .then().statusCode(200);
        String roleArn = role("batch-" + suffix());
        String create = "<CreateJobRequest xmlns=\"" + NS + "\"><ConfirmationRequired>true</ConfirmationRequired>"
                + "<Operation><S3PutObjectTagging><TagSet><member><Key>processed</Key><Value>yes</Value></member>"
                + "</TagSet></S3PutObjectTagging></Operation><Report><Bucket>arn:aws:s3:::" + bucket + "</Bucket>"
                + "<Format>Report_CSV_20180820</Format><Enabled>true</Enabled><Prefix>reports</Prefix>"
                + "<ReportScope>AllTasks</ReportScope></Report><ClientRequestToken>" + UUID.randomUUID()
                + "</ClientRequestToken><Manifest><Spec><Format>S3BatchOperations_CSV_20180820</Format><Fields>"
                + "<member>Bucket</member><member>Key</member></Fields></Spec><Location><ObjectArn>arn:aws:s3:::"
                + bucket + "/manifest.csv</ObjectArn></Location></Manifest><Priority>10</Priority><RoleArn>"
                + roleArn + "</RoleArn></CreateJobRequest>";
        String jobId = control().body(create).post("/v20180820/jobs").then().statusCode(200).extract().xmlPath()
                .getString("CreateJobResult.JobId");
        control().get("/v20180820/jobs/" + jobId).then().statusCode(200)
                .body(containsString("<Status>Suspended</Status>"))
                .body(containsString("<TotalNumberOfTasks>2</TotalNumberOfTasks>"));
        control().post("/v20180820/jobs/" + jobId + "/priority?priority=20").then().statusCode(200)
                .body(containsString("<Priority>20</Priority>"));
        control().post("/v20180820/jobs/" + jobId + "/status?requestedJobStatus=Ready").then().statusCode(200);
        control().get("/v20180820/jobs/" + jobId).then().statusCode(200)
                .body(containsString("<Status>Complete</Status>"))
                .body(containsString("<NumberOfTasksSucceeded>2</NumberOfTasksSucceeded>"));
        given().get("/" + bucket + "/data/a.txt?tagging").then().statusCode(200)
                .body(containsString("<Key>processed</Key>"));
        given().get("/" + bucket + "?list-type=2&prefix=reports/job-" + jobId).then().statusCode(200)
                .body(containsString("manifest.json"));
        control().post("/v20180820/jobs/" + jobId + "/priority?priority=30").then().statusCode(400)
                .body(containsString("job status forbidden"));
        control().get("/v20180820/jobs?jobStatuses=Complete").then().statusCode(200)
                .body(containsString("<JobId>" + jobId + "</JobId>"));

        String missingRole = create.replace(roleArn, "arn:aws:iam::000000000000:role/missing-" + suffix())
                .replaceAll("<ClientRequestToken>[^<]+", "<ClientRequestToken>" + UUID.randomUUID());
        control().body(missingRole).post("/v20180820/jobs").then().statusCode(400)
                .body(containsString("InvalidRequest"));
        control().get("/v20180820/jobs/" + UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void accessGrantsVendCredentialsForTheNarrowestGrant() {
        control().delete("/v20180820/accessgrantsinstance");
        control().get("/v20180820/accessgrantsinstance").then().statusCode(404);
        control().body("<CreateAccessGrantsInstanceRequest xmlns=\"" + NS + "\"/>")
                .post("/v20180820/accessgrantsinstance").then().statusCode(200)
                .body(containsString("<AccessGrantsInstanceId>default</AccessGrantsInstanceId>"));
        String bucket = "grants-" + suffix();
        given().put("/" + bucket).then().statusCode(200);
        String roleArn = role("grants-" + suffix());
        String locationId = control().body("<CreateAccessGrantsLocationRequest xmlns=\"" + NS + "\"><LocationScope>"
                        + "s3://" + bucket + "</LocationScope><IAMRoleArn>" + roleArn + "</IAMRoleArn>"
                        + "</CreateAccessGrantsLocationRequest>")
                .post("/v20180820/accessgrantsinstance/location").then().statusCode(200).extract().xmlPath()
                .getString("CreateAccessGrantsLocationResult.AccessGrantsLocationId");
        String grantId = control().body("<CreateAccessGrantRequest xmlns=\"" + NS + "\"><AccessGrantsLocationId>"
                        + locationId + "</AccessGrantsLocationId><AccessGrantsLocationConfiguration><S3SubPrefix>"
                        + "reports/*</S3SubPrefix></AccessGrantsLocationConfiguration><Grantee><GranteeType>IAM"
                        + "</GranteeType><GranteeIdentifier>arn:aws:iam::000000000000:root</GranteeIdentifier>"
                        + "</Grantee><Permission>READ</Permission></CreateAccessGrantRequest>")
                .post("/v20180820/accessgrantsinstance/grant").then().statusCode(200)
                .body(containsString("<GrantScope>s3://" + bucket + "/reports/*</GrantScope>")).extract().xmlPath()
                .getString("CreateAccessGrantResult.AccessGrantId");
        control().get("/v20180820/accessgrantsinstance/dataaccess?target=s3://" + bucket + "/reports/q1.csv"
                        + "&permission=READ").then().statusCode(200)
                .body(containsString("<AccessKeyId>ASIA"))
                .body(containsString("<MatchedGrantTarget>s3://" + bucket + "/reports/*</MatchedGrantTarget>"));
        control().get("/v20180820/accessgrantsinstance/dataaccess?target=s3://" + bucket + "/reports/q1.csv"
                + "&permission=WRITE").then().statusCode(403);
        control().get("/v20180820/accessgrantsinstance/caller/grants").then().statusCode(200)
                .body(containsString("<Permission>READ</Permission>"));
        control().get("/v20180820/accessgrantsinstance/prefix?s3prefix=s3://" + bucket + "/reports/")
                .then().statusCode(200).body(containsString("access-grants/default"));
        control().delete("/v20180820/accessgrantsinstance/location/" + locationId).then().statusCode(400);
        control().delete("/v20180820/accessgrantsinstance/grant/" + grantId).then().statusCode(204);
        control().delete("/v20180820/accessgrantsinstance/location/" + locationId).then().statusCode(204);
        control().delete("/v20180820/accessgrantsinstance").then().statusCode(204);
    }

    @Test
    void outpostsBucketsAreNotAvailable() {
        control().get("/v20180820/bucket").then().statusCode(200).body(containsString("<RegionalBucketList"));
        control().put("/v20180820/bucket/outpost-bucket").then().statusCode(404)
                .body(containsString("NoSuchOutpost"));
        control().get("/v20180820/no-such-route").then().statusCode(404).body(containsString("NoSuchResource"));
    }
}
