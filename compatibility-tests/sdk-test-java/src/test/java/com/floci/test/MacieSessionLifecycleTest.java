package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.macie2.Macie2Client;
import software.amazon.awssdk.services.macie2.model.AccessDeniedException;
import software.amazon.awssdk.services.macie2.model.ConflictException;
import software.amazon.awssdk.services.macie2.model.GetMacieSessionResponse;
import software.amazon.awssdk.services.macie2.model.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Macie session lifecycle")
class MacieSessionLifecycleTest {
    private static final String ACCOUNT = "731000000001";
    private static final String OTHER = "731000000002";

    @Test
    @DisplayName("round-trips session metadata, rejects foreign scopes, and disables cleanly")
    void sessionLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Only changes isolated local emulator accounts");
        try (Macie2Client owner = client(ACCOUNT, Region.US_EAST_1);
             Macie2Client foreign = client(OTHER, Region.US_EAST_1);
             Macie2Client otherRegion = client(ACCOUNT, Region.US_WEST_2)) {
            assertThatThrownBy(() -> owner.getMacieSession(request -> {}))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> owner.enableMacie(request -> request.status("DISABLED")))
                    .isInstanceOf(ValidationException.class);
            owner.enableMacie(request -> request.status("ENABLED").findingPublishingFrequency("SIX_HOURS"));
            try {
                GetMacieSessionResponse initial = owner.getMacieSession(request -> {});
                assertThat(initial.statusAsString()).isEqualTo("ENABLED");
                assertThat(initial.findingPublishingFrequencyAsString()).isEqualTo("SIX_HOURS");
                assertThat(initial.createdAt()).isNotNull();
                assertThat(initial.updatedAt()).isEqualTo(initial.createdAt());
                assertThat(initial.serviceRole()).contains(ACCOUNT).endsWith("AWSServiceRoleForAmazonMacie");
                assertThatThrownBy(() -> owner.enableMacie(request -> {})).isInstanceOf(ConflictException.class);
                for (Macie2Client isolated : new Macie2Client[]{foreign, otherRegion}) {
                    assertThatThrownBy(() -> isolated.getMacieSession(request -> {}))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> isolated.updateMacieSession(request -> request.status("PAUSED")))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> isolated.disableMacie(request -> {}))
                            .isInstanceOf(AccessDeniedException.class);
                }
                owner.createMember(request -> request.account(account -> account
                        .accountId(OTHER).email("member@example.com")));
                assertThat(owner.listMembers(request -> request.onlyAssociated("false")).members()).hasSize(1);
                owner.updateMacieSession(request -> request.status("PAUSED")
                        .findingPublishingFrequency("FIFTEEN_MINUTES"));
                GetMacieSessionResponse paused = owner.getMacieSession(request -> {});
                assertThat(paused.statusAsString()).isEqualTo("PAUSED");
                assertThat(paused.findingPublishingFrequencyAsString()).isEqualTo("FIFTEEN_MINUTES");
                assertThat(paused.createdAt()).isEqualTo(initial.createdAt());
                assertThat(paused.updatedAt()).isAfterOrEqualTo(initial.updatedAt());
                assertThatThrownBy(() -> owner.updateMacieSession(request -> request.status("ENABLED")
                        .findingPublishingFrequency("INVALID"))).isInstanceOf(ValidationException.class);
                assertThat(owner.getMacieSession(request -> {}).statusAsString()).isEqualTo("PAUSED");
                owner.updateMacieSession(request -> request.status("ENABLED"));
                assertThat(owner.getMacieSession(request -> {}).findingPublishingFrequencyAsString())
                        .isEqualTo("FIFTEEN_MINUTES");
                owner.disableMacie(request -> {});
                assertThatThrownBy(() -> owner.getMacieSession(request -> {}))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> owner.disableMacie(request -> {}))
                        .isInstanceOf(AccessDeniedException.class);
                owner.enableMacie(request -> request.status("PAUSED").findingPublishingFrequency("ONE_HOUR"));
                assertThat(owner.getMacieSession(request -> {}).findingPublishingFrequencyAsString())
                        .isEqualTo("ONE_HOUR");
                assertThat(owner.listMembers(request -> request.onlyAssociated("false")).members()).isEmpty();
            } finally {
                try {
                    owner.disableMacie(request -> {});
                } catch (AccessDeniedException expected) {
                    // A failure after the explicit disable leaves no session to clean up.
                }
            }
        }
    }

    @Test
    @DisplayName("tests identifier regex and proximity over signed SDK HTTP requests")
    void customDataIdentifierRuntimeUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Only changes isolated local emulator accounts");
        try (Macie2Client owner = client(ACCOUNT, Region.US_EAST_1);
             Macie2Client foreign = client(OTHER, Region.US_EAST_1);
             Macie2Client otherRegion = client(ACCOUNT, Region.US_WEST_2)) {
            owner.enableMacie(request -> {});
            try {
                assertThat(owner.testCustomDataIdentifier(request -> request.regex("EMP-[0-9]{8}")
                        .sampleText("ids EMP-12345678 and EMP-87654321 but not EMP-123")).matchCount()).isEqualTo(2);
                assertThat(owner.testCustomDataIdentifier(request -> request.regex("EMP-[0-9]{8}")
                        .sampleText("Employee EMP-12345678").keywords("employee").maximumMatchDistance(13))
                        .matchCount()).isEqualTo(1);
                assertThat(owner.testCustomDataIdentifier(request -> request.regex("EMP-[0-9]{8}")
                        .sampleText("Employee EMP-12345678").keywords("employee").maximumMatchDistance(12))
                        .matchCount()).isZero();
                assertThat(owner.testCustomDataIdentifier(request -> request.regex("EMP-[0-9]{8}")
                        .sampleText("EMP-12345678 EMP-87654321").ignoreWords("EMP-1234"))
                        .matchCount()).isEqualTo(1);
                assertThatThrownBy(() -> owner.testCustomDataIdentifier(request -> request.regex("[").sampleText("x")))
                        .isInstanceOf(ValidationException.class);
                for (Macie2Client isolated : new Macie2Client[]{foreign, otherRegion}) {
                    assertThatThrownBy(() -> isolated.testCustomDataIdentifier(
                            request -> request.regex("EMP-[0-9]{8}").sampleText("EMP-12345678")))
                            .isInstanceOf(AccessDeniedException.class);
                }
                assertThat(owner.testCustomDataIdentifier(request -> request.regex("EMP-[0-9]{8}")
                        .sampleText("no identifiers")).matchCount()).isZero();
                assertThat(owner.getAdministratorAccount(request -> {}).administrator()).isNull();
                assertThat(owner.listInvitations(request -> {}).invitations()).isEmpty();
                assertThat(owner.getInvitationsCount(request -> {}).invitationsCount()).isZero();
                assertThatThrownBy(() -> foreign.listInvitations(request -> {}))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> otherRegion.getAdministratorAccount(request -> {}))
                        .isInstanceOf(AccessDeniedException.class);
                owner.createSampleFindings(request -> {});
                assertThat(owner.listFindings(request -> {}).findingIds()).isNotEmpty();
            } finally {
                owner.disableMacie(request -> {});
            }
        }
    }

    private static Macie2Client client(String account, Region region) {
        return Macie2Client.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
