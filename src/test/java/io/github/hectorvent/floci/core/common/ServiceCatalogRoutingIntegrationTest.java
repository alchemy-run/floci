package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.cloudfront.CloudFrontKvsDataPlaneController;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontServingController;
import io.github.hectorvent.floci.services.cloudfront.edge.CloudFrontEdgeController;
import io.github.hectorvent.floci.services.lambda.durable.LambdaDurableController;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmController;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmEndpointProxyController;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsController;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaNetworkConnectorsController;
import io.github.hectorvent.floci.services.securityadmin.SecurityAdminController;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ServiceCatalogRoutingIntegrationTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @Test
    void targetResolutionExtractsMatchingPrefixAndAction() {
        ServiceCatalog.TargetMatch match = catalog.matchTarget("AWSEvents.PutEvents").orElseThrow();

        assertEquals("events", match.descriptor().externalKey());
        assertEquals("AWSEvents.", match.prefix());
        assertEquals("PutEvents", match.action());
    }

    @Test
    void dynamodbStreamsTargetUsesStreamsPrefix() {
        ServiceCatalog.TargetMatch match = catalog.matchTarget("DynamoDBStreams_20120810.DescribeStream").orElseThrow();

        assertEquals("dynamodb", match.descriptor().externalKey());
        assertEquals("DynamoDBStreams_20120810.", match.prefix());
        assertEquals("DescribeStream", match.action());
    }

    @Test
    void cborSdkServiceIdsResolveThroughCatalog() {
        assertEquals("states", catalog.byCborSdkServiceId("SFN").orElseThrow().externalKey());
        assertEquals("monitoring", catalog.byCborSdkServiceId("GraniteServiceVersion20100801").orElseThrow().externalKey());
    }

    @Test
    void rpcV2ServiceNamesDeriveFromTargetPrefixes() {
        // The smithy-rpc-v2 path segment is the service shape name == target prefix
        // without the trailing dot (what the AWS SDKs actually send on the wire).
        assertEquals("dynamodb", catalog.byCborSdkServiceId("DynamoDB_20120810").orElseThrow().externalKey());
        assertEquals("dynamodb", catalog.byCborSdkServiceId("DynamoDBStreams_20120810").orElseThrow().externalKey());
        assertEquals("kinesis", catalog.byCborSdkServiceId("Kinesis_20131202").orElseThrow().externalKey());
        assertEquals("sqs", catalog.byCborSdkServiceId("AmazonSQS").orElseThrow().externalKey());
        assertEquals("sns", catalog.byCborSdkServiceId("SNS_20100331").orElseThrow().externalKey());
        assertEquals("states", catalog.byCborSdkServiceId("AWSStepFunctions").orElseThrow().externalKey());
    }

    @Test
    void queryProtocolAliasesAreDeclaredOnDescriptors() {
        assertTrue(catalog.byCredentialScope("sesv2").orElseThrow().supportsProtocol(ServiceProtocol.QUERY));
        assertTrue(catalog.byCredentialScope("cognito-idp").orElseThrow().supportsProtocol(ServiceProtocol.QUERY));
    }

    @Test
    void rdsDataResolvesAsRestJsonByCredentialScope() {
        ServiceDescriptor descriptor = catalog.byCredentialScope("rds-data").orElseThrow();

        assertEquals("rds-data", descriptor.externalKey());
        assertEquals(ServiceProtocol.REST_JSON, descriptor.defaultProtocol());
        assertTrue(descriptor.supportsProtocol(ServiceProtocol.REST_JSON));
    }

    @Test
    void fisResolvesAsRestJsonByCredentialScope() {
        ServiceDescriptor descriptor = catalog.byCredentialScope("fis").orElseThrow();

        assertEquals("fis", descriptor.externalKey());
        assertEquals("fis", descriptor.storageKey());
        assertEquals(ServiceProtocol.REST_JSON, descriptor.defaultProtocol());
        assertTrue(descriptor.supportsProtocol(ServiceProtocol.REST_JSON));
    }

    @Test
    void sharedSecurityAdminRouteResolvesOnlyBySigningScope() {
        assertTrue(catalog.byResourceClass(SecurityAdminController.class).isEmpty());
        assertEquals("macie2", catalog.byCredentialScope("macie2").orElseThrow().externalKey());
        assertEquals("guardduty", catalog.byCredentialScope("guardduty").orElseThrow().externalKey());
    }

    @Test
    void lambdaDurableAndBothMicrovmApisResolveToLambda() {
        for (Class<?> controller : List.of(LambdaDurableController.class, MicrovmController.class,
                MicrovmEndpointProxyController.class, LambdaMicrovmsController.class,
                LambdaNetworkConnectorsController.class)) {
            assertEquals("lambda", catalog.byResourceClass(controller).orElseThrow().externalKey());
        }
    }

    @Test
    void cloudFrontServingAndForkDataPlanesStayRegistered() {
        assertEquals("cloudfront", catalog.byCredentialScope("cloudfront-keyvaluestore")
                .orElseThrow().externalKey());
        for (Class<?> controller : List.of(CloudFrontServingController.class,
                CloudFrontKvsDataPlaneController.class, CloudFrontEdgeController.class)) {
            assertEquals("cloudfront", catalog.byResourceClass(controller).orElseThrow().externalKey());
        }
    }

    @Test
    void stepFunctionsAlternateTargetAndCognitoIdentityStayRegistered() {
        assertEquals("states", catalog.matchTarget("AmazonStatesService.StartSyncExecution")
                .orElseThrow().descriptor().externalKey());
        assertEquals("cognito-identity", catalog.matchTarget("AWSCognitoIdentityService.GetId")
                .orElseThrow().descriptor().externalKey());
        assertEquals("cognitoidentity", catalog.byCredentialScope("cognito-identity")
                .orElseThrow().configKey());
        assertEquals(1, catalog.all().stream()
                .filter(descriptor -> "cognito-identity".equals(descriptor.externalKey())).count());
    }

    @Test
    void unknownTargetsRemainUnresolved() {
        assertTrue(catalog.matchTarget("UnknownService.DoThing").isEmpty());
    }
}
