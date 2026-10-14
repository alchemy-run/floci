package io.github.hectorvent.floci.services.acm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateOptions;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.acm.model.DomainValidation;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ResourceRecord;
import io.github.hectorvent.floci.services.acm.model.RevocationReason;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.security.Security;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The domain validation status a certificate reports must follow its issuance. Clients such as the
 * AWS SDK {@code CertificateValidated} waiter and the CDK {@code DnsValidatedCertificate} handler
 * poll {@code DomainValidationOptions[].ValidationStatus}, not {@code Status}, and never finish
 * while an issued certificate still reports PENDING_VALIDATION.
 */
class AcmIssuanceServiceTest {

    private static final String REGION = "us-east-1";
    private static final String PRIVATE_CA =
            "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/11111111-2222-3333-4444-555555555555";
    private static CertificateGenerator generator;

    @BeforeAll
    static void setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    @Test
    void issuedCertificateReportsSuccessfulValidationForEveryDomain(@TempDir Path dir) {
        Route53Service route53 = newRoute53();
        AcmService service = newService(dir, store(dir), 0, route53);
        Certificate requested = request(service, null);
        publishValidations(route53, requested);
        Certificate cert = service.describeCertificate(requested.getArn(), REGION);

        assertEquals(CertificateStatus.ISSUED, cert.getStatus());
        assertEquals(2, cert.getDomainValidationOptions().size());
        for (DomainValidation validation : cert.getDomainValidationOptions()) {
            assertEquals("SUCCESS", validation.validationStatus(), validation.domainName());
            assertEquals("DNS", validation.validationMethod());
            assertNotNull(validation.resourceRecord(), "the validation CNAME stays on an issued certificate");
        }
    }

    @Test
    void privateCertificateReportsSuccessfulValidationRegardlessOfTheWait(@TempDir Path dir) {
        Certificate cert = request(newService(dir, 3600), PRIVATE_CA);

        assertEquals(CertificateStatus.ISSUED, cert.getStatus());
        assertTrue(cert.getDomainValidationOptions().stream()
                .allMatch(validation -> "SUCCESS".equals(validation.validationStatus())));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1})
    void dnsCertificateStaysPendingWithoutRecordsAfterTheWaitHasPassed(int waitSeconds, @TempDir Path dir) {
        PersistentStorage<String, Certificate> storage = store(dir);
        AcmService service = newService(dir, storage, waitSeconds, newRoute53());
        Certificate requested = request(service, null);
        String arn = requested.getArn();
        requested.setCreatedAt(Instant.now().minusSeconds(3600));
        storage.put(REGION + "::" + requested.extractCertificateId(), requested);

        for (int attempt = 0; attempt < 3; attempt++) {
            Certificate pending = service.describeCertificate(arn, REGION);
            assertEquals(CertificateStatus.PENDING_VALIDATION, pending.getStatus());
            assertNull(pending.getIssuedAt());
            assertTrue(pending.getDomainValidationOptions().stream()
                    .allMatch(validation -> "PENDING_VALIDATION".equals(validation.validationStatus())));
            AwsException error = assertThrows(AwsException.class, () -> service.getCertificate(arn, REGION));
            assertEquals("RequestInProgressException", error.getErrorCode());
        }
        assertEquals(1, service.listCertificates(List.of(CertificateStatus.PENDING_VALIDATION),
                null, REGION, 100, null).certificates().size());
        assertTrue(service.listCertificates(List.of(CertificateStatus.ISSUED),
                null, REGION, 100, null).certificates().isEmpty());
        assertEquals(CertificateStatus.PENDING_VALIDATION,
                service.searchCertificates(null, REGION, 100, null).certificates().getFirst().getStatus());
        assertEquals(CertificateStatus.PENDING_VALIDATION,
                newService(dir, 0).describeCertificate(arn, REGION).getStatus());
    }

    @Test
    void everyDomainRequiresTheExpectedCnameInAPublicZone(@TempDir Path dir) {
        Route53Service route53 = newRoute53();
        AcmService service = newService(dir, store(dir), 0, route53);
        Certificate requested = request(service, null);
        HostedZone zone = route53.createHostedZone("example.com.", "validation", null, null).zone();
        ResourceRecord primary = requested.getDomainValidationOptions().getFirst().resourceRecord();
        ResourceRecord san = requested.getDomainValidationOptions().getLast().resourceRecord();

        putRecord(route53, zone.getId(), primary, primary.value());
        assertEquals(CertificateStatus.PENDING_VALIDATION,
                service.describeCertificate(requested.getArn(), REGION).getStatus());
        assertEquals("SUCCESS", requested.getDomainValidationOptions().getFirst().validationStatus());
        putRecord(route53, zone.getId(), san, "_incorrect.acm-validations.aws.");
        assertEquals(CertificateStatus.PENDING_VALIDATION,
                service.describeCertificate(requested.getArn(), REGION).getStatus());
        putRecord(route53, zone.getId(), san, san.value().toUpperCase(Locale.ROOT).replaceAll("\\.$", ""));
        zone.setPrivateZone(true);
        assertEquals(CertificateStatus.PENDING_VALIDATION,
                service.describeCertificate(requested.getArn(), REGION).getStatus());
        zone.setPrivateZone(false);

        Certificate issued = service.getCertificate(requested.getArn(), REGION);
        assertEquals(CertificateStatus.ISSUED, issued.getStatus());
        assertNotNull(issued.getIssuedAt());
        assertNotNull(issued.getCertificateBody());
        assertTrue(issued.getDomainValidationOptions().stream()
                .allMatch(validation -> "SUCCESS".equals(validation.validationStatus())));
    }

    @Test
    void wildcardAndBaseDomainShareValidationRecordsAcrossRegions(@TempDir Path dir) {
        Route53Service route53 = newRoute53();
        AcmService service = newService(dir, store(dir), -1, route53);
        Certificate requested = service.requestCertificate("example.com", List.of("*.example.com"),
                ValidationMethod.DNS, null, KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        ResourceRecord record = requested.getDomainValidationOptions().getFirst().resourceRecord();
        assertEquals(record, requested.getDomainValidationOptions().getLast().resourceRecord());
        HostedZone zone = route53.createHostedZone("example.com.", "wildcard", null, null).zone();
        putRecord(route53, zone.getId(), record, record.value());

        assertEquals(CertificateStatus.ISSUED, service.getCertificate(requested.getArn(), REGION).getStatus());
        Certificate otherRegion = service.requestCertificate("*.example.com", List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, null, Map.of(), "us-west-2");
        assertEquals(CertificateStatus.ISSUED,
                service.getCertificate(otherRegion.getArn(), "us-west-2").getStatus());
    }

    @Test
    void settledCertificateIsPersistedNotRecomputed(@TempDir Path dir) {
        Route53Service route53 = newRoute53();
        AcmService service = newService(dir, store(dir), 0, route53);
        Certificate requested = request(service, null);
        String arn = requested.getArn();
        publishValidations(route53, requested);
        service.getCertificate(arn, REGION);

        // A restarted service without DNS records must retain the persisted issuance.
        Certificate reloaded = newService(dir, 3600).describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.ISSUED, reloaded.getStatus());
        assertTrue(reloaded.getDomainValidationOptions().stream()
                .allMatch(validation -> "SUCCESS".equals(validation.validationStatus())));
    }

    @Test
    void listCertificatesSettlesPendingCertificatesBeforeFilteringByStatus(@TempDir Path dir) {
        Route53Service route53 = newRoute53();
        AcmService service = newService(dir, store(dir), 0, route53);
        Certificate requested = request(service, null);
        String arn = requested.getArn();
        assertTrue(service.listCertificates(List.of(CertificateStatus.ISSUED), null, REGION, 100, null)
                .certificates().isEmpty(), "still pending");

        publishValidations(route53, requested);

        List<Certificate> issued = service.listCertificates(List.of(CertificateStatus.ISSUED), null, REGION, 100, null)
                .certificates();
        assertEquals(1, issued.size());
        assertEquals(arn, issued.get(0).getArn());
        assertEquals(CertificateStatus.ISSUED, issued.get(0).getStatus());
    }

    @Test
    void issuedCertificateStoredWithPendingValidationIsRepairedOnRead(@TempDir Path dir) {
        // Earlier releases stored ISSUED certificates whose validation entries stayed
        // PENDING_VALIDATION. The first read after an upgrade repairs and stores them.
        String id = "11111111-2222-3333-4444-555555555555";
        String arn = "arn:aws:acm:us-east-1:000000000000:certificate/" + id;
        Certificate legacy = new Certificate();
        legacy.setArn(arn);
        legacy.setDomainName("legacy.example.com");
        legacy.setStatus(CertificateStatus.ISSUED);
        legacy.setCreatedAt(Instant.now());
        legacy.setDomainValidationOptions(List.of(new DomainValidation("legacy.example.com", "example.com",
                "PENDING_VALIDATION", "DNS", new ResourceRecord("_a.legacy.example.com.", "CNAME", "_b.acm-validations.aws."), null)));
        PersistentStorage<String, Certificate> store = store(dir);
        store.put(REGION + "::" + id, legacy);

        Certificate repaired = newService(dir, store, 0).describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.ISSUED, repaired.getStatus());
        assertEquals("SUCCESS", repaired.getDomainValidationOptions().get(0).validationStatus());
        assertEquals("_a.legacy.example.com.", repaired.getDomainValidationOptions().get(0).resourceRecord().name());
        Certificate reloaded = store(dir).get(REGION + "::" + id).orElseThrow();
        assertEquals("SUCCESS", reloaded.getDomainValidationOptions().get(0).validationStatus(), "repair is stored");
    }

    @Test
    void certificateRevokedAfterIssuanceStoredWithPendingValidationIsRepaired(@TempDir Path dir) {
        String id = "22222222-2222-3333-4444-555555555555";
        String arn = "arn:aws:acm:us-east-1:000000000000:certificate/" + id;
        Certificate legacy = new Certificate();
        legacy.setArn(arn);
        legacy.setDomainName("revoked.example.com");
        legacy.setStatus(CertificateStatus.REVOKED);
        legacy.setCreatedAt(Instant.now().minusSeconds(60));
        legacy.setIssuedAt(Instant.now().minusSeconds(60));
        legacy.setDomainValidationOptions(List.of(new DomainValidation("revoked.example.com", "example.com",
                "PENDING_VALIDATION", "DNS", null, null)));
        PersistentStorage<String, Certificate> store = store(dir);
        store.put(REGION + "::" + id, legacy);

        Certificate repaired = newService(dir, store, 0).describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.REVOKED, repaired.getStatus());
        assertEquals("SUCCESS", repaired.getDomainValidationOptions().get(0).validationStatus());
    }

    @Test
    void certificateRevokedWhileStillPendingKeepsItsPendingValidation(@TempDir Path dir) {
        // Never issued, so nothing was ever validated: leaving PENDING_VALIDATION is the honest answer.
        AcmService service = newService(dir, 3600);
        String arn = service.requestCertificate("example.com", List.of("www.example.com"), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, new CertificateOptions(null, "ENABLED"), Map.of(), REGION).getArn();
        service.exportCertificate(arn, "dGVzdHBhc3NwaHJhc2U=", REGION);
        service.revokeCertificate(arn, RevocationReason.UNSPECIFIED, REGION);

        Certificate revoked = service.describeCertificate(arn, REGION);

        assertEquals(CertificateStatus.REVOKED, revoked.getStatus());
        assertNull(revoked.getIssuedAt());
        assertTrue(revoked.getDomainValidationOptions().stream()
                .allMatch(validation -> "PENDING_VALIDATION".equals(validation.validationStatus())));
    }

    private static Certificate request(AcmService service, String certificateAuthorityArn) {
        return service.requestCertificate("example.com", List.of("www.example.com"), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, certificateAuthorityArn, null, Map.of(), REGION);
    }

    private static AcmService newService(Path dir, int validationWaitSeconds) {
        return newService(dir, store(dir), validationWaitSeconds);
    }

    private static AcmService newService(Path dir, StorageBackend<String, Certificate> store, int validationWaitSeconds) {
        return newService(dir, store, validationWaitSeconds, null);
    }

    private static AcmService newService(Path dir, StorageBackend<String, Certificate> store,
                                         int validationWaitSeconds, Route53Service route53) {
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        return new AcmService(store, generator, FlociCertificateAuthority.loadOrCreate(dir.resolve("tls")),
                regionResolver, validationWaitSeconds, route53);
    }

    private static Route53Service newRoute53() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Route53ServiceConfig dns = mock(EmulatorConfig.Route53ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.route53()).thenReturn(dns);
        when(dns.defaultNameserver1()).thenReturn("ns-1.example.test.");
        when(dns.defaultNameserver2()).thenReturn("ns-2.example.test.");
        when(dns.defaultNameserver3()).thenReturn("ns-3.example.test.");
        when(dns.defaultNameserver4()).thenReturn("ns-4.example.test.");
        return new Route53Service(factory, config, new RegionResolver(REGION, "000000000000"),
                mock(Ec2Service.class));
    }

    private static void publishValidations(Route53Service route53, Certificate certificate) {
        HostedZone zone = route53.createHostedZone("example.com.", "validation", null, null).zone();
        for (DomainValidation validation : certificate.getDomainValidationOptions()) {
            putRecord(route53, zone.getId(), validation.resourceRecord(), validation.resourceRecord().value());
        }
    }

    private static void putRecord(Route53Service route53, String zoneId, ResourceRecord record, String value) {
        ResourceRecordSet recordSet = new ResourceRecordSet();
        recordSet.setName(record.name().toUpperCase(Locale.ROOT));
        recordSet.setType(record.type());
        recordSet.setTtl(60L);
        // Route 53 and ACM have distinct ResourceRecord wire models.
        recordSet.setRecords(List.of(new io.github.hectorvent.floci.services.route53.model.ResourceRecord(value)));
        route53.changeResourceRecordSets(zoneId, List.of(Map.of("action", "UPSERT", "rrs", recordSet)), null);
    }

    private static PersistentStorage<String, Certificate> store(Path dir) {
        PersistentStorage<String, Certificate> store = new PersistentStorage<>(
                dir.resolve("acm-certificates.json"), new TypeReference<Map<String, Certificate>>() {});
        store.load();
        return store;
    }
}
