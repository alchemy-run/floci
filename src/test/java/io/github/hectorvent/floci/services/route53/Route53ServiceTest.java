package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.QueryLoggingConfig;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53ServiceTest {

    private Route53Service service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Route53ServiceConfig route53 = Mockito.mock(EmulatorConfig.Route53ServiceConfig.class);
        Mockito.when(config.services()).thenReturn(services);
        Mockito.when(services.route53()).thenReturn(route53);
        Mockito.when(route53.defaultNameserver1()).thenReturn("ns-1.awsdns-01.org");
        Mockito.when(route53.defaultNameserver2()).thenReturn("ns-2.awsdns-02.net");
        Mockito.when(route53.defaultNameserver3()).thenReturn("ns-3.awsdns-03.com");
        Mockito.when(route53.defaultNameserver4()).thenReturn("ns-4.awsdns-04.co.uk");

        service = new Route53Service(storageFactory, config);
    }

    @Test
    void listHostedZonesByName_maxItems1ReturnsExactPublicZone() {
        service.createHostedZone("zzz.aaa.", "ref-lex-first", null, false, null, null);
        service.createHostedZone("aaa.zzz.", "ref-dns-first", null, false, null, null);
        service.createHostedZone("alchemy-ecs-domain-test.example", "ref-target",
                null, false, null, null);

        List<HostedZone> listed = service.listHostedZonesByName(
                "alchemy-ecs-domain-test.example.", 1);

        assertEquals(1, listed.size());
        assertEquals("alchemy-ecs-domain-test.example.", listed.get(0).getName());
        assertTrue(!listed.get(0).isPrivateZone());
    }

    @Test
    void listHostedZonesByName_subdomainStartStillFindsParentOnSecondLookup() {
        service.createHostedZone("alchemy-ecs-domain-test.example", "ref-parent",
                null, false, null, null);

        List<HostedZone> subdomain = service.listHostedZonesByName(
                "svc.alchemy-ecs-domain-test.example.", 1);
        assertTrue(subdomain.isEmpty()
                || !subdomain.get(0).getName().equals("svc.alchemy-ecs-domain-test.example."));

        List<HostedZone> parent = service.listHostedZonesByName(
                "alchemy-ecs-domain-test.example.", 1);
        assertEquals(1, parent.size());
        assertEquals("alchemy-ecs-domain-test.example.", parent.get(0).getName());
    }

    @Test
    void changeResourceRecordSets_roundTripsWeightedAndFailover() {
        var created = service.createHostedZone("alchemy-route53-routing.alchemy.",
                "ref-routing", null, false, null, null);
        String zoneId = created.zone().getId();

        service.changeResourceRecordSets(zoneId, List.of(
                change("CREATE", weighted("api.alchemy-route53-routing.alchemy.", "blue", 90, "1.2.3.4")),
                change("CREATE", weighted("api.alchemy-route53-routing.alchemy.", "green", 10, "5.6.7.8")),
                change("CREATE", failover("app.alchemy-route53-routing.alchemy.", "primary",
                        "PRIMARY", "hc-1", "1.1.1.1")),
                change("CREATE", failover("app.alchemy-route53-routing.alchemy.", "secondary",
                        "SECONDARY", null, "2.2.2.2"))
        ), "routing");

        List<ResourceRecordSet> sets = service.listResourceRecordSets(zoneId, null, null, 100);
        ResourceRecordSet blue = findSet(sets, "api.alchemy-route53-routing.alchemy.", "blue");
        ResourceRecordSet green = findSet(sets, "api.alchemy-route53-routing.alchemy.", "green");
        ResourceRecordSet primary = findSet(sets, "app.alchemy-route53-routing.alchemy.", "primary");
        ResourceRecordSet secondary = findSet(sets, "app.alchemy-route53-routing.alchemy.", "secondary");

        assertEquals(90L, blue.getWeight());
        assertEquals(10L, green.getWeight());
        assertEquals(60L, blue.getTtl());
        assertEquals("PRIMARY", primary.getFailover());
        assertEquals("hc-1", primary.getHealthCheckId());
        assertEquals("SECONDARY", secondary.getFailover());

        service.changeResourceRecordSets(zoneId, List.of(
                change("DELETE", weighted("api.alchemy-route53-routing.alchemy.", "blue", 90, "1.2.3.4")),
                change("DELETE", weighted("api.alchemy-route53-routing.alchemy.", "green", 10, "5.6.7.8")),
                change("DELETE", failover("app.alchemy-route53-routing.alchemy.", "primary",
                        "PRIMARY", "hc-1", "1.1.1.1")),
                change("DELETE", failover("app.alchemy-route53-routing.alchemy.", "secondary",
                        "SECONDARY", null, "2.2.2.2"))
        ), "cleanup");
    }

    @Test
    void createQueryLoggingConfig_secondCreateIsAlreadyExists() {
        var created = service.createHostedZone("alchemy-test-qlc.alchemy.",
                "ref-qlc", null, false, null, null);
        QueryLoggingConfig first = service.createQueryLoggingConfig(
                created.zone().getId(),
                "arn:aws:logs:us-east-1:000000000000:log-group:/aws/route53/a");
        assertEquals(created.zone().getId(), first.getHostedZoneId());

        AwsException error = assertThrows(AwsException.class, () ->
                service.createQueryLoggingConfig(
                        "/hostedzone/" + created.zone().getId(),
                        "arn:aws:logs:us-east-1:000000000000:log-group:/aws/route53/b"));
        assertEquals("QueryLoggingConfigAlreadyExists", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
    }

    @Test
    void getChange_acceptsPrefixedId() {
        var created = service.createHostedZone("change-prefix.example.",
                "ref-change", null, false, null, null);
        assertEquals("INSYNC", service.getChange("/change/" + created.change().getId()).getStatus());
        assertEquals("INSYNC", service.getChange(created.change().getId()).getStatus());
    }

    @Test
    void compareDnsNames_parentBeforeChild() {
        assertTrue(Route53Service.compareDnsNames(
                "alchemy-ecs-domain-test.example.",
                "svc.alchemy-ecs-domain-test.example.") < 0);
        assertEquals(0, Route53Service.compareDnsNames(
                "alchemy-ecs-domain-test.example",
                "alchemy-ecs-domain-test.example."));
    }

    private static Map<String, Object> change(String action, ResourceRecordSet rrs) {
        Map<String, Object> change = new HashMap<>();
        change.put("action", action);
        change.put("rrs", rrs);
        return change;
    }

    private static ResourceRecordSet weighted(String name, String setId, long weight, String ip) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType("A");
        rrs.setTtl(60L);
        rrs.setSetIdentifier(setId);
        rrs.setWeight(weight);
        rrs.setRecords(List.of(new ResourceRecord(ip)));
        return rrs;
    }

    private static ResourceRecordSet failover(String name, String setId, String failover,
                                              String healthCheckId, String ip) {
        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType("A");
        rrs.setTtl(60L);
        rrs.setSetIdentifier(setId);
        rrs.setFailover(failover);
        rrs.setHealthCheckId(healthCheckId);
        rrs.setRecords(List.of(new ResourceRecord(ip)));
        return rrs;
    }

    private static ResourceRecordSet findSet(List<ResourceRecordSet> sets, String name, String setId) {
        return sets.stream()
                .filter(s -> name.equals(s.getName()) && setId.equals(s.getSetIdentifier()))
                .findFirst()
                .orElseThrow();
    }
}
