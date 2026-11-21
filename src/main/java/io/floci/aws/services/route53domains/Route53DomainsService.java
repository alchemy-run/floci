package io.floci.aws.services.route53domains;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.route53.Route53Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Route 53 Domains, with Floci as the registrar. Domains registered or transferred in
 * Floci belong to the calling account, get a Route 53 public hosted zone whose name servers they
 * delegate to, and expire after their registration period. Every change is an operation that is
 * {@code SUBMITTED}, then {@code IN_PROGRESS}, then {@code SUCCESSFUL}. A domain no Floci account
 * holds is available unless it is one of the well-known names the gazetteer of taken domains
 * lists ({@code example.com}, {@code amazon.com}, …), which are registered elsewhere and
 * transferable with an auth code.
 */
@ApplicationScoped
public class Route53DomainsService implements Resettable {
    static final long SUBMITTED_MILLIS = 1_000;
    static final long COMPLETED_MILLIS = 2_000;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern PHONE = Pattern.compile("\\+[0-9]{1,3}\\.[0-9]{4,14}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Set<String> TAKEN = Set.of("example", "amazon", "aws", "google", "github", "microsoft",
            "apple", "facebook", "wikipedia", "cloudflare", "alchemy");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** USD prices per year: registration, renewal, transfer, change of ownership, restoration. */
    private static final Map<String, double[]> PRICES = prices();

    private static Map<String, double[]> prices() {
        Map<String, double[]> prices = new LinkedHashMap<>();
        Object[][] table = {
                {"com", 15}, {"net", 17}, {"org", 15}, {"info", 29}, {"biz", 23}, {"io", 71}, {"co", 32},
                {"dev", 17}, {"app", 20}, {"ai", 120}, {"me", 20}, {"us", 15}, {"uk", 9}, {"co.uk", 9},
                {"de", 9}, {"fr", 12}, {"es", 12}, {"it", 15}, {"nl", 12}, {"eu", 11}, {"ca", 13}, {"au", 30},
                {"com.au", 30}, {"jp", 90}, {"in", 12}, {"tv", 32}, {"cloud", 23}, {"tech", 55}, {"online", 39},
                {"site", 35}, {"store", 60}, {"xyz", 13}, {"link", 10}, {"click", 10}, {"name", 9}, {"mobi", 25},
                {"pro", 20}, {"club", 15}, {"agency", 26}, {"design", 48}, {"studio", 27}, {"systems", 26},
                {"software", 31}, {"email", 26}, {"solutions", 26}};
        for (Object[] row : table) {
            double price = ((Number) row[1]).doubleValue();
            prices.put((String) row[0], new double[]{price, price, price, 0, price * 5});
        }
        return prices;
    }

    private final AccountAwareStorageBackend<ObjectNode> domains;
    private final AccountAwareStorageBackend<ObjectNode> operations;
    private final AccountAwareStorageBackend<ObjectNode> billing;
    private final RegionResolver regionResolver;
    private final Instance<Route53Service> route53;
    private final ObjectMapper mapper;

    @Inject
    public Route53DomainsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                 Instance<Route53Service> route53, ObjectMapper mapper) {
        this.domains = storageFactory.create("route53domains", "route53domains-domains.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.operations = storageFactory.create("route53domains", "route53domains-operations.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.billing = storageFactory.create("route53domains", "route53domains-billing.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.route53 = route53;
        this.mapper = mapper;
    }

    // ── Names, TLDs and availability ───────────────────────────────────────

    /** The normalized name and its TLD, or {@code InvalidInput} / {@code UnsupportedTLD}. */
    record Name(String domain, String label, String tld) {}

    static Name name(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("DomainName is required.");
        }
        String domain = raw.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        if (domain.length() > 255) {
            throw invalid("The domain name " + raw + " is too long.");
        }
        String tld = null;
        for (String candidate : PRICES.keySet()) {
            if (domain.endsWith("." + candidate) && (tld == null || candidate.length() > tld.length())) {
                tld = candidate;
            }
        }
        if (tld == null) {
            String last = domain.substring(domain.lastIndexOf('.') + 1);
            throw new AwsException("UnsupportedTLD", "Amazon Route 53 does not support the top-level domain "
                    + last + ".", 400);
        }
        String label = domain.substring(0, domain.length() - tld.length() - 1);
        if (label.contains(".") || !LABEL.matcher(label).matches()) {
            throw invalid("The domain name " + raw + " is not valid.");
        }
        return new Name(domain, label, tld);
    }

    private ObjectNode owned(String domain) {
        return domains.get(domain).orElse(null);
    }

    private boolean heldByAnyAccount(String domain) {
        return domains.scanAllAccountEntries(k -> k.equals(domain)).stream().findAny().isPresent();
    }

    private String availability(Name name) {
        if (heldByAnyAccount(name.domain())) {
            return "UNAVAILABLE";
        }
        return TAKEN.contains(name.label()) ? "UNAVAILABLE" : "AVAILABLE";
    }

    public ObjectNode checkAvailability(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        ObjectNode response = mapper.createObjectNode();
        String availability = availability(name);
        response.put("Availability", availability);
        if ("AVAILABLE".equals(availability)) {
            response.set("Price", price(PRICES.get(name.tld())[0], null));
        }
        return response;
    }

    public ObjectNode checkTransferability(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        String transferable;
        if (owned(name.domain()) != null) {
            transferable = "DOMAIN_IN_OWN_ACCOUNT";
        } else if (heldByAnyAccount(name.domain())) {
            transferable = "DOMAIN_IN_ANOTHER_ACCOUNT";
        } else if (TAKEN.contains(name.label())) {
            transferable = "TRANSFERABLE";
        } else {
            transferable = "UNTRANSFERABLE";
        }
        ObjectNode response = mapper.createObjectNode();
        response.putObject("Transferability").put("Transferable", transferable);
        if ("TRANSFERABLE".equals(transferable)) {
            response.set("Price", price(PRICES.get(name.tld())[2], null));
        } else if ("UNTRANSFERABLE".equals(transferable)) {
            response.put("Message", "The domain " + name.domain() + " is not registered.");
        }
        return response;
    }

    public ObjectNode suggestions(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        int count = request.path("SuggestionCount").asInt(0);
        if (count < 1 || count > 50) {
            throw invalid("SuggestionCount must be between 1 and 50.");
        }
        boolean onlyAvailable = request.path("OnlyAvailable").asBoolean(false);
        List<String> candidates = new ArrayList<>();
        for (String tld : PRICES.keySet()) {
            if (!tld.equals(name.tld()) && !tld.contains(".")) {
                candidates.add(name.label() + "." + tld);
            }
        }
        for (String affix : List.of("get", "my", "try", "the")) {
            candidates.add(affix + name.label() + "." + name.tld());
        }
        for (String suffix : List.of("app", "hq", "online", "now")) {
            candidates.add(name.label() + suffix + "." + name.tld());
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("SuggestionsList");
        for (String candidate : candidates) {
            if (list.size() >= count) {
                break;
            }
            Name parsed = name(candidate);
            String availability = availability(parsed);
            if (onlyAvailable && !"AVAILABLE".equals(availability)) {
                continue;
            }
            ObjectNode suggestion = list.addObject().put("DomainName", candidate).put("Availability", availability);
            if ("AVAILABLE".equals(availability)) {
                suggestion.set("Price", price(PRICES.get(parsed.tld())[0], null));
            }
        }
        return response;
    }

    public ObjectNode listPrices(JsonNode request) {
        String tld = request.path("Tld").asText(null);
        if (tld != null && !PRICES.containsKey(tld.toLowerCase(Locale.ROOT))) {
            throw new AwsException("UnsupportedTLD", "Amazon Route 53 does not support the top-level domain " + tld
                    + ".", 400);
        }
        int max = request.path("MaxItems").asInt(1000);
        if (max < 1 || max > 1000) {
            throw invalid("MaxItems must be between 1 and 1000.");
        }
        List<String> tlds = tld == null ? new ArrayList<>(PRICES.keySet()) : List.of(tld.toLowerCase(Locale.ROOT));
        int start = marker(request.path("Marker").asText(null));
        int end = Math.min(tlds.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("Prices");
        tlds.subList(Math.min(start, end), end).forEach(t -> list.add(domainPrice(t)));
        if (end < tlds.size()) {
            response.put("NextPageMarker", Integer.toString(end));
        }
        return response;
    }

    public ObjectNode domainPrices(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        ObjectNode response = mapper.createObjectNode();
        response.set("DomainPrice", domainPrice(name.tld()));
        return response;
    }

    private ObjectNode domainPrice(String tld) {
        double[] p = PRICES.get(tld);
        ObjectNode price = mapper.createObjectNode();
        price.put("Name", tld);
        price.set("RegistrationPrice", price(p[0], null));
        price.set("TransferPrice", price(p[2], null));
        price.set("RenewalPrice", price(p[1], null));
        price.set("ChangeOwnershipPrice", price(p[3], null));
        price.set("RestorationPrice", price(p[4], null));
        return price;
    }

    private ObjectNode price(double amount, String type) {
        ObjectNode price = mapper.createObjectNode().put("Price", amount).put("Currency", "USD");
        if (type != null) {
            price.put("PriceType", type);
        }
        return price;
    }

    // ── Registration and transfer ──────────────────────────────────────────

    public synchronized ObjectNode register(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        int years = years(request.path("DurationInYears"), true);
        checkConsent(request, PRICES.get(name.tld())[0] * years);
        if (owned(name.domain()) != null || pending(name.domain(), "REGISTER_DOMAIN")) {
            throw new AwsException("DuplicateRequest", "The domain " + name.domain()
                    + " is already being registered by this account.", 400);
        }
        if (!"AVAILABLE".equals(availability(name))) {
            throw invalid("The domain " + name.domain() + " is not available for registration.");
        }
        ObjectNode domain = newDomain(name, request, years);
        domain.set("Nameservers", hostedZoneNameservers(name.domain()));
        domains.put(name.domain(), domain);
        bill(name.domain(), "REGISTER_DOMAIN", PRICES.get(name.tld())[0] * years);
        return operation(name.domain(), "REGISTER_DOMAIN");
    }

    public synchronized ObjectNode transferIn(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        int years = request.has("DurationInYears") ? years(request.get("DurationInYears"), false) : 1;
        checkConsent(request, PRICES.get(name.tld())[2]);
        if (owned(name.domain()) != null) {
            throw new AwsException("DuplicateRequest", "The domain " + name.domain() + " is already in this account.",
                    400);
        }
        if (heldByAnyAccount(name.domain()) || !TAKEN.contains(name.label())) {
            throw invalid("The domain " + name.domain() + " can't be transferred into this account.");
        }
        if (request.path("AuthCode").asText("").isEmpty()) {
            throw invalid("An AuthCode from the current registrar is required to transfer " + name.domain() + ".");
        }
        ObjectNode domain = newDomain(name, request, years);
        JsonNode nameservers = request.path("Nameservers");
        domain.set("Nameservers", nameservers.isArray() && !nameservers.isEmpty() ? nameservers.deepCopy()
                : hostedZoneNameservers(name.domain()));
        domains.put(name.domain(), domain);
        bill(name.domain(), "TRANSFER_IN_DOMAIN", PRICES.get(name.tld())[2]);
        return operation(name.domain(), "TRANSFER_IN_DOMAIN");
    }

    private ObjectNode newDomain(Name name, JsonNode request, int years) {
        ObjectNode domain = mapper.createObjectNode();
        domain.put("DomainName", name.domain());
        for (String role : List.of("Admin", "Registrant", "Tech", "Billing")) {
            JsonNode contact = request.path(role + "Contact");
            if (contact.isMissingNode() && "Billing".equals(role)) {
                contact = request.path("AdminContact");
            }
            checkContact(contact, role + "Contact");
            domain.set(role + "Contact", contact.deepCopy());
            domain.put(role + "Privacy", request.path("PrivacyProtect" + role + "Contact").asBoolean(true));
        }
        domain.put("AutoRenew", request.path("AutoRenew").asBoolean(true));
        domain.put("TransferLock", !Set.of("co.uk", "uk", "de", "eu").contains(name.tld()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        domain.put("CreationDate", now.toString());
        domain.put("UpdatedDate", now.toString());
        domain.put("ExpirationDate", now.atZone(ZoneOffset.UTC).plusYears(years).toInstant().toString());
        domain.put("RegistryDomainId", HexFormat.of().formatHex(sha256(name.domain())).substring(0, 16).toUpperCase(
                Locale.ROOT) + "_DOMAIN_" + name.tld().toUpperCase(Locale.ROOT).replace('.', '_') + "-VRSN");
        domain.put("AuthCode", token(12));
        domain.putArray("DnssecKeys");
        domain.putArray("Tags");
        return domain;
    }

    private ArrayNode hostedZoneNameservers(String domain) {
        ArrayNode nameservers = mapper.createArrayNode();
        List<String> servers = null;
        if (route53.isResolvable()) {
            try {
                Route53Service zones = route53.get();
                zones.createHostedZone(domain, "route53domains-" + domain + "-" + UUID.randomUUID(),
                        "HostedZone created by Route53 Registrar", false, null, null);
                servers = zones.getNameServers();
            } catch (AwsException e) {
                servers = null;
            }
        }
        if (servers == null || servers.isEmpty()) {
            servers = List.of("ns-1.awsdns-01.org", "ns-2.awsdns-02.co.uk", "ns-3.awsdns-03.com",
                    "ns-4.awsdns-04.net");
        }
        servers.forEach(server -> nameservers.addObject().put("Name", server).putArray("GlueIps"));
        return nameservers;
    }

    private static void checkContact(JsonNode contact, String member) {
        if (!contact.isObject()) {
            throw invalid(member + " is required.");
        }
        for (String field : List.of("FirstName", "LastName", "ContactType", "AddressLine1", "City", "CountryCode",
                "PhoneNumber", "Email")) {
            if (contact.path(field).asText("").isBlank()) {
                throw invalid(member + "." + field + " is required.");
            }
        }
        if (!PHONE.matcher(contact.path("PhoneNumber").asText()).matches()) {
            throw invalid(member + ".PhoneNumber must be in the format +[country dialing code].[number].");
        }
        if (!EMAIL.matcher(contact.path("Email").asText()).matches()) {
            throw invalid(member + ".Email is not a valid email address.");
        }
        if (!"PERSON".equals(contact.path("ContactType").asText()) && contact.path("OrganizationName").asText("")
                .isBlank()) {
            throw invalid(member + ".OrganizationName is required for a contact that is not a PERSON.");
        }
    }

    private static void checkConsent(JsonNode request, double expected) {
        JsonNode consent = request.path("Consent");
        if (consent.isObject() && consent.path("MaxPrice").asDouble(Double.MAX_VALUE) < expected) {
            throw invalid("The price of " + expected + " USD is higher than Consent.MaxPrice.");
        }
    }

    private static int years(JsonNode node, boolean required) {
        if (node.isMissingNode() && !required) {
            return 1;
        }
        int years = node.asInt(0);
        if (years < 1 || years > 10) {
            throw invalid("DurationInYears must be between 1 and 10.");
        }
        return years;
    }

    // ── Domain changes ─────────────────────────────────────────────────────

    ObjectNode require(String rawName) {
        Name name = name(rawName);
        ObjectNode domain = owned(name.domain());
        if (domain == null) {
            throw invalid("Domain " + name.domain() + " not found in account " + regionResolver.getAccountId() + ".");
        }
        return domain;
    }

    public ObjectNode detail(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null)).deepCopy();
        ObjectNode response = mapper.createObjectNode();
        response.put("DomainName", domain.path("DomainName").asText());
        response.set("Nameservers", domain.get("Nameservers"));
        response.put("AutoRenew", domain.path("AutoRenew").asBoolean());
        for (String role : List.of("Admin", "Registrant", "Tech", "Billing")) {
            boolean privacy = domain.path(role + "Privacy").asBoolean();
            response.set(role + "Contact", privacy ? redacted(domain.path(role + "Contact")) : domain.get(role + "Contact"));
            response.put(role + "Privacy", privacy);
        }
        response.put("RegistrarName", "Amazon Registrar, Inc.");
        response.put("WhoIsServer", "whois.registrar.amazon");
        response.put("RegistrarUrl", "https://registrar.amazon.com");
        response.put("AbuseContactEmail", "abuse@registrar.amazon");
        response.put("AbuseContactPhone", "+1.2024422253");
        response.put("RegistryDomainId", domain.path("RegistryDomainId").asText());
        response.put("CreationDate", domain.path("CreationDate").asText());
        response.put("UpdatedDate", domain.path("UpdatedDate").asText());
        response.put("ExpirationDate", domain.path("ExpirationDate").asText());
        response.put("Reseller", "Amazon");
        response.put("DnsSec", domain.path("DnssecKeys").isEmpty() ? "unsigned" : "signedDelegation");
        ArrayNode status = response.putArray("StatusList");
        if (domain.path("TransferLock").asBoolean()) {
            status.add("clientTransferProhibited");
        }
        if (status.isEmpty()) {
            status.add("ok");
        }
        response.set("DnssecKeys", domain.get("DnssecKeys"));
        return response;
    }

    private ObjectNode redacted(JsonNode contact) {
        ObjectNode copy = mapper.createObjectNode();
        copy.put("FirstName", "On behalf of " + contact.path("FirstName").asText() + " owner");
        copy.put("LastName", "Identity Protection Service");
        copy.put("ContactType", contact.path("ContactType").asText("PERSON"));
        copy.put("OrganizationName", "Identity Protection Service");
        copy.put("AddressLine1", "PO Box 786");
        copy.put("City", "Hayes");
        copy.put("State", "Middlesex");
        copy.put("CountryCode", "GB");
        copy.put("ZipCode", "UB3 9TR");
        copy.put("PhoneNumber", "+44.1483307527");
        copy.put("Email", "owner@registrar.amazon");
        return copy;
    }

    public synchronized ObjectNode updateNameservers(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        JsonNode nameservers = request.path("Nameservers");
        if (!nameservers.isArray() || nameservers.isEmpty() || nameservers.size() > 6) {
            throw invalid("Nameservers must list one to six name servers.");
        }
        for (JsonNode server : nameservers) {
            String host = server.path("Name").asText("");
            if (!host.matches("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")) {
                throw invalid("The name server " + host + " is not a valid host name.");
            }
            if (host.toLowerCase(Locale.ROOT).endsWith("." + domain.path("DomainName").asText())
                    && server.path("GlueIps").isEmpty()) {
                throw invalid("The name server " + host + " is inside the domain and needs glue IPs.");
            }
        }
        domain.set("Nameservers", nameservers.deepCopy());
        return save(domain, "UPDATE_NAMESERVER");
    }

    public synchronized ObjectNode updateContact(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        boolean changed = false;
        for (String role : List.of("Admin", "Registrant", "Tech", "Billing")) {
            JsonNode contact = request.path(role + "Contact");
            if (contact.isObject()) {
                checkContact(contact, role + "Contact");
                domain.set(role + "Contact", contact.deepCopy());
                changed = true;
            }
        }
        if (!changed) {
            throw invalid("Specify at least one contact to update.");
        }
        return save(domain, request.has("RegistrantContact") ? "CHANGE_DOMAIN_OWNER" : "UPDATE_DOMAIN_CONTACT");
    }

    public synchronized ObjectNode updatePrivacy(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        for (String role : List.of("Admin", "Registrant", "Tech", "Billing")) {
            if (request.has(role + "Privacy")) {
                domain.put(role + "Privacy", request.get(role + "Privacy").asBoolean());
            }
        }
        return save(domain, "CHANGE_PRIVACY_PROTECTION");
    }

    public synchronized ObjectNode autoRenew(JsonNode request, boolean enabled) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        domain.put("AutoRenew", enabled);
        save(domain, enabled ? "ENABLE_AUTORENEW" : "DISABLE_AUTORENEW");
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode transferLock(JsonNode request, boolean enabled) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        Name name = name(domain.path("DomainName").asText());
        if (Set.of("co.uk", "uk", "de", "eu").contains(name.tld())) {
            throw new AwsException("TLDRulesViolation", "The ." + name.tld() + " registry doesn't support transfer locks.",
                    400);
        }
        domain.put("TransferLock", enabled);
        return save(domain, "DOMAIN_LOCK");
    }

    public synchronized ObjectNode renew(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        int years = request.has("DurationInYears") ? years(request.get("DurationInYears"), false) : 1;
        int expiryYear = Instant.parse(domain.path("ExpirationDate").asText()).atZone(ZoneOffset.UTC).getYear();
        if (request.path("CurrentExpiryYear").asInt() != expiryYear) {
            throw invalid("CurrentExpiryYear " + request.path("CurrentExpiryYear").asInt()
                    + " does not match the domain's expiration year " + expiryYear + ".");
        }
        Instant expiry = Instant.parse(domain.path("ExpirationDate").asText());
        if (expiry.atZone(ZoneOffset.UTC).plusYears(years).isAfter(Instant.now().atZone(ZoneOffset.UTC).plusYears(10))) {
            throw invalid("A domain can't be registered for more than ten years from today.");
        }
        Name name = name(domain.path("DomainName").asText());
        checkConsent(request, PRICES.get(name.tld())[1] * years);
        domain.put("ExpirationDate", expiry.atZone(ZoneOffset.UTC).plusYears(years).toInstant().toString());
        bill(name.domain(), "RENEW_DOMAIN", PRICES.get(name.tld())[1] * years);
        return save(domain, "RENEW_DOMAIN");
    }

    public synchronized ObjectNode delete(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        Name name = name(domain.path("DomainName").asText());
        if (Set.of("co.uk", "uk").contains(name.tld())) {
            throw new AwsException("TLDRulesViolation", "Domains under ." + name.tld()
                    + " can't be deleted; release them with PushDomain.", 400);
        }
        domains.delete(name.domain());
        return operation(name.domain(), "DELETE_DOMAIN");
    }

    public ObjectNode authCode(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        return mapper.createObjectNode().put("AuthCode", domain.path("AuthCode").asText());
    }

    public synchronized ObjectNode push(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        Name name = name(domain.path("DomainName").asText());
        if (!Set.of("co.uk", "uk", "me.uk", "org.uk").contains(name.tld())) {
            throw invalid("PushDomain is only available for .uk domains.");
        }
        if (request.path("Target").asText("").isBlank()) {
            throw invalid("Target is required.");
        }
        domains.delete(name.domain());
        operation(name.domain(), "PUSH_DOMAIN");
        return mapper.createObjectNode();
    }

    // ── DNSSEC ─────────────────────────────────────────────────────────────

    public synchronized ObjectNode associateDelegationSigner(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        JsonNode attributes = request.path("SigningAttributes");
        int algorithm = attributes.path("Algorithm").asInt(-1);
        int flags = attributes.path("Flags").asInt(-1);
        String publicKey = attributes.path("PublicKey").asText("");
        if (!Set.of(5, 7, 8, 10, 13, 14, 15, 16).contains(algorithm)) {
            throw invalid("SigningAttributes.Algorithm is not a supported DNSSEC algorithm.");
        }
        if (flags != 256 && flags != 257) {
            throw invalid("SigningAttributes.Flags must be 256 (ZSK) or 257 (KSK).");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(publicKey);
        } catch (IllegalArgumentException e) {
            throw invalid("SigningAttributes.PublicKey must be base64.");
        }
        ArrayNode keys = (ArrayNode) domain.get("DnssecKeys");
        if (keys.size() >= 6) {
            throw new AwsException("DnssecLimitExceeded", "A domain can have at most 6 DNSSEC keys.", 400);
        }
        for (JsonNode existing : keys) {
            if (existing.path("PublicKey").asText().equals(publicKey)) {
                throw new AwsException("DuplicateRequest", "The key is already associated with the domain.", 400);
            }
        }
        byte[] rdata = new byte[4 + key.length];
        rdata[0] = (byte) (flags >> 8);
        rdata[1] = (byte) flags;
        rdata[2] = 3;
        rdata[3] = (byte) algorithm;
        System.arraycopy(key, 0, rdata, 4, key.length);
        long tag = 0;
        for (int i = 0; i < rdata.length; i++) {
            tag += (i & 1) == 1 ? rdata[i] & 0xFF : (rdata[i] & 0xFF) << 8;
        }
        tag += (tag >> 16) & 0xFFFF;
        byte[] owner = wireName(domain.path("DomainName").asText());
        byte[] digestInput = new byte[owner.length + rdata.length];
        System.arraycopy(owner, 0, digestInput, 0, owner.length);
        System.arraycopy(rdata, 0, digestInput, owner.length, rdata.length);
        ObjectNode dnssec = keys.addObject();
        dnssec.put("Algorithm", algorithm);
        dnssec.put("Flags", flags);
        dnssec.put("PublicKey", publicKey);
        dnssec.put("DigestType", 2);
        dnssec.put("Digest", HexFormat.of().formatHex(sha256(digestInput)).toUpperCase(Locale.ROOT));
        dnssec.put("KeyTag", (int) (tag & 0xFFFF));
        dnssec.put("Id", UUID.randomUUID().toString());
        return save(domain, "ADD_DNSSEC");
    }

    public synchronized ObjectNode disassociateDelegationSigner(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        ArrayNode keys = (ArrayNode) domain.get("DnssecKeys");
        String id = request.path("Id").asText("");
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).path("Id").asText().equals(id)) {
                keys.remove(i);
                return save(domain, "REMOVE_DNSSEC");
            }
        }
        throw invalid("The DNSSEC key " + id + " is not associated with the domain.");
    }

    private static byte[] wireName(String domain) {
        byte[] out = new byte[domain.length() + 2];
        int position = 0;
        for (String label : domain.split("\\.")) {
            out[position++] = (byte) label.length();
            for (char c : label.toCharArray()) {
                out[position++] = (byte) c;
            }
        }
        out[position] = 0;
        return out;
    }

    // ── Transfers between accounts ─────────────────────────────────────────

    public synchronized ObjectNode transferToAccount(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        String account = request.path("AccountId").asText("");
        if (!account.matches("\\d{12}") || account.equals(regionResolver.getAccountId())) {
            throw invalid("AccountId must be another 12-digit AWS account.");
        }
        domain.put("TransferTarget", account);
        domain.put("TransferPassword", token(10));
        ObjectNode response = save(domain, "INTERNAL_TRANSFER_OUT_DOMAIN");
        response.put("Password", domain.path("TransferPassword").asText());
        return response;
    }

    public synchronized ObjectNode cancelTransferToAccount(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        if (!domain.has("TransferTarget")) {
            throw invalid("The domain has no transfer to another account to cancel.");
        }
        domain.remove(List.of("TransferTarget", "TransferPassword"));
        return save(domain, "INTERNAL_TRANSFER_OUT_DOMAIN");
    }

    public synchronized ObjectNode acceptTransfer(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        String me = regionResolver.getAccountId();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : domains.scanAllAccountEntries(k -> k.equals(name.domain()))) {
            ObjectNode domain = entry.value();
            if (me.equals(domain.path("TransferTarget").asText(null))) {
                if (!domain.path("TransferPassword").asText().equals(request.path("Password").asText())) {
                    throw invalid("The password for the transfer of " + name.domain() + " is not correct.");
                }
                domain.remove(List.of("TransferTarget", "TransferPassword"));
                domains.deleteForAccount(entry.accountId(), entry.key());
                domains.put(name.domain(), domain);
                return operation(name.domain(), "INTERNAL_TRANSFER_IN_DOMAIN");
            }
        }
        throw invalid("No transfer of " + name.domain() + " to this account is pending.");
    }

    public synchronized ObjectNode rejectTransfer(JsonNode request) {
        Name name = name(request.path("DomainName").asText(null));
        String me = regionResolver.getAccountId();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : domains.scanAllAccountEntries(k -> k.equals(name.domain()))) {
            ObjectNode domain = entry.value();
            if (me.equals(domain.path("TransferTarget").asText(null))) {
                domain.remove(List.of("TransferTarget", "TransferPassword"));
                domains.putForAccount(entry.accountId(), entry.key(), domain);
                return operation(name.domain(), "INTERNAL_TRANSFER_IN_DOMAIN");
            }
        }
        throw invalid("No transfer of " + name.domain() + " to this account is pending.");
    }

    // ── Lists, tags, reachability ──────────────────────────────────────────

    public ObjectNode listDomains(JsonNode request) {
        int max = request.path("MaxItems").asInt(20);
        if (max < 1 || max > 100) {
            throw invalid("MaxItems must be between 1 and 100.");
        }
        List<ObjectNode> all = new ArrayList<>(domains.scan(k -> true));
        for (JsonNode condition : request.path("FilterConditions")) {
            String attribute = condition.path("Name").asText();
            String operator = condition.path("Operator").asText();
            List<String> values = new ArrayList<>();
            condition.path("Values").forEach(v -> values.add(v.asText()));
            all.removeIf(domain -> !matches(domain, attribute, operator, values));
        }
        String sortBy = request.path("SortCondition").path("Name").asText("DomainName");
        Comparator<ObjectNode> order = "Expiry".equals(sortBy)
                ? Comparator.comparing(d -> d.path("ExpirationDate").asText())
                : Comparator.comparing(d -> d.path("DomainName").asText());
        if ("DESC".equals(request.path("SortCondition").path("SortOrder").asText("ASC"))) {
            order = order.reversed();
        }
        all.sort(order);
        int start = marker(request.path("Marker").asText(null));
        int end = Math.min(all.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("Domains");
        for (ObjectNode domain : all.subList(Math.min(start, end), end)) {
            list.addObject().put("DomainName", domain.path("DomainName").asText())
                    .put("AutoRenew", domain.path("AutoRenew").asBoolean())
                    .put("TransferLock", domain.path("TransferLock").asBoolean())
                    .put("Expiry", domain.path("ExpirationDate").asText());
        }
        if (end < all.size()) {
            response.put("NextPageMarker", Integer.toString(end));
        }
        return response;
    }

    private static boolean matches(ObjectNode domain, String attribute, String operator, List<String> values) {
        if (!"DomainName".equals(attribute)) {
            String expiry = domain.path("ExpirationDate").asText();
            return values.stream().allMatch(v -> "GE".equals(operator) ? expiry.compareTo(v) >= 0
                    : "LE".equals(operator) ? expiry.compareTo(v) <= 0 : expiry.startsWith(v));
        }
        String name = domain.path("DomainName").asText();
        return values.stream().anyMatch(v -> "BEGINS_WITH".equals(operator) ? name.startsWith(v) : name.equals(v));
    }

    public ObjectNode listTags(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        ObjectNode response = mapper.createObjectNode();
        response.set("TagList", domain.get("Tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode updateTags(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        Map<String, String> tags = new LinkedHashMap<>();
        domain.get("Tags").forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
        request.path("TagsToUpdate").forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText("")));
        if (tags.size() > 50) {
            throw invalid("A domain can have at most 50 tags.");
        }
        ArrayNode list = mapper.createArrayNode();
        tags.forEach((k, v) -> list.addObject().put("Key", k).put("Value", v));
        domain.set("Tags", list);
        domains.put(domain.path("DomainName").asText(), domain);
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode deleteTags(JsonNode request) {
        ObjectNode domain = require(request.path("DomainName").asText(null));
        List<String> keys = new ArrayList<>();
        request.path("TagsToDelete").forEach(k -> keys.add(k.asText()));
        ArrayNode list = mapper.createArrayNode();
        domain.get("Tags").forEach(t -> {
            if (!keys.contains(t.path("Key").asText())) {
                list.add(t);
            }
        });
        domain.set("Tags", list);
        domains.put(domain.path("DomainName").asText(), domain);
        return mapper.createObjectNode();
    }

    public ObjectNode reachability(JsonNode request) {
        ObjectNode domain = require(request.path("domainName").asText(null));
        return mapper.createObjectNode().put("domainName", domain.path("DomainName").asText()).put("status", "DONE");
    }

    public ObjectNode resendReachability(JsonNode request) {
        ObjectNode domain = require(request.path("domainName").asText(null));
        return mapper.createObjectNode().put("domainName", domain.path("DomainName").asText())
                .put("emailAddress", domain.path("RegistrantContact").path("Email").asText())
                .put("isAlreadyVerified", true);
    }

    // ── Operations and billing ─────────────────────────────────────────────

    private ObjectNode save(ObjectNode domain, String operationType) {
        domain.put("UpdatedDate", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        domains.put(domain.path("DomainName").asText(), domain);
        return operation(domain.path("DomainName").asText(), operationType);
    }

    private ObjectNode operation(String domain, String type) {
        String id = UUID.randomUUID().toString();
        ObjectNode operation = mapper.createObjectNode();
        operation.put("OperationId", id);
        operation.put("DomainName", domain);
        operation.put("Type", type);
        operation.put("SubmittedDate", Instant.now().toString());
        operations.put(id, operation);
        return mapper.createObjectNode().put("OperationId", id);
    }

    private boolean pending(String domain, String type) {
        return operations.scan(k -> true).stream().map(this::view).anyMatch(op -> domain.equals(op.path("DomainName")
                .asText()) && type.equals(op.path("Type").asText()) && !"SUCCESSFUL".equals(op.path("Status").asText()));
    }

    /** The operation as of now: submitted, in progress for a second, then successful. */
    private ObjectNode view(ObjectNode operation) {
        ObjectNode copy = operation.deepCopy();
        Instant submitted = Instant.parse(operation.path("SubmittedDate").asText());
        long elapsed = Instant.now().toEpochMilli() - submitted.toEpochMilli();
        String status = elapsed >= COMPLETED_MILLIS ? "SUCCESSFUL" : elapsed >= SUBMITTED_MILLIS ? "IN_PROGRESS"
                : "SUBMITTED";
        copy.put("Status", status);
        copy.put("LastUpdatedDate", "SUCCESSFUL".equals(status)
                ? submitted.plusMillis(COMPLETED_MILLIS).toString() : submitted.toString());
        return copy;
    }

    public ObjectNode operationDetail(JsonNode request) {
        String id = request.path("OperationId").asText(null);
        if (id == null || id.isBlank()) {
            throw invalid("OperationId is required.");
        }
        ObjectNode operation = operations.get(id).map(this::view).orElseThrow(() -> invalid("Operation with id " + id
                + " not found."));
        return operation;
    }

    public ObjectNode listOperations(JsonNode request) {
        int max = request.path("MaxItems").asInt(20);
        if (max < 1 || max > 100) {
            throw invalid("MaxItems must be between 1 and 100.");
        }
        String since = request.path("SubmittedSince").asText(null);
        List<String> statuses = new ArrayList<>();
        request.path("Status").forEach(s -> statuses.add(s.asText()));
        List<String> types = new ArrayList<>();
        request.path("Type").forEach(t -> types.add(t.asText()));
        Comparator<ObjectNode> order = Comparator.comparing(op -> op.path("SubmittedDate").asText());
        if (!"ASC".equals(request.path("SortOrder").asText("DESC"))) {
            order = order.reversed();
        }
        List<ObjectNode> all = operations.scan(k -> true).stream().map(this::view)
                .filter(op -> since == null || op.path("SubmittedDate").asText().compareTo(normalize(since)) >= 0)
                .filter(op -> statuses.isEmpty() || statuses.contains(op.path("Status").asText()))
                .filter(op -> types.isEmpty() || types.contains(op.path("Type").asText()))
                .sorted(order).toList();
        int start = marker(request.path("Marker").asText(null));
        int end = Math.min(all.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("Operations");
        all.subList(Math.min(start, end), end).forEach(list::add);
        if (end < all.size()) {
            response.put("NextPageMarker", Integer.toString(end));
        }
        return response;
    }

    private static String normalize(String timestamp) {
        try {
            return Instant.ofEpochMilli((long) (Double.parseDouble(timestamp) * 1000)).toString();
        } catch (NumberFormatException e) {
            return timestamp;
        }
    }

    public ObjectNode operationAuthorization(JsonNode request) {
        operationDetail(request);
        return mapper.createObjectNode();
    }

    private void bill(String domain, String operation, double price) {
        String id = UUID.randomUUID().toString();
        ObjectNode record = mapper.createObjectNode();
        record.put("DomainName", domain);
        record.put("Operation", operation);
        record.put("InvoiceId", "FL" + id.substring(0, 8).toUpperCase(Locale.ROOT));
        record.put("BillDate", Instant.now().toString());
        record.put("Price", price);
        billing.put(id, record);
    }

    public ObjectNode viewBilling(JsonNode request) {
        String from = request.hasNonNull("Start") ? normalize(request.get("Start").asText()) : "";
        String to = request.hasNonNull("End") ? normalize(request.get("End").asText()) : "9999";
        int max = request.path("MaxItems").asInt(20);
        List<ObjectNode> all = billing.scan(k -> true).stream()
                .filter(r -> r.path("BillDate").asText().compareTo(from) >= 0
                        && r.path("BillDate").asText().compareTo(to) <= 0)
                .sorted(Comparator.comparing(r -> r.path("BillDate").asText())).toList();
        int start = marker(request.path("Marker").asText(null));
        int end = Math.min(all.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("BillingRecords");
        all.subList(Math.min(start, end), end).forEach(list::add);
        if (end < all.size()) {
            response.put("NextPageMarker", Integer.toString(end));
        }
        return response;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static int marker(String marker) {
        if (marker == null || marker.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(marker));
        } catch (NumberFormatException e) {
            throw invalid("The Marker is not valid.");
        }
    }

    private static String token(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidInput", message, 400);
    }

    @Override
    public void clear() {
        domains.clear();
        operations.clear();
        billing.clear();
    }
}
