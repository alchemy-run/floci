package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private static final String ACCOUNT_ID = "123456789012";
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountService service;
    private OrganizationsService organizations;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        when(config.storage().persistentPath()).thenReturn("unused-account-memory");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("memory");
        StorageFactory storageFactory = new StorageFactory(config, access);
        organizations = new OrganizationsService(storageFactory, mapper, config);
        service = new AccountService(storageFactory, organizations);
    }

    @Test
    void ownAccountPutAndGetRoundTrip() {
        ObjectNode request = contactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        AlternateContact contact = service.getAlternateContact(ACCOUNT_ID, request);
        assertEquals("security@example.com", contact.getEmailAddress());
        assertEquals("SECURITY", contact.getAlternateContactType());
    }

    @Test
    void nonStringAccountIdReturnsSerializationException() {
        ObjectNode request = contactRequest();
        request.put("AccountId", 123456789012L);
        AwsException error = assertThrows(AwsException.class,
                () -> service.putAlternateContact(ACCOUNT_ID, request));
        assertEquals("SerializationException", error.getErrorCode());
    }

    @Test
    void clearRemovesContacts() {
        ObjectNode request = contactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        service.clear();
        AwsException error = assertThrows(AwsException.class,
                () -> service.getAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void standaloneMetadataUsesResolvedIdentityAndSurvivesRename() {
        Instant before = Instant.now();
        Map<String, Object> original = service.getAccountInformation(ACCOUNT_ID, mapper.createObjectNode());
        assertEquals(ACCOUNT_ID, original.get("AccountId"));
        assertEquals(ACCOUNT_ID, original.get("AccountName"));
        assertEquals("ACTIVE", original.get("AccountState"));
        assertFalse(Instant.parse((String) original.get("AccountCreatedDate")).isBefore(before));
        service.putAccountName(ACCOUNT_ID, mapper.createObjectNode().put("AccountName", "Local owner"));
        Map<String, Object> renamed = service.getAccountInformation(ACCOUNT_ID, mapper.createObjectNode());
        assertEquals("Local owner", renamed.get("AccountName"));
        assertEquals(original.get("AccountCreatedDate"), renamed.get("AccountCreatedDate"));
        assertEquals("222222222222", service.getAccountInformation("222222222222", mapper.createObjectNode())
                .get("AccountName"));
        for (String invalid : List.of("", "a".repeat(51), "<invalid>", "non-ascii-é", "name\n")) {
            assertError("ValidationException", () -> service.putAccountName(ACCOUNT_ID,
                    mapper.createObjectNode().put("AccountName", invalid)));
        }
        assertEquals(renamed, service.getAccountInformation(ACCOUNT_ID, mapper.createObjectNode()));
    }

    @Test
    void primaryContactRequiresExplicitConfigurationAndFullyReplacesOptionalFields() {
        assertError("ResourceNotFoundException", () -> service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()));
        ObjectNode request = primaryContactRequest();
        ((ObjectNode) request.get("ContactInformation")).put("CompanyName", "Owner company")
                .put("AddressLine2", "Suite 2").put("AddressLine3", "Floor 3")
                .put("DistrictOrCounty", "King").put("WebsiteUrl", "https://example.com");
        service.putContactInformation(ACCOUNT_ID, request);
        Map<String, String> original = service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode());
        assertEquals("Owner company", original.get("CompanyName"));
        assertError("ResourceNotFoundException", () -> service.getContactInformation("222222222222", mapper.createObjectNode()));
        assertThrows(UnsupportedOperationException.class, () -> original.put("FullName", "not persisted"));
        ObjectNode replacement = primaryContactRequest();
        ((ObjectNode) replacement.get("ContactInformation")).put("FullName", "New owner");
        service.putContactInformation(ACCOUNT_ID, replacement);
        Map<String, String> updated = service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode());
        assertEquals("New owner", updated.get("FullName"));
        for (String optional : List.of("CompanyName", "AddressLine2", "AddressLine3", "DistrictOrCounty", "WebsiteUrl")) {
            assertFalse(updated.containsKey(optional));
        }
        service.clear();
        assertError("ResourceNotFoundException", () -> service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()));
        assertEquals(ACCOUNT_ID, service.getAccountInformation(ACCOUNT_ID, mapper.createObjectNode()).get("AccountName"));
    }

    @Test
    void invalidContactWritesLeaveTheStoredContactUnchanged() {
        service.putContactInformation(ACCOUNT_ID, primaryContactRequest());
        Map<String, String> before = service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode());
        List<Consumer<ObjectNode>> invalidChanges = List.of(
                contact -> contact.remove("FullName"),
                contact -> contact.remove("StateOrRegion"),
                contact -> contact.put("CountryCode", "ZZ"),
                contact -> contact.put("PhoneNumber", "not-a-phone"),
                contact -> contact.put("AddressLine1", "x".repeat(61)),
                contact -> contact.put("City", 42),
                contact -> contact.put("CompanyName", ""),
                contact -> contact.put("PostalCode", "x".repeat(21)),
                contact -> contact.put("WebsiteUrl", "x".repeat(257)));
        for (Consumer<ObjectNode> change : invalidChanges) {
            ObjectNode request = primaryContactRequest();
            change.accept((ObjectNode) request.get("ContactInformation"));
            assertError("ValidationException", () -> service.putContactInformation(ACCOUNT_ID, request));
            assertEquals(before, service.getContactInformation(ACCOUNT_ID, mapper.createObjectNode()));
        }
    }

    @Test
    void organizationIdentityAndTrustedAccessRemainAuthoritative() {
        organizations.createOrganization(ACCOUNT_ID, "ALL");
        String member = organizations.createAccount(ACCOUNT_ID, "owner@example.com", "Member owner", Map.of(), false)
                .getAccountId();
        String delegate = organizations.createAccount(ACCOUNT_ID, "delegate@example.com", "Delegate", Map.of(), false)
                .getAccountId();
        ObjectNode target = mapper.createObjectNode().put("AccountId", member);
        assertError("AccessDeniedException", () -> service.getAccountInformation(ACCOUNT_ID, target));
        organizations.enableAWSServiceAccess(ACCOUNT_ID, "account.amazonaws.com");
        Map<String, Object> observed = service.getAccountInformation(ACCOUNT_ID, target);
        assertEquals("Member owner", observed.get("AccountName"));
        assertEquals(organizations.describeAccount(ACCOUNT_ID, member).getJoinedTimestamp().toString(),
                observed.get("AccountCreatedDate"));
        assertEquals(observed, service.getAccountInformation(member, mapper.createObjectNode()));
        assertFalse(service.getAccountInformation(ACCOUNT_ID, mapper.createObjectNode()).containsKey("AccountCreatedDate"));
        service.putAccountName(ACCOUNT_ID, target.deepCopy().put("AccountName", "Renamed member"));
        assertEquals("Renamed member", organizations.describeAccount(ACCOUNT_ID, member).getName());
        assertError("AccessDeniedException", () -> service.putAccountName(delegate,
                target.deepCopy().put("AccountName", "unauthorized")));
        organizations.registerDelegatedAdministrator(ACCOUNT_ID, delegate, "account.amazonaws.com");
        service.putContactInformation(delegate, primaryContactRequest().put("AccountId", member));
        assertEquals("Primary owner", service.getContactInformation(member, mapper.createObjectNode()).get("FullName"));
        assertEquals("ENABLED_BY_DEFAULT", service.getRegionOptStatus(delegate,
                target.deepCopy().put("RegionName", "us-east-1")).get("RegionOptStatus"));
        assertError("ValidationException", () -> service.getAccountInformation(ACCOUNT_ID,
                mapper.createObjectNode().put("AccountId", ACCOUNT_ID)));
        assertError("AccessDeniedException", () -> service.getContactInformation("222222222222", target));
        assertError("AccessDeniedException", () -> service.listRegions(ACCOUNT_ID,
                mapper.createObjectNode().put("AccountId", "222222222222")));
        organizations.deregisterDelegatedAdministrator(ACCOUNT_ID, delegate, "account.amazonaws.com");
        organizations.disableAWSServiceAccess(ACCOUNT_ID, "account.amazonaws.com");
        assertError("AccessDeniedException", () -> service.getContactInformation(delegate, target));
        assertError("AccessDeniedException", () -> service.putContactInformation(ACCOUNT_ID,
                primaryContactRequest().put("AccountId", member)));
    }

    @Test
    void listsOnlyAdvertisedRegionsWithFilteringPaginationAndValidation() {
        List<String> names = new ArrayList<>();
        ObjectNode request = mapper.createObjectNode().put("MaxResults", 4);
        for (int page = 0; page < AwsRegions.ALL.size(); page++) {
            JsonNode response = mapper.valueToTree(service.listRegions(ACCOUNT_ID, request));
            for (JsonNode region : response.get("Regions")) {
                names.add(region.get("RegionName").textValue());
                assertEquals("ENABLED_BY_DEFAULT", region.get("RegionOptStatus").textValue());
            }
            if (!response.has("NextToken")) {
                break;
            }
            request.put("NextToken", response.get("NextToken").textValue());
        }
        assertEquals(AwsRegions.ALL.stream().sorted().toList(), names);
        ObjectNode filter = mapper.createObjectNode();
        filter.putArray("RegionOptStatusContains").add("DISABLED");
        assertEquals(List.of(), service.listRegions(ACCOUNT_ID, filter).get("Regions"));
        filter.withArray("RegionOptStatusContains").add("ENABLED_BY_DEFAULT");
        assertEquals(AwsRegions.ALL.size(), mapper.valueToTree(service.listRegions(ACCOUNT_ID, filter)).get("Regions").size());
        for (int size : List.of(0, -1, 51)) {
            assertError("ValidationException", () -> service.listRegions(ACCOUNT_ID,
                    mapper.createObjectNode().put("MaxResults", size)));
        }
        assertError("ValidationException", () -> service.listRegions(ACCOUNT_ID,
                mapper.createObjectNode().put("MaxResults", 1.5)));
        assertError("ValidationException", () -> service.listRegions(ACCOUNT_ID,
                mapper.createObjectNode().put("NextToken", "%%%")));
        ObjectNode invalidFilter = mapper.createObjectNode();
        invalidFilter.putArray("RegionOptStatusContains").add("UNKNOWN");
        assertError("ValidationException", () -> service.listRegions(ACCOUNT_ID, invalidFilter));
        for (String region : List.of("us-east-1", "ap-east-1", "not-a-region")) {
            assertError("ValidationException", () -> service.rejectRegionChange(ACCOUNT_ID,
                    mapper.createObjectNode().put("RegionName", region)));
        }
        assertError("ValidationException", () -> service.getRegionOptStatus(ACCOUNT_ID,
                mapper.createObjectNode().put("RegionName", "ap-east-1")));
        assertEquals("ENABLED_BY_DEFAULT", service.getRegionOptStatus(ACCOUNT_ID,
                mapper.createObjectNode().put("RegionName", "us-east-1")).get("RegionOptStatus"));
    }

    private static void assertError(String code, Runnable action) {
        assertEquals(code, assertThrows(AwsException.class, action::run).getErrorCode());
    }

    private ObjectNode primaryContactRequest() {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("ContactInformation").put("FullName", "Primary owner")
                .put("AddressLine1", "123 Main Street").put("City", "Seattle")
                .put("StateOrRegion", "WA").put("PostalCode", "98101")
                .put("CountryCode", "US").put("PhoneNumber", "+12025550123");
        return request;
    }

    private ObjectNode contactRequest() {
        ObjectNode request = mapper.createObjectNode();
        request.put("AlternateContactType", "SECURITY");
        request.put("EmailAddress", "security@example.com");
        request.put("Name", "Security Team");
        request.put("PhoneNumber", "+1 555 0100");
        request.put("Title", "Security");
        return request;
    }
}
