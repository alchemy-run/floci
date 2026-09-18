package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import io.github.hectorvent.floci.services.apigateway.model.V2DomainName;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Startup
@ApplicationScoped
public class ApiGatewayV2DomainMigration {

    private static final Logger LOG = Logger.getLogger(ApiGatewayV2DomainMigration.class);

    @Inject
    public ApiGatewayV2DomainMigration(StorageFactory factory, EmulatorConfig config) {
        AccountAwareStorageBackend<V2DomainName> legacy = factory.create("apigateway", "apigateway-v2-domains.json",
                new TypeReference<Map<String, V2DomainName>>() {});
        AccountAwareStorageBackend<CustomDomain> canonical = factory.create("apigateway", "apigateway-domains.json",
                new TypeReference<Map<String, CustomDomain>>() {});
        AccountAwareStorageBackend<Boolean> migrated = factory.create("apigateway", "apigateway-v2-domain-migrations.json",
                new TypeReference<Map<String, Boolean>>() {});
        AccountAwareStorageBackend<Boolean> metadataMigrated = factory.create("apigateway",
                "apigateway-v2-domain-metadata-migrations.json", new TypeReference<Map<String, Boolean>>() {});
        synchronized (canonical) {
            for (AccountAwareStorageBackend.AccountEntry<V2DomainName> entry : legacy.scanAllAccountEntries(k -> true)) {
                String accountId = entry.accountId();
                String key = entry.key();
                boolean resourceMigrated = migrated.getForAccount(accountId, key).orElse(false);
                if (resourceMigrated && metadataMigrated.getForAccount(accountId, key).orElse(false)) {
                    continue;
                }
                V2DomainName source = legacy.getForAccount(accountId, key).orElse(entry.value());
                int separator = key.indexOf("::");
                if (separator <= 0 || source.getDomainName() == null
                        || source.getDomainName().isBlank()
                        || !key.substring(separator + 2).equals(source.getDomainName())) {
                    LOG.warnv("Skipping legacy API Gateway domain with inconsistent key: {0}/{1}", accountId, key);
                    continue;
                }
                if (accountId.equals(config.defaultAccountId())) {
                    canonical.getForAccountMigratingLegacyKeys(accountId, key, List.of(), domain -> true);
                }
                CustomDomain domain = canonical.getForAccount(accountId, key).orElse(null);
                if (domain == null && !resourceMigrated) {
                    domain = convert(source);
                }
                if (domain != null && !metadataMigrated.getForAccount(accountId, key).orElse(false)) {
                    preserveMissingMetadata(domain, source);
                    canonical.putForAccount(accountId, key, domain);
                }
                // Persist the destination before the markers; never recreate a previously migrated deletion.
                canonical.flush();
                migrated.putForAccount(accountId, key, true);
                migrated.flush();
                metadataMigrated.putForAccount(accountId, key, true);
                metadataMigrated.flush();
            }
        }
    }

    private static CustomDomain convert(V2DomainName source) {
        List<Map<String, Object>> configurations = source.getDomainNameConfigurations();
        Map<String, Object> configuration = configurations == null || configurations.isEmpty()
                || configurations.getFirst() == null ? Map.of() : configurations.getFirst();
        CustomDomain domain = new CustomDomain();
        domain.setDomainName(source.getDomainName());
        domain.setCertificateArn(string(configuration, "certificateArn", null));
        domain.setCertificateName(string(configuration, "certificateName", null));
        domain.setCertificateUploadDate(string(configuration, "certificateUploadDate", null));
        domain.setEndpointConfigurationType(string(configuration, "endpointType", "REGIONAL"));
        domain.setSecurityPolicy(string(configuration, "securityPolicy", "TLS_1_2"));
        domain.setDomainNameStatus(string(configuration, "domainNameStatus", "AVAILABLE"));
        domain.setRegionalDomainName(string(configuration, "apiGatewayDomainName",
                source.getDomainName() + ".regional.local"));
        domain.setRegionalHostedZoneId(string(configuration, "hostedZoneId", "Z2FDTNDATAQYW2"));
        if ("REGIONAL".equals(domain.getEndpointConfigurationType())) {
            domain.setRegionalCertificateArn(domain.getCertificateArn());
            domain.setRegionalCertificateName(domain.getCertificateName());
        } else if ("EDGE".equals(domain.getEndpointConfigurationType())) {
            domain.setDistributionDomainName(domain.getRegionalDomainName());
            domain.setDistributionHostedZoneId(domain.getRegionalHostedZoneId());
        }
        domain.setTags(source.getTags() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getTags()));
        return domain;
    }

    private static void preserveMissingMetadata(CustomDomain domain, V2DomainName source) {
        if (domain.getRoutingMode() == null) {
            domain.setRoutingMode(source.getRoutingMode() == null ? "API_MAPPING_ONLY" : source.getRoutingMode());
        }
        if (domain.getApiMappingSelectionExpression() == null) {
            domain.setApiMappingSelectionExpression(source.getApiMappingSelectionExpression() == null
                    ? "$request.basepath" : source.getApiMappingSelectionExpression());
        }
        if (domain.getMutualTlsAuthentication() == null) {
            domain.setMutualTlsAuthentication(source.getMutualTlsAuthentication());
        }
        if (domain.getDomainNameConfigurations() == null) {
            domain.setDomainNameConfigurations(source.getDomainNameConfigurations());
            domain.synchronizePrimaryConfiguration();
        }
    }

    private static String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
