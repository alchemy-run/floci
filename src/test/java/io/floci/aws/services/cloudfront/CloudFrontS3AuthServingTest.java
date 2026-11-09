package io.floci.aws.services.cloudfront;

import io.floci.aws.services.cloudfront.model.CloudFrontOriginAccessIdentity;
import io.floci.aws.services.cloudfront.model.DefaultCacheBehavior;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.services.cloudfront.model.Origin;
import io.floci.aws.services.cloudfront.model.OriginAccessControl;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.S3EnforceAuthProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class CloudFrontS3AuthServingTest {

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    S3Service s3Service;

    @Test
    void enforcesAnonymousS3OriginReadAccess() {
        String suffix = Long.toString(System.nanoTime(), 36);
        Distribution privateDistribution =
                distribution("cf-private-" + suffix, false, null, null);
        Distribution publicDistribution =
                distribution("cf-public-" + suffix, true, null, null);

        given().header("Host", privateDistribution.getDomainName()).when().get("/index.html")
                .then().statusCode(403);
        given().header("Host", publicDistribution.getDomainName()).when().get("/index.html")
                .then().statusCode(200).body(equalTo("PUBLIC-ORIGIN"));
    }

    @Test
    void servesPrivateS3OriginThroughOacPolicyGrant() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oac-" + suffix;
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("oac-" + suffix);
        oac.setSigningBehavior("always");
        oac.setSigningProtocol("sigv4");
        oac.setOriginAccessControlOriginType("s3");
        oac = cloudFrontService.createOriginAccessControl(oac);

        Distribution distribution = distribution(bucket, false, oac.getId(), null);
        s3Service.putBucketPolicy(
                bucket, oacReadPolicy(bucket, distribution.getArn()));

        given().header("Host", distribution.getDomainName()).when().get("/index.html")
                .then().statusCode(200).body(equalTo("PRIVATE-ORIGIN"));
    }

    @Test
    void servesPrivateS3OriginThroughOaiObjectAclGrant() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oai-" + suffix;
        CloudFrontOriginAccessIdentity oai = new CloudFrontOriginAccessIdentity();
        oai.setCallerReference("oai-" + suffix);
        oai.setComment("Private origin access");
        oai = cloudFrontService.createCloudFrontOriginAccessIdentity(oai);

        Distribution distribution = distribution(
                bucket,
                false,
                null,
                "origin-access-identity/cloudfront/" + oai.getId());
        s3Service.putBucketOwnershipControls(bucket, "<OwnershipControls><Rule>"
                + "<ObjectOwnership>ObjectWriter</ObjectOwnership></Rule></OwnershipControls>");
        s3Service.putObjectAcl(
                bucket,
                "index.html",
                null,
                canonicalReadAcl(oai.getS3CanonicalUserId()),
                null,
                null,
                null,
                null,
                null,
                null);

        given().header("Host", distribution.getDomainName()).when().get("/index.html")
                .then().statusCode(200).body(equalTo("PRIVATE-ORIGIN"));
    }

    @Test
    void oacNeverSigningRequiresPublicS3Access() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oac-never-" + suffix;
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("oac-never-" + suffix);
        oac.setSigningBehavior("never");
        oac.setSigningProtocol("sigv4");
        oac.setOriginAccessControlOriginType("s3");
        oac = cloudFrontService.createOriginAccessControl(oac);

        Distribution distribution = distribution(bucket, false, oac.getId(), null);
        s3Service.putBucketPolicy(
                bucket, oacReadPolicy(bucket, distribution.getArn()));

        given().header("Host", distribution.getDomainName()).when().get("/index.html")
                .then().statusCode(403);
    }

    @Test
    void oacNoOverridePreservesViewerAuthorization() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oac-viewer-auth-" + suffix;
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("oac-viewer-auth-" + suffix);
        oac.setSigningBehavior("no-override");
        oac.setSigningProtocol("sigv4");
        oac.setOriginAccessControlOriginType("s3");
        oac = cloudFrontService.createOriginAccessControl(oac);

        Distribution distribution = distribution(bucket, false, oac.getId(), null);

        given()
                .header("Host", distribution.getDomainName())
                .header("Authorization", viewerAuthorization("test"))
        .when()
                .get("/index.html")
        .then()
                .statusCode(200)
                .body(equalTo("PRIVATE-ORIGIN"));

        given()
                .header("Host", distribution.getDomainName())
                .header("Authorization", viewerAuthorization("unknown"))
        .when()
                .get("/index.html")
        .then()
                .statusCode(403);
    }

    @Test
    void aViewerRequestFunctionsOriginAccessControlSignsTheS3OriginItChose() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-fn-oac-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "index.html", "FUNCTION-ORIGIN".getBytes(StandardCharsets.UTF_8),
                "text/html", Map.of());
        String functionName = "fn-oac-" + suffix;
        String code = """
                import cf from "cloudfront";
                async function handler(event) {
                  cf.updateRequestOrigin({
                    domainName: "%s.s3.us-east-1.amazonaws.com",
                    originAccessControlConfig: {
                      enabled: true, signingBehavior: "always", signingProtocol: "sigv4", originType: "s3"
                    }
                  });
                  return event.request;
                }
                """.formatted(bucket);
        ExtractableResponse<Response> created =
                given().contentType("application/xml")
                .body("""
                        <CreateFunctionRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                          <Name>%s</Name>
                          <FunctionConfig><Comment>oac</Comment><Runtime>cloudfront-js-2.0</Runtime></FunctionConfig>
                          <FunctionCode>%s</FunctionCode>
                        </CreateFunctionRequest>
                        """.formatted(functionName,
                        Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8))))
                .when().post("/2020-05-31/function")
                .then().statusCode(201).extract();
        String functionArn = created.body().asString()
                .replaceAll("(?s).*<FunctionARN>([^<]*)</FunctionARN>.*", "$1");
        given().header("If-Match", created.header("ETag")).when().post("/2020-05-31/function/" + functionName + "/publish")
                .then().statusCode(200);

        Origin placeholder = new Origin();
        placeholder.setId("placeholder");
        placeholder.setDomainName("placeholder.invalid");
        placeholder.setCustomOriginConfig(new LinkedHashMap<>(Map.of(
                "HTTPPort", "80", "HTTPSPort", "443", "OriginProtocolPolicy", "https-only")));
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(placeholder.getId());
        behavior.setViewerProtocolPolicy("allow-all");
        behavior.setFunctionAssociations(List.of(Map.of(
                "EventType", "viewer-request", "FunctionARN", functionArn)));
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setDefaultRootObject("index.html");
        config.setOrigins(List.of(placeholder));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        distribution = cloudFrontService.createDistribution(distribution, Map.of());
        try {
            given().header("Host", distribution.getDomainName()).when().get("/")
                    .then().statusCode(403);
            s3Service.putBucketPolicy(bucket, oacReadPolicy(bucket, distribution.getArn()));
            given().header("Host", distribution.getDomainName()).when().get("/")
                    .then().statusCode(200).body(equalTo("FUNCTION-ORIGIN"));
        } finally {
            cloudFrontService.removeDistribution(distribution.getId());
        }
    }

    private Distribution distribution(
            String bucket, boolean publicRead, String oacId, String originAccessIdentity) {
        s3Service.createBucket(bucket, "us-east-1");
        String body = publicRead ? "PUBLIC-ORIGIN" : "PRIVATE-ORIGIN";
        s3Service.putObject(bucket, "index.html", body.getBytes(StandardCharsets.UTF_8),
                "text/html", Map.of());
        if (publicRead) {
            s3Service.deletePublicAccessBlock(bucket);
            s3Service.putBucketPolicy(bucket, publicReadPolicy(bucket));
        }

        Origin origin = new Origin();
        origin.setId("s3-origin");
        origin.setDomainName(bucket + ".s3.us-east-1.amazonaws.com");
        origin.setOriginAccessControlId(oacId);
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of(
                "OriginAccessIdentity",
                originAccessIdentity != null ? originAccessIdentity : "")));
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(origin.getId());
        behavior.setViewerProtocolPolicy("allow-all");
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return cloudFrontService.createDistribution(distribution, Map.of());
    }

    private static String publicReadPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/*"
                  }
                }
                """.formatted(bucket);
    }

    private static String oacReadPolicy(String bucket, String distributionArn) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": {"Service": "cloudfront.amazonaws.com"},
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/*",
                    "Condition": {
                      "StringEquals": {"AWS:SourceArn": "%s"}
                    }
                  }
                }
                """.formatted(bucket, distributionArn);
    }

    private static String canonicalReadAcl(String canonicalUserId) {
        return """
                <AccessControlPolicy>
                  <AccessControlList>
                    <Grant>
                      <Grantee xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                               xsi:type="CanonicalUser">
                        <ID>%s</ID>
                      </Grantee>
                      <Permission>READ</Permission>
                    </Grant>
                  </AccessControlList>
                </AccessControlPolicy>
                """.formatted(canonicalUserId);
    }

    private static String viewerAuthorization(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                + "/20260730/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-date, Signature=abc123";
    }
}
