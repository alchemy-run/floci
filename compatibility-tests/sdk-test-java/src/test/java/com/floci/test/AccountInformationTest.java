package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.account.model.AccessDeniedException;
import software.amazon.awssdk.services.account.model.ContactInformation;
import software.amazon.awssdk.services.account.model.GetAccountInformationResponse;
import software.amazon.awssdk.services.account.model.ListRegionsResponse;
import software.amazon.awssdk.services.account.model.ResourceNotFoundException;
import software.amazon.awssdk.services.account.model.ValidationException;
import software.amazon.awssdk.services.organizations.OrganizationsClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("AWS Account identity, primary contacts, and region catalog")
class AccountInformationTest {
    private static final String OWNER = "722000000001";
    private static final String OTHER = "722000000002";
    private static final String MANAGEMENT = "722000000003";

    @Test
    @DisplayName("signed SDK requests rename accounts and replace primary contacts across signing regions")
    void signedAccountAndPrimaryContactLifecycle() {
        assumeFalse(TestFixtures.isRealAws(), "Writes isolated local emulator account metadata");
        try (AccountClient owner = client(OWNER, Region.US_EAST_1);
             AccountClient west = client(OWNER, Region.US_WEST_2);
             AccountClient other = client(OTHER, Region.US_EAST_1)) {
            GetAccountInformationResponse original = owner.getAccountInformation(request -> {});
            assertThat(original.accountId()).isEqualTo(OWNER);
            assertThat(original.accountStateAsString()).isEqualTo("ACTIVE");
            assertThat(original.accountCreatedDate()).isNotNull();
            assertThat(original.accountName()).isNotBlank();
            ContactInformation previous = null;
            try {
                previous = owner.getContactInformation(request -> {}).contactInformation();
            } catch (ResourceNotFoundException expected) {
                // A local account has no primary contact until an owner supplies one.
            }
            ContactInformation contact = ContactInformation.builder().fullName("SDK account owner")
                    .addressLine1("1 Main Street").addressLine2("Suite 2").addressLine3("Floor 3")
                    .city("Seattle").stateOrRegion("WA").districtOrCounty("King")
                    .postalCode("98101").countryCode("US").phoneNumber("+12025550123")
                    .companyName("SDK owner company").websiteUrl("https://example.com").build();
            try {
                owner.putAccountName(request -> request.accountName("Renamed SDK account"));
                GetAccountInformationResponse renamed = west.getAccountInformation(request -> {});
                assertThat(renamed.accountName()).isEqualTo("Renamed SDK account");
                assertThat(renamed.accountCreatedDate()).isEqualTo(original.accountCreatedDate());
                assertThat(other.getAccountInformation(request -> {}).accountName()).isEqualTo(OTHER);
                owner.putContactInformation(request -> request.contactInformation(contact));
                assertThat(west.getContactInformation(request -> {}).contactInformation()).isEqualTo(contact);
                ContactInformation replacement = ContactInformation.builder().fullName("Replacement owner")
                        .addressLine1("2 Main Street").city("Paris").postalCode("75001")
                        .countryCode("FR").phoneNumber("+33155550123").build();
                west.putContactInformation(request -> request.contactInformation(replacement));
                assertThat(owner.getContactInformation(request -> {}).contactInformation()).isEqualTo(replacement);
                assertThatThrownBy(() -> owner.putContactInformation(request -> request.contactInformation(
                        replacement.toBuilder().countryCode("ZZ").build())))
                        .isInstanceOf(ValidationException.class);
                assertThat(owner.getContactInformation(request -> {}).contactInformation()).isEqualTo(replacement);
                assertThatThrownBy(() -> owner.putAccountName(request -> request.accountName("<invalid>")))
                        .isInstanceOf(ValidationException.class);
                assertThat(owner.getAccountInformation(request -> {}).accountName()).isEqualTo("Renamed SDK account");
                assertThatThrownBy(() -> other.getContactInformation(request -> {}))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> other.getAccountInformation(request -> request.accountId(OWNER)))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> other.putAccountName(request -> request.accountId(OWNER).accountName("forbidden")))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> other.getContactInformation(request -> request.accountId(OWNER)))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> other.putContactInformation(request -> request.accountId(OWNER)
                        .contactInformation(contact))).isInstanceOf(AccessDeniedException.class);
            } finally {
                owner.putAccountName(request -> request.accountName(original.accountName()));
                if (previous != null) {
                    ContactInformation restore = previous;
                    owner.putContactInformation(request -> request.contactInformation(restore));
                }
            }
            assertThat(owner.getAccountInformation(request -> {}).accountName()).isEqualTo(original.accountName());
        }
    }

    @Test
    @DisplayName("signed region reads paginate, opt-in regions enable and disable, and invalid requests are rejected")
    void signedRegionCatalogPaginationAndNegativeRequests() {
        assumeFalse(TestFixtures.isRealAws(), "Enables and disables an opt-in region on an emulator account");
        try (AccountClient owner = client(OWNER, Region.US_EAST_1)) {
            ListRegionsResponse all = owner.listRegions(request -> request.maxResults(50));
            List<String> expected = all.regions().stream().map(region -> region.regionName()).toList();
            assertThat(expected).contains("us-east-1", "ap-east-1").doesNotHaveDuplicates().isSorted();
            // A fresh account has every default region enabled and every opt-in region disabled.
            assertThat(all.regions()).allMatch(region -> "ENABLED_BY_DEFAULT".equals(region.regionOptStatusAsString())
                    || "DISABLED".equals(region.regionOptStatusAsString()));
            List<String> paged = new ArrayList<>();
            String next = null;
            for (int page = 0; page < 50; page++) {
                String token = next;
                ListRegionsResponse response = owner.listRegions(request -> request.maxResults(2).nextToken(token));
                response.regions().forEach(region -> paged.add(region.regionName()));
                next = response.nextToken();
                if (next == null) {
                    break;
                }
            }
            assertThat(next).isNull();
            assertThat(paged).containsExactlyElementsOf(expected);
            List<String> disabled = owner.listRegions(request -> request.regionOptStatusContainsWithStrings("DISABLED"))
                    .regions().stream().map(region -> region.regionName()).toList();
            List<String> enabledByDefault = owner.listRegions(request -> request
                    .regionOptStatusContainsWithStrings("ENABLED_BY_DEFAULT"))
                    .regions().stream().map(region -> region.regionName()).toList();
            assertThat(disabled).contains("ap-east-1").doesNotContain("us-east-1");
            assertThat(enabledByDefault).contains("us-east-1").doesNotContain("ap-east-1");
            assertThat(disabled.size() + enabledByDefault.size()).isEqualTo(expected.size());
            assertThat(owner.getRegionOptStatus(request -> request.regionName("us-east-1")).regionOptStatusAsString())
                    .isEqualTo("ENABLED_BY_DEFAULT");
            assertThat(owner.getRegionOptStatus(request -> request.regionName("ap-east-1")).regionOptStatusAsString())
                    .isEqualTo("DISABLED");
            assertThatThrownBy(() -> owner.listRegions(request -> request.maxResults(0)))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> owner.listRegions(request -> request.maxResults(51)))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> owner.listRegions(request -> request.nextToken("%%%")))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> owner.listRegions(request -> request.regionOptStatusContainsWithStrings("UNKNOWN")))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> owner.getRegionOptStatus(request -> request.regionName("xx-nowhere-1")))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> owner.enableRegion(request -> request.regionName("us-east-1")))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> owner.disableRegion(request -> request.regionName("us-east-1")))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> owner.listRegions(request -> request.accountId(OTHER)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> owner.getRegionOptStatus(request -> request.accountId(OTHER).regionName("us-east-1")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> owner.enableRegion(request -> request.accountId(OTHER).regionName("ap-east-1")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> owner.disableRegion(request -> request.accountId(OTHER).regionName("us-east-1")))
                    .isInstanceOf(AccessDeniedException.class);

            owner.enableRegion(request -> request.regionName("ap-east-1"));
            assertThat(owner.getRegionOptStatus(request -> request.regionName("ap-east-1")).regionOptStatusAsString())
                    .isIn("ENABLING", "ENABLED");
            awaitRegionStatus(owner, "ap-east-1", "ENABLED");
            owner.disableRegion(request -> request.regionName("ap-east-1"));
            assertThat(owner.getRegionOptStatus(request -> request.regionName("ap-east-1")).regionOptStatusAsString())
                    .isIn("DISABLING", "DISABLED");
            awaitRegionStatus(owner, "ap-east-1", "DISABLED");
            assertThat(owner.listRegions(request -> request.maxResults(50)).regions()).isEqualTo(all.regions());
        }
    }

    private static void awaitRegionStatus(AccountClient client, String region, String status) {
        String observed = null;
        for (int attempt = 0; attempt < 10 && !status.equals(observed); attempt++) {
            observed = client.getRegionOptStatus(request -> request.regionName(region)).regionOptStatusAsString();
        }
        assertThat(observed).isEqualTo(status);
    }

    @Test
    @DisplayName("signed Account APIs reuse Organizations identity and require trusted access for member writes")
    void signedOrganizationIdentityAndAuthorization() {
        assumeFalse(TestFixtures.isRealAws(), "Creates an isolated emulator organization");
        try (OrganizationsClient organizations = OrganizationsClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1).credentialsProvider(credentials(MANAGEMENT)).build();
             AccountClient management = client(MANAGEMENT, Region.US_EAST_1)) {
            organizations.createOrganization(request -> request.featureSet("ALL"));
            String member = null;
            try {
                member = organizations.createAccount(request -> request.email("account-sdk-member@example.com")
                        .accountName("SDK member")).createAccountStatus().accountId();
                String target = member;
                assertThatThrownBy(() -> management.getAccountInformation(request -> request.accountId(target)))
                        .isInstanceOf(AccessDeniedException.class);
                organizations.enableAWSServiceAccess(request -> request.servicePrincipal("account.amazonaws.com"));
                GetAccountInformationResponse initial = management.getAccountInformation(request -> request.accountId(target));
                assertThat(initial.accountName()).isEqualTo("SDK member");
                assertThat(initial.accountCreatedDate().toEpochMilli())
                        .isEqualTo(organizations.describeAccount(request -> request.accountId(target))
                                .account().joinedTimestamp().toEpochMilli());
                management.putAccountName(request -> request.accountId(target).accountName("Renamed SDK member"));
                assertThat(organizations.describeAccount(request -> request.accountId(target)).account().name())
                        .isEqualTo("Renamed SDK member");
                try (AccountClient self = client(target, Region.US_WEST_2)) {
                    assertThat(self.getAccountInformation(request -> {}).accountName()).isEqualTo("Renamed SDK member");
                    assertThatThrownBy(() -> self.getAccountInformation(request -> request.accountId(MANAGEMENT)))
                            .isInstanceOf(AccessDeniedException.class);
                }
                assertThatThrownBy(() -> management.getAccountInformation(request -> request.accountId(MANAGEMENT)))
                        .isInstanceOf(ValidationException.class);
                organizations.disableAWSServiceAccess(request -> request.servicePrincipal("account.amazonaws.com"));
                assertThatThrownBy(() -> management.putAccountName(request -> request.accountId(target).accountName("denied")))
                        .isInstanceOf(AccessDeniedException.class);
            } finally {
                if (member != null) {
                    String target = member;
                    organizations.removeAccountFromOrganization(request -> request.accountId(target));
                }
                organizations.deleteOrganization();
            }
        }
    }

    private static AccountClient client(String accountId, Region region) {
        return AccountClient.builder().endpointOverride(TestFixtures.endpoint()).region(region)
                .credentialsProvider(credentials(accountId)).build();
    }

    private static StaticCredentialsProvider credentials(String accountId) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accountId, "test"));
    }
}
