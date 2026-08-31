package io.github.hectorvent.floci.services.dax;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dax.model.ParameterGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DaxServiceTest {

    private DaxService service;

    @BeforeEach
    void setUp() {
        service = new DaxService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                mock(EmulatorConfig.class),
                mock(RegionResolver.class),
                null);
    }

    @Test
    void describeMissingGroupThrowsParameterGroupNotFoundFault() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeParameterGroups(List.of("missing-group")));
        assertEquals("ParameterGroupNotFoundFault", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void createUpdateDeleteRoundTrip() {
        ParameterGroup created = service.createParameterGroup("pg1", "desc");
        assertEquals("pg1", created.getParameterGroupName());
        assertEquals("desc", created.getDescription());

        Map<String, String> defaults = service.describeParameters("pg1");
        assertEquals("300000", defaults.get("query-ttl-millis"));

        service.updateParameterGroup("pg1", Map.of("query-ttl-millis", "60000"));
        Map<String, String> updated = service.describeParameters("pg1");
        assertEquals("60000", updated.get("query-ttl-millis"));
        assertEquals("300000", updated.get("record-ttl-millis"));

        service.deleteParameterGroup("pg1");
        AwsException gone = assertThrows(AwsException.class,
                () -> service.describeParameterGroups(List.of("pg1")));
        assertEquals("ParameterGroupNotFoundFault", gone.getErrorCode());
    }

    @Test
    void duplicateCreateThrowsAlreadyExists() {
        service.createParameterGroup("pg1", null);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createParameterGroup("pg1", null));
        assertEquals("ParameterGroupAlreadyExistsFault", ex.getErrorCode());
    }

    @Test
    void cannotDeleteDefaultGroup() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteParameterGroup(DaxService.DEFAULT_PARAMETER_GROUP));
        assertEquals("InvalidParameterGroupStateFault", ex.getErrorCode());
    }
}
