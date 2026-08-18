package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.KeyValueStore;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import io.github.hectorvent.floci.services.cloudfront.model.VpcOrigin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontServiceTest {

    private static final String ACCOUNT = "000000000000";

    private CloudFrontService serviceWithDomainSuffix(String domainSuffix) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var cloudFrontConfig = Mockito.mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(domainSuffix);

        return new CloudFrontService(storageFactory, config);
    }

    @Test
    void createDistributionUsesDefaultDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.net"),
                "Expected default suffix, got: " + dist.getDomainName());
    }

    @Test
    void createDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + dist.getDomainName());
    }

    @Test
    void createStreamingDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        StreamingDistribution sd = service.createStreamingDistribution(new StreamingDistribution());

        assertTrue(sd.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + sd.getDomainName());
    }

    @Test
    void createVpcOriginRejectsBogusArn() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        VpcOrigin origin = new VpcOrigin();
        origin.setName("probe");
        origin.setOriginEndpointArn("not-an-arn");
        AwsException error = assertThrows(AwsException.class, () -> service.createVpcOrigin(origin, Map.of()));
        assertEquals("InvalidArgument", error.getErrorCode());
    }

    @Test
    void keyValueStoreRoundTrip() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        KeyValueStore created = new KeyValueStore();
        created.setName("router-store");
        created.setComment("list");

        KeyValueStore stored = service.createKeyValueStore(created, Map.of());
        assertEquals("READY", stored.getStatus());
        assertTrue(stored.getArn().contains("key-value-store/"));

        KeyValueStore described = service.describeKeyValueStore("router-store");
        assertEquals(stored.getId(), described.getId());

        List<KeyValueStore> listed = service.listKeyValueStores(null, 100, null);
        assertTrue(listed.stream().anyMatch(s -> s.getId().equals(stored.getId())));

        KeyValueStore updated = service.updateKeyValueStore("router-store", stored.getEtag(), "updated");
        assertEquals("updated", updated.getComment());

        service.deleteKeyValueStore("router-store", updated.getEtag());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeKeyValueStore("router-store"));
        assertEquals("EntityNotFound", missing.getErrorCode());
    }

    @Test
    void createFunctionStoresKeyValueStoreAssociations() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        CloudFrontFunction fn = new CloudFrontFunction();
        fn.setName("assoc-fn");
        fn.setComment("request handler");
        fn.setKeyValueStoreArns(List.of("arn:aws:cloudfront::000000000000:key-value-store/abc"));

        CloudFrontFunction created = service.createFunction(fn);
        assertEquals(List.of("arn:aws:cloudfront::000000000000:key-value-store/abc"),
                created.getKeyValueStoreArns());
        assertEquals("arn:aws:cloudfront::000000000000:key-value-store/abc",
                service.describeFunction("assoc-fn", null).getKeyValueStoreArns().get(0));
    }
}
