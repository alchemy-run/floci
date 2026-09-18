package io.github.hectorvent.floci.services.route53;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import io.github.hectorvent.floci.services.route53.model.ZoneVpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Route53VpcAuthorizationPersistenceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void importsMissingAuthorizationsPreservingCanonicalOwnersAndLaterDeletions(@TempDir Path directory)
            throws Exception {
        String legacy = """
                {
                  "ZLEGACY/vpc-new":{"vpcId":"vpc-new","vpcRegion":"us-east-1"},
                  "ZLEGACY/vpc-kept":{"vpcId":"vpc-kept","vpcRegion":"us-east-1"}
                }
                """;
        Path source = directory.resolve("route53-vpc-auth.json");
        Files.writeString(source, legacy);
        Files.writeString(directory.resolve("route53-zones.json"), """
                {"ZLEGACY":{"id":"ZLEGACY","name":"legacy.example.","callerReference":"legacy",
                  "privateZone":true,"vpcs":[{"vpcId":"vpc-initial","vpcRegion":"us-east-1"}]}}
                """);
        Files.writeString(directory.resolve("route53-vpc-association-authorizations.json"), """
                {"ZLEGACY":[
                  {"vpcId":"vpc-kept","vpcRegion":"us-east-1","ownerAccountId":"111111111111"},
                  {"vpcId":"vpc-canonical","vpcRegion":"eu-west-1","ownerAccountId":"222222222222"}]}
                """);
        Fixture first = fixture(directory);
        Route53Service service = first.service();
        List<VpcAssociation> authorizations = service.listVpcAssociationAuthorizations("ZLEGACY");
        assertEquals(3, authorizations.size());
        assertEquals(OTHER_ACCOUNT, authorization(authorizations, "vpc-kept").getOwnerAccountId());
        assertEquals("222222222222", authorization(authorizations, "vpc-canonical").getOwnerAccountId());
        assertNull(authorization(authorizations, "vpc-new").getOwnerAccountId());
        assertEquals("vpc-initial", service.getHostedZone("ZLEGACY").getVpcAssociations().getFirst().getVpcId());
        first.service();
        assertEquals(3, service.listVpcAssociationAuthorizations("ZLEGACY").size());
        service.deleteVpcAssociationAuthorization("ZLEGACY", new VpcAssociation("vpc-new", REGION));
        first.factory().shutdownAll();

        Fixture second = fixture(directory);
        Route53Service restarted = second.service();
        List<VpcAssociation> remaining = restarted.listVpcAssociationAuthorizations("ZLEGACY");
        assertEquals(2, remaining.size());
        assertTrue(remaining.stream().noneMatch(vpc -> "vpc-new".equals(vpc.getVpcId())));
        assertEquals(OTHER_ACCOUNT, authorization(remaining, "vpc-kept").getOwnerAccountId());
        restarted.deleteVpcAssociationAuthorization("ZLEGACY", new VpcAssociation("vpc-kept", REGION));
        restarted.deleteVpcAssociationAuthorization("ZLEGACY", new VpcAssociation("vpc-canonical", "eu-west-1"));
        second.factory().shutdownAll();

        Fixture third = fixture(directory);
        assertTrue(third.service().listVpcAssociationAuthorizations("ZLEGACY").isEmpty());
        third.factory().shutdownAll();
        assertLegacyPreserved(legacy, source);
    }

    @Test
    void importsScopedAndUnscopedEntriesWithoutCrossAccountOrRegionMixing(@TempDir Path directory) throws Exception {
        String legacy = """
                {
                  "ZSCOPED/vpc-shared":{"vpcId":"vpc-shared","vpcRegion":"us-east-1"},
                  "000000000000/ZSCOPED/vpc-shared":{"vpcId":"vpc-shared","vpcRegion":"eu-west-1"},
                  "111111111111/ZSCOPED/vpc-shared":{"vpcId":"vpc-shared","vpcRegion":"ap-south-1"}
                }
                """;
        Files.writeString(directory.resolve("route53-vpc-auth.json"), legacy);
        Files.writeString(directory.resolve("route53-zones.json"), MAPPER.writeValueAsString(Map.of(
                "ZSCOPED", zone("ZSCOPED"), OTHER_ACCOUNT + "/ZSCOPED", zone("ZSCOPED"))));
        Fixture first = fixture(directory);
        Route53Service service = first.service();
        assertEquals("eu-west-1", service.listVpcAssociationAuthorizations("ZSCOPED").getFirst().getVpcRegion());
        assertEquals("ap-south-1", first.authorizations().getForAccount(OTHER_ACCOUNT, "ZSCOPED")
                .orElseThrow().getFirst().getVpcRegion());
        assertEquals(2, first.authorizations().scanAllAccountEntries(k -> true).size());
        first.factory().shutdownAll();

        Fixture restarted = fixture(directory);
        restarted.service();
        assertEquals(1, restarted.authorizations().getForAccount(ACCOUNT, "ZSCOPED").orElseThrow().size());
        assertEquals(1, restarted.authorizations().getForAccount(OTHER_ACCOUNT, "ZSCOPED").orElseThrow().size());
        restarted.factory().shutdownAll();
        assertLegacyPreserved(legacy, directory.resolve("route53-vpc-auth.json"));
    }

    @Test
    void keepsMalformedOrOrphanedSourcesWithoutGrantingForeignZoneAccess(@TempDir Path directory) throws Exception {
        String legacy = """
                {
                  "ZMISSING/vpc-orphan":{"vpcId":"vpc-orphan","vpcRegion":"us-east-1"},
                  "ZFOREIGN/vpc-foreign":{"vpcId":"vpc-foreign","vpcRegion":"us-east-1"},
                  "ZVALID/vpc-wrong-key":{"vpcId":"vpc-different","vpcRegion":"us-east-1"},
                  "ZVALID/vpc-no-region":{"vpcId":"vpc-no-region"}
                }
                """;
        Files.writeString(directory.resolve("route53-vpc-auth.json"), legacy);
        HostedZone foreign = zone("ZFOREIGN");
        foreign.setOwnerAccountId(OTHER_ACCOUNT);
        Files.writeString(directory.resolve("route53-zones.json"), MAPPER.writeValueAsString(Map.of(
                "ZFOREIGN", foreign, "ZVALID", zone("ZVALID"))));
        Fixture first = fixture(directory);
        first.service();
        assertTrue(first.authorizations().scanAllAccountEntries(k -> true).isEmpty());
        first.factory().create("route53", "route53-zones.json", new TypeReference<Map<String, HostedZone>>() {})
                .putForAccount(ACCOUNT, "ZMISSING", zone("ZMISSING"));
        first.factory().shutdownAll();

        Fixture restarted = fixture(directory);
        Route53Service service = restarted.service();
        assertEquals("vpc-orphan", service.listVpcAssociationAuthorizations("ZMISSING").getFirst().getVpcId());
        assertTrue(restarted.authorizations().getForAccount(ACCOUNT, "ZFOREIGN").isEmpty());
        assertTrue(restarted.authorizations().getForAccount(OTHER_ACCOUNT, "ZFOREIGN").isEmpty());
        assertTrue(restarted.authorizations().getForAccount(ACCOUNT, "ZVALID").isEmpty());
        restarted.factory().shutdownAll();
        assertLegacyPreserved(legacy, directory.resolve("route53-vpc-auth.json"));
    }

    private static void assertLegacyPreserved(String original, Path source) throws Exception {
        TypeReference<Map<String, ZoneVpc>> type = new TypeReference<>() {};
        assertEquals(MAPPER.valueToTree(MAPPER.readValue(original, type)),
                MAPPER.valueToTree(MAPPER.readValue(Files.readString(source), type)));
    }

    private static VpcAssociation authorization(List<VpcAssociation> authorizations, String vpcId) {
        return authorizations.stream().filter(vpc -> vpcId.equals(vpc.getVpcId())).findFirst().orElseThrow();
    }

    private static HostedZone zone(String id) {
        return new HostedZone(id, "legacy.example.", "legacy-" + id, null,
                new VpcAssociation("vpc-initial", REGION));
    }

    private static Fixture fixture(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.services().route53().defaultNameserver1()).thenReturn("ns-1.example.");
        when(config.services().route53().defaultNameserver2()).thenReturn("ns-2.example.");
        when(config.services().route53().defaultNameserver3()).thenReturn("ns-3.example.");
        when(config.services().route53().defaultNameserver4()).thenReturn("ns-4.example.");
        when(config.services().route53().vpcAssociationControlPlaneDelayMs()).thenReturn(0L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        when(access.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new Fixture(config, new StorageFactory(config, access));
    }

    private record Fixture(EmulatorConfig config, StorageFactory factory) {
        Route53Service service() {
            return new Route53Service(factory, config, new RegionResolver(REGION, ACCOUNT), mock(Ec2Service.class));
        }

        AccountAwareStorageBackend<List<VpcAssociation>> authorizations() {
            return factory.create("route53", "route53-vpc-association-authorizations.json",
                    new TypeReference<Map<String, List<VpcAssociation>>>() {});
        }
    }
}
