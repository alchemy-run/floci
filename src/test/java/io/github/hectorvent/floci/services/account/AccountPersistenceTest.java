package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AccountPersistenceTest {
    private static final String OWNER = "721000000001";
    private static final String OTHER = "721000000002";
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void identityContactsAndOrganizationRenameSurviveReloadWithoutCrossAccountLeakage() {
        String member;
        Map<String, Object> originalIdentity;
        Map<String, String> originalContact;
        try (Fixture first = open()) {
            originalIdentity = first.account().getAccountInformation(OWNER, mapper.createObjectNode());
            first.account().putAccountName(OWNER, mapper.createObjectNode().put("AccountName", "Persisted owner"));
            ObjectNode contact = mapper.createObjectNode();
            contact.putObject("ContactInformation").put("FullName", "Persisted contact")
                    .put("AddressLine1", "1 Main Street").put("City", "Paris")
                    .put("PostalCode", "75001").put("CountryCode", "FR").put("PhoneNumber", "+33155550123");
            first.account().putContactInformation(OWNER, contact);
            originalContact = first.account().getContactInformation(OWNER, mapper.createObjectNode());
            first.organizations().createOrganization(OTHER, "ALL");
            first.organizations().enableAWSServiceAccess(OTHER, "account.amazonaws.com");
            member = first.organizations().createAccount(OTHER, "member@example.com", "Member", Map.of(), false)
                    .getAccountId();
            first.account().putAccountName(OTHER, mapper.createObjectNode()
                    .put("AccountId", member).put("AccountName", "Persisted member"));
            ObjectNode alternate = mapper.createObjectNode().put("AlternateContactType", "SECURITY")
                    .put("EmailAddress", "security@example.com").put("Name", "Security owner")
                    .put("PhoneNumber", "+12025550123").put("Title", "Security");
            first.account().putAlternateContact(OWNER, alternate);
        }
        try (Fixture reloaded = open()) {
            Map<String, Object> identity = reloaded.account().getAccountInformation(OWNER, mapper.createObjectNode());
            assertEquals("Persisted owner", identity.get("AccountName"));
            assertEquals(originalIdentity.get("AccountCreatedDate"), identity.get("AccountCreatedDate"));
            assertEquals(originalContact, reloaded.account().getContactInformation(OWNER, mapper.createObjectNode()));
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> reloaded.account().getContactInformation(OTHER, mapper.createObjectNode())).getErrorCode());
            assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                    () -> reloaded.account().getContactInformation(OTHER,
                            mapper.createObjectNode().put("AccountId", OWNER))).getErrorCode());
            assertEquals("Persisted member", reloaded.organizations().describeAccount(OTHER, member).getName());
            assertEquals("Persisted member", reloaded.account().getAccountInformation(member, mapper.createObjectNode())
                    .get("AccountName"));
            assertEquals(reloaded.organizations().describeAccount(OTHER, member).getJoinedTimestamp().toString(),
                    reloaded.account().getAccountInformation(member, mapper.createObjectNode()).get("AccountCreatedDate"));
            assertEquals("security@example.com", reloaded.account().getAlternateContact(OWNER,
                    mapper.createObjectNode().put("AlternateContactType", "SECURITY")).getEmailAddress());
            reloaded.account().clear();
        }
        try (Fixture cleared = open()) {
            assertEquals(OWNER, cleared.account().getAccountInformation(OWNER, mapper.createObjectNode()).get("AccountName"));
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> cleared.account().getContactInformation(OWNER, mapper.createObjectNode())).getErrorCode());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> cleared.account().getAlternateContact(OWNER,
                            mapper.createObjectNode().put("AlternateContactType", "SECURITY"))).getErrorCode());
            assertEquals("Persisted member", cleared.organizations().describeAccount(OTHER, member).getName());
        }
    }

    private Fixture open() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(OWNER);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, access);
        OrganizationsService organizations = new OrganizationsService(factory, mapper, config);
        return new Fixture(factory, organizations, new AccountService(factory, organizations));
    }

    private record Fixture(StorageFactory factory, OrganizationsService organizations, AccountService account)
            implements AutoCloseable {
        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}
