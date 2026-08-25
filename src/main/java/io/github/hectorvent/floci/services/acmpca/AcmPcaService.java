package io.github.hectorvent.floci.services.acmpca;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acmpca.model.AuditReport;
import io.github.hectorvent.floci.services.acmpca.model.CertificateAuthority;
import io.github.hectorvent.floci.services.acmpca.model.IssuedCertificate;
import io.github.hectorvent.floci.services.acmpca.model.Permission;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AWS Private CA (ACM PCA) emulator. JSON 1.1 protocol, target prefix {@code ACMPrivateCA.}.
 *
 * @see <a href="https://docs.aws.amazon.com/privateca/latest/APIReference/Welcome.html">AWS Private CA API</a>
 */
@ApplicationScoped
public class AcmPcaService {

    private static final Logger LOG = Logger.getLogger(AcmPcaService.class);

    static final Pattern ACM_PCA_ARN = Pattern.compile(
        "arn:[\\w+=/,.@-]+:acm-pca:[\\w+=/,.@-]*:[0-9]*:[\\w+=,.@-]+(/[\\w+=,.@-]+)*");
    static final Pattern CA_ARN = Pattern.compile(
        "arn:[\\w+=/,.@-]+:acm-pca:[\\w+=/,.@-]*:[0-9]*:certificate-authority/"
            + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String ACM_PRINCIPAL = "acm.amazonaws.com";
    private static final Set<String> ACM_REQUIRED_ACTIONS = Set.of(
        "IssueCertificate", "GetCertificate", "ListPermissions");
    private static final int DEFAULT_DELETION_WINDOW_DAYS = 30;
    private static final int MIN_DELETION_WINDOW_DAYS = 7;
    private static final int MAX_DELETION_WINDOW_DAYS = 30;
    private static final int MAX_TAGS = 50;

    private final StorageBackend<String, CertificateAuthority> store;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;

    @Inject
    public AcmPcaService(StorageFactory factory, RegionResolver regionResolver, S3Service s3Service) {
        this(factory.create("acm-pca", "acm-pca-cas.json",
            new TypeReference<Map<String, CertificateAuthority>>() {}),
            regionResolver, s3Service);
    }

    AcmPcaService(StorageBackend<String, CertificateAuthority> store, RegionResolver regionResolver) {
        this(store, regionResolver, null);
    }

    AcmPcaService(StorageBackend<String, CertificateAuthority> store, RegionResolver regionResolver,
                  S3Service s3Service) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
    }

    public CertificateAuthority createCertificateAuthority(
            Map<String, Object> configuration,
            Map<String, Object> revocationConfiguration,
            String type,
            String usageMode,
            String keyStorageSecurityStandard,
            Map<String, String> tags,
            String idempotencyToken,
            String region) {
        if (configuration == null || configuration.isEmpty()) {
            throw new AwsException("InvalidArgsException",
                "CertificateAuthorityConfiguration must not be null.", 400);
        }
        if (type == null || type.isBlank()) {
            throw new AwsException("InvalidArgsException",
                "CertificateAuthorityType must not be null.", 400);
        }
        if (!"ROOT".equals(type) && !"SUBORDINATE".equals(type)) {
            throw new AwsException("InvalidArgsException",
                "CertificateAuthorityType must be ROOT or SUBORDINATE.", 400);
        }
        if (tags != null && tags.size() > MAX_TAGS) {
            throw new AwsException("TooManyTagsException",
                "The request contains too many tags.", 400);
        }

        if (idempotencyToken != null && !idempotencyToken.isBlank()) {
            for (CertificateAuthority existing : store.scan(k -> true)) {
                if (idempotencyToken.equals(existing.getIdempotencyToken())
                    && !"DELETED".equals(existing.getStatus())) {
                    LOG.debugv("Returning existing CA for idempotency token {0}", idempotencyToken);
                    return existing;
                }
            }
        }

        String accountId = regionResolver.getAccountId();
        String caId = UUID.randomUUID().toString();
        String arn = AwsArnUtils.Arn.of("acm-pca", region, accountId,
            "certificate-authority/" + caId).toString();
        long now = Instant.now().getEpochSecond();

        CertificateAuthority ca = new CertificateAuthority();
        ca.setArn(arn);
        ca.setOwnerAccount(accountId);
        ca.setRegion(region);
        ca.setCreatedAt(now);
        ca.setLastStateChangeAt(now);
        ca.setType(type);
        ca.setStatus("PENDING_CERTIFICATE");
        ca.setUsageMode(usageMode == null || usageMode.isBlank() ? "GENERAL_PURPOSE" : usageMode);
        ca.setKeyStorageSecurityStandard(
            keyStorageSecurityStandard == null || keyStorageSecurityStandard.isBlank()
                ? "FIPS_140_2_LEVEL_3_OR_HIGHER"
                : keyStorageSecurityStandard);
        ca.setCertificateAuthorityConfiguration(configuration);
        if (revocationConfiguration != null) {
            ca.setRevocationConfiguration(revocationConfiguration);
        }
        if (tags != null) {
            ca.setTags(tags);
        }
        ca.setIdempotencyToken(idempotencyToken);
        AcmPcaCertificates.KeyMaterial keys = AcmPcaCertificates.generateKeyMaterial(configuration);
        ca.setCsrPem(keys.csrPem());
        ca.setPrivateKeyPem(keys.privateKeyPem());

        store.put(arn, ca);
        LOG.infov("Created certificate authority {0}", arn);
        return ca;
    }

    public CertificateAuthority describeCertificateAuthority(String arn) {
        return requireCa(arn);
    }

    public List<CertificateAuthority> listCertificateAuthorities() {
        return store.scan(k -> true).stream()
            .sorted(Comparator.comparingLong(CertificateAuthority::getCreatedAt).reversed())
            .collect(Collectors.toList());
    }

    public void updateCertificateAuthority(String arn, Map<String, Object> revocationConfiguration, String status) {
        CertificateAuthority ca = requireCa(arn);
        rejectDeleted(ca);
        if (status != null && !status.isBlank()) {
            if (!"ACTIVE".equals(ca.getStatus()) && !"DISABLED".equals(ca.getStatus())
                && !"PENDING_CERTIFICATE".equals(ca.getStatus())) {
                throw invalidState(arn);
            }
            if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
                throw new AwsException("InvalidArgsException",
                    "Status must be ACTIVE or DISABLED.", 400);
            }
            // AWS only permits status changes while ACTIVE or DISABLED. PENDING_CERTIFICATE
            // CAs stay pending until ImportCertificateAuthorityCertificate.
            if ("PENDING_CERTIFICATE".equals(ca.getStatus()) && !"PENDING_CERTIFICATE".equals(status)) {
                throw invalidState(arn);
            }
            ca.setStatus(status);
        }
        if (revocationConfiguration != null) {
            if (!"ACTIVE".equals(ca.getStatus()) && !"DISABLED".equals(ca.getStatus())) {
                throw invalidState(arn);
            }
            ca.setRevocationConfiguration(revocationConfiguration);
        }
        ca.setLastStateChangeAt(Instant.now().getEpochSecond());
        store.put(arn, ca);
    }

    public void deleteCertificateAuthority(String arn, Integer permanentDeletionTimeInDays) {
        CertificateAuthority ca = requireCa(arn);
        if ("DELETED".equals(ca.getStatus())) {
            return;
        }
        if ("ACTIVE".equals(ca.getStatus())) {
            throw invalidState(arn);
        }
        int days = permanentDeletionTimeInDays == null
            ? DEFAULT_DELETION_WINDOW_DAYS
            : permanentDeletionTimeInDays;
        if (days < MIN_DELETION_WINDOW_DAYS || days > MAX_DELETION_WINDOW_DAYS) {
            throw new AwsException("InvalidArgsException",
                "PermanentDeletionTimeInDays must be between 7 and 30.", 400);
        }
        long now = Instant.now().getEpochSecond();
        ca.setStatus("DELETED");
        ca.setPermanentDeletionTimeInDays(days);
        ca.setRestorableUntil(now + days * 86400L);
        ca.setLastStateChangeAt(now);
        store.put(arn, ca);
    }

    public Map<String, String> listTags(String arn) {
        return new LinkedHashMap<>(requireCa(arn).getTags());
    }

    public void tagCertificateAuthority(String arn, Map<String, String> tags) {
        CertificateAuthority ca = requireCa(arn);
        rejectDeleted(ca);
        if (tags == null || tags.isEmpty()) {
            throw new AwsException("InvalidArgsException", "Tags must not be empty.", 400);
        }
        Map<String, String> merged = new LinkedHashMap<>(ca.getTags());
        merged.putAll(tags);
        if (merged.size() > MAX_TAGS) {
            throw new AwsException("TooManyTagsException",
                "The request contains too many tags.", 400);
        }
        ca.setTags(merged);
        store.put(arn, ca);
    }

    public void untagCertificateAuthority(String arn, List<String> keys) {
        CertificateAuthority ca = requireCa(arn);
        rejectDeleted(ca);
        if (keys == null || keys.isEmpty()) {
            throw new AwsException("InvalidArgsException", "Tags must not be empty.", 400);
        }
        Map<String, String> remaining = new LinkedHashMap<>(ca.getTags());
        for (String key : keys) {
            remaining.remove(key);
        }
        ca.setTags(remaining);
        store.put(arn, ca);
    }

    public void createPermission(String arn, String principal, String sourceAccount, List<String> actions) {
        CertificateAuthority ca = requireCa(arn);
        rejectDeleted(ca);
        if (principal == null || principal.isBlank()) {
            throw new AwsException("InvalidArgsException", "Principal must not be null.", 400);
        }
        if (actions == null || actions.isEmpty()) {
            throw new AwsException("InvalidArgsException", "Actions must not be empty.", 400);
        }
        if (ACM_PRINCIPAL.equals(principal) && !actions.containsAll(ACM_REQUIRED_ACTIONS)) {
            throw new AwsException("ValidationException",
                "Permissions must contain all three actions [IssueCertificate, GetCertificate, ListPermissions] for ACM to perform renewals.",
                400);
        }
        for (Permission existing : ca.getPermissions()) {
            if (principal.equals(existing.getPrincipal())) {
                throw new AwsException("PermissionAlreadyExistsException",
                    "A permission for principal " + principal + " already exists.", 400);
            }
        }
        Permission permission = new Permission();
        permission.setCertificateAuthorityArn(arn);
        permission.setCreatedAt(Instant.now().getEpochSecond());
        permission.setPrincipal(principal);
        permission.setSourceAccount(sourceAccount != null ? sourceAccount : ca.getOwnerAccount());
        permission.setActions(actions);
        ca.getPermissions().add(permission);
        store.put(arn, ca);
    }

    public void deletePermission(String arn, String principal) {
        CertificateAuthority ca = requireCa(arn);
        rejectDeleted(ca);
        boolean removed = ca.getPermissions().removeIf(p -> principal.equals(p.getPrincipal()));
        if (!removed) {
            throw new AwsException("ResourceNotFoundException",
                "Permission for principal " + principal + " was not found.", 400);
        }
        store.put(arn, ca);
    }

    public List<Permission> listPermissions(String arn) {
        CertificateAuthority ca = requireCa(arn);
        rejectDeleted(ca);
        return new ArrayList<>(ca.getPermissions());
    }

    public void putPolicy(String arn, String policy) {
        CertificateAuthority ca = requireCa(arn, "resourceArn");
        rejectDeleted(ca);
        if (policy == null || policy.isBlank()) {
            throw new AwsException("InvalidPolicyException", "Policy must not be empty.", 400);
        }
        ca.setPolicy(policy);
        store.put(arn, ca);
    }

    public String getPolicy(String arn) {
        CertificateAuthority ca = requireCa(arn, "resourceArn");
        rejectDeleted(ca);
        if (ca.getPolicy() == null) {
            throw new AwsException("ResourceNotFoundException",
                "A policy for " + arn + " was not found.", 400);
        }
        return ca.getPolicy();
    }

    public void deletePolicy(String arn) {
        CertificateAuthority ca = requireCa(arn, "resourceArn");
        rejectDeleted(ca);
        ca.setPolicy(null);
        store.put(arn, ca);
    }

    public String getCertificateAuthorityCsr(String arn) {
        CertificateAuthority ca = requireCa(arn);
        rejectDeleted(ca);
        if ("CREATING".equals(ca.getStatus()) || "FAILED".equals(ca.getStatus())) {
            throw new AwsException("RequestInProgressException",
                "The certificate authority CSR is not yet available", 400);
        }
        if (ca.getCsrPem() == null) {
            throw new AwsException("RequestFailedException",
                "The certificate authority CSR is not available", 400);
        }
        return ca.getCsrPem();
    }

    public IssuedCertificate issueCertificate(String caArn, String csrPem, String signingAlgorithm,
                                              String templateArn, Map<String, Object> validity) {
        CertificateAuthority ca = requireCa(caArn);
        rejectDeleted(ca);
        boolean rootTemplate = templateArn != null && templateArn.contains("RootCACertificate");
        boolean caTemplate = rootTemplate || (templateArn != null && templateArn.contains("CACertificate"));
        if (rootTemplate) {
            if (!"PENDING_CERTIFICATE".equals(ca.getStatus()) && !"ACTIVE".equals(ca.getStatus())) {
                throw invalidState(caArn);
            }
        } else if (!"ACTIVE".equals(ca.getStatus())) {
            throw invalidState(caArn);
        }
        if (csrPem == null || csrPem.isBlank()) {
            throw new AwsException("MalformedCSRException", "The CSR is empty", 400);
        }
        String sigAlg = signingAlgorithm != null
            ? signingAlgorithm
            : stringFromConfig(ca.getCertificateAuthorityConfiguration(), "SigningAlgorithm", "SHA256WITHRSA");
        AcmPcaCertificates.Issued issued = AcmPcaCertificates.issue(
            csrPem, sigAlg, caTemplate, ca.getCertificatePem(), ca.getCsrPem(), ca.getPrivateKeyPem(), validity);
        String certId = UUID.randomUUID().toString();
        String certArn = ca.getArn() + "/certificate/" + certId;
        IssuedCertificate certificate = new IssuedCertificate();
        certificate.setArn(certArn);
        certificate.setPem(issued.pem());
        certificate.setChainPem(ca.getCertificatePem());
        certificate.setSerialHex(issued.serialHex());
        certificate.setStatus("ISSUED");
        certificate.setIssuedAt(Instant.now().toEpochMilli());
        ca.getCertificates().put(certArn, certificate);
        store.put(ca.getArn(), ca);
        return certificate;
    }

    public IssuedCertificate getCertificate(String caArn, String certificateArn) {
        CertificateAuthority ca = requireCa(caArn);
        rejectDeleted(ca);
        IssuedCertificate issued = ca.getCertificates().get(certificateArn);
        if (issued == null) {
            throw new AwsException("ResourceNotFoundException",
                "Certificate not found: " + certificateArn, 400);
        }
        return issued;
    }

    public void importCertificateAuthorityCertificate(String caArn, String certificatePem, String chainPem) {
        CertificateAuthority ca = requireCa(caArn);
        if (!"PENDING_CERTIFICATE".equals(ca.getStatus())) {
            throw invalidState(caArn);
        }
        if (certificatePem == null || certificatePem.isBlank()) {
            throw new AwsException("MalformedCertificateException",
                "The certificate body is empty", 400);
        }
        try {
            X509Certificate cert = AcmPcaCertificates.parseCertificate(certificatePem);
            ca.setCertificatePem(AcmPcaCertificates.toPem(cert));
            ca.setCertificateChainPem(chainPem);
            ca.setSerial(AcmPcaCertificates.toSerialHex(cert.getSerialNumber()));
            ca.setNotBefore(cert.getNotBefore().toInstant().getEpochSecond());
            ca.setNotAfter(cert.getNotAfter().toInstant().getEpochSecond());
            ca.setStatus("ACTIVE");
            ca.setLastStateChangeAt(Instant.now().getEpochSecond());
            store.put(ca.getArn(), ca);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("MalformedCertificateException",
                "The certificate could not be parsed: " + e.getMessage(), 400);
        }
    }

    public CertificateAuthority getCertificateAuthorityCertificate(String caArn) {
        CertificateAuthority ca = requireCa(caArn);
        rejectDeleted(ca);
        if (!"ACTIVE".equals(ca.getStatus()) && !"DISABLED".equals(ca.getStatus())) {
            throw invalidState(caArn);
        }
        if (ca.getCertificatePem() == null) {
            throw invalidState(caArn);
        }
        return ca;
    }

    public void revokeCertificate(String caArn, String serial, String reason) {
        CertificateAuthority ca = requireCa(caArn);
        if (!"ACTIVE".equals(ca.getStatus())) {
            throw invalidState(caArn);
        }
        if (serial == null || serial.isBlank()) {
            throw new AwsException("InvalidArgsException",
                "Value null at 'certificateSerial' failed to satisfy constraint: Member must not be null", 400);
        }
        String normalized = AcmPcaCertificates.normalizeSerial(serial);
        IssuedCertificate match = null;
        for (IssuedCertificate issued : ca.getCertificates().values()) {
            if (AcmPcaCertificates.normalizeSerial(issued.getSerialHex()).equals(normalized)) {
                match = issued;
                break;
            }
        }
        if (match == null) {
            throw new AwsException("ResourceNotFoundException",
                "Certificate with serial " + serial + " was not found", 400);
        }
        match.setStatus("REVOKED");
        match.setRevocationReason(reason != null ? reason : "UNSPECIFIED");
        store.put(ca.getArn(), ca);
    }

    public AuditReport createAuditReport(String caArn, String bucket, String format) {
        CertificateAuthority ca = requireCa(caArn);
        if (!"ACTIVE".equals(ca.getStatus())) {
            throw invalidState(caArn);
        }
        if (bucket == null || bucket.isBlank()) {
            throw new AwsException("InvalidArgsException",
                "Value null at 's3BucketName' failed to satisfy constraint: Member must not be null", 400);
        }
        if (s3Service == null) {
            throw new AwsException("RequestFailedException", "S3 is not available", 400);
        }
        String reportId = UUID.randomUUID().toString();
        String caId = ca.getArn().substring(ca.getArn().lastIndexOf('/') + 1);
        String ext = "CSV".equalsIgnoreCase(format) ? "csv" : "json";
        String key = caId + "/" + reportId + "." + ext;
        String body = buildAuditReportBody(ca, format);
        try {
            s3Service.putObject(bucket, key, body.getBytes(StandardCharsets.UTF_8),
                "CSV".equalsIgnoreCase(format) ? "text/csv" : "application/json",
                Map.of());
        } catch (RuntimeException e) {
            throw new AwsException("RequestFailedException",
                "Failed to write audit report to s3://" + bucket + "/" + key + ": " + e.getMessage(),
                400);
        }
        AuditReport report = new AuditReport();
        report.setAuditReportId(reportId);
        report.setS3BucketName(bucket);
        report.setS3Key(key);
        report.setStatus("SUCCESS");
        report.setFormat(format != null ? format : "JSON");
        report.setCreatedAt(Instant.now().toEpochMilli());
        ca.getAuditReports().put(reportId, report);
        store.put(ca.getArn(), ca);
        return report;
    }

    public AuditReport describeAuditReport(String caArn, String reportId) {
        CertificateAuthority ca = requireCa(caArn);
        AuditReport report = ca.getAuditReports().get(reportId);
        if (report == null) {
            throw new AwsException("ResourceNotFoundException",
                "Audit report not found: " + reportId, 400);
        }
        return report;
    }

    private static String buildAuditReportBody(CertificateAuthority ca, String format) {
        StringBuilder sb = new StringBuilder();
        boolean csv = "CSV".equalsIgnoreCase(format);
        if (csv) {
            sb.append("awsAccountId,certificateArn,serial,status\n");
        } else {
            sb.append("{\"auditReportEntries\":[");
        }
        boolean first = true;
        for (IssuedCertificate issued : ca.getCertificates().values()) {
            if (csv) {
                sb.append(ca.getOwnerAccount()).append(',')
                    .append(issued.getArn()).append(',')
                    .append(issued.getSerialHex()).append(',')
                    .append(issued.getStatus()).append('\n');
            } else {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"awsAccountId\":\"").append(ca.getOwnerAccount())
                    .append("\",\"certificateArn\":\"").append(issued.getArn())
                    .append("\",\"serial\":\"").append(issued.getSerialHex())
                    .append("\",\"status\":\"").append(issued.getStatus())
                    .append("\"}");
            }
        }
        if (!csv) {
            sb.append("]}");
        }
        return sb.toString();
    }

    private static String stringFromConfig(Map<String, Object> configuration, String key, String fallback) {
        Object value = configuration == null ? null : configuration.get(key);
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    String requireCaArn(String arn) {
        return requireCaArn(arn, "certificateAuthorityArn");
    }

    String requireCaArn(String arn, String field) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationException",
                "1 validation error detected: Value null at '" + field
                    + "' failed to satisfy constraint: Member must not be null",
                400);
        }
        if (!ACM_PCA_ARN.matcher(arn).matches()) {
            throw new AwsException("ValidationException",
                "1 validation error detected: Value at '" + field
                    + "' failed to satisfy constraint: Member must satisfy regular expression pattern: arn:[\\w+=/,.@-]+:acm-pca:[\\w+=/,.@-]*:[0-9]*:[\\w+=,.@-]+(/[\\w+=,.@-]+)*",
                400);
        }
        if (!CA_ARN.matcher(arn).matches()) {
            throw new AwsException("InvalidArnException",
                "ARN " + arn + " is not a valid certificate-authority ARN.", 400);
        }
        return arn;
    }

    private CertificateAuthority requireCa(String arn) {
        return requireCa(arn, "certificateAuthorityArn");
    }

    CertificateAuthority requireCa(String arn, String field) {
        String valid = requireCaArn(arn, field);
        return store.get(valid).orElseThrow(() ->
            new AwsException("ResourceNotFoundException",
                "Resource " + valid + " does not exist.", 400));
    }

    private static void rejectDeleted(CertificateAuthority ca) {
        if ("DELETED".equals(ca.getStatus())) {
            throw invalidState(ca.getArn());
        }
    }

    private static AwsException invalidState(String arn) {
        return new AwsException("InvalidStateException",
            "The certificate authority " + arn + " is not in a valid state for this operation.", 400);
    }
}
