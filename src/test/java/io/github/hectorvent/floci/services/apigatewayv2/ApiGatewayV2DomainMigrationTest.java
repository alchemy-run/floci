package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import io.github.hectorvent.floci.services.apigateway.model.V2DomainName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayV2DomainMigrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void migratesLegacyDomainsAcrossAccountsAndRegionsWithoutChangingTheSource(@TempDir Path directory) throws Exception {
        String legacy = """
                {
                  "us-east-1::legacy.example": {
                    "domainName":"legacy.example", "routingMode":"API_MAPPING_ONLY", "tags":{"source":"fork"},
                    "domainNameConfigurations":[{"certificateArn":"arn:legacy", "certificateName":"legacy-cert",
                      "apiGatewayDomainName":"d-legacy.execute-api.us-east-1.amazonaws.com", "hostedZoneId":"ZLEGACY",
                      "endpointType":"REGIONAL", "securityPolicy":"TLS_1_0", "domainNameStatus":"AVAILABLE"}]
                  },
                  "111111111111/us-east-1::legacy.example": {
                    "domainName":"legacy.example", "domainNameConfigurations":[{"certificateArn":"arn:other"}]
                  },
                  "000000000000/eu-west-1::legacy.example": {
                    "domainName":"legacy.example", "domainNameConfigurations":[{"certificateArn":"arn:west"}]
                  },
                  "us-east-1::mismatch.example": {"domainName":"foreign.example"}
                }
                """;
        Path source = directory.resolve("apigateway-v2-domains.json");
        Files.writeString(source, legacy);
        Fixture first = fixture(directory);
        // The migration must reuse the store already held by the canonical service.
        ApiGatewayService service = first.service();
        new ApiGatewayV2DomainMigration(first.factory(), first.config());
        CustomDomain domain = service.getDomainName(REGION, "legacy.example");
        assertEquals("arn:legacy", domain.getCertificateArn());
        assertEquals("arn:legacy", domain.getRegionalCertificateArn());
        assertEquals("legacy-cert", domain.getCertificateName());
        assertEquals("d-legacy.execute-api.us-east-1.amazonaws.com", domain.getRegionalDomainName());
        assertEquals("ZLEGACY", domain.getRegionalHostedZoneId());
        assertEquals("TLS_1_0", domain.getSecurityPolicy());
        assertEquals(Map.of("source", "fork"), domain.getTags());
        assertEquals(1, service.getDomainNames(REGION).size());
        assertEquals("arn:west", service.getDomainName("eu-west-1", "legacy.example").getCertificateArn());
        assertEquals("arn:other", first.domains().getForAccount(OTHER_ACCOUNT, REGION + "::legacy.example")
                .orElseThrow().getCertificateArn());
        assertTrue(first.domains().getForAccount(ACCOUNT, REGION + "::foreign.example").isEmpty());
        first.factory().shutdownAll();

        Fixture restarted = fixture(directory);
        new ApiGatewayV2DomainMigration(restarted.factory(), restarted.config());
        new ApiGatewayV2DomainMigration(restarted.factory(), restarted.config());
        assertEquals(3, restarted.domains().scanAllAccountEntries(k -> true).size());
        assertEquals("arn:legacy", restarted.service().getDomainName(REGION, "legacy.example").getCertificateArn());
        restarted.factory().shutdownAll();
        assertLegacyPreserved(legacy, source);
    }

    @Test
    void canonicalRowsWinAndDeletedDomainsDoNotReappearAfterRestart(@TempDir Path directory) throws Exception {
        String legacy = """
                {
                  "us-east-1::existing.example":{"domainName":"existing.example",
                    "domainNameConfigurations":[{"certificateArn":"arn:stale"}]},
                  "us-east-1::imported.example":{"domainName":"imported.example"},
                  "us-east-1::unscoped.example":{"domainName":"unscoped.example"}
                }
                """;
        Files.writeString(directory.resolve("apigateway-v2-domains.json"), legacy);
        Files.writeString(directory.resolve("apigateway-domains.json"), """
                {
                  "us-east-1::existing.example":{"domainName":"existing.example", "certificateArn":"arn:old-unscoped"},
                  "000000000000/us-east-1::existing.example":{"domainName":"existing.example",
                    "certificateArn":"arn:newer", "securityPolicy":"TLS_1_2", "tags":{"canonical":"yes"}},
                  "us-east-1::unscoped.example":{"domainName":"unscoped.example", "certificateArn":"arn:canonical"}
                }
                """);
        Fixture first = fixture(directory);
        new ApiGatewayV2DomainMigration(first.factory(), first.config());
        ApiGatewayService service = first.service();
        assertEquals("arn:newer", service.getDomainName(REGION, "existing.example").getCertificateArn());
        assertEquals(Map.of("canonical", "yes"), service.getDomainName(REGION, "existing.example").getTags());
        assertEquals("arn:canonical", service.getDomainName(REGION, "unscoped.example").getCertificateArn());
        service.deleteDomainName(REGION, "existing.example");
        service.deleteDomainName(REGION, "imported.example");
        service.deleteDomainName(REGION, "unscoped.example");
        first.factory().shutdownAll();

        Fixture restarted = fixture(directory);
        new ApiGatewayV2DomainMigration(restarted.factory(), restarted.config());
        ApiGatewayService afterRestart = restarted.service();
        assertThrows(AwsException.class, () -> afterRestart.getDomainName(REGION, "existing.example"));
        assertThrows(AwsException.class, () -> afterRestart.getDomainName(REGION, "imported.example"));
        assertThrows(AwsException.class, () -> afterRestart.getDomainName(REGION, "unscoped.example"));
        restarted.factory().shutdownAll();
        assertLegacyPreserved(legacy, directory.resolve("apigateway-v2-domains.json"));
    }

    @Test
    void scopedLegacyControlPlaneSettingsRemainActiveAcrossRestartsAndUpdates(@TempDir Path directory)
            throws Exception {
        String legacy = """
                {
                  "us-east-1::edge.example":{"domainName":"edge.example",
                    "domainNameConfigurations":[{"certificateArn":"arn:stale"}]},
                  "000000000000/us-east-1::edge.example":{"domainName":"edge.example",
                    "routingMode":"ROUTING_RULE_ONLY", "mutualTlsAuthentication":{"truststoreUri":"s3://trust/store"},
                    "apiMappingSelectionExpression":"$request.header.legacy",
                    "domainNameConfigurations":[{"certificateArn":"arn:scoped", "endpointType":"EDGE",
                      "apiGatewayDomainName":"d-existing.cloudfront.net", "hostedZoneId":"ZEDGE"},
                      {"certificateArn":"arn:second"}]}
                }
                """;
        Path source = directory.resolve("apigateway-v2-domains.json");
        Files.writeString(source, legacy);
        Fixture first = fixture(directory);
        new ApiGatewayV2DomainMigration(first.factory(), first.config());
        CustomDomain domain = first.service().getDomainName(REGION, "edge.example");
        assertEquals("arn:scoped", domain.getCertificateArn());
        assertEquals("EDGE", domain.getEndpointConfigurationType());
        assertEquals("d-existing.cloudfront.net", domain.getDistributionDomainName());
        assertEquals("ZEDGE", domain.getDistributionHostedZoneId());
        assertEquals("ROUTING_RULE_ONLY", domain.getRoutingMode());
        assertEquals("$request.header.legacy", domain.getApiMappingSelectionExpression());
        assertEquals(Map.of("truststoreUri", "s3://trust/store"), domain.getMutualTlsAuthentication());
        assertEquals(2, domain.getDomainNameConfigurations().size());
        first.factory().shutdownAll();

        Fixture second = fixture(directory);
        new ApiGatewayV2DomainMigration(second.factory(), second.config());
        V2DomainName active = second.service().getV2DomainName(REGION, "edge.example");
        assertEquals("ROUTING_RULE_ONLY", active.getRoutingMode());
        assertEquals("$request.header.legacy", active.getApiMappingSelectionExpression());
        assertEquals("s3://trust/store", active.getMutualTlsAuthentication().get("truststoreUri"));
        assertEquals("arn:second", active.getDomainNameConfigurations().get(1).get("certificateArn"));
        Map<String, Object> patch = new HashMap<>();
        patch.put("routingMode", "API_MAPPING_ONLY");
        patch.put("apiMappingSelectionExpression", "$request.header.updated");
        patch.put("mutualTlsAuthentication", null);
        patch.put("domainNameConfigurations", List.of());
        second.service().updateV2DomainName(REGION, "edge.example", patch);
        second.factory().shutdownAll();

        Fixture third = fixture(directory);
        new ApiGatewayV2DomainMigration(third.factory(), third.config());
        V2DomainName updated = third.service().getV2DomainName(REGION, "edge.example");
        assertEquals("API_MAPPING_ONLY", updated.getRoutingMode());
        assertEquals("$request.header.updated", updated.getApiMappingSelectionExpression());
        assertNull(updated.getMutualTlsAuthentication());
        assertTrue(updated.getDomainNameConfigurations().isEmpty());
        third.factory().shutdownAll();
        assertLegacyPreserved(legacy, source);
    }

    @Test
    void backfillsEarlierMigrationWithoutReplacingNewerFieldsOrRecreatingDeletedDomains(@TempDir Path directory)
            throws Exception {
        String legacy = """
                {
                  "us-east-1::upgraded.example":{"domainName":"upgraded.example", "routingMode":"ROUTING_RULE_ONLY",
                    "apiMappingSelectionExpression":"$request.header.legacy",
                    "mutualTlsAuthentication":{"truststoreUri":"s3://trust/old"},
                    "domainNameConfigurations":[{"certificateArn":"arn:old"},{"certificateArn":"arn:second"}]},
                  "us-east-1::deleted.example":{"domainName":"deleted.example","routingMode":"ROUTING_RULE_ONLY"}
                }
                """;
        Files.writeString(directory.resolve("apigateway-v2-domains.json"), legacy);
        Files.writeString(directory.resolve("apigateway-domains.json"), """
                {"000000000000/us-east-1::upgraded.example":{"domainName":"upgraded.example",
                  "certificateArn":"arn:newer", "routingMode":"API_MAPPING_ONLY", "tags":{"canonical":"yes"},
                  "mutualTlsAuthentication":{"truststoreUri":"s3://trust/newer"}}}
                """);
        Files.writeString(directory.resolve("apigateway-v2-domain-migrations.json"), """
                {"000000000000/us-east-1::upgraded.example":true,"000000000000/us-east-1::deleted.example":true}
                """);
        Fixture first = fixture(directory);
        new ApiGatewayV2DomainMigration(first.factory(), first.config());
        V2DomainName upgraded = first.service().getV2DomainName(REGION, "upgraded.example");
        assertEquals("API_MAPPING_ONLY", upgraded.getRoutingMode());
        assertEquals("$request.header.legacy", upgraded.getApiMappingSelectionExpression());
        assertEquals("s3://trust/newer", upgraded.getMutualTlsAuthentication().get("truststoreUri"));
        assertEquals("arn:newer", upgraded.getDomainNameConfigurations().getFirst().get("certificateArn"));
        assertEquals("arn:second", upgraded.getDomainNameConfigurations().get(1).get("certificateArn"));
        assertEquals(Map.of("canonical", "yes"), upgraded.getTags());
        assertThrows(AwsException.class, () -> first.service().getV2DomainName(REGION, "deleted.example"));
        first.factory().shutdownAll();

        Fixture restarted = fixture(directory);
        new ApiGatewayV2DomainMigration(restarted.factory(), restarted.config());
        assertEquals("$request.header.legacy", restarted.service().getV2DomainName(REGION, "upgraded.example")
                .getApiMappingSelectionExpression());
        assertThrows(AwsException.class, () -> restarted.service().getV2DomainName(REGION, "deleted.example"));
        restarted.factory().shutdownAll();
        assertLegacyPreserved(legacy, directory.resolve("apigateway-v2-domains.json"));
    }

    private static void assertLegacyPreserved(String original, Path source) throws Exception {
        TypeReference<Map<String, V2DomainName>> type = new TypeReference<>() {};
        assertEquals(MAPPER.valueToTree(MAPPER.readValue(original, type)),
                MAPPER.valueToTree(MAPPER.readValue(Files.readString(source), type)));
    }

    private static Fixture fixture(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        when(access.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new Fixture(config, new StorageFactory(config, access));
    }

    private record Fixture(EmulatorConfig config, StorageFactory factory) {
        ApiGatewayService service() {
            return new ApiGatewayService(factory, config, mock(TlsCertificateManager.class));
        }

        AccountAwareStorageBackend<CustomDomain> domains() {
            return factory.create("apigateway", "apigateway-domains.json",
                    new TypeReference<Map<String, CustomDomain>>() {});
        }
    }
}
