package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontOriginAccessIdentity;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.KeyValueStore;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(CloudFrontS3AuthServingTest.S3AuthProfile.class)
class CloudFrontS3AuthServingTest {

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    S3Service s3Service;

    @Inject
    ObjectMapper mapper;

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
    @Timeout(60)
    void kvsSelectedPrivateS3OriginRequiresTheExactDistributionAndBucketPolicy() throws Exception {
        try (DynamicOrigin fixture = new DynamicOrigin("cf-dynamic-policy", null)) {
            String arn = fixture.distribution.getArn();
            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, arn));
            assertDynamicResponse(fixture, 200, Map.of());
            given().head("/_floci/cloudfront/" + fixture.distribution.getId() + "/index.html")
                    .then().statusCode(200).body(equalTo(""));

            // Neither a source-ARN header nor an invented CloudFront credential supplies the OAC principal.
            given().get("/" + fixture.bucket + "/content/index.html").then().statusCode(403);
            given().header("X-Amz-Source-Arn", arn)
                    .header("X-Amz-Principal", "cloudfront.amazonaws.com")
                    .get("/" + fixture.bucket + "/content/index.html").then().statusCode(403);
            given().header("X-Amz-Source-Arn", arn)
                    .header("X-Amz-Principal", "cloudfront.amazonaws.com")
                    .header("Authorization", viewerAuthorization("cloudfront.amazonaws.com"))
                    .get("/" + fixture.bucket + "/content/index.html").then().statusCode(403);

            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, arn + "-other"));
            assertDynamicResponse(fixture, 403, Map.of("X-Amz-Source-Arn", arn + "-other"));
            s3Service.deleteBucketPolicy(fixture.bucket);
            assertDynamicResponse(fixture, 403, Map.of());
            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, arn)
                    .replace(fixture.bucket + "/*", fixture.bucket + "/different-key"));
            assertDynamicResponse(fixture, 403, Map.of());
            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, arn)
                    .replace("\"Allow\"", "\"Deny\""));
            assertDynamicResponse(fixture, 403, Map.of());

            String foreign = "cf-dynamic-policy-foreign";
            s3Service.createBucket(foreign, "us-east-1");
            try {
                s3Service.putObject(foreign, "content/index.html", "FOREIGN".getBytes(StandardCharsets.UTF_8),
                        "text/plain", Map.of());
                s3Service.putBucketPolicy(foreign, oacReadPolicy(foreign, arn + "-other"));
                fixture.select(s3Update(foreign, access("always")));
                assertDynamicResponse(fixture, 403, Map.of());
                s3Service.putBucketPolicy(foreign, oacReadPolicy(fixture.bucket, arn));
                assertDynamicResponse(fixture, 403, Map.of());
            } finally {
                s3Service.deleteObject(foreign, "content/index.html");
                s3Service.deleteBucket(foreign);
            }
            fixture.select(s3Update(fixture.bucket, access("always")));
            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, arn));
            assertDynamicResponse(fixture, 200, Map.of());
            Origin persisted = cloudFrontService.getDistribution(fixture.distribution.getId()).getConfig()
                    .getOrigins().getFirst();
            assertEquals("placeholder.invalid", persisted.getDomainName());
            assertNull(persisted.getOriginAccessControlId());
            assertNull(persisted.getS3OriginConfig());
        }
    }

    @Test
    @Timeout(60)
    void dynamicS3SigningHonorsDisabledNeverNoOverrideAndAlways() throws Exception {
        try (DynamicOrigin fixture = new DynamicOrigin("cf-dynamic-signing", null)) {
            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, fixture.distribution.getArn()));
            for (Map<String, Object> settings : List.of(Map.<String, Object>of("enabled", false), access("never"))) {
                fixture.select(s3Update(fixture.bucket, settings));
                assertDynamicResponse(fixture, 403, Map.of());
                assertDynamicResponse(fixture, 403, Map.of("Authorization", viewerAuthorization("unknown")));
                assertDynamicResponse(fixture, 200, Map.of("Authorization", viewerAuthorization("test")));
                s3Service.putBucketPolicy(fixture.bucket, publicReadPolicy(fixture.bucket));
                assertDynamicResponse(fixture, 200, Map.of());
                s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, fixture.distribution.getArn()));
            }
            fixture.select(s3Update(fixture.bucket, access("no-override")));
            assertDynamicResponse(fixture, 200, Map.of());
            assertDynamicResponse(fixture, 403, Map.of("Authorization", ""));
            assertDynamicResponse(fixture, 403, Map.of("Authorization", viewerAuthorization("unknown")));
            assertDynamicResponse(fixture, 200, Map.of("Authorization", viewerAuthorization("test")));
            fixture.select(s3Update(fixture.bucket, access("always")));
            assertDynamicResponse(fixture, 200, Map.of("Authorization", viewerAuthorization("unknown")));
            fixture.select(Map.of("domainName", fixture.bucket + ".s3.us-east-1.amazonaws.com"));
            assertDynamicResponse(fixture, 403, Map.of());
        }
    }

    @Test
    @Timeout(60)
    void dynamicS3OverridesInheritAndDisableConfiguredOacWithoutPersistingChanges() throws Exception {
        OriginAccessControl control = new OriginAccessControl();
        control.setName("cf-dynamic-inherited-control");
        control.setSigningBehavior("always");
        control.setSigningProtocol("sigv4");
        control.setOriginAccessControlOriginType("s3");
        control = cloudFrontService.createOriginAccessControl(control);
        String etag = control.getEtag();
        try (DynamicOrigin fixture = new DynamicOrigin("cf-dynamic-inherited", control)) {
            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, fixture.distribution.getArn()));
            fixture.select(Map.of("domainName", fixture.bucket + ".s3.us-east-1.amazonaws.com"));
            assertDynamicResponse(fixture, 200, Map.of());
            fixture.select(Map.of("originAccessControlConfig", Map.of("enabled", false)));
            assertDynamicResponse(fixture, 403, Map.of());
            fixture.select(Map.of("originAccessControlConfig", access("never")));
            assertDynamicResponse(fixture, 403, Map.of());
            fixture.select(Map.of("originAccessControlConfig", access("always")));
            assertDynamicResponse(fixture, 200, Map.of());
            Origin persisted = cloudFrontService.getDistribution(fixture.distribution.getId()).getConfig()
                    .getOrigins().getFirst();
            assertEquals(control.getId(), persisted.getOriginAccessControlId());
            assertEquals("/content", persisted.getOriginPath());
            OriginAccessControl unchanged = cloudFrontService.getOriginAccessControl(control.getId());
            assertEquals(etag, unchanged.getEtag());
            assertEquals("always", unchanged.getSigningBehavior());
        } finally {
            cloudFrontService.deleteOriginAccessControl(control.getId(), etag);
        }
    }

    @Test
    @Timeout(60)
    void dynamicS3InvalidAccessSettingsFailClosedBeforeOriginAccess() throws Exception {
        try (DynamicOrigin fixture = new DynamicOrigin("cf-dynamic-invalid", null)) {
            s3Service.putBucketPolicy(fixture.bucket, oacReadPolicy(fixture.bucket, fixture.distribution.getArn()));
            for (Map<String, Object> settings : List.of(Map.<String, Object>of("enabled", true),
                    Map.<String, Object>of("enabled", "true"),
                    Map.<String, Object>of("enabled", true, "signingBehavior", "always",
                            "signingProtocol", "sigv2", "originType", "s3"),
                    Map.<String, Object>of("enabled", true, "signingBehavior", "always",
                            "signingProtocol", "sigv4", "originType", "lambda"))) {
                fixture.select(s3Update(fixture.bucket, settings));
                given().get("/_floci/cloudfront/" + fixture.distribution.getId() + "/index.html")
                        .then().statusCode(502).body(containsString("Invalid CloudFront origin settings"));
            }
            fixture.select(Map.of("domainName", fixture.bucket + ".s3-website-us-east-1.amazonaws.com",
                    "originAccessControlConfig", access("always")));
            assertDynamicResponse(fixture, 502, Map.of());
            fixture.select(s3Update(fixture.bucket, access("always")));
            assertDynamicResponse(fixture, 200, Map.of());
        }
    }

    private void assertDynamicResponse(DynamicOrigin fixture, int status, Map<String, String> headers) {
        String edgeUrl = cloudFrontService.edgeUrl(fixture.distribution.getId());
        assertNotNull(edgeUrl);
        for (String url : List.of("/_floci/cloudfront/" + fixture.distribution.getId(), edgeUrl)) {
            String body = given().headers(headers).get(url + "/index.html")
                    .then().statusCode(status).extract().asString();
            if (status == 200) {
                assertEquals("DYNAMIC-PRIVATE-ORIGIN", body);
            }
        }
    }

    private static Map<String, Object> access(String signingBehavior) {
        return Map.of("enabled", true, "signingBehavior", signingBehavior,
                "signingProtocol", "sigv4", "originType", "s3");
    }

    private static Map<String, Object> s3Update(String bucket, Map<String, Object> access) {
        return Map.of("domainName", bucket + ".s3.us-east-1.amazonaws.com", "originAccessControlConfig", access);
    }

    private final class DynamicOrigin implements AutoCloseable {
        private final String bucket;
        private final KeyValueStore store;
        private final CloudFrontFunction function;
        private final Distribution distribution;

        private DynamicOrigin(String name, OriginAccessControl control) throws Exception {
            bucket = name;
            s3Service.createBucket(bucket, "us-east-1");
            s3Service.putObject(bucket, "content/index.html", "DYNAMIC-PRIVATE-ORIGIN".getBytes(StandardCharsets.UTF_8),
                    "text/plain", Map.of());
            KeyValueStore createdStore = new KeyValueStore();
            createdStore.setName(name + "-kvs");
            store = cloudFrontService.createKeyValueStore(createdStore, Map.of());
            select(s3Update(bucket, access("always")));
            CloudFrontFunction createdFunction = new CloudFrontFunction();
            createdFunction.setName(name + "-fn");
            createdFunction.setRuntime("cloudfront-js-2.0");
            createdFunction.setKeyValueStoreArns(List.of(store.getArn()));
            createdFunction.setFunctionCode("""
                    import cf from "cloudfront";
                    async function handler(event) {
                      var origin = JSON.parse(await cf.kvs().get("origin"));
                      cf.updateRequestOrigin(origin);
                      return event.request;
                    }
                    """);
            function = cloudFrontService.createFunction(createdFunction);
            cloudFrontService.publishFunction(function.getName(), function.getEtag());
            Origin origin = new Origin();
            origin.setId("assigned");
            origin.setOriginPath("/content");
            if (control == null) {
                origin.setDomainName("placeholder.invalid");
                origin.setCustomOriginConfig(Map.of("OriginProtocolPolicy", "https-only", "HTTPSPort", "443"));
            } else {
                origin.setDomainName(bucket + ".s3.us-east-1.amazonaws.com");
                origin.setS3OriginConfig(Map.of());
                origin.setOriginAccessControlId(control.getId());
            }
            DefaultCacheBehavior behavior = new DefaultCacheBehavior();
            behavior.setTargetOriginId(origin.getId());
            behavior.setViewerProtocolPolicy("allow-all");
            behavior.setFunctionAssociations(List.of(Map.of("EventType", "viewer-request", "FunctionARN",
                    "arn:aws:cloudfront::" + cloudFrontService.getAccountId() + ":function/" + function.getName())));
            DistributionConfig config = new DistributionConfig();
            config.setCallerReference(name);
            config.setEnabled(true);
            config.setOrigins(List.of(origin));
            config.setDefaultCacheBehavior(behavior);
            Distribution createdDistribution = new Distribution();
            createdDistribution.setConfig(config);
            distribution = cloudFrontService.createDistribution(createdDistribution, Map.of());
        }

        private void select(Map<String, Object> origin) throws Exception {
            KeyValueStore current = cloudFrontService.describeKeyValueStore(store.getName());
            cloudFrontService.kvsPutKey(store.getArn(), "origin", mapper.writeValueAsString(origin), current.getEtag());
        }

        @Override
        public void close() {
            distribution.getConfig().setEnabled(false);
            Distribution disabled = cloudFrontService.updateDistribution(
                    distribution.getId(), distribution.getEtag(), distribution);
            cloudFrontService.deleteDistribution(disabled.getId(), disabled.getEtag());
            cloudFrontService.deleteFunction(function.getName(),
                    cloudFrontService.describeFunction(function.getName(), "DEVELOPMENT").getEtag());
            cloudFrontService.deleteKeyValueStore(store.getName(),
                    cloudFrontService.describeKeyValueStore(store.getName()).getEtag());
            s3Service.deleteObject(bucket, "content/index.html");
            s3Service.deleteBucket(bucket);
        }
    }

    private Distribution distribution(
            String bucket, boolean publicRead, String oacId, String originAccessIdentity) {
        s3Service.createBucket(bucket, "us-east-1");
        String body = publicRead ? "PUBLIC-ORIGIN" : "PRIVATE-ORIGIN";
        s3Service.putObject(bucket, "index.html", body.getBytes(StandardCharsets.UTF_8),
                "text/html", Map.of());
        if (publicRead) {
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

    public static final class S3AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true");
        }
    }
}
