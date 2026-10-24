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
    void deleteRemovesContactAndDeletingUnsetThrows() {
        ObjectNode request = contactRequest();
        service.putAlternateContact(ACCOUNT_ID, request);
        service.deleteAlternateContact(ACCOUNT_ID, request);
        AwsException getError = assertThrows(AwsException.class,
                () -> service.getAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", getError.getErrorCode());
        AwsException deleteError = assertThrows(AwsException.class,
                () -> service.deleteAlternateContact(ACCOUNT_ID, request));
        assertEquals("ResourceNotFoundException", deleteError.getErrorCode());
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
