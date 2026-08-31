package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.account.model.AccountInformation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountServiceTest {

    private static final String ACCOUNT = "000000000201";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccountService service = new AccountService(
            new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT));

    @Test
    void getWithoutAPriorPutReturnsTheDefaultName() throws Exception {
        AccountInformation info = service.getAccountInformation(objectMapper.readTree("{}"));
        assertEquals(ACCOUNT, info.getAccountId());
        assertEquals(AccountService.DEFAULT_ACCOUNT_NAME, info.getAccountName());
        assertEquals(AccountService.DEFAULT_ACCOUNT_STATE, info.getAccountState());
        assertEquals(AccountService.DEFAULT_CREATED_DATE, info.getAccountCreatedDate());
    }

    @Test
    void putThenGetRoundTripsTheAccountName() throws Exception {
        service.putAccountName(objectMapper.readTree("{\"AccountName\":\"acme-prod\"}"));
        AccountInformation info = service.getAccountInformation(objectMapper.readTree("{}"));
        assertEquals("acme-prod", info.getAccountName());
        assertEquals(ACCOUNT, info.getAccountId());
    }

    @Test
    void putRejectsAnOversizedName() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.putAccountName(objectMapper.readTree(
                        "{\"AccountName\":\"" + "x".repeat(51) + "\"}")));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void getContactInformationSeedsADefaultPrimaryContact() throws Exception {
        var contact = service.getContactInformation(objectMapper.readTree("{}"));
        assertEquals("Floci User", contact.getFullName());
        assertEquals("Floci", contact.getCompanyName());
        assertEquals("+12025550100", contact.getPhoneNumber());
    }

    @Test
    void putThenGetRoundTripsPrimaryContact() throws Exception {
        service.putContactInformation(objectMapper.readTree("""
                {
                  "ContactInformation": {
                    "FullName": "Alchemy Test",
                    "AddressLine1": "123 Any Street",
                    "City": "Seattle",
                    "StateOrRegion": "WA",
                    "PostalCode": "98101",
                    "CountryCode": "US",
                    "PhoneNumber": "+12025550100",
                    "CompanyName": "Alchemy"
                  }
                }
                """));
        var contact = service.getContactInformation(objectMapper.readTree("{}"));
        assertEquals("Alchemy Test", contact.getFullName());
        assertEquals("Alchemy", contact.getCompanyName());
        assertEquals("Seattle", contact.getCity());
    }
}
