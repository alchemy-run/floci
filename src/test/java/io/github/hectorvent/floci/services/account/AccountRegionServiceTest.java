package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRegionServiceTest {

    private static final String ACCOUNT = "000000000601";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccountRegionService service = new AccountRegionService(
            new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT));

    @Test
    void usEast1IsEnabledByDefault() throws Exception {
        AccountRegionService.RegionOpt status = service.getRegionOptStatus(
                objectMapper.readTree("{\"RegionName\":\"us-east-1\"}"));
        assertEquals("us-east-1", status.regionName());
        assertEquals(AccountRegionService.ENABLED_BY_DEFAULT, status.regionOptStatus());
    }

    @Test
    void optInRegionStartsDisabledAndEnablePersists() throws Exception {
        AccountRegionService.RegionOpt before = service.getRegionOptStatus(
                objectMapper.readTree("{\"RegionName\":\"ap-east-1\"}"));
        assertEquals(AccountRegionService.DISABLED, before.regionOptStatus());

        service.enableRegion(objectMapper.readTree("{\"RegionName\":\"ap-east-1\"}"));

        AccountRegionService.RegionOpt after = service.getRegionOptStatus(
                objectMapper.readTree("{\"RegionName\":\"ap-east-1\"}"));
        assertEquals(AccountRegionService.ENABLED, after.regionOptStatus());
    }

    @Test
    void disableOfEnabledByDefaultIsRejected() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.disableRegion(objectMapper.readTree("{\"RegionName\":\"us-east-1\"}")));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void listRegionsFiltersByStatus() throws Exception {
        AccountRegionService.RegionPage page = service.listRegions(
                objectMapper.readTree("{\"RegionOptStatusContains\":[\"ENABLED_BY_DEFAULT\"]}"));
        assertTrue(page.regions().stream().anyMatch(r -> "us-east-1".equals(r.regionName())));
        assertTrue(page.regions().stream().noneMatch(r -> "ap-east-1".equals(r.regionName())));
        assertNull(page.nextToken());
    }
}
