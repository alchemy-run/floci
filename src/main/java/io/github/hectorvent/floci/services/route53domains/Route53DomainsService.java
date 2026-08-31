package io.github.hectorvent.floci.services.route53domains;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory Route 53 Domains registrar. Availability, suggestions, and
 * pricing are synthetic; registered domains live in this process until reset.
 *
 * @see <a href="https://docs.aws.amazon.com/Route53/latest/APIReference/API_Operations_Amazon_Route_53_Domains.html">Route 53 Domains API</a>
 */
@ApplicationScoped
public class Route53DomainsService implements Resettable {

    static final String TARGET_PREFIX = "Route53Domains_v20140515.";

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Set<String> SUPPORTED_TLDS = Set.of(
            "com", "net", "org", "io", "info", "biz", "co", "me", "us",
            "dev", "app", "ai", "cloud", "shop", "xyz", "online", "site");
    private static final List<String> DEFAULT_NAMESERVERS = List.of(
            "ns-1.awsdns-01.org",
            "ns-2.awsdns-02.net",
            "ns-3.awsdns-03.com",
            "ns-4.awsdns-04.co.uk");
    private static final Map<String, Double> REGISTRATION_PRICES = Map.ofEntries(
            Map.entry("com", 12.0),
            Map.entry("net", 11.0),
            Map.entry("org", 12.0),
            Map.entry("io", 45.0),
            Map.entry("info", 12.0),
            Map.entry("biz", 12.0),
            Map.entry("co", 30.0),
            Map.entry("me", 20.0),
            Map.entry("us", 12.0),
            Map.entry("dev", 12.0),
            Map.entry("app", 14.0),
            Map.entry("ai", 79.0),
            Map.entry("cloud", 20.0),
            Map.entry("shop", 28.0),
            Map.entry("xyz", 12.0),
            Map.entry("online", 30.0),
            Map.entry("site", 28.0));

    static final class Domain {
        String domainName;
        boolean autoRenew = true;
        boolean transferLock = true;
        long creationDate;
        long updatedDate;
        long expiry;
        final List<String> nameservers = new ArrayList<>();
        String authCode;
    }

    static final class Operation {
        String operationId;
        String status;
        String type;
        String domainName;
        String message;
        long submittedDate;
        long lastUpdatedDate;
    }

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Domain> domains = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Operation> operations = new ConcurrentHashMap<>();

    @Inject
    public Route53DomainsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        domains.clear();
        operations.clear();
    }

    public ObjectNode checkDomainAvailability(JsonNode request) {
        String domainName = requireDomainName(request);
        requireSupportedTld(domainName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Availability", domains.containsKey(domainName) ? "UNAVAILABLE" : "AVAILABLE");
        return response;
    }

    public ObjectNode checkDomainTransferability(JsonNode request) {
        String domainName = requireDomainName(request);
        requireSupportedTld(domainName);
        ObjectNode transferability = objectMapper.createObjectNode();
        if (domains.containsKey(domainName)) {
            transferability.put("Transferable", "DOMAIN_IN_OWN_ACCOUNT");
        } else {
            transferability.put("Transferable", "UNTRANSFERABLE");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Transferability", transferability);
        return response;
    }

    public ObjectNode getDomainDetail(JsonNode request) {
        Domain domain = requireOwnedDomain(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DomainName", domain.domainName);
        response.set("Nameservers", nameserverNodes(domain.nameservers));
        response.put("AutoRenew", domain.autoRenew);
        response.put("AdminPrivacy", true);
        response.put("RegistrantPrivacy", true);
        response.put("TechPrivacy", true);
        response.put("RegistrarName", "Amazon Registrar, Inc.");
        response.put("WhoIsServer", "whois.registrar.amazon");
        response.put("RegistrarUrl", "https://registrar.amazon.com");
        response.put("CreationDate", domain.creationDate);
        response.put("UpdatedDate", domain.updatedDate);
        response.put("ExpirationDate", domain.expiry);
        ArrayNode status = objectMapper.createArrayNode();
        status.add("ok");
        if (domain.transferLock) {
            status.add("clientTransferProhibited");
        }
        response.set("StatusList", status);
        return response;
    }

    public ObjectNode getDomainSuggestions(JsonNode request) {
        String domainName = requireDomainName(request);
        int count = integerOrDefault(request, "SuggestionCount", 5);
        if (count < 1) {
            throw invalidInput("SuggestionCount must be greater than 0.");
        }
        boolean onlyAvailable = booleanOrDefault(request, "OnlyAvailable", true);
        String sld = sld(domainName);
        List<String> candidates = List.of(
                sld + ".net",
                sld + ".org",
                sld + ".io",
                sld + ".dev",
                sld + ".app",
                sld + "-online.com",
                sld + "-site.com",
                "get" + sld + ".com",
                "try" + sld + ".com",
                sld + "hq.com");
        ArrayNode suggestions = objectMapper.createArrayNode();
        for (String candidate : candidates) {
            if (suggestions.size() >= count) {
                break;
            }
            boolean owned = domains.containsKey(candidate);
            if (onlyAvailable && owned) {
                continue;
            }
            ObjectNode suggestion = objectMapper.createObjectNode();
            suggestion.put("DomainName", candidate);
            suggestion.put("Availability", owned ? "UNAVAILABLE" : "AVAILABLE");
            suggestions.add(suggestion);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SuggestionsList", suggestions);
        return response;
    }

    public ObjectNode getOperationDetail(JsonNode request) {
        String operationId = requireText(request, "OperationId");
        Operation operation = operations.get(operationId);
        if (operation == null) {
            throw invalidInput("No operation found for the specified ID.");
        }
        return operationNode(operation);
    }

    public ObjectNode listDomains(JsonNode request) {
        List<Domain> all = new ArrayList<>(domains.values());
        all.sort((a, b) -> a.domainName.compareTo(b.domainName));
        int offset = markerOffset(request);
        int maxItems = integerOrDefault(request, "MaxItems", 20);
        maxItems = Math.min(Math.max(maxItems, 1), 100);
        int end = Math.min(offset + maxItems, all.size());
        ArrayNode items = objectMapper.createArrayNode();
        for (int i = offset; i < end; i++) {
            Domain domain = all.get(i);
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("DomainName", domain.domainName);
            summary.put("AutoRenew", domain.autoRenew);
            summary.put("TransferLock", domain.transferLock);
            summary.put("Expiry", domain.expiry);
            items.add(summary);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Domains", items);
        if (end < all.size()) {
            response.put("NextPageMarker", Integer.toString(end));
        }
        return response;
    }

    public ObjectNode listOperations(JsonNode request) {
        List<Operation> all = new ArrayList<>(operations.values());
        all.sort((a, b) -> Long.compare(b.submittedDate, a.submittedDate));
        int offset = markerOffset(request);
        int maxItems = integerOrDefault(request, "MaxItems", 100);
        maxItems = Math.min(Math.max(maxItems, 1), 100);
        int end = Math.min(offset + maxItems, all.size());
        ArrayNode items = objectMapper.createArrayNode();
        for (int i = offset; i < end; i++) {
            items.add(operationSummary(all.get(i)));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Operations", items);
        if (end < all.size()) {
            response.put("NextPageMarker", Integer.toString(end));
        }
        return response;
    }

    public ObjectNode listPrices(JsonNode request) {
        String tld = optionalText(request, "Tld");
        List<String> tlds;
        if (tld != null) {
            String normalized = tld.toLowerCase(Locale.ROOT);
            if (normalized.startsWith(".")) {
                normalized = normalized.substring(1);
            }
            if (!SUPPORTED_TLDS.contains(normalized)) {
                throw unsupportedTld(normalized);
            }
            tlds = List.of(normalized);
        } else {
            tlds = new ArrayList<>(SUPPORTED_TLDS);
            tlds.sort(String::compareTo);
        }
        int offset = markerOffset(request);
        int maxItems = integerOrDefault(request, "MaxItems", 100);
        maxItems = Math.min(Math.max(maxItems, 1), 100);
        int end = Math.min(offset + maxItems, tlds.size());
        ArrayNode prices = objectMapper.createArrayNode();
        for (int i = offset; i < end; i++) {
            prices.add(priceNode(tlds.get(i)));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Prices", prices);
        if (end < tlds.size()) {
            response.put("NextPageMarker", Integer.toString(end));
        }
        return response;
    }

    public ObjectNode registerDomain(JsonNode request) {
        String domainName = requireDomainName(request);
        requireSupportedTld(domainName);
        if (domains.containsKey(domainName)) {
            throw new AwsException("DuplicateRequest",
                    "Domain " + domainName + " is already registered in this account.", 400);
        }
        int years = integerOrDefault(request, "DurationInYears", 1);
        if (years < 1 || years > 10) {
            throw invalidInput("DurationInYears must be between 1 and 10.");
        }
        long now = Instant.now().getEpochSecond();
        Domain domain = new Domain();
        domain.domainName = domainName;
        domain.autoRenew = booleanOrDefault(request, "AutoRenew", true);
        domain.creationDate = now;
        domain.updatedDate = now;
        domain.expiry = now + years * 365L * 24L * 3600L;
        domain.nameservers.addAll(DEFAULT_NAMESERVERS);
        domain.authCode = "AUTH-" + domainName.replace(".", "-");
        domains.put(domainName, domain);
        Operation operation = recordOperation(domainName, "REGISTER_DOMAIN", "SUCCESSFUL",
                "Domain " + domainName + " registered.");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("OperationId", operation.operationId);
        return response;
    }

    public ObjectNode renewDomain(JsonNode request) {
        Domain domain = requireOwnedDomain(request);
        int years = integerOrDefault(request, "DurationInYears", 1);
        if (years < 1 || years > 10) {
            throw invalidInput("DurationInYears must be between 1 and 10.");
        }
        domain.expiry += years * 365L * 24L * 3600L;
        domain.updatedDate = Instant.now().getEpochSecond();
        Operation operation = recordOperation(domain.domainName, "RENEW_DOMAIN", "SUCCESSFUL",
                "Domain " + domain.domainName + " renewed.");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("OperationId", operation.operationId);
        return response;
    }

    public ObjectNode retrieveDomainAuthCode(JsonNode request) {
        Domain domain = requireOwnedDomain(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AuthCode", domain.authCode);
        return response;
    }

    public ObjectNode updateDomainNameservers(JsonNode request) {
        Domain domain = requireOwnedDomain(request);
        JsonNode nameserversNode = request.get("Nameservers");
        if (nameserversNode == null || !nameserversNode.isArray() || nameserversNode.isEmpty()) {
            throw invalidInput("Nameservers is required.");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode entry : nameserversNode) {
            String name = optionalText(entry, "Name");
            if (name == null) {
                throw invalidInput("Each nameserver must include Name.");
            }
            names.add(name.toLowerCase(Locale.ROOT));
        }
        domain.nameservers.clear();
        domain.nameservers.addAll(names);
        domain.updatedDate = Instant.now().getEpochSecond();
        Operation operation = recordOperation(domain.domainName, "UPDATE_NAMESERVER", "SUCCESSFUL",
                "Nameservers updated for " + domain.domainName + ".");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("OperationId", operation.operationId);
        return response;
    }

    private Operation recordOperation(String domainName, String type, String status, String message) {
        long now = Instant.now().getEpochSecond();
        Operation operation = new Operation();
        operation.operationId = UUID.randomUUID().toString();
        operation.type = type;
        operation.status = status;
        operation.domainName = domainName;
        operation.message = message;
        operation.submittedDate = now;
        operation.lastUpdatedDate = now;
        operations.put(operation.operationId, operation);
        return operation;
    }

    private ObjectNode operationNode(Operation operation) {
        ObjectNode node = operationSummary(operation);
        node.put("Message", operation.message);
        return node;
    }

    private ObjectNode operationSummary(Operation operation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("OperationId", operation.operationId);
        node.put("Status", operation.status);
        node.put("Type", operation.type);
        node.put("SubmittedDate", operation.submittedDate);
        node.put("LastUpdatedDate", operation.lastUpdatedDate);
        node.put("DomainName", operation.domainName);
        node.put("Message", operation.message);
        return node;
    }

    private ArrayNode nameserverNodes(List<String> nameservers) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String name : nameservers) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Name", name);
            array.add(node);
        }
        return array;
    }

    private ObjectNode priceNode(String tld) {
        double registration = REGISTRATION_PRICES.getOrDefault(tld, 12.0);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", tld);
        node.set("RegistrationPrice", money(registration));
        node.set("TransferPrice", money(registration));
        node.set("RenewalPrice", money(registration));
        node.set("ChangeOwnershipPrice", money(0.0));
        node.set("RestorationPrice", money(Math.max(registration * 2, 40.0)));
        return node;
    }

    private ObjectNode money(double price) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Price", price);
        node.put("Currency", "USD");
        return node;
    }

    private Domain requireOwnedDomain(JsonNode request) {
        String domainName = requireDomainName(request);
        Domain domain = domains.get(domainName);
        if (domain == null) {
            throw invalidInput("Domain " + domainName + " not found in account.");
        }
        return domain;
    }

    private String requireDomainName(JsonNode request) {
        String domainName = optionalText(request, "DomainName");
        if (domainName == null) {
            throw invalidInput("Request is missing required parameter DomainName.");
        }
        domainName = domainName.toLowerCase(Locale.ROOT).trim();
        validateDomainName(domainName);
        return domainName;
    }

    private void validateDomainName(String domainName) {
        int dot = domainName.lastIndexOf('.');
        if (dot <= 0 || dot == domainName.length() - 1) {
            throw invalidInput("The domain name " + domainName + " is not valid.");
        }
        String[] labels = domainName.split("\\.");
        if (labels.length < 2) {
            throw invalidInput("The domain name " + domainName + " is not valid.");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw invalidInput("The domain name " + domainName + " is not valid.");
            }
        }
    }

    private void requireSupportedTld(String domainName) {
        String tld = tld(domainName);
        if (!SUPPORTED_TLDS.contains(tld)) {
            throw unsupportedTld(tld);
        }
    }

    private static String tld(String domainName) {
        int dot = domainName.lastIndexOf('.');
        return domainName.substring(dot + 1);
    }

    private static String sld(String domainName) {
        int dot = domainName.lastIndexOf('.');
        return domainName.substring(0, dot);
    }

    private static int markerOffset(JsonNode request) {
        String marker = optionalText(request, "Marker");
        if (marker == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(marker), 0);
        } catch (NumberFormatException e) {
            throw invalidInput("Marker is not valid.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalidInput("Request is missing required parameter " + field + ".");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        String text = request.get(field).asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static int integerOrDefault(JsonNode request, String field, int fallback) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return fallback;
        }
        JsonNode value = request.get(field);
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            throw invalidInput(field + " must be an integer.");
        }
    }

    private static boolean booleanOrDefault(JsonNode request, String field, boolean fallback) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return fallback;
        }
        return request.get(field).asBoolean(fallback);
    }

    private static AwsException invalidInput(String message) {
        return new AwsException("InvalidInput", message, 400);
    }

    private static AwsException unsupportedTld(String tld) {
        return new AwsException("UnsupportedTLD",
                "Amazon Route 53 doesn't support the TLD ." + tld + ".", 400);
    }
}
