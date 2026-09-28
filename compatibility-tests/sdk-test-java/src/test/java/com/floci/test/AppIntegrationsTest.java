package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appintegrations.AppIntegrationsClient;
import software.amazon.awssdk.services.appintegrations.model.AccessDeniedException;
import software.amazon.awssdk.services.appintegrations.model.CreateApplicationRequest;
import software.amazon.awssdk.services.appintegrations.model.CreateApplicationResponse;
import software.amazon.awssdk.services.appintegrations.model.CreateDataIntegrationRequest;
import software.amazon.awssdk.services.appintegrations.model.CreateDataIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.CreateEventIntegrationRequest;
import software.amazon.awssdk.services.appintegrations.model.CreateEventIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.GetApplicationResponse;
import software.amazon.awssdk.services.appintegrations.model.GetDataIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.GetEventIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.InvalidRequestException;
import software.amazon.awssdk.services.appintegrations.model.ListApplicationsResponse;
import software.amazon.awssdk.services.appintegrations.model.ListDataIntegrationsResponse;
import software.amazon.awssdk.services.appintegrations.model.ListEventIntegrationAssociationsResponse;
import software.amazon.awssdk.services.appintegrations.model.ListEventIntegrationsResponse;
import software.amazon.awssdk.services.appintegrations.model.ResourceNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Amazon AppIntegrations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppIntegrationsTest {

    private static AppIntegrationsClient appIntegrations;
    private static String eventIntegrationName;
    private static String eventIntegrationArn;
    private static String dataIntegrationName;
    private static String dataIntegrationId;
    private static String dataIntegrationArn;

    @BeforeAll
    static void setup() {
        appIntegrations = TestFixtures.appIntegrationsClient();
        long stamp = System.currentTimeMillis();
        eventIntegrationName = "sdk-test-event-integration-" + stamp;
        dataIntegrationName = "sdk-test-data-integration-" + stamp;
    }

    @AfterAll
    static void cleanup() {
        if (appIntegrations == null) {
            return;
        }
        if (eventIntegrationArn != null) {
            try {
                appIntegrations.deleteEventIntegration(r -> r.name(eventIntegrationName));
            } catch (RuntimeException e) {
                System.out.println("AppIntegrations cleanup skipped the event integration: " + e);
            }
        }
        if (dataIntegrationId != null) {
            try {
                appIntegrations.deleteDataIntegration(r -> r.dataIntegrationIdentifier(dataIntegrationId));
            } catch (RuntimeException e) {
                System.out.println("AppIntegrations cleanup skipped the data integration: " + e);
            }
        }
        appIntegrations.close();
    }

    @Test
    @Order(12)
    @DisplayName("Applications retain identity through updates, pagination, tags, and namespace replacement")
    void applicationLifecycleWithEncodedArnAndIdempotentCreate() {
        try (AppIntegrationsClient client = scopedClient("555555555555", Region.US_EAST_1)) {
            CreateApplicationRequest request = application("com.example.sdk.workspace", UUID.randomUUID().toString());
            CreateApplicationResponse created = client.createApplication(request);
            String arn = created.arn();
            String id = created.id();
            try {
                assertThat(client.createApplication(request).id()).isEqualTo(id);
                GetApplicationResponse observed = client.getApplication(r -> r.arn(arn));
                assertThat(observed.id()).isEqualTo(id);
                assertThat(observed.namespace()).isEqualTo(request.namespace());
                assertThat(observed.createdTime()).isNotNull();
                assertThat(observed.applicationSourceConfig().externalUrlConfig().accessUrl())
                        .isEqualTo("https://example.com");
                assertThat(client.listApplicationAssociations(r -> r.applicationId(id)).applicationAssociations()).isEmpty();
                assertThatThrownBy(() -> client.createApplication(request.toBuilder().name("different").build()))
                        .isInstanceOf(InvalidRequestException.class);
                client.updateApplication(r -> r.arn(arn).name("renamed").description("updated")
                        .permissions("User.Details.View")
                        .applicationSourceConfig(s -> s.externalUrlConfig(u -> u.accessUrl("https://updated.example.com")
                                .approvedOrigins("https://origin.example.com"))));
                client.tagResource(r -> r.resourceArn(arn).tags(Map.of("phase", "two")));
                client.untagResource(r -> r.resourceArn(arn).tagKeys("team"));
                GetApplicationResponse updated = client.getApplication(r -> r.arn(id));
                assertThat(updated.arn()).isEqualTo(arn);
                assertThat(updated.namespace()).isEqualTo(request.namespace());
                assertThat(updated.name()).isEqualTo("renamed");
                assertThat(updated.description()).isEqualTo("updated");
                assertThat(updated.permissions()).containsExactly("User.Details.View");
                assertThat(updated.createdTime()).isEqualTo(observed.createdTime());
                assertThat(updated.lastModifiedTime()).isAfterOrEqualTo(observed.lastModifiedTime());
                assertThat(updated.applicationSourceConfig().externalUrlConfig().approvedOrigins())
                        .containsExactly("https://origin.example.com");
                assertThat(updated.tags()).containsEntry("phase", "two").doesNotContainKey("team");
                assertThat(client.listTagsForResource(r -> r.resourceArn(arn)).tags()).isEqualTo(updated.tags());

                CreateApplicationResponse replacement = client.createApplication(
                        application("com.example.sdk.workspace.v2", UUID.randomUUID().toString()));
                try {
                    assertThat(replacement.id()).isNotEqualTo(id);
                    ListApplicationsResponse firstPage = client.listApplications(r -> r.maxResults(1));
                    assertThat(firstPage.applications()).hasSize(1);
                    assertThat(firstPage.nextToken()).isNotBlank();
                    ListApplicationsResponse secondPage = client.listApplications(r -> r.maxResults(1)
                            .nextToken(firstPage.nextToken()));
                    assertThat(secondPage.applications()).hasSize(1);
                    assertThat(secondPage.nextToken()).isNull();
                    assertThat(List.of(firstPage.applications().get(0).id(), secondPage.applications().get(0).id()))
                            .containsExactlyInAnyOrder(id, replacement.id());
                } finally {
                    client.deleteApplication(r -> r.arn(replacement.arn()));
                }
            } finally {
                client.deleteApplication(r -> r.arn(arn));
            }
            assertThatThrownBy(() -> client.getApplication(r -> r.arn(id))).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> client.listApplicationAssociations(r -> r.applicationId(id)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @Order(13)
    @DisplayName("Signed account and region scopes cannot alias application identities or tags")
    void signedApplicationScopesRemainIsolated() {
        try (AppIntegrationsClient owner = scopedClient("666666666666", Region.US_EAST_1);
             AppIntegrationsClient otherAccount = scopedClient("777777777777", Region.US_EAST_1);
             AppIntegrationsClient otherRegion = scopedClient("666666666666", Region.US_WEST_2)) {
            CreateApplicationRequest request = application("com.example.sdk.isolation", UUID.randomUUID().toString());
            CreateApplicationResponse first = owner.createApplication(request);
            try {
                assertThatThrownBy(() -> otherAccount.getApplication(r -> r.arn(first.id())))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> otherAccount.tagResource(r -> r.resourceArn(first.arn()).tags(Map.of("foreign", "true"))))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> otherRegion.getApplication(r -> r.arn(first.arn())))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> owner.getApplication(r -> r.arn(first.arn().replace("us-east-1", "us-west-2"))))
                        .isInstanceOf(ResourceNotFoundException.class);
                for (AppIntegrationsClient peer : List.of(otherAccount, otherRegion)) {
                    CreateApplicationResponse independent = peer.createApplication(request);
                    try {
                        assertThat(independent.id()).isNotEqualTo(first.id());
                        assertThat(peer.getApplication(r -> r.arn(independent.arn())).namespace()).isEqualTo(request.namespace());
                    } finally {
                        peer.deleteApplication(r -> r.arn(independent.arn()));
                    }
                }
                assertThat(owner.getApplication(r -> r.arn(first.id())).tags()).doesNotContainKey("foreign");
            } finally {
                owner.deleteApplication(r -> r.arn(first.arn()));
            }
        }
    }

    @Test
    @Order(14)
    @DisplayName("Integration create tokens replay and ordinary principals cannot start association jobs")
    void integrationIdempotencyAndAssociationAuthorizationUseTypedSdkErrors() {
        try (AppIntegrationsClient client = scopedClient("888888888888", Region.US_EAST_1)) {
            CreateDataIntegrationRequest request = CreateDataIntegrationRequest.builder()
                    .name("sdk-association-contract").kmsKey("key/abc").sourceURI("s3://source")
                    .clientToken(UUID.randomUUID().toString()).build();
            CreateDataIntegrationResponse data = client.createDataIntegration(request);
            try {
                assertThat(client.createDataIntegration(request).id()).isEqualTo(data.id());
                assertThat(client.listDataIntegrationAssociations(r -> r.dataIntegrationIdentifier(data.id()))
                        .dataIntegrationAssociations()).isEmpty();
                assertThatThrownBy(() -> client.createDataIntegrationAssociation(r -> r.dataIntegrationIdentifier(data.id())
                        .clientId("ordinary-client")))
                        .isInstanceOf(AccessDeniedException.class)
                        .hasMessageContaining("app-integrations:CreateDataIntegrationAssociation on resource: " + data.arn())
                        .hasMessageContaining("explicit deny in a resource-based policy");
                assertThatThrownBy(() -> client.updateDataIntegrationAssociation(r -> r.dataIntegrationIdentifier(data.arn())
                        .dataIntegrationAssociationIdentifier("00000000-0000-0000-0000-000000000001")
                        .executionConfiguration(c -> c.executionMode("ON_DEMAND"))))
                        .isInstanceOf(AccessDeniedException.class);
                assertThat(client.listDataIntegrationAssociations(r -> r.dataIntegrationIdentifier(data.arn()))
                        .dataIntegrationAssociations()).isEmpty();
            } finally {
                client.deleteDataIntegration(r -> r.dataIntegrationIdentifier(data.id()));
            }
            CreateEventIntegrationRequest event = CreateEventIntegrationRequest.builder()
                    .name("sdk-idempotent-event").eventBridgeBus("default")
                    .eventFilter(f -> f.source("aws.partner/example.com/source"))
                    .clientToken(UUID.randomUUID().toString()).build();
            CreateEventIntegrationResponse created = client.createEventIntegration(event);
            try {
                assertThat(client.createEventIntegration(event).eventIntegrationArn()).isEqualTo(created.eventIntegrationArn());
                assertThatThrownBy(() -> client.createEventIntegration(event.toBuilder().description("changed").build()))
                        .isInstanceOf(InvalidRequestException.class);
            } finally {
                client.deleteEventIntegration(r -> r.name(event.name()));
            }
        }
    }

    private static AppIntegrationsClient scopedClient(String account, Region region) {
        return AppIntegrationsClient.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private static CreateApplicationRequest application(String namespace, String token) {
        return CreateApplicationRequest.builder().name("SDK workspace").namespace(namespace).clientToken(token)
                .applicationSourceConfig(s -> s.externalUrlConfig(u -> u.accessUrl("https://example.com")))
                .tags(Map.of("team", "support")).build();
    }

    @Test
    @Order(1)
    void createEventIntegration() {
        CreateEventIntegrationResponse response = appIntegrations.createEventIntegration(r -> r
                .name(eventIntegrationName)
                .description("partner events")
                .eventBridgeBus("sdk-test-bus")
                .eventFilter(f -> f.source("aws.partner/example.com/1234"))
                .tags(Map.of("team", "data")));

        eventIntegrationArn = response.eventIntegrationArn();

        assertThat(eventIntegrationArn)
                .contains(":app-integrations:")
                .contains(":event-integration/" + eventIntegrationName);
    }

    @Test
    @Order(2)
    void getEventIntegration() {
        GetEventIntegrationResponse response = appIntegrations.getEventIntegration(r -> r
                .name(eventIntegrationName));

        assertThat(response.name()).isEqualTo(eventIntegrationName);
        assertThat(response.description()).isEqualTo("partner events");
        assertThat(response.eventIntegrationArn()).isEqualTo(eventIntegrationArn);
        assertThat(response.eventBridgeBus()).isEqualTo("sdk-test-bus");
        assertThat(response.eventFilter().source()).isEqualTo("aws.partner/example.com/1234");
        assertThat(response.tags()).containsEntry("team", "data");
    }

    @Test
    @Order(3)
    void listEventIntegrations() {
        ListEventIntegrationsResponse response = appIntegrations.listEventIntegrations(r -> { });

        assertThat(response.eventIntegrations()).anySatisfy(integration -> {
            assertThat(integration.name()).isEqualTo(eventIntegrationName);
            assertThat(integration.eventIntegrationArn()).isEqualTo(eventIntegrationArn);
            assertThat(integration.eventBridgeBus()).isEqualTo("sdk-test-bus");
        });
    }

    @Test
    @Order(4)
    void updateEventIntegrationDescription() {
        appIntegrations.updateEventIntegration(r -> r
                .name(eventIntegrationName)
                .description("partner events, revised"));

        assertThat(appIntegrations.getEventIntegration(r -> r.name(eventIntegrationName)).description())
                .isEqualTo("partner events, revised");
    }

    /**
     * Associations are written by the consuming service, so a fresh integration reports none.
     * The call still has to deserialize, which is what pins the wire member name.
     */
    @Test
    @Order(5)
    void listEventIntegrationAssociationsIsEmpty() {
        ListEventIntegrationAssociationsResponse response = appIntegrations
                .listEventIntegrationAssociations(r -> r.eventIntegrationName(eventIntegrationName));

        assertThat(response.eventIntegrationAssociations()).isEmpty();
    }

    @Test
    @Order(6)
    void createDataIntegration() {
        CreateDataIntegrationResponse response = appIntegrations.createDataIntegration(r -> r
                .name(dataIntegrationName)
                .description("salesforce pull")
                .kmsKey("arn:aws:kms:us-east-1:000000000000:key/abc")
                .sourceURI("Salesforce://AppFlow/test")
                .scheduleConfig(s -> s
                        .scheduleExpression("rate(1 hour)")
                        .firstExecutionFrom("1439788800000")
                        .object("Account"))
                .fileConfiguration(f -> f.folders(List.of("/home/data")))
                .tags(Map.of("team", "data")));

        dataIntegrationId = response.id();
        dataIntegrationArn = response.arn();

        assertThat(dataIntegrationId).isNotBlank();
        assertThat(dataIntegrationArn)
                .contains(":app-integrations:")
                .contains(":data-integration/" + dataIntegrationId);
        assertThat(response.name()).isEqualTo(dataIntegrationName);
        assertThat(response.kmsKey()).isEqualTo("arn:aws:kms:us-east-1:000000000000:key/abc");
        assertThat(response.sourceURI()).isEqualTo("Salesforce://AppFlow/test");
        assertThat(response.scheduleConfiguration().scheduleExpression()).isEqualTo("rate(1 hour)");
        assertThat(response.fileConfiguration().folders()).containsExactly("/home/data");
        assertThat(response.tags()).containsEntry("team", "data");
    }

    @Test
    @Order(7)
    void getDataIntegrationByIdAndByArn() {
        GetDataIntegrationResponse byId = appIntegrations.getDataIntegration(r -> r
                .identifier(dataIntegrationId));

        assertThat(byId.id()).isEqualTo(dataIntegrationId);
        assertThat(byId.arn()).isEqualTo(dataIntegrationArn);
        assertThat(byId.description()).isEqualTo("salesforce pull");
        assertThat(byId.scheduleConfiguration().firstExecutionFrom()).isEqualTo("1439788800000");
        assertThat(byId.scheduleConfiguration().object()).isEqualTo("Account");

        assertThat(appIntegrations.getDataIntegration(r -> r.identifier(dataIntegrationArn)).id())
                .isEqualTo(dataIntegrationId);
    }

    @Test
    @Order(8)
    void listDataIntegrations() {
        ListDataIntegrationsResponse response = appIntegrations.listDataIntegrations(r -> { });

        assertThat(response.dataIntegrations()).anySatisfy(summary -> {
            assertThat(summary.name()).isEqualTo(dataIntegrationName);
            assertThat(summary.arn()).isEqualTo(dataIntegrationArn);
            assertThat(summary.sourceURI()).isEqualTo("Salesforce://AppFlow/test");
        });
    }

    @Test
    @Order(9)
    void updateDataIntegrationDescription() {
        appIntegrations.updateDataIntegration(r -> r
                .identifier(dataIntegrationId)
                .description("salesforce pull, revised"));

        assertThat(appIntegrations.getDataIntegration(r -> r.identifier(dataIntegrationId)).description())
                .isEqualTo("salesforce pull, revised");
    }

    @Test
    @Order(10)
    void tagRoundTripOnBothIntegrationTypes() {
        for (String arn : List.of(eventIntegrationArn, dataIntegrationArn)) {
            appIntegrations.tagResource(r -> r.resourceArn(arn).tags(Map.of("env", "test")));

            assertThat(appIntegrations.listTagsForResource(r -> r.resourceArn(arn)).tags())
                    .containsEntry("team", "data")
                    .containsEntry("env", "test");

            appIntegrations.untagResource(r -> r.resourceArn(arn).tagKeys("env"));

            assertThat(appIntegrations.listTagsForResource(r -> r.resourceArn(arn)).tags())
                    .containsEntry("team", "data")
                    .doesNotContainKey("env");
        }
    }

    @Test
    @Order(11)
    void deleteBothIntegrationsThenGetThrowsResourceNotFound() {
        appIntegrations.deleteEventIntegration(r -> r.name(eventIntegrationName));
        appIntegrations.deleteDataIntegration(r -> r.dataIntegrationIdentifier(dataIntegrationId));

        assertThatThrownBy(() -> appIntegrations.getEventIntegration(r -> r.name(eventIntegrationName)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> appIntegrations.getDataIntegration(r -> r.identifier(dataIntegrationId)))
                .isInstanceOf(ResourceNotFoundException.class);

        eventIntegrationArn = null;
        dataIntegrationId = null;
    }
}
