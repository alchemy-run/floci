package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.detective.DetectiveClient;
import software.amazon.awssdk.services.detective.model.ConflictException;
import software.amazon.awssdk.services.detective.model.DatasourcePackage;
import software.amazon.awssdk.services.detective.model.DatasourcePackageIngestDetail;
import software.amazon.awssdk.services.detective.model.DatasourcePackageIngestState;
import software.amazon.awssdk.services.detective.model.Graph;
import software.amazon.awssdk.services.detective.model.ResourceNotFoundException;
import software.amazon.awssdk.services.detective.model.ValidationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Detective graph lifecycle")
class DetectiveGraphLifecycleTest {
    private static final String ACCOUNT = "732000000001";
    private static final String OTHER = "732000000002";

    @Test
    @DisplayName("creates, lists, retags, and deletes graphs with account and region isolation")
    void graphLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Only changes isolated local emulator accounts");
        try (DetectiveClient owner = client(ACCOUNT, Region.US_EAST_1);
             DetectiveClient foreign = client(OTHER, Region.US_EAST_1);
             DetectiveClient otherRegion = client(ACCOUNT, Region.US_WEST_2)) {
            assertThat(owner.listGraphs(request -> {}).graphList()).isEmpty();
            assertThatThrownBy(() -> owner.createGraph(request -> request.tags(Map.of("", "invalid"))))
                    .isInstanceOf(ValidationException.class);
            String arn = owner.createGraph(request -> request.tags(Map.of("env", "test", "obsolete", "value")))
                    .graphArn();
            try {
                assertThat(arn).startsWith("arn:aws:detective:us-east-1:" + ACCOUNT + ":graph:");
                assertThat(owner.listGraphs(request -> request.maxResults(1)).graphList()).hasSize(1);
                Graph graph = owner.listGraphs(request -> {}).graphList().getFirst();
                assertThat(graph.arn()).isEqualTo(arn);
                assertThat(graph.createdTime()).isNotNull();
                assertThat(owner.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .containsEntry("env", "test");
                assertThatThrownBy(() -> owner.createGraph(request -> {})).isInstanceOf(ConflictException.class);
                for (DetectiveClient isolated : new DetectiveClient[]{foreign, otherRegion}) {
                    assertThat(isolated.listGraphs(request -> {}).graphList()).isEmpty();
                    assertMissing(isolated, arn);
                }
                assertMissing(owner, "arn:aws:detective:us-east-1:" + ACCOUNT
                        + ":graph:ffffffffffffffffffffffffffffffff");
                owner.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "prod", "team", "security")));
                owner.untagResource(request -> request.resourceArn(arn).tagKeys("obsolete", "absent"));
                assertThat(owner.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .containsExactlyInAnyOrderEntriesOf(Map.of("env", "prod", "team", "security"));
                Graph updated = owner.listGraphs(request -> {}).graphList().getFirst();
                assertThat(updated.arn()).isEqualTo(arn);
                assertThat(updated.createdTime()).isEqualTo(graph.createdTime());
                assertThatThrownBy(() -> owner.tagResource(request -> request.resourceArn(arn)
                        .tags(Map.of("x".repeat(129), "invalid")))).isInstanceOf(ValidationException.class);
                assertThatThrownBy(() -> owner.deleteGraph(request -> request.graphArn("bad")))
                        .isInstanceOf(ValidationException.class);
                owner.deleteGraph(request -> request.graphArn(arn));
                assertMissing(owner, arn);
                assertThat(owner.listGraphs(request -> {}).graphList()).isEmpty();
                String replacement = owner.createGraph(request -> {}).graphArn();
                try {
                    assertThat(replacement).isNotEqualTo(arn);
                    assertMissing(owner, arn);
                    assertThat(owner.listTagsForResource(request -> request.resourceArn(replacement)).tags()).isEmpty();
                    assertThat(owner.listGraphs(request -> {}).graphList()).singleElement()
                            .satisfies(current -> assertThat(current.arn()).isEqualTo(replacement));
                } finally {
                    owner.deleteGraph(request -> request.graphArn(replacement));
                }
            } finally {
                try {
                    owner.deleteGraph(request -> request.graphArn(arn));
                } catch (ResourceNotFoundException expected) {
                    // The explicit delete may already have removed this generation.
                }
            }
        }
    }

    @Test
    @DisplayName("reads actual members and invitations without inventing ingestion or investigations")
    void graphBindingMetadataUsesSignedAwsSdkRequests() {
        assumeFalse(TestFixtures.isRealAws(), "Only changes isolated local emulator accounts");
        try (DetectiveClient owner = client(ACCOUNT, Region.US_EAST_1);
             DetectiveClient invited = client(OTHER, Region.US_EAST_1);
             DetectiveClient unrelated = client("732000000003", Region.US_EAST_1);
             DetectiveClient otherRegion = client(OTHER, Region.US_WEST_2)) {
            String arn = owner.createGraph(request -> {}).graphArn();
            try {
                var absent = owner.getMembers(request -> request.graphArn(arn).accountIds(OTHER));
                assertThat(absent.memberDetails()).isEmpty();
                assertThat(absent.unprocessedAccounts()).singleElement()
                        .satisfies(account -> assertThat(account.accountId()).isEqualTo(OTHER));
                owner.createMembers(request -> request.graphArn(arn).accounts(
                        software.amazon.awssdk.services.detective.model.Account.builder()
                                .accountId(OTHER).emailAddress("member@example.com").build()));
                var found = owner.getMembers(request -> request.graphArn(arn).accountIds(OTHER, "732000000003"));
                assertThat(found.memberDetails()).singleElement().satisfies(member -> {
                    assertThat(member.accountId()).isEqualTo(OTHER);
                    assertThat(member.statusAsString()).isEqualTo("INVITED");
                    assertThat(member.invitedTime()).isNotNull();
                });
                assertThat(found.unprocessedAccounts()).singleElement()
                        .satisfies(account -> assertThat(account.accountId()).isEqualTo("732000000003"));
                assertThat(invited.listInvitations(request -> request.maxResults(1)).invitations())
                        .singleElement().satisfies(invitation -> {
                            assertThat(invitation.graphArn()).isEqualTo(arn);
                            assertThat(invitation.administratorId()).isEqualTo(ACCOUNT);
                            assertThat(invitation.invitationTypeAsString()).isEqualTo("INVITATION");
                        });
                for (DetectiveClient isolated : new DetectiveClient[]{owner, unrelated, otherRegion}) {
                    assertThat(isolated.listInvitations(request -> {}).invitations()).isEmpty();
                }
                for (DetectiveClient isolated : new DetectiveClient[]{invited, unrelated, otherRegion}) {
                    assertThatThrownBy(() -> isolated.getMembers(request -> request.graphArn(arn).accountIds(OTHER)))
                            .isInstanceOf(ResourceNotFoundException.class);
                    assertThatThrownBy(() -> isolated.listInvestigations(request -> request.graphArn(arn)))
                            .isInstanceOf(ResourceNotFoundException.class);
                }
                assertThat(owner.listInvestigations(request -> request.graphArn(arn)).investigationDetails()).isEmpty();
                DatasourcePackageIngestDetail core = owner.listDatasourcePackages(request -> request.graphArn(arn)
                        .maxResults(1)).datasourcePackages().get(DatasourcePackage.DETECTIVE_CORE);
                assertThat(core.datasourcePackageIngestState()).isEqualTo(DatasourcePackageIngestState.STARTED);
                assertThat(core.lastIngestStateChange().get(DatasourcePackageIngestState.STARTED).timestamp()).isNotNull();
                assertThat(owner.listDatasourcePackages(request -> request.graphArn(arn)).datasourcePackages()
                        .get(DatasourcePackage.DETECTIVE_CORE)).isEqualTo(core);
                assertThatThrownBy(() -> owner.listDatasourcePackages(request -> request.graphArn(arn).nextToken("invented")))
                        .isInstanceOf(ValidationException.class);
                for (DetectiveClient isolated : new DetectiveClient[]{invited, unrelated, otherRegion}) {
                    assertThatThrownBy(() -> isolated.listDatasourcePackages(request -> request.graphArn(arn)))
                            .isInstanceOf(ResourceNotFoundException.class);
                }
                assertThatThrownBy(() -> owner.startInvestigation(request -> request.graphArn(arn)
                        .entityArn("arn:aws:iam::" + ACCOUNT + ":role/test")
                        .scopeStartTime(java.time.Instant.parse("2026-09-20T00:00:00Z"))
                        .scopeEndTime(java.time.Instant.parse("2026-09-21T00:00:00Z"))))
                        .isInstanceOf(ValidationException.class).hasMessageContaining("does not support Detective");
                assertThatThrownBy(() -> owner.getInvestigation(request -> request.graphArn(arn)
                        .investigationId("00000000000000000000000000000000")))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThat(owner.listInvestigations(request -> request.graphArn(arn)).investigationDetails()).isEmpty();
            } finally {
                owner.deleteGraph(request -> request.graphArn(arn));
            }
            assertThat(invited.listInvitations(request -> {}).invitations()).isEmpty();
        }
    }

    private static void assertMissing(DetectiveClient client, String arn) {
        assertThatThrownBy(() -> client.listTagsForResource(request -> request.resourceArn(arn)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> client.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "foreign"))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> client.untagResource(request -> request.resourceArn(arn).tagKeys("env")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> client.deleteGraph(request -> request.graphArn(arn)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static DetectiveClient client(String account, Region region) {
        return DetectiveClient.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
