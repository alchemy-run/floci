package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.AccessAdvisorJob;
import io.github.hectorvent.floci.services.iam.model.AccountPasswordPolicy;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.LoginProfile;
import io.github.hectorvent.floci.services.iam.model.OidcProvider;
import io.github.hectorvent.floci.services.iam.model.SamlProvider;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.github.hectorvent.floci.services.iam.model.ServiceSpecificCredential;
import io.github.hectorvent.floci.services.iam.model.SigningCertificate;
import io.github.hectorvent.floci.services.iam.model.SshPublicKey;
import io.github.hectorvent.floci.services.iam.model.VirtualMfaDevice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IAM surfaces that sit beside the core user/group/role/policy store:
 * account alias and password policy, login profiles, federation providers,
 * credentials, certificates, virtual MFA, credential reports, and access advisor.
 */
@ApplicationScoped
public class IamExtendedService {

    private static final String ACCOUNT_KEY = "current";
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Pattern CONTEXT_KEY = Pattern.compile("\"((?:aws|iam|s3|sts|kms|ec2):[^\"]+)\"");
    private static final Pattern ACTION_SERVICE = Pattern.compile("\"([a-z0-9-]+):[^\"]+\"");

    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final StorageBackend<String, String> accountAliases;
    private final StorageBackend<String, AccountPasswordPolicy> passwordPolicies;
    private final StorageBackend<String, LoginProfile> loginProfiles;
    private final StorageBackend<String, VirtualMfaDevice> virtualMfaDevices;
    private final StorageBackend<String, SamlProvider> samlProviders;
    private final StorageBackend<String, OidcProvider> oidcProviders;
    private final StorageBackend<String, SshPublicKey> sshPublicKeys;
    private final StorageBackend<String, SigningCertificate> signingCertificates;
    private final StorageBackend<String, ServiceSpecificCredential> serviceCredentials;
    private final StorageBackend<String, ServerCertificate> serverCertificates;
    private final StorageBackend<String, String> credentialReports;
    private final StorageBackend<String, AccessAdvisorJob> accessAdvisorJobs;

    @Inject
    public IamExtendedService(IamService iamService, RegionResolver regionResolver, StorageFactory storageFactory) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.accountAliases = storageFactory.create("iam", "iam-account-aliases.json", new TypeReference<>() {});
        this.passwordPolicies = storageFactory.create("iam", "iam-password-policies.json", new TypeReference<>() {});
        this.loginProfiles = storageFactory.create("iam", "iam-login-profiles.json", new TypeReference<>() {});
        this.virtualMfaDevices = storageFactory.create("iam", "iam-virtual-mfa.json", new TypeReference<>() {});
        this.samlProviders = storageFactory.create("iam", "iam-saml-providers.json", new TypeReference<>() {});
        this.oidcProviders = storageFactory.create("iam", "iam-oidc-providers.json", new TypeReference<>() {});
        this.sshPublicKeys = storageFactory.create("iam", "iam-ssh-keys.json", new TypeReference<>() {});
        this.signingCertificates = storageFactory.create("iam", "iam-signing-certs.json", new TypeReference<>() {});
        this.serviceCredentials = storageFactory.create("iam", "iam-service-credentials.json", new TypeReference<>() {});
        this.serverCertificates = storageFactory.create("iam", "iam-server-certs.json", new TypeReference<>() {});
        this.credentialReports = storageFactory.create("iam", "iam-credential-reports.json", new TypeReference<>() {});
        this.accessAdvisorJobs = storageFactory.create("iam", "iam-access-advisor.json", new TypeReference<>() {});
    }

    // =========================================================================
    // Account alias
    // =========================================================================

    public void createAccountAlias(String alias) {
        require(alias, "AccountAlias");
        accountAliases.put(ACCOUNT_KEY, alias);
    }

    public void deleteAccountAlias(String alias) {
        require(alias, "AccountAlias");
        String existing = accountAliases.get(ACCOUNT_KEY).orElse(null);
        if (existing == null || !existing.equals(alias)) {
            throw new AwsException("NoSuchEntity",
                    "The account alias " + alias + " cannot be found.", 404);
        }
        accountAliases.delete(ACCOUNT_KEY);
    }

    public Optional<String> getAccountAlias() {
        return accountAliases.get(ACCOUNT_KEY);
    }

    // =========================================================================
    // Account password policy
    // =========================================================================

    public AccountPasswordPolicy getAccountPasswordPolicy() {
        return passwordPolicies.get(ACCOUNT_KEY)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "The Password Policy with domain name cannot be found.", 404));
    }

    public AccountPasswordPolicy updateAccountPasswordPolicy(AccountPasswordPolicy policy) {
        passwordPolicies.put(ACCOUNT_KEY, policy);
        return policy;
    }

    public void deleteAccountPasswordPolicy() {
        if (passwordPolicies.get(ACCOUNT_KEY).isEmpty()) {
            throw new AwsException("NoSuchEntity",
                    "The Password Policy with domain name cannot be found.", 404);
        }
        passwordPolicies.delete(ACCOUNT_KEY);
    }

    // =========================================================================
    // Login profiles
    // =========================================================================

    public LoginProfile createLoginProfile(String userName, String password, boolean resetRequired) {
        iamService.getUser(userName);
        if (loginProfiles.get(userName).isPresent()) {
            throw new AwsException("EntityAlreadyExists",
                    "Login Profile for User " + userName + " already exists.", 409);
        }
        LoginProfile profile = new LoginProfile(userName, password, resetRequired);
        loginProfiles.put(userName, profile);
        return profile;
    }

    public LoginProfile getLoginProfile(String userName) {
        iamService.getUser(userName);
        return loginProfiles.get(userName)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Login Profile for User " + userName + " cannot be found.", 404));
    }

    public void updateLoginProfile(String userName, String password, Boolean resetRequired) {
        LoginProfile profile = getLoginProfile(userName);
        if (password != null) {
            profile.setPassword(password);
        }
        if (resetRequired != null) {
            profile.setPasswordResetRequired(resetRequired);
        }
        loginProfiles.put(userName, profile);
    }

    public void deleteLoginProfile(String userName) {
        getLoginProfile(userName);
        loginProfiles.delete(userName);
    }

    // =========================================================================
    // Virtual MFA
    // =========================================================================

    public VirtualMfaDevice createVirtualMfaDevice(String name, String path, Map<String, String> tags) {
        require(name, "VirtualMFADeviceName");
        String normalizedPath = normalizePath(path);
        String serial = iamArn("mfa", normalizedPath, name);
        if (virtualMfaDevices.get(serial).isPresent()) {
            throw new AwsException("EntityAlreadyExists",
                    "MFA device " + serial + " already exists.", 409);
        }
        byte[] seed = new byte[20];
        new SecureRandom().nextBytes(seed);
        String seedB64 = Base64.getEncoder().encodeToString(seed);
        VirtualMfaDevice device = new VirtualMfaDevice(serial, name, normalizedPath, seedB64, seedB64);
        if (tags != null) {
            device.getTags().putAll(tags);
        }
        virtualMfaDevices.put(serial, device);
        return device;
    }

    public VirtualMfaDevice getVirtualMfaDevice(String serialNumber) {
        return virtualMfaDevices.get(serialNumber)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Virtual MFA device " + serialNumber + " cannot be found.", 404));
    }

    public List<VirtualMfaDevice> listVirtualMfaDevices(String assignmentStatus) {
        return virtualMfaDevices.scan(k -> true).stream()
                .filter(d -> assignmentStatus == null
                        || assignmentStatus.isBlank()
                        || "Any".equalsIgnoreCase(assignmentStatus)
                        || ("Assigned".equalsIgnoreCase(assignmentStatus) && d.isAssigned())
                        || ("Unassigned".equalsIgnoreCase(assignmentStatus) && !d.isAssigned()))
                .toList();
    }

    public void enableMfaDevice(String userName, String serialNumber) {
        iamService.getUser(userName);
        VirtualMfaDevice device = getVirtualMfaDevice(serialNumber);
        device.setUserName(userName);
        device.setEnableDate(Instant.now());
        virtualMfaDevices.put(serialNumber, device);
    }

    public void deactivateMfaDevice(String userName, String serialNumber) {
        VirtualMfaDevice device = getVirtualMfaDevice(serialNumber);
        if (userName != null && device.getUserName() != null && !userName.equals(device.getUserName())) {
            throw new AwsException("NoSuchEntity",
                    "MFA device " + serialNumber + " is not assigned to user " + userName + ".", 404);
        }
        device.setUserName(null);
        device.setEnableDate(null);
        virtualMfaDevices.put(serialNumber, device);
    }

    public void deleteVirtualMfaDevice(String serialNumber) {
        getVirtualMfaDevice(serialNumber);
        virtualMfaDevices.delete(serialNumber);
    }

    public Map<String, String> listMfaDeviceTags(String serialNumber) {
        return getVirtualMfaDevice(serialNumber).getTags();
    }

    public void tagMfaDevice(String serialNumber, Map<String, String> tags) {
        VirtualMfaDevice device = getVirtualMfaDevice(serialNumber);
        device.getTags().putAll(tags);
        virtualMfaDevices.put(serialNumber, device);
    }

    public void untagMfaDevice(String serialNumber, List<String> keys) {
        VirtualMfaDevice device = getVirtualMfaDevice(serialNumber);
        keys.forEach(device.getTags()::remove);
        virtualMfaDevices.put(serialNumber, device);
    }

    // =========================================================================
    // SAML providers
    // =========================================================================

    public SamlProvider createSamlProvider(String name, String metadata, String encryptionMode,
                                           Map<String, String> tags) {
        require(name, "Name");
        require(metadata, "SAMLMetadataDocument");
        String arn = iamArn("saml-provider/", "", name);
        if (samlProviders.get(arn).isPresent()) {
            throw new AwsException("EntityAlreadyExists",
                    "SAML provider " + name + " already exists.", 409);
        }
        SamlProvider provider = new SamlProvider(arn, name, UUID.randomUUID().toString(),
                metadata, encryptionMode);
        if (tags != null) {
            provider.getTags().putAll(tags);
        }
        samlProviders.put(arn, provider);
        return provider;
    }

    public SamlProvider getSamlProvider(String arn) {
        return samlProviders.get(arn)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "SAML provider " + arn + " cannot be found.", 404));
    }

    public List<SamlProvider> listSamlProviders() {
        return samlProviders.scan(k -> true);
    }

    public void updateSamlProvider(String arn, String metadata, String encryptionMode) {
        SamlProvider provider = getSamlProvider(arn);
        if (metadata != null) {
            provider.setMetadataDocument(metadata);
        }
        if (encryptionMode != null) {
            provider.setAssertionEncryptionMode(encryptionMode);
        }
        samlProviders.put(arn, provider);
    }

    public void deleteSamlProvider(String arn) {
        getSamlProvider(arn);
        samlProviders.delete(arn);
    }

    public Map<String, String> listSamlProviderTags(String arn) {
        return getSamlProvider(arn).getTags();
    }

    public void tagSamlProvider(String arn, Map<String, String> tags) {
        SamlProvider provider = getSamlProvider(arn);
        provider.getTags().putAll(tags);
        samlProviders.put(arn, provider);
    }

    public void untagSamlProvider(String arn, List<String> keys) {
        SamlProvider provider = getSamlProvider(arn);
        keys.forEach(provider.getTags()::remove);
        samlProviders.put(arn, provider);
    }

    // =========================================================================
    // OIDC providers
    // =========================================================================

    public OidcProvider createOidcProvider(String url, List<String> clientIds,
                                           List<String> thumbprints, Map<String, String> tags) {
        require(url, "Url");
        String host = url.replaceFirst("^https?://", "");
        String arn = iamArn("oidc-provider/", "", host);
        if (oidcProviders.get(arn).isPresent()) {
            throw new AwsException("EntityAlreadyExists",
                    "OpenIDConnect provider " + url + " already exists.", 409);
        }
        OidcProvider provider = new OidcProvider(arn, host);
        if (clientIds != null) {
            provider.getClientIds().addAll(clientIds);
        }
        if (thumbprints != null) {
            provider.getThumbprints().addAll(thumbprints);
        }
        if (tags != null) {
            provider.getTags().putAll(tags);
        }
        oidcProviders.put(arn, provider);
        return provider;
    }

    public OidcProvider getOidcProvider(String arn) {
        return oidcProviders.get(arn)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "OpenIDConnect provider " + arn + " cannot be found.", 404));
    }

    public List<OidcProvider> listOidcProviders() {
        return oidcProviders.scan(k -> true);
    }

    public void addOidcClientId(String arn, String clientId) {
        OidcProvider provider = getOidcProvider(arn);
        if (!provider.getClientIds().contains(clientId)) {
            provider.getClientIds().add(clientId);
            oidcProviders.put(arn, provider);
        }
    }

    public void removeOidcClientId(String arn, String clientId) {
        OidcProvider provider = getOidcProvider(arn);
        provider.getClientIds().remove(clientId);
        oidcProviders.put(arn, provider);
    }

    public void updateOidcThumbprints(String arn, List<String> thumbprints) {
        OidcProvider provider = getOidcProvider(arn);
        provider.setThumbprints(thumbprints != null ? thumbprints : List.of());
        oidcProviders.put(arn, provider);
    }

    public void deleteOidcProvider(String arn) {
        getOidcProvider(arn);
        oidcProviders.delete(arn);
    }

    public Map<String, String> listOidcProviderTags(String arn) {
        return getOidcProvider(arn).getTags();
    }

    public void tagOidcProvider(String arn, Map<String, String> tags) {
        OidcProvider provider = getOidcProvider(arn);
        provider.getTags().putAll(tags);
        oidcProviders.put(arn, provider);
    }

    public void untagOidcProvider(String arn, List<String> keys) {
        OidcProvider provider = getOidcProvider(arn);
        keys.forEach(provider.getTags()::remove);
        oidcProviders.put(arn, provider);
    }

    // =========================================================================
    // SSH public keys
    // =========================================================================

    public SshPublicKey uploadSshPublicKey(String userName, String body) {
        iamService.getUser(userName);
        require(body, "SSHPublicKeyBody");
        String id = "AAAA" + randomId(16);
        SshPublicKey key = new SshPublicKey(userName, id, md5Fingerprint(body), body);
        sshPublicKeys.put(id, key);
        return key;
    }

    public SshPublicKey getSshPublicKey(String userName, String keyId) {
        SshPublicKey key = sshPublicKeys.get(keyId)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "SSH public key " + keyId + " cannot be found.", 404));
        if (userName != null && !userName.equals(key.getUserName())) {
            throw new AwsException("NoSuchEntity",
                    "SSH public key " + keyId + " cannot be found.", 404);
        }
        iamService.getUser(key.getUserName());
        return key;
    }

    public List<SshPublicKey> listSshPublicKeys(String userName) {
        iamService.getUser(userName);
        return sshPublicKeys.scan(k -> true).stream()
                .filter(key -> userName.equals(key.getUserName()))
                .toList();
    }

    public void updateSshPublicKey(String userName, String keyId, String status) {
        SshPublicKey key = getSshPublicKey(userName, keyId);
        if (status != null) {
            key.setStatus(status);
        }
        sshPublicKeys.put(keyId, key);
    }

    public void deleteSshPublicKey(String userName, String keyId) {
        getSshPublicKey(userName, keyId);
        sshPublicKeys.delete(keyId);
    }

    // =========================================================================
    // Signing certificates
    // =========================================================================

    public SigningCertificate uploadSigningCertificate(String userName, String body) {
        iamService.getUser(userName);
        require(body, "CertificateBody");
        String id = randomHex(32);
        SigningCertificate cert = new SigningCertificate(userName, id, body);
        signingCertificates.put(id, cert);
        return cert;
    }

    public List<SigningCertificate> listSigningCertificates(String userName) {
        iamService.getUser(userName);
        return signingCertificates.scan(k -> true).stream()
                .filter(cert -> userName.equals(cert.getUserName()))
                .toList();
    }

    public void updateSigningCertificate(String userName, String certificateId, String status) {
        SigningCertificate cert = signingCertificates.get(certificateId)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Signing certificate " + certificateId + " cannot be found.", 404));
        if (!userName.equals(cert.getUserName())) {
            throw new AwsException("NoSuchEntity",
                    "Signing certificate " + certificateId + " cannot be found.", 404);
        }
        if (status != null) {
            cert.setStatus(status);
        }
        signingCertificates.put(certificateId, cert);
    }

    public void deleteSigningCertificate(String userName, String certificateId) {
        updateSigningCertificate(userName, certificateId, null);
        signingCertificates.delete(certificateId);
    }

    // =========================================================================
    // Service-specific credentials
    // =========================================================================

    public ServiceSpecificCredential createServiceSpecificCredential(String userName, String serviceName,
                                                                     Integer ageDays) {
        iamService.getUser(userName);
        require(serviceName, "ServiceName");
        String id = "ACCA" + randomId(16);
        String serviceUser = userName + "-" + randomId(8).toLowerCase();
        String password = randomSecret(20);
        ServiceSpecificCredential cred = new ServiceSpecificCredential(
                userName, serviceName, id, serviceUser, password);
        if (ageDays != null && ageDays > 0) {
            cred.setExpirationDate(Instant.now().plus(ageDays, ChronoUnit.DAYS));
        }
        serviceCredentials.put(id, cred);
        return cred;
    }

    public List<ServiceSpecificCredential> listServiceSpecificCredentials(String userName, String serviceName) {
        iamService.getUser(userName);
        return serviceCredentials.scan(k -> true).stream()
                .filter(c -> userName.equals(c.getUserName()))
                .filter(c -> serviceName == null || serviceName.isBlank() || serviceName.equals(c.getServiceName()))
                .toList();
    }

    public void updateServiceSpecificCredential(String userName, String credentialId, String status) {
        ServiceSpecificCredential cred = serviceCredentials.get(credentialId)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Service specific credential " + credentialId + " cannot be found.", 404));
        if (userName != null && !userName.equals(cred.getUserName())) {
            throw new AwsException("NoSuchEntity",
                    "Service specific credential " + credentialId + " cannot be found.", 404);
        }
        if (status != null) {
            cred.setStatus(status);
        }
        serviceCredentials.put(credentialId, cred);
    }

    public void deleteServiceSpecificCredential(String userName, String credentialId) {
        updateServiceSpecificCredential(userName, credentialId, null);
        serviceCredentials.delete(credentialId);
    }

    // =========================================================================
    // Server certificates
    // =========================================================================

    public ServerCertificate uploadServerCertificate(String name, String path, String body,
                                                     String chain, String privateKey,
                                                     Map<String, String> tags) {
        require(name, "ServerCertificateName");
        require(body, "CertificateBody");
        if (serverCertificates.get(name).isPresent()) {
            throw new AwsException("EntityAlreadyExists",
                    "Server certificate " + name + " already exists.", 409);
        }
        String normalizedPath = normalizePath(path);
        String id = "ASC" + randomId(16);
        String arn = iamArn("server-certificate", normalizedPath, name);
        ServerCertificate cert = new ServerCertificate(name, id, arn, normalizedPath, body, chain, privateKey);
        if (tags != null) {
            cert.getTags().putAll(tags);
        }
        serverCertificates.put(name, cert);
        return cert;
    }

    public ServerCertificate getServerCertificate(String name) {
        return serverCertificates.get(name)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "The Server Certificate with name " + name + " cannot be found.", 404));
    }

    public List<ServerCertificate> listServerCertificates(String pathPrefix) {
        String prefix = pathPrefix != null ? pathPrefix : "/";
        return serverCertificates.scan(k -> true).stream()
                .filter(c -> c.getPath().startsWith(prefix))
                .toList();
    }

    public void deleteServerCertificate(String name) {
        getServerCertificate(name);
        serverCertificates.delete(name);
    }

    public Map<String, String> listServerCertificateTags(String name) {
        return getServerCertificate(name).getTags();
    }

    public void tagServerCertificate(String name, Map<String, String> tags) {
        ServerCertificate cert = getServerCertificate(name);
        cert.getTags().putAll(tags);
        serverCertificates.put(name, cert);
    }

    public void untagServerCertificate(String name, List<String> keys) {
        ServerCertificate cert = getServerCertificate(name);
        keys.forEach(cert.getTags()::remove);
        serverCertificates.put(name, cert);
    }

    // =========================================================================
    // Account summary / authorization details
    // =========================================================================

    public record AccountCounts(int users, int groups, int roles, int policies,
                                int instanceProfiles, int serverCertificates, int providers,
                                int mfaDevices, int mfaInUse) {}

    public AccountCounts accountCounts() {
        List<IamUser> users = iamService.listUsers("/");
        List<IamGroup> groups = iamService.listGroups("/");
        List<IamRole> roles = iamService.listRoles("/");
        List<IamPolicy> policies = iamService.listPolicies("Local", "/");
        int mfa = virtualMfaDevices.scan(k -> true).size();
        int mfaInUse = (int) virtualMfaDevices.scan(k -> true).stream().filter(VirtualMfaDevice::isAssigned).count();
        return new AccountCounts(
                users.size(), groups.size(), roles.size(), policies.size(),
                iamService.listInstanceProfiles("/").size(),
                serverCertificates.scan(k -> true).size(),
                samlProviders.scan(k -> true).size() + oidcProviders.scan(k -> true).size(),
                mfa, mfaInUse);
    }

    // =========================================================================
    // Credential report
    // =========================================================================

    public String generateCredentialReport() {
        String csv = buildCredentialReportCsv();
        credentialReports.put(ACCOUNT_KEY, Instant.now().toString() + "\n" + csv);
        return "COMPLETE";
    }

    public record CredentialReport(Instant generatedTime, String csv) {}

    public CredentialReport getCredentialReport() {
        String stored = credentialReports.get(ACCOUNT_KEY)
                .orElseThrow(() -> new AwsException("ReportNotPresent",
                        "Credential report not present. Generate one first.", 410));
        int nl = stored.indexOf('\n');
        Instant generated = Instant.parse(stored.substring(0, nl));
        return new CredentialReport(generated, stored.substring(nl + 1));
    }

    private String buildCredentialReportCsv() {
        StringBuilder csv = new StringBuilder();
        csv.append("user,arn,user_creation_time,password_enabled,password_last_used,")
                .append("password_last_changed,password_next_rotation,mfa_active,")
                .append("access_key_1_active,access_key_1_last_rotated,access_key_1_last_used_date,")
                .append("access_key_1_last_used_region,access_key_1_last_used_service,")
                .append("access_key_2_active,access_key_2_last_rotated,access_key_2_last_used_date,")
                .append("access_key_2_last_used_region,access_key_2_last_used_service\n");
        for (IamUser user : iamService.listUsers("/")) {
            boolean hasPassword = loginProfiles.get(user.getUserName()).isPresent();
            boolean mfa = virtualMfaDevices.scan(k -> true).stream()
                    .anyMatch(d -> user.getUserName().equals(d.getUserName()));
            csv.append(user.getUserName()).append(',')
                    .append(user.getArn()).append(',')
                    .append(user.getCreateDate()).append(',')
                    .append(hasPassword).append(',')
                    .append("N/A,N/A,N/A,")
                    .append(mfa)
                    .append(",false,N/A,N/A,N/A,N/A,false,N/A,N/A,N/A,N/A\n");
        }
        return csv.toString();
    }

    // =========================================================================
    // Access advisor
    // =========================================================================

    public AccessAdvisorJob generateServiceLastAccessedDetails(String arn, String granularity) {
        require(arn, "Arn");
        AccessAdvisorJob job = new AccessAdvisorJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setArn(arn);
        job.setGranularity(granularity != null ? granularity : "SERVICE_LEVEL");
        job.setStatus("COMPLETED");
        job.setCreationDate(Instant.now());
        job.setCompletionDate(Instant.now());
        populateAdvisorEntity(job, arn);
        job.setServiceNamespaces(new ArrayList<>(extractServicesFromArn(arn)));
        accessAdvisorJobs.put(job.getJobId(), job);
        return job;
    }

    public AccessAdvisorJob getAccessAdvisorJob(String jobId) {
        return accessAdvisorJobs.get(jobId)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Job " + jobId + " cannot be found.", 404));
    }

    private void populateAdvisorEntity(AccessAdvisorJob job, String arn) {
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        if (arn.contains(":user/")) {
            IamUser user = iamService.getUser(name);
            job.setEntityName(user.getUserName());
            job.setEntityType("USER");
            job.setEntityId(user.getUserId());
            job.setEntityPath(user.getPath());
        } else if (arn.contains(":role/")) {
            IamRole role = iamService.getRole(name);
            job.setEntityName(role.getRoleName());
            job.setEntityType("ROLE");
            job.setEntityId(role.getRoleId());
            job.setEntityPath(role.getPath());
        } else if (arn.contains(":group/")) {
            IamGroup group = iamService.getGroup(name);
            job.setEntityName(group.getGroupName());
            job.setEntityType("GROUP");
            job.setEntityId(group.getGroupId());
            job.setEntityPath(group.getPath());
        } else {
            job.setEntityName(name);
            job.setEntityType("USER");
            job.setEntityId(name);
            job.setEntityPath("/");
        }
    }

    private Set<String> extractServicesFromArn(String arn) {
        Set<String> services = new LinkedHashSet<>();
        for (String document : identityDocuments(arn)) {
            services.addAll(extractServices(document));
        }
        if (services.isEmpty()) {
            services.add("iam");
        }
        return services;
    }

    // =========================================================================
    // Policies granting service access / context keys
    // =========================================================================

    public record PolicyGrant(String policyName, String policyType, String policyArn,
                              String entityType, String entityName) {}

    public List<PolicyGrant> policiesGrantingAccess(String arn, String serviceNamespace) {
        List<PolicyGrant> grants = new ArrayList<>();
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        if (arn.contains(":user/")) {
            IamUser user = iamService.getUser(name);
            user.getInlinePolicies().forEach((policyName, document) -> {
                if (documentGrantsService(document, serviceNamespace)) {
                    grants.add(new PolicyGrant(policyName, "INLINE", null, "USER", name));
                }
            });
            for (String policyArn : user.getAttachedPolicyArns()) {
                IamPolicy policy = iamService.getPolicy(policyArn);
                if (documentGrantsService(policy.getDefaultDocument(), serviceNamespace)) {
                    grants.add(new PolicyGrant(policy.getPolicyName(), "MANAGED", policyArn, "USER", name));
                }
            }
        } else if (arn.contains(":role/")) {
            IamRole role = iamService.getRole(name);
            role.getInlinePolicies().forEach((policyName, document) -> {
                if (documentGrantsService(document, serviceNamespace)) {
                    grants.add(new PolicyGrant(policyName, "INLINE", null, "ROLE", name));
                }
            });
            for (String policyArn : role.getAttachedPolicyArns()) {
                IamPolicy policy = iamService.getPolicy(policyArn);
                if (documentGrantsService(policy.getDefaultDocument(), serviceNamespace)) {
                    grants.add(new PolicyGrant(policy.getPolicyName(), "MANAGED", policyArn, "ROLE", name));
                }
            }
        }
        return grants;
    }

    public List<String> contextKeysForDocuments(List<String> documents) {
        Set<String> keys = new LinkedHashSet<>();
        for (String document : documents) {
            if (document == null) {
                continue;
            }
            Matcher matcher = CONTEXT_KEY.matcher(document);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return new ArrayList<>(keys);
    }

    public List<String> contextKeysForPrincipal(String arn, List<String> extraDocuments) {
        List<String> documents = new ArrayList<>(identityDocuments(arn));
        if (extraDocuments != null) {
            documents.addAll(extraDocuments);
        }
        return contextKeysForDocuments(documents);
    }

    private List<String> identityDocuments(String arn) {
        List<String> documents = new ArrayList<>();
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        try {
            if (arn.contains(":user/")) {
                IamUser user = iamService.getUser(name);
                documents.addAll(user.getInlinePolicies().values());
                for (String policyArn : user.getAttachedPolicyArns()) {
                    documents.add(iamService.getPolicy(policyArn).getDefaultDocument());
                }
            } else if (arn.contains(":role/")) {
                IamRole role = iamService.getRole(name);
                documents.addAll(role.getInlinePolicies().values());
                for (String policyArn : role.getAttachedPolicyArns()) {
                    documents.add(iamService.getPolicy(policyArn).getDefaultDocument());
                }
            } else if (arn.contains(":group/")) {
                IamGroup group = iamService.getGroup(name);
                documents.addAll(group.getInlinePolicies().values());
                for (String policyArn : group.getAttachedPolicyArns()) {
                    documents.add(iamService.getPolicy(policyArn).getDefaultDocument());
                }
            }
        } catch (AwsException ignored) {
            // Missing principal — callers that need a hard 404 call getUser/getRole themselves.
        }
        return documents;
    }

    private static boolean documentGrantsService(String document, String serviceNamespace) {
        return document != null && extractServices(document).contains(serviceNamespace);
    }

    private static Set<String> extractServices(String document) {
        Set<String> services = new LinkedHashSet<>();
        if (document == null) {
            return services;
        }
        Matcher matcher = ACTION_SERVICE.matcher(document);
        while (matcher.find()) {
            services.add(matcher.group(1));
        }
        return services;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String iamArn(String resourceType, String path, String name) {
        return AwsArnUtils.Arn.of("iam", "", regionResolver.getAccountId(), resourceType + path + name).toString();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String normalized = path;
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", name + " is required.", 400);
        }
    }

    private static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(ThreadLocalRandom.current().nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString(ThreadLocalRandom.current().nextInt(16)));
        }
        return sb.toString();
    }

    private static String randomSecret(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String md5Fingerprint(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            StringBuilder colon = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) {
                    colon.append(':');
                }
                colon.append(hex, i, i + 2);
            }
            return colon.toString();
        } catch (Exception e) {
            return "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00";
        }
    }
}
