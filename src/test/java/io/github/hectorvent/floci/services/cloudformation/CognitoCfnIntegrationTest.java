package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsActionJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::Cognito::UserPoolDomain} through a CloudFormation stack, laid out the
 * way CDK emits a custom domain: a user pool, an {@code AWS::CertificateManager::Certificate}, the
 * domain referencing the certificate, and a Route 53 alias record whose target is
 * {@code Fn::GetAtt CloudFrontDistribution}. Asserts that the attribute is the CloudFront name
 * {@code DescribeUserPoolDomain} reports rather than the literal {@code Domain.CloudFrontDistribution}
 * the stub arm would leave in the record, that rotating to a new certificate resource updates the
 * domain in place so the alias target survives, that a changed domain name replaces the domain, and
 * that deleting the stack removes the domain before the pool that owns it and the certificate after
 * the domain that uses it. A prefix domain resolves the same distribution name that
 * {@code DescribeUserPoolDomain} returns.
 *
 * <p>The client case moves an {@code AWS::Cognito::UserPoolClient} between pools and flips
 * {@code GenerateSecret}, its two create-only properties: each is a replacement whose displaced client
 * is removed once the update completes, while a renamed client is updated in place. A replacement
 * without a secret leaves no stale {@code ClientSecret} behind. Under Floci's deterministic client-id
 * override a create whose derived id an existing client already holds is refused and the stack rolls
 * back intact, whether the holder is the client being replaced or one in another stack.
 */
@QuarkusTest
class CognitoCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/cloudformation/aws4_request";
    private static final String CUSTOM_STACK = "cognito-cfn-it";
    private static final String PREFIX_STACK = "cognito-cfn-prefix-it";
    private static final String DOMAIN = "auth.cognito-cfn-it.example.com";
    private static final String REPLACEMENT_DOMAIN = "login.cognito-cfn-it.example.com";
    private static final String PREFIX_DOMAIN = "cognito-cfn-it-prefix";
    private static final String CLIENT_STACK = "cognito-cfn-client-it";

    private static String clientTemplate(String poolLogicalId, boolean generateSecret, String clientName) {
        return """
            {
              "Resources": {
                "PoolA": {"Type": "AWS::Cognito::UserPool", "Properties": {"UserPoolName": "cognito-cfn-client-it-a"}},
                "PoolB": {"Type": "AWS::Cognito::UserPool", "Properties": {"UserPoolName": "cognito-cfn-client-it-b"}},
                "Client": {
                  "Type": "AWS::Cognito::UserPoolClient",
                  "Properties": {"UserPoolId": {"Ref": "%s"}, "ClientName": "%s", "GenerateSecret": %s}
                }
              },
              "Outputs": {
                "PoolA": {"Value": {"Ref": "PoolA"}},
                "PoolB": {"Value": {"Ref": "PoolB"}},
                "ClientId": {"Value": {"Ref": "Client"}},
                "Name": {"Value": {"Fn::GetAtt": ["Client", "Name"]}},
                "ProviderName": {"Value": {"Fn::GetAtt": ["PoolA", "ProviderName"]}}
              }
            }
            """.formatted(poolLogicalId, clientName, generateSecret);
    }

    /**
     * The certificate carries the logical id so a rotation can add a new certificate resource and
     * drop the old one, which is how a certificate is replaced without a moment where the domain
     * points at a certificate that is being deleted. A wildcard name keeps a domain rename from
     * replacing the certificate as well.
     */
    private static String customDomainTemplate(String domain, String certificateLogicalId) {
        return """
            {
              "Resources": {
                "Pool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {"UserPoolName": "cognito-cfn-it-pool"}
                },
                "%1$s": {
                  "Type": "AWS::CertificateManager::Certificate",
                  "Properties": {"DomainName": "*.cognito-cfn-it.example.com", "ValidationMethod": "DNS"}
                },
                "Domain": {
                  "Type": "AWS::Cognito::UserPoolDomain",
                  "Properties": {
                    "Domain": "%2$s",
                    "UserPoolId": {"Ref": "Pool"},
                    "CustomDomainConfig": {"CertificateArn": {"Fn::GetAtt": ["%1$s", "CertificateArn"]}},
                    "ManagedLoginVersion": 2
                  }
                },
                "Zone": {
                  "Type": "AWS::Route53::HostedZone",
                  "Properties": {"Name": "cognito-cfn-it.example.com"}
                },
                "AuthAlias": {
                  "Type": "AWS::Route53::RecordSet",
                  "Properties": {
                    "HostedZoneId": {"Ref": "Zone"},
                    "Name": "%2$s",
                    "Type": "A",
                    "AliasTarget": {
                      "DNSName": {"Fn::GetAtt": ["Domain", "CloudFrontDistribution"]},
                      "HostedZoneId": "Z2FDTNDATAQYW2"
                    }
                  }
                }
              },
              "Outputs": {
                "PoolId": {"Value": {"Ref": "Pool"}},
                "DomainRef": {"Value": {"Ref": "Domain"}},
                "CertArn": {"Value": {"Fn::GetAtt": ["%1$s", "CertificateArn"]}},
                "ZoneId": {"Value": {"Ref": "Zone"}},
                "AliasTarget": {"Value": {"Fn::GetAtt": ["Domain", "CloudFrontDistribution"]}}
              }
            }
            """.formatted(certificateLogicalId, domain);
    }

    private static String certificatePrerequisitesTemplate() throws Exception {
        ObjectNode template = (ObjectNode) new ObjectMapper().readTree(customDomainTemplate(DOMAIN, "Cert"));
        ObjectNode resources = (ObjectNode) template.path("Resources");
        resources.remove("Domain");
        resources.remove("AuthAlias");
        ObjectNode outputs = (ObjectNode) template.path("Outputs");
        outputs.remove("DomainRef");
        outputs.remove("AliasTarget");
        return template.toString();
    }

    private static final String PREFIX_DOMAIN_TEMPLATE = """
            {
              "Resources": {
                "Pool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {"UserPoolName": "cognito-cfn-prefix-it-pool"}
                },
                "Domain": {
                  "Type": "AWS::Cognito::UserPoolDomain",
                  "Properties": {
                    "Domain": "%s",
                    "UserPoolId": {"Ref": "Pool"}
                  }
                }
              },
              "Outputs": {
                "PoolId": {"Value": {"Ref": "Pool"}},
                "DomainRef": {"Value": {"Ref": "Domain"}},
                "CloudFront": {"Value": {"Fn::GetAtt": ["Domain", "CloudFrontDistribution"]}}
              }
            }
            """.formatted(PREFIX_DOMAIN);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void customDomainStackExposesTheCloudFrontNameRotatesTheCertificateReplacesOnRenameAndDeletesBeforeThePool()
            throws Exception {
        // Validate the stack-owned certificate before adding its dependent custom domain.
        cloudFormation(CUSTOM_STACK, "CreateStack", certificatePrerequisitesTemplate());
        String prerequisites = describeStacks(CUSTOM_STACK, "CREATE_COMPLETE");
        String certificateArn = outputValue(prerequisites, "CertArn");
        String zoneId = outputValue(prerequisites, "ZoneId").replace("/hostedzone/", "");
        given().when().get("/2013-04-01/hostedzone/" + zoneId).then().statusCode(200)
            .body("GetHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("false"));
        assertCertificateStatus(certificateArn, "PENDING_VALIDATION");
        JsonNode validations = awsActionJson("CertificateManager", "DescribeCertificate",
                "{\"CertificateArn\": \"" + certificateArn + "\"}")
                .path("Certificate").path("DomainValidationOptions");
        assertEquals(1, validations.size());
        JsonNode validationRecord = validations.get(0).path("ResourceRecord");
        changeValidationRecord(zoneId, validationRecord, "UPSERT");
        assertCertificateStatus(certificateArn, "ISSUED");

        cloudFormation(CUSTOM_STACK, "UpdateStack", customDomainTemplate(DOMAIN, "Cert"));
        String stacks = describeStacks(CUSTOM_STACK, "UPDATE_COMPLETE");
        assertEquals(certificateArn, outputValue(stacks, "CertArn"));
        String poolId = outputValue(stacks, "PoolId");
        String aliasTarget = outputValue(stacks, "AliasTarget");
        assertEquals(DOMAIN, outputValue(stacks, "DomainRef"));
        assertTrue(aliasTarget.endsWith(".cloudfront.net"),
                "Fn::GetAtt CloudFrontDistribution must be a CloudFront name: " + aliasTarget);
        assertTrue(certificateArn.startsWith("arn:aws:acm:us-east-1:"),
                "the certificate must be provisioned rather than stubbed: " + certificateArn);
        assertCertificateStatus(certificateArn, "ISSUED");
        assertCertificateConsumers(certificateArn, 1);

        JsonNode description = describeDomain(DOMAIN);
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals(aliasTarget, description.path("CloudFrontDistribution").asText());
        assertEquals(certificateArn, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(2, description.path("ManagedLoginVersion").asInt());

        // Rotation: a new certificate resource takes over and the old one leaves the template. The
        // domain moves to the new certificate in place, and the old certificate is removed once
        // nothing references it. The existing validation CNAME also validates the renewed certificate.
        cloudFormation(CUSTOM_STACK, "UpdateStack", customDomainTemplate(DOMAIN, "RenewedCert"));

        stacks = describeStacks(CUSTOM_STACK, "UPDATE_COMPLETE");
        String renewedCertificateArn = outputValue(stacks, "CertArn");
        assertNotEquals(certificateArn, renewedCertificateArn);
        assertCertificateStatus(renewedCertificateArn, "ISSUED");
        description = describeDomain(DOMAIN);
        assertEquals(renewedCertificateArn, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(aliasTarget, description.path("CloudFrontDistribution").asText(),
                "a certificate change must keep the CloudFront distribution the alias record points at");
        assertCertificateIsGone(certificateArn);
        assertCertificateConsumers(renewedCertificateArn, 1);

        // The pool permits one custom domain: delete its old name before creating the replacement.
        cloudFormation(CUSTOM_STACK, "UpdateStack", customDomainTemplate(REPLACEMENT_DOMAIN, "RenewedCert"));

        stacks = describeStacks(CUSTOM_STACK, "UPDATE_COMPLETE");
        assertEquals(REPLACEMENT_DOMAIN, outputValue(stacks, "DomainRef"));
        String replacementTarget = outputValue(stacks, "AliasTarget");
        assertTrue(replacementTarget.endsWith(".cloudfront.net"), replacementTarget);
        assertNotEquals(aliasTarget, replacementTarget, "the replacement is a new domain with its own distribution");
        assertDomainIsGone(DOMAIN);
        description = describeDomain(REPLACEMENT_DOMAIN);
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals(replacementTarget, description.path("CloudFrontDistribution").asText());
        assertEquals(renewedCertificateArn, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertCertificateConsumers(renewedCertificateArn, 1);

        changeValidationRecord(zoneId, validationRecord, "DELETE");
        cloudFormation(CUSTOM_STACK, "DeleteStack", null);
        awaitStackDeleted(CUSTOM_STACK);

        assertDomainIsGone(REPLACEMENT_DOMAIN);
        assertCertificateIsGone(renewedCertificateArn);
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .then()
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void prefixDomainStackResolvesItsCloudFrontDistribution() throws Exception {
        cloudFormation(PREFIX_STACK, "CreateStack", PREFIX_DOMAIN_TEMPLATE);

        String stacks = describeStacks(PREFIX_STACK, "CREATE_COMPLETE");
        assertEquals(PREFIX_DOMAIN, outputValue(stacks, "DomainRef"));
        String distribution = outputValue(stacks, "CloudFront");
        assertTrue(distribution.endsWith(".cloudfront.net"));

        JsonNode description = describeDomain(PREFIX_DOMAIN);
        assertEquals(outputValue(stacks, "PoolId"), description.path("UserPoolId").asText());
        assertEquals(distribution, description.path("CloudFrontDistribution").asText());
        assertFalse(description.has("CustomDomainConfig"));

        cloudFormation(PREFIX_STACK, "DeleteStack", null);
        awaitStackDeleted(PREFIX_STACK);

        assertDomainIsGone(PREFIX_DOMAIN);
    }

    private static String domainOnlyTemplate(String poolId, String domain, boolean retain, boolean failLater)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = mapper.createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        ObjectNode definition = resources.putObject("Domain").put("Type", "AWS::Cognito::UserPoolDomain");
        if (retain) {
            definition.put("UpdateReplacePolicy", "Retain");
        }
        definition.putObject("Properties").put("UserPoolId", poolId).put("Domain", domain);
        if (failLater) {
            resources.putObject("Failure").put("Type", "AWS::Cognito::UserPoolClient").put("DependsOn", "Domain")
                    .putObject("Properties").put("UserPoolId", "us-east-1_missing-cognito-rename");
        }
        ObjectNode outputs = template.putObject("Outputs");
        outputs.putObject("DomainRef").putObject("Value").put("Ref", "Domain");
        outputs.putObject("CloudFront").putObject("Value").putArray("Fn::GetAtt")
                .add("Domain").add("CloudFrontDistribution");
        return template.toString();
    }

    private static String customDomainOnlyTemplate(String poolId, String domain, String certificateArn,
                                                   boolean failLater) throws Exception {
        ObjectNode template = (ObjectNode) new ObjectMapper().readTree(
                domainOnlyTemplate(poolId, domain, false, failLater));
        ((ObjectNode) template.path("Resources").path("Domain").path("Properties"))
                .put("ManagedLoginVersion", 2).putObject("CustomDomainConfig").put("CertificateArn", certificateArn);
        return template.toString();
    }

    @Test
    void customRenameFailuresRestoreTheDistributionAndCertificateConsumer() throws Exception {
        String prerequisites = "cognito-cfn-domain-rollback-prereq";
        String stack = "cognito-cfn-domain-rollback-it";
        String original = "auth.cognito-cfn-rollback-it.example.com";
        String replacement = "login.cognito-cfn-rollback-it.example.com";
        // Keep prerequisites outside the update: in-place pool rollback is not implemented.
        cloudFormation(prerequisites, "CreateStack", certificatePrerequisitesTemplate()
                .replace("cognito-cfn-it", "cognito-cfn-rollback-it"));
        String outputs = describeStacks(prerequisites, "CREATE_COMPLETE");
        String poolId = outputValue(outputs, "PoolId");
        String certificateArn = outputValue(outputs, "CertArn");
        String zoneId = outputValue(outputs, "ZoneId").replace("/hostedzone/", "");
        JsonNode validation = awsActionJson("CertificateManager", "DescribeCertificate",
                "{\"CertificateArn\": \"" + certificateArn + "\"}")
                .path("Certificate").path("DomainValidationOptions").get(0).path("ResourceRecord");
        changeValidationRecord(zoneId, validation, "UPSERT");
        assertCertificateStatus(certificateArn, "ISSUED");
        try {
            cloudFormation(stack, "CreateStack", customDomainOnlyTemplate(poolId, original, certificateArn, false));
            String distribution = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "CloudFront");
            JsonNode prior = describeDomain(original);
            assertTrue(distribution.endsWith(".cloudfront.net"));
            assertCertificateConsumers(certificateArn, 1);

            cloudFormation(stack, "UpdateStack", customDomainOnlyTemplate(poolId, replacement,
                    "arn:aws:acm:us-east-1:000000000000:certificate/missing-cognito-cfn", false));
            outputs = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertTrue(describeStackEvents(stack).contains("specified SSL certificate"));
            assertEquals(original, outputValue(outputs, "DomainRef"));
            assertEquals(distribution, outputValue(outputs, "CloudFront"));
            assertEquals(prior, describeDomain(original));
            assertDomainIsGone(replacement);
            assertCertificateConsumers(certificateArn, 1);

            cloudFormation(stack, "UpdateStack", customDomainOnlyTemplate(poolId, replacement, certificateArn, true));
            outputs = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, outputValue(outputs, "DomainRef"));
            assertEquals(distribution, outputValue(outputs, "CloudFront"));
            assertEquals(prior, describeDomain(original));
            assertDomainIsGone(replacement);
            assertCertificateConsumers(certificateArn, 1);
        } finally {
            cloudFormation(stack, "DeleteStack", null);
            awaitStackDeleted(stack);
            assertCertificateConsumers(certificateArn, 0);
            changeValidationRecord(zoneId, validation, "DELETE");
            cloudFormation(prerequisites, "DeleteStack", null);
            awaitStackDeleted(prerequisites);
        }
        assertDomainIsGone(original);
        assertDomainIsGone(replacement);
        assertCertificateIsGone(certificateArn);
    }

    @Test
    void prefixRenameRollsBackAfterALaterResourceFailsThenCanBeRetried() throws Exception {
        String stack = "cognito-cfn-rename-rollback-it";
        String original = "cognito-cfn-rename-original";
        String replacement = "cognito-cfn-rename-replacement";
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\": \"cognito-cfn-rename-rollback-pool\"}")
                .path("UserPool").path("Id").asText();
        try {
            cloudFormation(stack, "CreateStack", domainOnlyTemplate(poolId, original, false, false));
            String stacks = describeStacks(stack, "CREATE_COMPLETE");
            String distribution = outputValue(stacks, "CloudFront");
            assertTrue(distribution.endsWith(".cloudfront.net"));

            cloudFormation(stack, "UpdateStack", domainOnlyTemplate(poolId, replacement, false, true));
            stacks = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, outputValue(stacks, "DomainRef"));
            assertEquals(distribution, outputValue(stacks, "CloudFront"));
            assertEquals(distribution, describeDomain(original).path("CloudFrontDistribution").asText());
            assertEquals(poolId, describeDomain(original).path("UserPoolId").asText());
            assertDomainIsGone(replacement);
            String events = describeStackEvents(stack);
            assertTrue(events.contains("<PhysicalResourceId>" + replacement + "</PhysicalResourceId>"), events);
            assertTrue(events.contains("Resource update rolled back"), events);

            cloudFormation(stack, "UpdateStack", domainOnlyTemplate(poolId, replacement, false, false));
            stacks = describeStacks(stack, "UPDATE_COMPLETE");
            assertEquals(replacement, outputValue(stacks, "DomainRef"));
            assertNotEquals(distribution, outputValue(stacks, "CloudFront"));
            assertEquals(outputValue(stacks, "CloudFront"),
                    describeDomain(replacement).path("CloudFrontDistribution").asText());
            assertDomainIsGone(original);
        } finally {
            cloudFormation(stack, "DeleteStack", null);
            awaitStackDeleted(stack);
            cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + poolId + "\"}").then().statusCode(200);
        }
        assertDomainIsGone(original);
        assertDomainIsGone(replacement);
    }

    @Test
    void retainedSamePoolDomainRenameIsRejectedWithoutDeletingThePredecessor() throws Exception {
        String stack = "cognito-cfn-rename-retain-it";
        String original = "cognito-cfn-retain-original";
        String replacement = "cognito-cfn-retain-replacement";
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\": \"cognito-cfn-rename-retain-pool\"}")
                .path("UserPool").path("Id").asText();
        try {
            cloudFormation(stack, "CreateStack", domainOnlyTemplate(poolId, original, true, false));
            String distribution = outputValue(describeStacks(stack, "CREATE_COMPLETE"), "CloudFront");

            cloudFormation(stack, "UpdateStack", domainOnlyTemplate(poolId, replacement, true, false));
            String stacks = describeStacks(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertTrue(describeStackEvents(stack).contains("UpdateReplacePolicy Retain"));
            assertEquals(original, outputValue(stacks, "DomainRef"));
            assertEquals(distribution, describeDomain(original).path("CloudFrontDistribution").asText());
            assertDomainIsGone(replacement);
        } finally {
            cloudFormation(stack, "DeleteStack", null);
            awaitStackDeleted(stack);
            cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + poolId + "\"}").then().statusCode(200);
        }
        assertDomainIsGone(original);
    }

    private static final String OVERRIDE_STACK = "cognito-cfn-override-it";

    /**
     * A client in a pool whose clients take their id from their name, through Floci's reserved
     * override tag. The pool lives outside the stack: an in-place pool update has no rollback in
     * the engine yet, and this case is about the client.
     */
    private static String overrideClientTemplate(String poolId, boolean generateSecret) {
        return """
            {
              "Resources": {
                "Client": {
                  "Type": "AWS::Cognito::UserPoolClient",
                  "Properties": {"UserPoolId": "%s", "ClientName": "override-web", "GenerateSecret": %s}
                }
              },
              "Outputs": {"ClientId": {"Value": {"Ref": "Client"}}}
            }
            """.formatted(poolId, generateSecret);
    }

    @Test
    void aDerivedIdLeftBehindByADeletedPoolDoesNotBlockAStackInAnotherPool() throws Exception {
        // Deleting a pool does not remove its clients yet (#2949 adds that), so the record of
        // orphan-web lingers under its id; a stack in a live pool must still be able to claim it.
        String deletedPool = cognitoJson("CreateUserPool", """
            {"PoolName": "cognito-cfn-orphan-a", "UserPoolTags": {"floci:override-cognito-client-id": "use-name"}}
            """).path("UserPool").path("Id").asText();
        cognitoAction("CreateUserPoolClient", "{\"UserPoolId\": \"" + deletedPool + "\", \"ClientName\": \"orphan-web\"}")
            .then().statusCode(200);
        cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + deletedPool + "\"}").then().statusCode(200);
        String livePool = cognitoJson("CreateUserPool", """
            {"PoolName": "cognito-cfn-orphan-b", "UserPoolTags": {"floci:override-cognito-client-id": "use-name"}}
            """).path("UserPool").path("Id").asText();
        String stack = "cognito-cfn-orphan-it";

        cloudFormation(stack, "CreateStack", overrideClientTemplate(livePool, false).replace("override-web", "orphan-web"));

        String stacks = describeStacks(stack, "CREATE_COMPLETE");
        assertEquals("orphan-web", outputValue(stacks, "ClientId"));
        assertClientInPool(livePool, "orphan-web", "orphan-web");

        cloudFormation(stack, "DeleteStack", null);
        awaitStackDeleted(stack);
        cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + livePool + "\"}").then().statusCode(200);
    }

    @Test
    void aReplacementThatWouldReuseADeterministicClientIdRollsTheUpdateBackWithThePriorClientIntact() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
            {"PoolName": "cognito-cfn-override-it", "UserPoolTags": {"floci:override-cognito-client-id": "use-name"}}
            """).path("UserPool").path("Id").asText();
        cloudFormation(OVERRIDE_STACK, "CreateStack", overrideClientTemplate(poolId, false));

        String stacks = describeStacks(OVERRIDE_STACK, "CREATE_COMPLETE");
        assertEquals("override-web", outputValue(stacks, "ClientId"), "the override makes the id the name");
        assertClientInPool(poolId, "override-web", "override-web");

        // GenerateSecret is createOnly, but the replacement would be created under the same id and
        // overwrite the client it replaces, so the update is refused and the stack rolls back.
        cloudFormation(OVERRIDE_STACK, "UpdateStack", overrideClientTemplate(poolId, true));

        stacks = describeStacks(OVERRIDE_STACK, "UPDATE_ROLLBACK_COMPLETE");
        String events = describeStackEvents(OVERRIDE_STACK);
        assertTrue(events.contains("already belongs to an existing client"), events);
        assertEquals("override-web", outputValue(stacks, "ClientId"));
        cognitoAction("DescribeUserPoolClient", "{\"UserPoolId\": \"" + poolId + "\", \"ClientId\": \"override-web\"}")
            .then()
            .statusCode(200)
            .body("UserPoolClient.ClientName", equalTo("override-web"))
            .body("UserPoolClient.ClientSecret", nullValue());

        // A second stack claiming the same derived id must not overwrite the first stack's client.
        cloudFormation(OVERRIDE_STACK + "-2", "CreateStack", overrideClientTemplate(poolId, false));
        describeStacks(OVERRIDE_STACK + "-2", "ROLLBACK_COMPLETE");
        assertClientInPool(poolId, "override-web", "override-web");
        cloudFormation(OVERRIDE_STACK + "-2", "DeleteStack", null);
        awaitStackDeleted(OVERRIDE_STACK + "-2");
        assertClientInPool(poolId, "override-web", "override-web");

        cloudFormation(OVERRIDE_STACK, "DeleteStack", null);
        awaitStackDeleted(OVERRIDE_STACK);
        assertClientIsGone(poolId, "override-web");
        cognitoAction("DeleteUserPool", "{\"UserPoolId\": \"" + poolId + "\"}").then().statusCode(200);
    }

    @Test
    void clientCreateOnlyChangesReplaceItAndARenameUpdatesItInPlace() throws Exception {
        cloudFormation(CLIENT_STACK, "CreateStack", clientTemplate("PoolA", false, "web"));

        String stacks = describeStacks(CLIENT_STACK, "CREATE_COMPLETE");
        String poolA = outputValue(stacks, "PoolA");
        String poolB = outputValue(stacks, "PoolB");
        String clientId = outputValue(stacks, "ClientId");
        assertEquals("web", outputValue(stacks, "Name"));
        assertEquals("cognito-idp.us-east-1.amazonaws.com/" + poolA, outputValue(stacks, "ProviderName"));
        assertClientInPool(poolA, clientId, "web");

        // UserPoolId is createOnly: the client is created in the new pool and the old one removed.
        cloudFormation(CLIENT_STACK, "UpdateStack", clientTemplate("PoolB", false, "web"));

        stacks = describeStacks(CLIENT_STACK, "UPDATE_COMPLETE");
        String moved = outputValue(stacks, "ClientId");
        assertNotEquals(clientId, moved, "a pool move must be a replacement");
        assertClientInPool(poolB, moved, "web");
        assertClientIsGone(poolA, clientId);

        // GenerateSecret is createOnly too: the replacement has a secret, the displaced client goes.
        cloudFormation(CLIENT_STACK, "UpdateStack", clientTemplate("PoolB", true, "web"));

        stacks = describeStacks(CLIENT_STACK, "UPDATE_COMPLETE");
        String withSecret = outputValue(stacks, "ClientId");
        assertNotEquals(moved, withSecret, "a GenerateSecret change must be a replacement");
        assertClientInPool(poolB, withSecret, "web");
        assertClientIsGone(poolB, moved);
        cognitoAction("DescribeUserPoolClient", "{\"UserPoolId\": \"" + poolB + "\", \"ClientId\": \"" + withSecret + "\"}")
            .then()
            .statusCode(200)
            .body("UserPoolClient.ClientSecret", notNullValue());

        // ClientName is updatable: same client, new name.
        cloudFormation(CLIENT_STACK, "UpdateStack", clientTemplate("PoolB", true, "web-renamed"));

        stacks = describeStacks(CLIENT_STACK, "UPDATE_COMPLETE");
        assertEquals(withSecret, outputValue(stacks, "ClientId"), "a rename must update the client in place");
        assertEquals("web-renamed", outputValue(stacks, "Name"));
        assertClientInPool(poolB, withSecret, "web-renamed");

        // GenerateSecret back to false: a replacement again, and the new client carries no secret.
        cloudFormation(CLIENT_STACK, "UpdateStack", clientTemplate("PoolB", false, "web-renamed"));

        stacks = describeStacks(CLIENT_STACK, "UPDATE_COMPLETE");
        String withoutSecret = outputValue(stacks, "ClientId");
        assertNotEquals(withSecret, withoutSecret, "a GenerateSecret change must be a replacement");
        assertClientIsGone(poolB, withSecret);
        cognitoAction("DescribeUserPoolClient", "{\"UserPoolId\": \"" + poolB + "\", \"ClientId\": \"" + withoutSecret + "\"}")
            .then()
            .statusCode(200)
            .body("UserPoolClient.ClientSecret", nullValue());

        cloudFormation(CLIENT_STACK, "DeleteStack", null);
        awaitStackDeleted(CLIENT_STACK);

        assertClientIsGone(poolB, withoutSecret);
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolB + "\"}")
            .then()
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static void assertClientInPool(String poolId, String clientId, String name) {
        cognitoAction("DescribeUserPoolClient", "{\"UserPoolId\": \"" + poolId + "\", \"ClientId\": \"" + clientId + "\"}")
            .then()
            .statusCode(200)
            .body("UserPoolClient.UserPoolId", equalTo(poolId))
            .body("UserPoolClient.ClientName", equalTo(name));
    }

    private static void assertClientIsGone(String poolId, String clientId) {
        cognitoAction("DescribeUserPoolClient", "{\"UserPoolId\": \"" + poolId + "\", \"ClientId\": \"" + clientId + "\"}")
            .then()
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * A pool with deletion protection refuses its own delete, and the stack has to say so: on AWS
     * the resource and the stack report DELETE_FAILED with Cognito's reason, the pool stays, and
     * the stack can only go once an update has switched the protection off.
     */
    @Test
    void deletingAStackWhosePoolHasDeletionProtectionFailsAndLeavesThePoolUntilItIsDeactivated() throws Exception {
        String stack = "cognito-cfn-deletion-protection-it";
        cloudFormation(stack, "CreateStack", deletionProtectionTemplate("ACTIVE"));
        String stacks = describeStacks(stack, "CREATE_COMPLETE");
        String poolId = outputValue(stacks, "PoolId");
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .then().statusCode(200)
            .body("UserPool.DeletionProtection", equalTo("ACTIVE"));

        cloudFormation(stack, "DeleteStack", null);
        awaitStackStatus(stack, "DELETE_FAILED");
        String events = describeStackEvents(stack);
        assertTrue(events.contains("<ResourceStatus>DELETE_FAILED</ResourceStatus>"), events);
        assertTrue(events.contains("deletion protection is activated"), events);
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .then().statusCode(200)
            .body("UserPool.Id", equalTo(poolId));

        cloudFormation(stack, "UpdateStack", deletionProtectionTemplate("INACTIVE"));
        describeStacks(stack, "UPDATE_COMPLETE");
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .then().statusCode(200)
            .body("UserPool.DeletionProtection", equalTo("INACTIVE"));

        cloudFormation(stack, "DeleteStack", null);
        awaitStackDeleted(stack);
        cognitoAction("DescribeUserPool", "{\"UserPoolId\": \"" + poolId + "\"}")
            .then()
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String deletionProtectionTemplate(String deletionProtection) {
        return """
            {
              "AWSTemplateFormatVersion": "2010-09-09",
              "Resources": {
                "Pool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {"UserPoolName": "cognito-cfn-protected-pool", "DeletionProtection": "%s"}
                }
              },
              "Outputs": {"PoolId": {"Value": {"Ref": "Pool"}}}
            }
            """.formatted(deletionProtection);
    }

    private static void awaitStackStatus(String stack, String expectedStatus) throws InterruptedException {
        String body = null;
        for (int i = 0; i < 100; i++) {
            body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("<StackStatus>" + expectedStatus + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stack + " did not reach " + expectedStatus + " within the timeout: " + body);
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStackEvents(String stack) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .extract().asString();
    }

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted(String stack) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stack + " was not deleted within the timeout");
    }

    private static JsonNode describeDomain(String domain) throws Exception {
        return cognitoJson("DescribeUserPoolDomain", "{\"Domain\": \"" + domain + "\"}").path("DomainDescription");
    }

    private static void assertDomainIsGone(String domain) {
        cognitoAction("DescribeUserPoolDomain", "{\"Domain\": \"" + domain + "\"}")
            .then()
            .statusCode(200)
            .body("DomainDescription", equalTo(Map.of()));
    }

    private static void changeValidationRecord(String zoneId, JsonNode record, String action) {
        assertEquals("CNAME", record.path("Type").asText());
        given().contentType("application/xml").body("""
            <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
              <ChangeBatch><Changes><Change><Action>%s</Action><ResourceRecordSet>
                <Name>%s</Name><Type>CNAME</Type><TTL>60</TTL>
                <ResourceRecords><ResourceRecord><Value>%s</Value></ResourceRecord></ResourceRecords>
              </ResourceRecordSet></Change></Changes></ChangeBatch>
            </ChangeResourceRecordSetsRequest>
            """.formatted(action, record.path("Name").asText(), record.path("Value").asText()))
            .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset").then().statusCode(200);
    }

    private static void assertCertificateStatus(String certificateArn, String status) {
        awsAction("CertificateManager", "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
            .then()
            .statusCode(200)
            .body("Certificate.Status", equalTo(status));
    }

    private static void assertCertificateConsumers(String certificateArn, int count) {
        awsAction("CertificateManager", "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
            .then()
            .statusCode(200)
            .body("Certificate.InUseBy.size()", equalTo(count));
    }

    private static void assertCertificateIsGone(String certificateArn) {
        awsAction("CertificateManager", "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
            .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
