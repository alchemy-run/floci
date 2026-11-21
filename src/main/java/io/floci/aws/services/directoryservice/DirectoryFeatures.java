package io.floci.aws.services.directoryservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.aws.services.directoryservice.DirectoryStore.error;
import static io.floci.aws.services.directoryservice.DirectoryStore.exists;
import static io.floci.aws.services.directoryservice.DirectoryStore.invalid;
import static io.floci.aws.services.directoryservice.DirectoryStore.missing;
import static io.floci.aws.services.directoryservice.DirectoryStore.now;
import static io.floci.aws.services.directoryservice.DirectoryStore.optional;
import static io.floci.aws.services.directoryservice.DirectoryStore.strings;
import static io.floci.aws.services.directoryservice.DirectoryStore.text;
import static io.floci.aws.services.directoryservice.DirectoryStore.unsupported;

/**
 * What a directory is configured with beyond its placement: trusts, conditional forwarders, IP routes, RADIUS,
 * the access URL alias and single sign-on, certificates with LDAPS and client authentication, directory settings,
 * schema extensions, log subscriptions, event topics, self-managed AD assessments with hybrid directories, CA
 * enrollment policies and Directory Service Data access.
 * <p>
 * A trust verifies when the remote domain is a directory floci knows (in any account) whose DNS addresses the
 * trust's conditional forwarders, or the directory's own, reach, and fails verification otherwise. An assessment
 * passes when its DNS addresses answer and its instances exist in its VPC.
 */
@ApplicationScoped
public class DirectoryFeatures {

    static final String TRUSTS = "trusts";
    static final String ASSESSMENTS = "assessments";
    private static final Pattern CN = Pattern.compile("CN=([^,]+)");
    /** Managed Microsoft AD's configurable settings and their defaults. */
    private static final Map<String, String[]> SETTINGS = new LinkedHashMap<>();

    static {
        for (String protocol : List.of("PCT_1_0", "SSL_2_0", "SSL_3_0", "TLS_1_0", "TLS_1_1")) {
            SETTINGS.put(protocol, new String[] {"Protocol", protocol.startsWith("TLS") ? "Enable" : "Disable"});
        }
        for (String cipher : List.of("AES_128_128", "DES_56_56", "3DES_168_168", "RC2_40_128", "RC2_56_128",
                "RC2_128_128", "RC4_40_128", "RC4_56_128", "RC4_64_128", "RC4_128_128")) {
            SETTINGS.put(cipher, new String[] {"Cipher", Set.of("AES_128_128", "3DES_168_168").contains(cipher)
                    ? "Enable" : "Disable"});
        }
        SETTINGS.put("CERTIFICATE_BACKDATING_COMPENSATION", new String[] {"Certificate-Based Authentication",
            "1577923200"});
        SETTINGS.put("CERTIFICATE_BASED_AUTHENTICATION", new String[] {"Certificate-Based Authentication",
            "Compatibility"});
    }

    private final DirectoryStore store;
    private final DirectoryService directories;
    private final Instance<SnsService> sns;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<Ec2Service> ec2;
    private final Instance<SecretsManagerService> secrets;

    @Inject
    public DirectoryFeatures(DirectoryStore store, DirectoryService directories, Instance<SnsService> sns,
                             Instance<CloudWatchLogsService> logs, Instance<Ec2Service> ec2,
                             Instance<SecretsManagerService> secrets) {
        this.store = store;
        this.directories = directories;
        this.sns = sns;
        this.logs = logs;
        this.ec2 = ec2;
        this.secrets = secrets;
    }

    private ObjectNode meta(ObjectNode directory) {
        return (ObjectNode) directory.get("_");
    }

    private boolean ready(JsonNode record) {
        return System.currentTimeMillis() >= record.path("readyAt").asLong();
    }

    private long readyAt() {
        return System.currentTimeMillis() + directories.transitionMillis();
    }

    // ---------------------------------------------------------------- conditional forwarders

    private static void addresses(JsonNode input, boolean required) {
        List<String> ips = strings(input.path("DnsIpAddrs"));
        if (required && ips.isEmpty() && strings(input.path("DnsIpv6Addrs")).isEmpty()) {
            throw invalid("DnsIpAddrs is required.");
        }
        for (String ip : ips) {
            if (!ip.matches("^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$")) {
                throw invalid("Invalid IP address: " + ip + ".");
            }
        }
    }

    public synchronized ObjectNode createConditionalForwarder(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Conditional forwarders");
        String domain = text(input, "RemoteDomainName");
        addresses(input, true);
        ObjectNode forwarders = meta(directory).withObject("forwarders");
        for (String existing : DirectoryService.names(forwarders)) {
            if (existing.equalsIgnoreCase(domain)) {
                throw exists("A conditional forwarder for " + domain + " already exists.");
            }
        }
        forwarders.set(domain, forwarder(domain, input));
        directories.save(region, directory);
        return store.object();
    }

    private ObjectNode forwarder(String domain, JsonNode input) {
        ObjectNode forwarder = store.object();
        forwarder.put("RemoteDomainName", domain);
        forwarder.set("DnsIpAddrs", input.path("DnsIpAddrs").isArray() ? input.get("DnsIpAddrs").deepCopy()
                : store.array());
        if (input.path("DnsIpv6Addrs").isArray()) {
            forwarder.set("DnsIpv6Addrs", input.get("DnsIpv6Addrs").deepCopy());
        }
        forwarder.put("ReplicationScope", "Domain");
        return forwarder;
    }

    private String forwarderName(ObjectNode directory, String domain) {
        for (String existing : DirectoryService.names(meta(directory).withObject("forwarders"))) {
            if (existing.equalsIgnoreCase(domain)) {
                return existing;
            }
        }
        throw missing("No conditional forwarder for " + domain + " exists.");
    }

    public synchronized ObjectNode updateConditionalForwarder(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Conditional forwarders");
        String domain = forwarderName(directory, text(input, "RemoteDomainName"));
        addresses(input, true);
        meta(directory).withObject("forwarders").set(domain, forwarder(domain, input));
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode deleteConditionalForwarder(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Conditional forwarders");
        String domain = forwarderName(directory, text(input, "RemoteDomainName"));
        meta(directory).withObject("forwarders").remove(domain);
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode describeConditionalForwarders(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Conditional forwarders");
        List<String> wanted = strings(input.path("RemoteDomainNames"));
        ArrayNode result = store.array();
        for (String domain : wanted) {
            forwarderName(directory, domain);
        }
        meta(directory).withObject("forwarders").properties().forEach(entry -> {
            if (wanted.isEmpty() || wanted.stream().anyMatch(name -> name.equalsIgnoreCase(entry.getKey()))) {
                result.add(entry.getValue().deepCopy());
            }
        });
        ObjectNode response = store.object();
        response.set("ConditionalForwarders", result);
        return response;
    }

    // ---------------------------------------------------------------- trusts

    public synchronized ObjectNode createTrust(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Trusts");
        String domain = text(input, "RemoteDomainName");
        String password = text(input, "TrustPassword");
        if (password.length() > 128) {
            throw invalid("TrustPassword must be at most 128 characters.");
        }
        String direction = text(input, "TrustDirection");
        if (!Set.of("One-Way: Outgoing", "One-Way: Incoming", "Two-Way").contains(direction)) {
            throw invalid("TrustDirection must be One-Way: Outgoing, One-Way: Incoming or Two-Way.");
        }
        String type = input.path("TrustType").asText("Forest");
        if (!Set.of("Forest", "External").contains(type)) {
            throw invalid("TrustType must be Forest or External.");
        }
        String directoryId = directory.path("DirectoryId").asText();
        for (ObjectNode trust : store.list(region, TRUSTS, trust -> directoryId.equals(trust.path("DirectoryId")
                .asText()) && domain.equalsIgnoreCase(trust.path("RemoteDomainName").asText()))) {
            throw exists("A trust with " + domain + " already exists.");
        }
        if (input.path("ConditionalForwarderIpAddrs").isArray() && !input.get("ConditionalForwarderIpAddrs")
                .isEmpty()) {
            ObjectNode request = store.object();
            request.set("DnsIpAddrs", input.get("ConditionalForwarderIpAddrs").deepCopy());
            meta(directory).withObject("forwarders").set(domain, forwarder(domain, request));
            directories.save(region, directory);
        }
        double now = now();
        ObjectNode trust = store.record();
        String id = store.id("t");
        trust.put("DirectoryId", directoryId);
        trust.put("TrustId", id);
        trust.put("RemoteDomainName", domain);
        trust.put("TrustType", type);
        trust.put("TrustDirection", direction);
        trust.put("TrustState", directories.transitionMillis() <= 0 ? "Verified" : "Creating");
        trust.put("CreatedDateTime", now);
        trust.put("LastUpdatedDateTime", now);
        trust.put("StateLastUpdatedDateTime", now);
        trust.put("SelectiveAuth", input.path("SelectiveAuth").asText("Disabled"));
        ObjectNode trustMeta = (ObjectNode) trust.get("_");
        trustMeta.put("readyAt", readyAt()).put("password", password);
        store.put(region, TRUSTS, id, trust);
        if (directories.transitionMillis() <= 0) {
            verify(region, trust);
        }
        return store.object().put("TrustId", id);
    }

    /** Verifies a trust: its remote domain must be a directory reachable through a forwarder or the VPC. */
    private void verify(String region, ObjectNode trust) {
        ObjectNode directory = store.get(region, DirectoryService.DIRECTORIES, trust.path("DirectoryId").asText())
                .orElse(null);
        String domain = trust.path("RemoteDomainName").asText();
        String reason = "The remote domain " + domain + " could not be reached; add a conditional forwarder to its "
                + "DNS servers.";
        if (directory != null) {
            List<String> reachable = new ArrayList<>();
            JsonNode forwarders = directory.path("_").path("forwarders");
            forwarders.properties().forEach(entry -> {
                if (entry.getKey().equalsIgnoreCase(domain)) {
                    reachable.addAll(strings(entry.getValue().path("DnsIpAddrs")));
                }
            });
            for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : directories.allDirectories(region)) {
                ObjectNode remote = entry.value();
                if (!domain.equalsIgnoreCase(remote.path("Name").asText())
                        || remote.path("DirectoryId").asText().equals(directory.path("DirectoryId").asText())) {
                    continue;
                }
                boolean sameVpc = remote.path("VpcSettings").path("VpcId").asText("")
                        .equals(directory.path("VpcSettings").path("VpcId").asText("-"));
                boolean forwarded = strings(remote.path("DnsIpAddrs")).stream().anyMatch(reachable::contains);
                if (sameVpc || forwarded) {
                    reason = null;
                }
            }
        }
        double now = now();
        trust.put("TrustState", reason == null ? "Verified" : "VerifyFailed");
        if (reason != null) {
            trust.put("TrustStateReason", reason);
        } else {
            trust.remove("TrustStateReason");
        }
        trust.put("StateLastUpdatedDateTime", now);
        trust.put("LastUpdatedDateTime", now);
        store.put(region, TRUSTS, trust.path("TrustId").asText(), trust);
    }

    private ObjectNode trust(String region, String id) {
        ObjectNode trust = store.get(region, TRUSTS, id).orElseThrow(() -> missing("Trust " + id
                + " does not exist."));
        String state = trust.path("TrustState").asText();
        if (Set.of("Creating", "Created", "Verifying", "Updating").contains(state) && ready(trust.path("_"))) {
            verify(region, trust);
        } else if ("Deleting".equals(state) && ready(trust.path("_"))) {
            store.delete(region, TRUSTS, id);
            trust.put("TrustState", "Deleted");
        }
        return trust;
    }

    public synchronized ObjectNode describeTrusts(JsonNode input, String region) {
        String directoryId = optional(input, "DirectoryId");
        if (directoryId != null) {
            directories.directory(region, directoryId);
        }
        List<String> ids = strings(input.path("TrustIds"));
        for (String id : ids) {
            trust(region, id);
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, TRUSTS, trust -> (directoryId == null || directoryId.equals(
                trust.path("DirectoryId").asText())) && (ids.isEmpty() || ids.contains(trust.path("TrustId")
                .asText())))) {
            items.add(store.view(trust(region, stored.path("TrustId").asText())));
        }
        return store.page(items, input, "Trusts", 100);
    }

    public synchronized ObjectNode verifyTrust(JsonNode input, String region) {
        ObjectNode trust = trust(region, text(input, "TrustId"));
        trust.put("TrustState", directories.transitionMillis() <= 0 ? "Verified" : "Verifying");
        ((ObjectNode) trust.get("_")).put("readyAt", readyAt());
        store.put(region, TRUSTS, trust.path("TrustId").asText(), trust);
        if (directories.transitionMillis() <= 0) {
            verify(region, trust);
        }
        return store.object().put("TrustId", trust.path("TrustId").asText());
    }

    public synchronized ObjectNode updateTrust(JsonNode input, String region) {
        ObjectNode trust = trust(region, text(input, "TrustId"));
        if (input.hasNonNull("SelectiveAuth")) {
            String selective = input.get("SelectiveAuth").asText();
            if (!Set.of("Enabled", "Disabled").contains(selective)) {
                throw invalid("SelectiveAuth must be Enabled or Disabled.");
            }
            trust.put("SelectiveAuth", selective);
        }
        trust.put("TrustState", directories.transitionMillis() <= 0 ? "Updated" : "Updating");
        trust.put("LastUpdatedDateTime", now());
        ((ObjectNode) trust.get("_")).put("readyAt", readyAt());
        store.put(region, TRUSTS, trust.path("TrustId").asText(), trust);
        return store.object().put("RequestId", UUID.randomUUID().toString())
                .put("TrustId", trust.path("TrustId").asText());
    }

    public synchronized ObjectNode deleteTrust(JsonNode input, String region) {
        ObjectNode trust = trust(region, text(input, "TrustId"));
        if (input.path("DeleteAssociatedConditionalForwarder").asBoolean(false)) {
            store.get(region, DirectoryService.DIRECTORIES, trust.path("DirectoryId").asText()).ifPresent(dir -> {
                ObjectNode forwarders = ((ObjectNode) dir.get("_")).withObject("forwarders");
                for (String name : DirectoryService.names(forwarders)) {
                    if (name.equalsIgnoreCase(trust.path("RemoteDomainName").asText())) {
                        forwarders.remove(name);
                    }
                }
                directories.save(region, dir);
            });
        }
        if (directories.transitionMillis() <= 0) {
            store.delete(region, TRUSTS, trust.path("TrustId").asText());
        } else {
            trust.put("TrustState", "Deleting");
            ((ObjectNode) trust.get("_")).put("readyAt", readyAt());
            store.put(region, TRUSTS, trust.path("TrustId").asText(), trust);
        }
        return store.object().put("TrustId", trust.path("TrustId").asText());
    }

    // ---------------------------------------------------------------- IP routes

    public synchronized ObjectNode addIpRoutes(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "IP routes");
        ObjectNode routes = meta(directory).withObject("routes");
        for (JsonNode route : input.path("IpRoutes")) {
            String cidr = route.path("CidrIp").asText(route.path("CidrIpv6").asText(null));
            if (cidr == null || !cidr.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}/([1-2]?[0-9]|3[0-2])$|^[0-9a-fA-F:]+/"
                    + "\\d{1,3}$")) {
                throw invalid("Invalid CIDR: " + cidr + ".");
            }
            if (routes.has(cidr)) {
                throw exists("The route " + cidr + " already exists.");
            }
            if (routes.size() >= 100) {
                throw error("IpRouteLimitExceededException", "A directory can have at most 100 IP routes.");
            }
            ObjectNode info = store.object();
            info.put("DirectoryId", directory.path("DirectoryId").asText());
            info.put(cidr.contains(":") ? "CidrIpv6" : "CidrIp", cidr);
            info.put("IpRouteStatusMsg", "Added");
            info.put("AddedDateTime", now());
            if (route.hasNonNull("Description")) {
                info.put("Description", route.get("Description").asText());
            }
            routes.set(cidr, info);
        }
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode removeIpRoutes(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "IP routes");
        ObjectNode routes = meta(directory).withObject("routes");
        List<String> cidrs = strings(input.path("CidrIps"));
        cidrs.addAll(strings(input.path("CidrIpv6s")));
        for (String cidr : cidrs) {
            if (!routes.has(cidr)) {
                throw missing("The route " + cidr + " does not exist.");
            }
            routes.remove(cidr);
        }
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode listIpRoutes(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        List<ObjectNode> items = new ArrayList<>();
        directory.path("_").path("routes").forEach(route -> items.add(((ObjectNode) route).deepCopy()));
        return store.page(items, input, "IpRoutesInfo", 100);
    }

    // ---------------------------------------------------------------- RADIUS

    private ObjectNode radius(JsonNode settings) {
        if (!settings.path("RadiusServers").isArray() || settings.get("RadiusServers").isEmpty()) {
            throw invalid("RadiusSettings.RadiusServers is required.");
        }
        int port = settings.path("RadiusPort").asInt(1812);
        if (port < 1025 || port > 65535) {
            throw invalid("RadiusPort must be between 1025 and 65535.");
        }
        int timeout = settings.path("RadiusTimeout").asInt(5);
        if (timeout < 1 || timeout > 50) {
            throw invalid("RadiusTimeout must be between 1 and 50 seconds.");
        }
        int retries = settings.path("RadiusRetries").asInt(4);
        if (retries < 0 || retries > 10) {
            throw invalid("RadiusRetries must be between 0 and 10.");
        }
        String protocol = settings.path("AuthenticationProtocol").asText("PAP");
        if (!Set.of("PAP", "CHAP", "MS-CHAPv1", "MS-CHAPv2").contains(protocol)) {
            throw invalid("AuthenticationProtocol must be PAP, CHAP, MS-CHAPv1 or MS-CHAPv2.");
        }
        String secret = text(settings, "SharedSecret");
        if (secret.length() < 8 || secret.length() > 512) {
            throw invalid("SharedSecret must be 8 to 512 characters.");
        }
        ObjectNode result = ((ObjectNode) settings).deepCopy();
        result.put("RadiusPort", port).put("RadiusTimeout", timeout).put("RadiusRetries", retries)
                .put("AuthenticationProtocol", protocol);
        return result;
    }

    public synchronized ObjectNode enableRadius(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        if (!Set.of("MicrosoftAD", "ADConnector").contains(directory.path("Type").asText())) {
            throw invalid("RADIUS is supported for Managed Microsoft AD and AD Connector directories.");
        }
        if (meta(directory).has("radius")) {
            throw exists("RADIUS is already enabled for directory " + directory.path("DirectoryId").asText() + ".");
        }
        meta(directory).set("radius", radius(input.path("RadiusSettings")));
        meta(directory).put("radiusReadyAt", readyAt());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode updateRadius(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        if (!meta(directory).has("radius")) {
            throw missing("RADIUS is not enabled for directory " + directory.path("DirectoryId").asText() + ".");
        }
        meta(directory).set("radius", radius(input.path("RadiusSettings")));
        meta(directory).put("radiusReadyAt", readyAt());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode disableRadius(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        meta(directory).remove("radius");
        meta(directory).remove("radiusReadyAt");
        directories.save(region, directory);
        return store.object();
    }

    // ---------------------------------------------------------------- alias and single sign-on

    public synchronized ObjectNode createAlias(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        String alias = text(input, "Alias");
        if (!alias.matches("^(?!D-|d-)([\\da-zA-Z]+)([-]*[\\da-zA-Z])*$") || alias.length() > 62) {
            throw invalid("Invalid alias: " + alias + ".");
        }
        if (!directory.path("Alias").asText().equals(directory.path("DirectoryId").asText())) {
            throw exists("Directory " + directory.path("DirectoryId").asText() + " already has the alias "
                    + directory.path("Alias").asText() + ".");
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : directories.allDirectories(region)) {
            if (alias.equalsIgnoreCase(entry.value().path("Alias").asText())) {
                throw exists("The alias " + alias + " is already in use.");
            }
        }
        directory.put("Alias", alias);
        directory.put("AccessUrl", alias + ".awsapps.com");
        directories.save(region, directory);
        return store.object().put("DirectoryId", directory.path("DirectoryId").asText()).put("Alias", alias);
    }

    private void sso(JsonNode input, String region, boolean enabled) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        if (enabled && directory.path("Alias").asText().equals(directory.path("DirectoryId").asText())) {
            throw error("InsufficientPermissionsException", "Create an alias for the directory before enabling "
                    + "single sign-on.");
        }
        String user = optional(input, "UserName");
        if (user != null) {
            String known = directory.path("_").path("users").path(user).asText(null);
            if (known == null || !known.equals(optional(input, "Password"))) {
                throw error("AuthenticationFailedException", "The credentials for " + user + " are not valid.");
            }
        }
        directory.put("SsoEnabled", enabled);
        directories.save(region, directory);
    }

    public synchronized ObjectNode enableSso(JsonNode input, String region) {
        sso(input, region, true);
        return store.object();
    }

    public synchronized ObjectNode disableSso(JsonNode input, String region) {
        sso(input, region, false);
        return store.object();
    }

    // ---------------------------------------------------------------- certificates, LDAPS and client authentication

    public synchronized ObjectNode registerCertificate(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Certificates");
        if (!"Active".equals(directory.path("Stage").asText())) {
            throw error("DirectoryUnavailableException", "Directory " + directory.path("DirectoryId").asText()
                    + " is not Active.");
        }
        String pem = text(input, "CertificateData");
        X509Certificate certificate;
        try {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (CertificateException | ClassCastException e) {
            throw error("InvalidCertificateException", "The certificate is not a valid PEM-encoded X.509 "
                    + "certificate.");
        }
        if (certificate.getNotAfter().getTime() < System.currentTimeMillis()) {
            throw error("InvalidCertificateException", "The certificate has expired.");
        }
        String type = input.path("Type").asText("ClientLDAPS");
        if (!Set.of("ClientCertAuth", "ClientLDAPS").contains(type)) {
            throw invalid("Type must be ClientCertAuth or ClientLDAPS.");
        }
        ObjectNode certificates = meta(directory).withObject("certificates");
        String serial = certificate.getSerialNumber().toString(16);
        for (JsonNode existing : certificates) {
            if (serial.equals(existing.path("_serial").asText())) {
                throw error("CertificateAlreadyExistsException", "The certificate is already registered.");
            }
        }
        if (certificates.size() >= 5) {
            throw error("CertificateLimitExceededException", "A directory can register at most five certificates.");
        }
        Matcher cn = CN.matcher(certificate.getSubjectX500Principal().getName());
        ObjectNode registered = store.object();
        String id = store.id("c");
        registered.put("CertificateId", id);
        registered.put("State", "Registered");
        registered.put("CommonName", cn.find() ? cn.group(1) : certificate.getSubjectX500Principal().getName());
        registered.put("RegisteredDateTime", now());
        registered.put("ExpiryDateTime", certificate.getNotAfter().getTime() / 1000.0);
        registered.put("Type", type);
        if (input.path("ClientCertAuthSettings").isObject()) {
            registered.set("ClientCertAuthSettings", input.get("ClientCertAuthSettings").deepCopy());
        }
        registered.put("_serial", serial);
        certificates.set(id, registered);
        directories.save(region, directory);
        return store.object().put("CertificateId", id);
    }

    private ObjectNode certificate(ObjectNode directory, String id) {
        JsonNode certificate = directory.path("_").path("certificates").get(id);
        if (certificate == null) {
            throw error("CertificateDoesNotExistException", "Certificate " + id + " does not exist.");
        }
        return (ObjectNode) certificate;
    }

    private ObjectNode certificateView(ObjectNode certificate) {
        ObjectNode view = certificate.deepCopy();
        view.remove("_serial");
        return view;
    }

    public synchronized ObjectNode describeCertificate(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Certificates");
        ObjectNode response = store.object();
        response.set("Certificate", certificateView(certificate(directory, text(input, "CertificateId"))));
        return response;
    }

    public synchronized ObjectNode listCertificates(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Certificates");
        List<ObjectNode> items = new ArrayList<>();
        directory.path("_").path("certificates").forEach(certificate -> {
            ObjectNode view = certificateView((ObjectNode) certificate);
            view.remove("RegisteredDateTime");
            view.remove("StateReason");
            view.remove("ClientCertAuthSettings");
            items.add(view);
        });
        return store.page(items, input, "CertificatesInfo", 50);
    }

    public synchronized ObjectNode deregisterCertificate(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Certificates");
        ObjectNode certificate = certificate(directory, text(input, "CertificateId"));
        String type = certificate.path("Type").asText();
        boolean inUse = "ClientLDAPS".equals(type) ? "Enabled".equals(directory.path("_").path("ldaps").asText())
                : directory.path("_").path("clientAuth").has("SmartCard");
        if (inUse && countOf(directory, type) == 1) {
            throw error("CertificateInUseException", "The certificate is in use; disable "
                    + ("ClientLDAPS".equals(type) ? "LDAPS" : "client authentication") + " first.");
        }
        meta(directory).withObject("certificates").remove(certificate.path("CertificateId").asText());
        directories.save(region, directory);
        return store.object();
    }

    private int countOf(ObjectNode directory, String type) {
        int count = 0;
        for (JsonNode certificate : directory.path("_").path("certificates")) {
            count += type.equals(certificate.path("Type").asText()) ? 1 : 0;
        }
        return count;
    }

    public synchronized ObjectNode enableLDAPS(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "LDAPS");
        if (!"Client".equals(text(input, "Type"))) {
            throw invalid("Type must be Client.");
        }
        if (countOf(directory, "ClientLDAPS") == 0) {
            throw error("NoAvailableCertificateException", "Register a ClientLDAPS certificate before enabling "
                    + "LDAPS.");
        }
        if ("Enabled".equals(directory.path("_").path("ldaps").asText())) {
            throw error("InvalidLDAPSStatusException", "LDAPS is already enabled.");
        }
        meta(directory).put("ldaps", "Enabled");
        meta(directory).put("ldapsUpdated", now());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode disableLDAPS(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "LDAPS");
        if (!"Enabled".equals(directory.path("_").path("ldaps").asText())) {
            throw error("InvalidLDAPSStatusException", "LDAPS is not enabled.");
        }
        meta(directory).put("ldaps", "Disabled");
        meta(directory).put("ldapsUpdated", now());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode describeLDAPSSettings(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "LDAPS");
        List<ObjectNode> items = new ArrayList<>();
        ObjectNode info = store.object();
        info.put("LDAPSStatus", directory.path("_").path("ldaps").asText("Disabled"));
        if (directory.path("_").has("ldapsUpdated")) {
            info.put("LastUpdatedDateTime", directory.path("_").path("ldapsUpdated").asDouble());
        }
        items.add(info);
        return store.page(items, input, "LDAPSSettingsInfo", 50);
    }

    public synchronized ObjectNode enableClientAuthentication(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Client authentication");
        String type = text(input, "Type");
        if (!Set.of("SmartCard", "SmartCardOrPassword").contains(type)) {
            throw invalid("Type must be SmartCard or SmartCardOrPassword.");
        }
        if (countOf(directory, "ClientCertAuth") == 0) {
            throw error("NoAvailableCertificateException", "Register a ClientCertAuth certificate before enabling "
                    + "client authentication.");
        }
        ObjectNode auth = meta(directory).withObject("clientAuth");
        if (auth.has(type)) {
            throw error("InvalidClientAuthStatusException", type + " client authentication is already enabled.");
        }
        auth.put(type, now());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode disableClientAuthentication(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Client authentication");
        String type = text(input, "Type");
        ObjectNode auth = meta(directory).withObject("clientAuth");
        if (!auth.has(type)) {
            throw error("InvalidClientAuthStatusException", type + " client authentication is not enabled.");
        }
        auth.remove(type);
        meta(directory).withObject("clientAuthDisabled").put(type, now());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode describeClientAuthenticationSettings(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Client authentication");
        String wanted = optional(input, "Type");
        List<ObjectNode> items = new ArrayList<>();
        for (String type : List.of("SmartCard", "SmartCardOrPassword")) {
            if (wanted != null && !wanted.equals(type)) {
                continue;
            }
            JsonNode enabled = directory.path("_").path("clientAuth").path(type);
            ObjectNode info = store.object().put("Type", type).put("Status", enabled.isMissingNode() ? "Disabled"
                    : "Enabled");
            double at = enabled.isMissingNode() ? directory.path("_").path("clientAuthDisabled").path(type)
                    .asDouble(directory.path("LaunchTime").asDouble()) : enabled.asDouble();
            info.put("LastUpdatedDateTime", at);
            items.add(info);
        }
        return store.page(items, input, "ClientAuthenticationSettingsInfo", 50);
    }

    // ---------------------------------------------------------------- settings

    public synchronized ObjectNode describeSettings(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Directory settings");
        String status = optional(input, "Status");
        JsonNode requested = directory.path("_").path("settings");
        List<ObjectNode> items = new ArrayList<>();
        SETTINGS.forEach((name, definition) -> {
            JsonNode request = requested.path(name);
            String applied = request.isMissingNode() ? definition[1] : request.path("value").asText();
            String requestStatus = request.isMissingNode() ? "Default"
                    : ready(request) ? "Updated" : "Updating";
            if (status != null && !status.equals(requestStatus)) {
                return;
            }
            ObjectNode entry = store.object();
            entry.put("Type", definition[0]);
            entry.put("Name", name);
            entry.put("AllowedValues", "Certificate-Based Authentication".equals(definition[0])
                    ? ("CERTIFICATE_BASED_AUTHENTICATION".equals(name) ? "[\"Compatibility\",\"Full "
                    + "Enforcement\"]" : "Epoch seconds") : "[\"Enable\",\"Disable\"]");
            entry.put("AppliedValue", applied);
            entry.put("DataType", "CERTIFICATE_BACKDATING_COMPENSATION".equals(name) ? "Integer" : "Enum");
            entry.put("RequestStatus", requestStatus);
            if (!request.isMissingNode()) {
                entry.put("RequestedValue", request.path("value").asText());
                entry.put("LastRequestedDateTime", request.path("requested").asDouble());
                entry.put("LastUpdatedDateTime", request.path("requested").asDouble());
            }
            items.add(entry);
        });
        ObjectNode response = store.page(items, input, "SettingEntries", 100);
        response.put("DirectoryId", directory.path("DirectoryId").asText());
        return response;
    }

    public synchronized ObjectNode updateSettings(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Directory settings");
        if (!"Active".equals(directory.path("Stage").asText())) {
            throw error("DirectoryUnavailableException", "Directory " + directory.path("DirectoryId").asText()
                    + " is not Active.");
        }
        ObjectNode settings = meta(directory).withObject("settings");
        for (JsonNode setting : input.path("Settings")) {
            String name = text(setting, "Name");
            String value = text(setting, "Value");
            String[] definition = SETTINGS.get(name);
            if (definition == null) {
                throw error("UnsupportedSettingsException", "Setting " + name + " is not supported.");
            }
            boolean valid = switch (name) {
                case "CERTIFICATE_BACKDATING_COMPENSATION" -> value.matches("^\\d+$");
                case "CERTIFICATE_BASED_AUTHENTICATION" -> Set.of("Compatibility", "Full Enforcement")
                        .contains(value);
                default -> Set.of("Enable", "Disable").contains(value);
            };
            if (!valid) {
                throw error("IncompatibleSettingsException", "Value " + value + " is not allowed for " + name + ".");
            }
            settings.putObject(name).put("value", value).put("requested", now()).put("readyAt", readyAt());
        }
        directories.save(region, directory);
        return store.object().put("DirectoryId", directory.path("DirectoryId").asText());
    }

    // ---------------------------------------------------------------- schema extensions

    public synchronized ObjectNode startSchemaExtension(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Schema extensions");
        String ldif = text(input, "LdifContent");
        if (!ldif.matches("(?s).*^dn:.*") && !ldif.contains("dn:")) {
            throw invalid("The LDIF content must contain at least one dn: entry.");
        }
        if (ldif.length() > 500000) {
            throw invalid("The LDIF content must be at most 500000 characters.");
        }
        String description = text(input, "Description");
        ObjectNode extensions = meta(directory).withObject("schemaExtensions");
        for (JsonNode extension : extensions) {
            if (!Set.of("Cancelled", "Failed", "Completed").contains(status(extension))) {
                throw error("DirectoryUnavailableException", "Schema extension " + extension.path("SchemaExtensionId")
                        .asText() + " is still in progress.");
            }
        }
        String id = store.id("e");
        ObjectNode extension = store.object();
        extension.put("DirectoryId", directory.path("DirectoryId").asText());
        extension.put("SchemaExtensionId", id);
        extension.put("Description", description);
        extension.put("StartDateTime", now());
        extension.put("_readyAt", readyAt());
        extension.put("_snapshot", input.path("CreateSnapshotBeforeSchemaExtension").asBoolean(false));
        extensions.set(id, extension);
        directories.save(region, directory);
        return store.object().put("SchemaExtensionId", id);
    }

    private String status(JsonNode extension) {
        if (extension.hasNonNull("_final")) {
            return extension.get("_final").asText();
        }
        return System.currentTimeMillis() >= extension.path("_readyAt").asLong() ? "Completed" : "UpdatingSchema";
    }

    public synchronized ObjectNode cancelSchemaExtension(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Schema extensions");
        String id = text(input, "SchemaExtensionId");
        JsonNode extension = directory.path("_").path("schemaExtensions").get(id);
        if (extension == null) {
            throw missing("Schema extension " + id + " does not exist.");
        }
        if (!"Completed".equals(status(extension))) {
            ((ObjectNode) extension).put("_final", "Cancelled");
            ((ObjectNode) extension).put("EndDateTime", now());
            directories.save(region, directory);
        }
        return store.object();
    }

    public synchronized ObjectNode listSchemaExtensions(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Schema extensions");
        List<ObjectNode> items = new ArrayList<>();
        directory.path("_").path("schemaExtensions").forEach(extension -> {
            ObjectNode view = store.object();
            String status = status(extension);
            for (String field : List.of("DirectoryId", "SchemaExtensionId", "Description", "StartDateTime")) {
                view.set(field, extension.get(field).deepCopy());
            }
            view.put("SchemaExtensionStatus", status);
            view.put("SchemaExtensionStatusReason", "Completed".equals(status) ? "Schema updates are complete."
                    : "Cancelled".equals(status) ? "Cancelled by the user." : "Updating the schema.");
            if (Set.of("Completed", "Cancelled").contains(status)) {
                view.put("EndDateTime", extension.path("EndDateTime").asDouble(extension.path("_readyAt")
                        .asLong() / 1000.0));
            }
            items.add(view);
        });
        return store.page(items, input, "SchemaExtensionsInfo", 10);
    }

    // ---------------------------------------------------------------- log subscriptions and event topics

    public synchronized ObjectNode createLogSubscription(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Log forwarding");
        String group = text(input, "LogGroupName");
        if (meta(directory).has("logSubscription")) {
            throw exists("Directory " + directory.path("DirectoryId").asText() + " already forwards its logs.");
        }
        if (logs.isResolvable() && !logs.get().logGroupExists(group, region)) {
            throw error("InsufficientPermissionsException", "Log group " + group + " does not exist or Directory "
                    + "Service may not write to it.");
        }
        meta(directory).putObject("logSubscription").put("LogGroupName", group).put("SubscriptionCreatedDateTime",
                now());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode deleteLogSubscription(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        if (!meta(directory).has("logSubscription")) {
            throw missing("Directory " + directory.path("DirectoryId").asText() + " has no log subscription.");
        }
        meta(directory).remove("logSubscription");
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode listLogSubscriptions(JsonNode input, String region) {
        String directoryId = optional(input, "DirectoryId");
        List<ObjectNode> items = new ArrayList<>();
        List<ObjectNode> candidates = directoryId != null ? List.of(directories.directory(region, directoryId))
                : store.list(region, DirectoryService.DIRECTORIES, directory -> true);
        for (ObjectNode directory : candidates) {
            JsonNode subscription = directory.path("_").path("logSubscription");
            if (subscription.isObject()) {
                ObjectNode item = ((ObjectNode) subscription).deepCopy();
                item.put("DirectoryId", directory.path("DirectoryId").asText());
                items.add(item);
            }
        }
        return store.page(items, input, "LogSubscriptions", 100);
    }

    public synchronized ObjectNode registerEventTopic(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        String name = text(input, "TopicName");
        String arn = store.regions().buildArn("sns", region, store.account(), name);
        if (sns.isResolvable() && !sns.get().topicExists(arn, region)) {
            throw missing("Topic " + name + " does not exist.");
        }
        ArrayNode topics = meta(directory).withArray("topics");
        for (JsonNode topic : topics) {
            if (name.equals(topic.path("TopicName").asText())) {
                return store.object();
            }
        }
        topics.addObject().put("DirectoryId", directory.path("DirectoryId").asText()).put("TopicName", name)
                .put("TopicArn", arn).put("CreatedDateTime", now()).put("Status", "Registered");
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode deregisterEventTopic(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        String name = text(input, "TopicName");
        ArrayNode topics = meta(directory).withArray("topics");
        for (int index = 0; index < topics.size(); index++) {
            if (name.equals(topics.get(index).path("TopicName").asText())) {
                topics.remove(index);
                directories.save(region, directory);
                return store.object();
            }
        }
        throw missing("Topic " + name + " is not registered for directory " + directory.path("DirectoryId").asText()
                + ".");
    }

    public synchronized ObjectNode describeEventTopics(JsonNode input, String region) {
        String directoryId = optional(input, "DirectoryId");
        List<String> names = strings(input.path("TopicNames"));
        ArrayNode result = store.array();
        List<ObjectNode> candidates = directoryId != null ? List.of(directories.directory(region, directoryId))
                : store.list(region, DirectoryService.DIRECTORIES, directory -> true);
        for (ObjectNode directory : candidates) {
            for (JsonNode topic : directory.path("_").path("topics")) {
                if (!names.isEmpty() && !names.contains(topic.path("TopicName").asText())) {
                    continue;
                }
                ObjectNode view = ((ObjectNode) topic).deepCopy();
                if (sns.isResolvable() && !sns.get().topicExists(topic.path("TopicArn").asText(), region)) {
                    view.put("Status", "Topic not found");
                }
                result.add(view);
            }
        }
        ObjectNode response = store.object();
        response.set("EventTopics", result);
        return response;
    }

    // ---------------------------------------------------------------- assessments and hybrid directories

    public synchronized ObjectNode startADAssessment(JsonNode input, String region) {
        JsonNode configuration = input.path("AssessmentConfiguration");
        String directoryId = optional(input, "DirectoryId");
        ObjectNode directory = directoryId != null ? directories.directory(region, directoryId) : null;
        if (directory != null && !"Hybrid".equals(directory.path("Edition").asText())) {
            throw unsupported("Assessments of an existing directory are for hybrid directories.");
        }
        if (directory == null && !configuration.isObject()) {
            throw invalid("Specify AssessmentConfiguration or the DirectoryId of a hybrid directory.");
        }
        JsonNode source = configuration.isObject() ? configuration : directory.path("_").path("assessment");
        String dnsName = configuration.isObject() ? text(configuration, "DnsName") : directory.path("Name").asText();
        List<String> dnsIps = strings(source.path("CustomerDnsIps"));
        List<String> instances = strings(source.path("InstanceIds"));
        JsonNode vpc = source.path("VpcSettings");
        if (dnsIps.isEmpty() || instances.isEmpty()) {
            throw invalid("CustomerDnsIps and InstanceIds are required.");
        }
        if (store.list(region, ASSESSMENTS, assessment -> "IN_PROGRESS".equals(assessment.path("Status").asText()))
                .size() >= 10) {
            throw error("ADAssessmentLimitExceededException", "At most ten assessments can run at once.");
        }
        double now = now();
        String id = store.id("da");
        ObjectNode assessment = store.record();
        assessment.put("AssessmentId", id);
        if (directoryId != null) {
            assessment.put("DirectoryId", directoryId);
        }
        assessment.put("DnsName", dnsName);
        assessment.put("StartTime", now);
        assessment.put("LastUpdateDateTime", now);
        assessment.putArray("CustomerDnsIps").addAll(store.array().addAll(dnsIps.stream().map(ip ->
                store.mapper().getNodeFactory().textNode(ip)).toList()));
        assessment.put("VpcId", vpc.path("VpcId").asText(""));
        assessment.set("SubnetIds", vpc.path("SubnetIds").deepCopy());
        assessment.set("SecurityGroupIds", source.path("SecurityGroupIds").isArray()
                ? source.get("SecurityGroupIds").deepCopy() : store.array());
        assessment.putArray("SelfManagedInstanceIds").addAll(store.array().addAll(instances.stream().map(i ->
                store.mapper().getNodeFactory().textNode(i)).toList()));
        assessment.put("ReportType", "CUSTOMER");
        assessment.put("Version", "1.0");
        ArrayNode reports = ((ObjectNode) assessment.get("_")).putArray("reports");
        boolean passed = true;
        for (String ip : dnsIps) {
            ObjectNode report = reports.addObject().put("DomainControllerIp", ip);
            ArrayNode validations = report.putArray("Validations");
            boolean dns = ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}$");
            boolean exists = instancesExist(region, instances, vpc.path("VpcId").asText(null));
            validations.addObject().put("Category", "DNS").put("Name", "DNS resolution of " + dnsName)
                    .put("Status", dns ? "SUCCESS" : "FAILED").put("StatusCode", dns ? "DNS_RESOLVED"
                    : "DNS_UNREACHABLE").put("StartTime", now).put("LastUpdateDateTime", now);
            validations.addObject().put("Category", "INSTANCES").put("Name", "Self-managed instances")
                    .put("Status", exists ? "SUCCESS" : "FAILED").put("StatusCode", exists ? "INSTANCES_FOUND"
                    : "INSTANCES_NOT_FOUND").put("StartTime", now).put("LastUpdateDateTime", now);
            passed &= dns && exists;
        }
        assessment.put("Status", passed ? "SUCCESS" : "FAILED");
        assessment.put("StatusCode", passed ? "ASSESSMENT_SUCCEEDED" : "ASSESSMENT_FAILED");
        assessment.put("StatusReason", passed ? "The self-managed domain meets the requirements."
                : "One or more validations failed.");
        store.put(region, ASSESSMENTS, id, assessment);
        return store.object().put("AssessmentId", id);
    }

    private boolean instancesExist(String region, List<String> instances, String vpcId) {
        if (!ec2.isResolvable()) {
            return true;
        }
        try {
            List<Reservation> reservations = ec2.get().describeInstances(region, instances, Map.of());
            int found = 0;
            for (Reservation reservation : reservations) {
                found += (int) reservation.getInstances().stream().filter(instance -> vpcId == null
                        || vpcId.equals(instance.getVpcId())).count();
            }
            return found == instances.size();
        } catch (AwsException e) {
            return false;
        }
    }

    private ObjectNode assessment(String region, String id) {
        return store.get(region, ASSESSMENTS, id).orElseThrow(() -> missing("Assessment " + id + " does not exist."));
    }

    public synchronized ObjectNode describeADAssessment(JsonNode input, String region) {
        ObjectNode assessment = assessment(region, text(input, "AssessmentId"));
        ObjectNode response = store.object();
        response.set("Assessment", store.view(assessment));
        response.set("AssessmentReports", assessment.path("_").path("reports").deepCopy());
        return response;
    }

    public synchronized ObjectNode listADAssessments(JsonNode input, String region) {
        String directoryId = optional(input, "DirectoryId");
        if (directoryId != null) {
            directories.directory(region, directoryId);
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode assessment : store.list(region, ASSESSMENTS, assessment -> directoryId == null
                || directoryId.equals(assessment.path("DirectoryId").asText()))) {
            ObjectNode summary = store.object();
            for (String field : List.of("AssessmentId", "DirectoryId", "DnsName", "StartTime", "LastUpdateDateTime",
                    "Status", "CustomerDnsIps", "ReportType")) {
                if (assessment.has(field)) {
                    summary.set(field, assessment.get(field).deepCopy());
                }
            }
            items.add(summary);
        }
        return store.page(items, input, "Assessments", 100);
    }

    public synchronized ObjectNode deleteADAssessment(JsonNode input, String region) {
        ObjectNode assessment = assessment(region, text(input, "AssessmentId"));
        if (assessment.hasNonNull("DirectoryId") && store.get(region, DirectoryService.DIRECTORIES,
                assessment.get("DirectoryId").asText()).isPresent()) {
            throw unsupported("The assessment of an existing hybrid directory cannot be deleted.");
        }
        store.delete(region, ASSESSMENTS, assessment.path("AssessmentId").asText());
        return store.object().put("AssessmentId", assessment.path("AssessmentId").asText());
    }

    private void requireSecret(String region, String arn) {
        if (secrets.isResolvable()) {
            try {
                secrets.get().describeSecret(arn, region);
            } catch (AwsException e) {
                throw missing("Secret " + arn + " does not exist.");
            }
        }
    }

    public synchronized ObjectNode createHybridAD(JsonNode input, String region) {
        String secret = text(input, "SecretArn");
        requireSecret(region, secret);
        ObjectNode assessment = assessment(region, text(input, "AssessmentId"));
        if (!"SUCCESS".equals(assessment.path("Status").asText())) {
            throw invalid("Assessment " + assessment.path("AssessmentId").asText() + " did not succeed.");
        }
        ObjectNode request = store.object();
        request.put("Name", assessment.path("DnsName").asText());
        request.put("Password", "Hybrid-" + store.id("p"));
        request.put("Edition", "Enterprise");
        ObjectNode vpc = request.putObject("VpcSettings");
        vpc.put("VpcId", assessment.path("VpcId").asText());
        vpc.set("SubnetIds", assessment.path("SubnetIds").deepCopy());
        request.set("Tags", input.path("Tags").isArray() ? input.get("Tags").deepCopy() : store.array());
        String id = directories.createMicrosoftAD(request, region).path("DirectoryId").asText();
        ObjectNode directory = directories.directory(region, id);
        directory.put("Edition", "Hybrid");
        ObjectNode hybrid = directory.putObject("HybridSettings");
        hybrid.set("SelfManagedDnsIpAddrs", assessment.path("CustomerDnsIps").deepCopy());
        hybrid.set("SelfManagedInstanceIds", assessment.path("SelfManagedInstanceIds").deepCopy());
        ObjectNode meta = meta(directory);
        meta.put("secretArn", secret);
        meta.putObject("assessment").set("CustomerDnsIps", assessment.path("CustomerDnsIps").deepCopy());
        ((ObjectNode) meta.get("assessment")).set("InstanceIds", assessment.path("SelfManagedInstanceIds")
                .deepCopy());
        ((ObjectNode) meta.get("assessment")).putObject("VpcSettings").put("VpcId", assessment.path("VpcId")
                .asText()).set("SubnetIds", assessment.path("SubnetIds").deepCopy());
        directories.save(region, directory);
        assessment.put("DirectoryId", id);
        store.put(region, ASSESSMENTS, assessment.path("AssessmentId").asText(), assessment);
        return store.object().put("DirectoryId", id);
    }

    public synchronized ObjectNode updateHybridAD(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        if (!"Hybrid".equals(directory.path("Edition").asText())) {
            throw unsupported("Directory " + directory.path("DirectoryId").asText() + " is not a hybrid directory.");
        }
        ObjectNode meta = meta(directory);
        ObjectNode updates = meta.withObject("hybridUpdates");
        double now = now();
        String assessmentId = null;
        if (input.path("HybridAdministratorAccountUpdate").isObject()) {
            String secret = text(input.get("HybridAdministratorAccountUpdate"), "SecretArn");
            requireSecret(region, secret);
            updates.withArray("HybridAdministratorAccount").addObject().put("Status", "Updated")
                    .put("InitiatedBy", store.regions().buildGlobalArn("iam", store.account(), "root"))
                    .put("StartTime", now).put("LastUpdatedDateTime", now);
            meta.put("secretArn", secret);
        }
        if (input.path("SelfManagedInstancesSettings").isObject()) {
            JsonNode settings = input.get("SelfManagedInstancesSettings");
            ObjectNode assessmentRequest = store.object();
            ObjectNode configuration = assessmentRequest.putObject("AssessmentConfiguration");
            configuration.put("DnsName", directory.path("Name").asText());
            configuration.set("CustomerDnsIps", settings.path("CustomerDnsIps").deepCopy());
            configuration.set("InstanceIds", settings.path("InstanceIds").deepCopy());
            configuration.set("VpcSettings", meta.path("assessment").path("VpcSettings").deepCopy());
            assessmentId = startADAssessment(assessmentRequest, region).path("AssessmentId").asText();
            ObjectNode entry = updates.withArray("SelfManagedInstances").addObject();
            entry.put("Status", "Updated").put("StartTime", now).put("LastUpdatedDateTime", now)
                    .put("AssessmentId", assessmentId);
            entry.putObject("PreviousValue").set("InstanceIds", directory.path("HybridSettings")
                    .path("SelfManagedInstanceIds").deepCopy());
            entry.putObject("NewValue").set("InstanceIds", settings.path("InstanceIds").deepCopy());
            ((ObjectNode) directory.get("HybridSettings")).set("SelfManagedInstanceIds", settings.path("InstanceIds")
                    .deepCopy());
            ((ObjectNode) directory.get("HybridSettings")).set("SelfManagedDnsIpAddrs",
                    settings.path("CustomerDnsIps").deepCopy());
        }
        directories.save(region, directory);
        ObjectNode response = store.object().put("DirectoryId", directory.path("DirectoryId").asText());
        if (assessmentId != null) {
            response.put("AssessmentId", assessmentId);
        }
        return response;
    }

    public synchronized ObjectNode describeHybridADUpdate(JsonNode input, String region) {
        ObjectNode directory = directories.directory(region, text(input, "DirectoryId"));
        if (!"Hybrid".equals(directory.path("Edition").asText())) {
            throw unsupported("Directory " + directory.path("DirectoryId").asText() + " is not a hybrid directory.");
        }
        String type = optional(input, "UpdateType");
        ObjectNode activities = store.object();
        JsonNode updates = directory.path("_").path("hybridUpdates");
        for (String name : List.of("SelfManagedInstances", "HybridAdministratorAccount")) {
            if (type == null || type.equals(name)) {
                activities.set(name, updates.path(name).isArray() ? updates.get(name).deepCopy() : store.array());
            }
        }
        ObjectNode response = store.object();
        response.set("UpdateActivities", activities);
        return response;
    }

    // ---------------------------------------------------------------- CA enrollment and data access

    public synchronized ObjectNode enableCAEnrollmentPolicy(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "CA enrollment policies");
        String connector = text(input, "PcaConnectorArn");
        if (!connector.matches("^arn:" + AwsArnUtils.PARTITION_REGEX + ":pca-connector-ad:[\\w-]+:\\d{12}:connector/"
                + "[\\w-]+$")) {
            throw invalid("PcaConnectorArn must be a Private CA Connector for Active Directory ARN.");
        }
        JsonNode policy = directory.path("_").path("caPolicy");
        if (policy.isObject() && !"Disabled".equals(policy.path("status").asText())) {
            throw exists("A CA enrollment policy is already enabled.");
        }
        meta(directory).putObject("caPolicy").put("connector", connector).put("status", "Success")
                .put("updated", now());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode disableCAEnrollmentPolicy(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "CA enrollment policies");
        JsonNode policy = directory.path("_").path("caPolicy");
        if (!policy.isObject() || "Disabled".equals(policy.path("status").asText())) {
            throw missing("No CA enrollment policy is enabled.");
        }
        ((ObjectNode) policy).put("status", "Disabled").put("updated", now());
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode describeCAEnrollmentPolicy(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "CA enrollment policies");
        JsonNode policy = directory.path("_").path("caPolicy");
        ObjectNode response = store.object().put("DirectoryId", directory.path("DirectoryId").asText());
        if (policy.isObject()) {
            response.put("PcaConnectorArn", policy.path("connector").asText());
            response.put("CaEnrollmentPolicyStatus", policy.path("status").asText());
            response.put("LastUpdatedDateTime", policy.path("updated").asDouble());
        } else {
            response.put("CaEnrollmentPolicyStatus", "Disabled");
        }
        return response;
    }

    private ObjectNode dataAccess(JsonNode input, String region, boolean enable) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Directory data access");
        if (!"Active".equals(directory.path("Stage").asText())) {
            throw error("DirectoryUnavailableException", "Directory " + directory.path("DirectoryId").asText()
                    + " is not Active.");
        }
        String status = directory.path("_").path("dataAccess").asText("Disabled");
        if ((enable && "Enabled".equals(status)) || (!enable && "Disabled".equals(status))) {
            throw error("DirectoryInDesiredStateException", "Directory data access is already " + status + ".");
        }
        meta(directory).put("dataAccess", enable ? "Enabled" : "Disabled");
        directories.save(region, directory);
        return store.object();
    }

    public synchronized ObjectNode enableDirectoryDataAccess(JsonNode input, String region) {
        return dataAccess(input, region, true);
    }

    public synchronized ObjectNode disableDirectoryDataAccess(JsonNode input, String region) {
        return dataAccess(input, region, false);
    }

    public synchronized ObjectNode describeDirectoryDataAccess(JsonNode input, String region) {
        ObjectNode directory = directories.microsoftAd(region, text(input, "DirectoryId"), "Directory data access");
        return store.object().put("DataAccessStatus", directory.path("_").path("dataAccess").asText("Disabled"));
    }
}
