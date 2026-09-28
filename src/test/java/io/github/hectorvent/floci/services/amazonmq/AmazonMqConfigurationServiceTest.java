package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.model.MqConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AmazonMqConfigurationServiceTest {

    private static final String ACTIVEMQ_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <broker xmlns="http://activemq.apache.org/schema/core"><plugins/></broker>
            """;

    private RegionResolver regionResolver;
    private AmazonMqConfigurationService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));
        regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        service = new AmazonMqConfigurationService(storageFactory, regionResolver);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void createSeedsDefaultRevisionOne() {
        MqConfiguration c = service.createConfiguration("cfg", "ActiveMQ", "5.18", null, Map.of("team", "a"));

        assertTrue(c.getId().startsWith("c-"));
        assertEquals("arn:aws:mq:us-east-1:000000000000:configuration:" + c.getId(), c.getArn());
        assertEquals("ACTIVEMQ", c.getEngineType());
        assertEquals("SIMPLE", c.getAuthenticationStrategy());
        assertEquals(1, c.latestRevision().getRevision());
        assertEquals("Auto-generated default for cfg on ActiveMQ 5.18", c.latestRevision().getDescription());
        String data = new String(Base64.getDecoder().decode(c.latestRevision().getData()), StandardCharsets.UTF_8);
        assertTrue(data.contains("<broker"));
        assertEquals("a", service.describeConfiguration(c.getId()).getTags().get("team"));
    }

    @Test
    void updatePublishesNewRevisionsAndKeepsOldOnes() {
        MqConfiguration c = service.createConfiguration("cfg", "ACTIVEMQ", "5.18", null, null);

        service.updateConfiguration(c.getId(), b64(ACTIVEMQ_XML), "v2");
        MqConfiguration updated = service.updateConfiguration(c.getId(), b64(ACTIVEMQ_XML), "v3");

        assertEquals(3, updated.latestRevision().getRevision());
        assertEquals("v2", service.describeConfigurationRevision(c.getId(), "2").getDescription());
        assertEquals(b64(ACTIVEMQ_XML), service.describeConfigurationRevision(c.getId(), "3").getData());
        assertEquals(3, service.listConfigurationRevisions(c.getId(), null, null).items().size());
    }

    @Test
    void updateRejectsMissingDataInvalidBase64AndMalformedActiveMqXml() {
        MqConfiguration c = service.createConfiguration("cfg", "ACTIVEMQ", null, null, null);

        assertEquals("BadRequestException",
                assertThrows(AwsException.class, () -> service.updateConfiguration(c.getId(), null, null)).getErrorCode());
        assertEquals("BadRequestException",
                assertThrows(AwsException.class, () -> service.updateConfiguration(c.getId(), "%%%", null)).getErrorCode());
        assertEquals("BadRequestException",
                assertThrows(AwsException.class,
                        () -> service.updateConfiguration(c.getId(), b64("<broker>"), null)).getErrorCode());
        assertEquals("BadRequestException",
                assertThrows(AwsException.class,
                        () -> service.updateConfiguration(c.getId(), b64("<beans/>"), null)).getErrorCode());
        assertEquals(1, service.describeConfiguration(c.getId()).latestRevision().getRevision());
    }

    @Test
    void rabbitMqDocumentsAreNotParsedAsXml() {
        MqConfiguration c = service.createConfiguration("rabbit", "RABBITMQ", null, null, null);
        MqConfiguration updated = service.updateConfiguration(c.getId(), b64("consumer_timeout = 60000\n"), null);
        assertEquals(2, updated.latestRevision().getRevision());
        assertEquals("3.13", updated.getEngineVersion());
    }

    @Test
    void missingConfigurationAndRevisionAreNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeConfiguration("c-00000000-0000-0000-0000-000000000000"));
        assertEquals("NotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());

        MqConfiguration c = service.createConfiguration("cfg", "ACTIVEMQ", null, null, null);
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.describeConfigurationRevision(c.getId(), "9")).getErrorCode());
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.describeConfigurationRevision(c.getId(), "latest")).getErrorCode());
    }

    @Test
    void deleteRemovesConfiguration() {
        MqConfiguration c = service.createConfiguration("cfg", "ACTIVEMQ", null, null, null);
        service.deleteConfiguration(c.getId());
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.deleteConfiguration(c.getId())).getErrorCode());
    }

    @Test
    void createValidatesInputsAndNameUniqueness() {
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.createConfiguration(null, "ACTIVEMQ", null, null, null)).getErrorCode());
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.createConfiguration("bad name!", "ACTIVEMQ", null, null, null)).getErrorCode());
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.createConfiguration("cfg", "KAFKA", null, null, null)).getErrorCode());
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.createConfiguration("cfg", "RABBITMQ", null, "LDAP", null)).getErrorCode());

        service.createConfiguration("cfg", "ACTIVEMQ", null, null, null);
        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createConfiguration("cfg", "ACTIVEMQ", null, null, null));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());
    }

    @Test
    void configurationsAreRegionScoped() {
        MqConfiguration east = service.createConfiguration("cfg", "ACTIVEMQ", null, null, null);

        when(regionResolver.getRegion()).thenReturn("eu-west-1");
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.describeConfiguration(east.getId())).getErrorCode());
        assertTrue(service.listConfigurations(null, null).items().isEmpty());
        // The same name is free in another region.
        MqConfiguration west = service.createConfiguration("cfg", "ACTIVEMQ", null, null, null);
        assertTrue(west.getArn().startsWith("arn:aws:mq:eu-west-1:"));
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.listTags(east.getArn())).getErrorCode());
    }

    @Test
    void tagsRoundTripOnConfigurationArn() {
        MqConfiguration c = service.createConfiguration("cfg", "ACTIVEMQ", null, null, Map.of("a", "1"));

        assertTrue(service.isConfigurationArn(c.getArn()));
        service.tagResource(c.getArn(), Map.of("b", "2"));
        service.untagResource(c.getArn(), List.of("a"));

        Map<String, String> tags = service.listTags(c.getArn());
        assertEquals(Map.of("b", "2"), tags);
    }

    @Test
    void brokerReferenceValidation() {
        MqConfiguration rabbit = service.createConfiguration("rabbit", "RABBITMQ", null, null, null);

        service.validateBrokerReference(rabbit.getId(), 1, "RabbitMQ");
        service.validateBrokerReference(rabbit.getId(), null, "RABBITMQ");
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.validateBrokerReference("c-missing", 1, "RABBITMQ")).getErrorCode());
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.validateBrokerReference(rabbit.getId(), 5, "RABBITMQ")).getErrorCode());
        assertEquals("BadRequestException", assertThrows(AwsException.class,
                () -> service.validateBrokerReference(rabbit.getId(), 1, "ACTIVEMQ")).getErrorCode());
    }
}
