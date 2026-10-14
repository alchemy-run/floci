package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iot.model.IotThingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Thing type deprecation and deletion as AWS enforces them: a type must be deprecated, and stay
 * deprecated for five minutes, before DeleteThingType accepts it. Also tagging by thing type and
 * thing group ARN.
 */
class IotThingTypeLifecycleTest {

    private static final String REGION = "us-east-1";
    private static final String TYPE = "sensor-type";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final ObjectMapper mapper = new ObjectMapper();
    private IotService service;

    @BeforeEach
    void setUp() {
        service = new IotServiceTestSupport(REGION, mock(FlociCertificateAuthority.class), true, clock).service;
        service.createThingType(TYPE, mapper.createObjectNode(), REGION);
    }

    @Test
    void deleteRequiresDeprecation() {
        AwsException error = assertThrows(AwsException.class, () -> service.deleteThingType(TYPE, REGION));
        assertEquals("InvalidRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void deleteIsRejectedUntilFiveMinutesAfterDeprecation() {
        service.deprecateThingType(TYPE, false, REGION);

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        AwsException error = assertThrows(AwsException.class, () -> service.deleteThingType(TYPE, REGION));
        assertEquals("InvalidRequestException", error.getErrorCode());

        clock.advance(Duration.ofSeconds(1));
        service.deleteThingType(TYPE, REGION);
        AwsException gone = assertThrows(AwsException.class, () -> service.describeThingType(TYPE, REGION));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    @Test
    void redeprecatingKeepsTheOriginalDeprecationDate() {
        service.deprecateThingType(TYPE, false, REGION);
        Instant first = service.describeThingType(TYPE, REGION).getDeprecatedDate();

        clock.advance(Duration.ofMinutes(3));
        service.deprecateThingType(TYPE, false, REGION);
        assertEquals(first, service.describeThingType(TYPE, REGION).getDeprecatedDate());

        clock.advance(Duration.ofMinutes(2));
        service.deleteThingType(TYPE, REGION);
    }

    @Test
    void undoDeprecateReactivatesTheType() {
        service.deprecateThingType(TYPE, false, REGION);
        AwsException blocked = assertThrows(AwsException.class,
                () -> service.createThing("typed-thing", Map.of(), TYPE, REGION));
        assertEquals("InvalidRequestException", blocked.getErrorCode());

        service.deprecateThingType(TYPE, true, REGION);
        IotThingType type = service.describeThingType(TYPE, REGION);
        assertFalse(type.isDeprecated());
        assertNull(type.getDeprecatedDate());
        assertEquals(TYPE, service.createThing("typed-thing", Map.of(), TYPE, REGION).getThingTypeName());
    }

    @Test
    void thingTypeAndThingGroupArnsAreTaggable() {
        String typeArn = service.describeThingType(TYPE, REGION).getThingTypeArn();
        String groupArn = service.createThingGroup("sensor-group", mapper.createObjectNode(), REGION).getThingGroupArn();
        assertTrue(typeArn.endsWith(":thingtype/" + TYPE));

        for (String arn : List.of(typeArn, groupArn)) {
            service.tagResource(arn, Map.of("purpose", "test", "owner", "iot"));
            assertEquals(Map.of("purpose", "test", "owner", "iot"), service.listTagsForResource(arn));
            service.untagResource(arn, List.of("purpose"));
            assertEquals(Map.of("owner", "iot"), service.listTagsForResource(arn));
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
