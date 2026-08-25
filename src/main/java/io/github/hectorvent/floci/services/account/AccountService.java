package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AccountInformation;
import io.github.hectorvent.floci.services.account.model.AccountSettings;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
import io.github.hectorvent.floci.services.account.model.ContactInformation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AWS Account Management (restJson1).
 *
 * <p>Each 12-digit account stores one {@link AccountSettings} row: display name, primary
 * contact, and alternate contacts. A first read seeds AWS-shaped defaults so the name and
 * primary contact always exist (they cannot be deleted on live AWS).
 */
@ApplicationScoped
public class AccountService {

    public static final String DEFAULT_ACCOUNT_NAME = "Floci Account";
    public static final String DEFAULT_ACCOUNT_STATE = "ACTIVE";
    public static final String DEFAULT_CREATED_DATE = "2017-01-01T00:00:00Z";

    static final String SETTINGS_KEY = "settings";

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern PHONE = Pattern.compile("^[+][\\s0-9()-]+$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern REGION_NAME = Pattern.compile("[a-z]{2}(?:-[a-z]+)+-\\d+");
    private static final Set<String> ALTERNATE_TYPES = Set.of("BILLING", "OPERATIONS", "SECURITY");
    static final String STATUS_ENABLED_BY_DEFAULT = "ENABLED_BY_DEFAULT";
    static final String STATUS_ENABLED = "ENABLED";
    static final String STATUS_DISABLED = "DISABLED";
    static final int DEFAULT_LIST_MAX_RESULTS = 20;
    static final int MAX_LIST_MAX_RESULTS = 50;
    private static final List<String> OPT_IN_REGIONS = List.of(
            "af-south-1",
            "ap-east-1",
            "ap-east-2",
            "ap-south-2",
            "ap-southeast-3",
            "ap-southeast-4",
            "ap-southeast-5",
            "ca-west-1",
            "eu-central-2",
            "eu-south-1",
            "eu-south-2",
            "il-central-1",
            "me-central-1",
            "me-south-1");

    private final StorageBackend<String, AccountSettings> store;
    private final RegionResolver regionResolver;

    @Inject
    public AccountService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(
                "account",
                "account-settings.json",
                new TypeReference<java.util.Map<String, AccountSettings>>() {
                }), regionResolver);
    }

    AccountService(StorageBackend<String, AccountSettings> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public AccountInformation getAccountInformation(JsonNode request) {
        requireObject(request, "Request body");
        AccountSettings settings = loadOrCreate(resolveTargetAccount(request));
        return new AccountInformation(
                settings.getAccountId(),
                settings.getAccountName(),
                settings.getAccountCreatedDate(),
                settings.getAccountState());
    }

    public synchronized void putAccountName(JsonNode request) {
        requireObject(request, "Request body");
        String name = requireLength(request, "AccountName", 1, 50);
        String accountId = resolveTargetAccount(request);
        AccountSettings settings = loadOrCreate(accountId);
        settings.setAccountName(name);
        save(accountId, settings);
    }

    public AlternateContact getAlternateContact(JsonNode request) {
        requireObject(request, "Request body");
        String type = requireAlternateType(request);
        String accountId = resolveTargetAccount(request);
        AlternateContact contact = loadOrCreate(accountId).getAlternateContacts().get(type);
        if (contact == null) {
            throw notFound("Alternate contact " + type + " not found.");
        }
        return contact;
    }

    public synchronized void putAlternateContact(JsonNode request) {
        requireObject(request, "Request body");
        String type = requireAlternateType(request);
        String accountId = resolveTargetAccount(request);
        AlternateContact contact = new AlternateContact(
                requireLength(request, "Name", 1, 64),
                requireLength(request, "Title", 1, 50),
                requireLength(request, "EmailAddress", 1, 254),
                requirePhone(request, "PhoneNumber"),
                type);
        AccountSettings settings = loadOrCreate(accountId);
        settings.getAlternateContacts().put(type, contact);
        save(accountId, settings);
    }

    public synchronized void deleteAlternateContact(JsonNode request) {
        requireObject(request, "Request body");
        String type = requireAlternateType(request);
        String accountId = resolveTargetAccount(request);
        AccountSettings settings = loadOrCreate(accountId);
        AlternateContact removed = settings.getAlternateContacts().remove(type);
        if (removed == null) {
            throw notFound("Alternate contact " + type + " not found.");
        }
        save(accountId, settings);
    }

    public ContactInformation getContactInformation(JsonNode request) {
        requireObject(request, "Request body");
        String accountId = resolveTargetAccount(request);
        AccountSettings settings = loadOrCreate(accountId);
        if (settings.getContactInformation() == null) {
            settings.setContactInformation(defaultContact());
            save(accountId, settings);
        }
        return settings.getContactInformation();
    }

    public synchronized void putContactInformation(JsonNode request) {
        requireObject(request, "Request body");
        String accountId = resolveTargetAccount(request);
        JsonNode contactNode = request.get("ContactInformation");
        requireObject(contactNode, "ContactInformation");
        AccountSettings settings = loadOrCreate(accountId);
        settings.setContactInformation(readPrimaryContact(contactNode));
        save(accountId, settings);
    }

    public synchronized ObjectNode listRegions(JsonNode request) {
        requireObject(request, "Request body");
        AccountSettings settings = loadOrCreate(resolveTargetAccount(request));
        ensureDefaultRegions(settings);
        Set<String> statusFilter = optionalStatusFilter(request);
        int maxResults = optionalMaxResults(request);
        String nextToken = optionalText(request, "NextToken");

        List<Map.Entry<String, String>> regions = new ArrayList<>(settings.getRegionOptStatus().entrySet());
        regions.sort(Comparator.comparing(Map.Entry::getKey));
        if (statusFilter != null) {
            regions.removeIf(entry -> !statusFilter.contains(entry.getValue()));
        }

        int start = 0;
        if (nextToken != null) {
            for (int i = 0; i < regions.size(); i++) {
                if (regions.get(i).getKey().equals(nextToken)) {
                    start = i + 1;
                    break;
                }
            }
        }
        int end = Math.min(start + maxResults, regions.size());
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        ArrayNode items = response.putArray("Regions");
        for (int i = start; i < end; i++) {
            Map.Entry<String, String> entry = regions.get(i);
            ObjectNode item = items.addObject();
            item.put("RegionName", entry.getKey());
            item.put("RegionOptStatus", entry.getValue());
        }
        if (end < regions.size()) {
            response.put("NextToken", regions.get(end - 1).getKey());
        }
        return response;
    }

    public synchronized ObjectNode getRegionOptStatus(JsonNode request) {
        requireObject(request, "Request body");
        String regionName = requireRegionName(request);
        AccountSettings settings = loadOrCreate(resolveTargetAccount(request));
        ensureDefaultRegions(settings);
        String status = settings.getRegionOptStatus().get(regionName);
        if (status == null) {
            status = STATUS_DISABLED;
            settings.getRegionOptStatus().put(regionName, status);
            save(settings.getAccountId(), settings);
        }
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("RegionName", regionName);
        response.put("RegionOptStatus", status);
        return response;
    }

    public synchronized ObjectNode enableRegion(JsonNode request) {
        requireObject(request, "Request body");
        String regionName = requireRegionName(request);
        AccountSettings settings = loadOrCreate(resolveTargetAccount(request));
        ensureDefaultRegions(settings);
        String status = settings.getRegionOptStatus().getOrDefault(regionName, STATUS_DISABLED);
        if (STATUS_ENABLED_BY_DEFAULT.equals(status) || STATUS_ENABLED.equals(status)) {
            return JsonNodeFactory.instance.objectNode();
        }
        settings.getRegionOptStatus().put(regionName, STATUS_ENABLED);
        save(settings.getAccountId(), settings);
        return JsonNodeFactory.instance.objectNode();
    }

    public synchronized ObjectNode disableRegion(JsonNode request) {
        requireObject(request, "Request body");
        String regionName = requireRegionName(request);
        AccountSettings settings = loadOrCreate(resolveTargetAccount(request));
        ensureDefaultRegions(settings);
        String status = settings.getRegionOptStatus().getOrDefault(regionName, STATUS_DISABLED);
        if (STATUS_ENABLED_BY_DEFAULT.equals(status)) {
            throw validation("Region " + regionName + " is enabled by default and cannot be disabled.");
        }
        if (STATUS_DISABLED.equals(status)) {
            return JsonNodeFactory.instance.objectNode();
        }
        settings.getRegionOptStatus().put(regionName, STATUS_DISABLED);
        save(settings.getAccountId(), settings);
        return JsonNodeFactory.instance.objectNode();
    }

    private String resolveTargetAccount(JsonNode request) {
        if (!request.has("AccountId") || request.get("AccountId").isNull()) {
            return regionResolver.getAccountId();
        }
        String accountId = requireText(request, "AccountId");
        if (!ACCOUNT_ID.matcher(accountId).matches()) {
            throw validation("AccountId must be a 12-digit identifier.");
        }
        return accountId;
    }

    private AccountSettings loadOrCreate(String accountId) {
        Optional<AccountSettings> existing = load(accountId);
        if (existing.isPresent()) {
            AccountSettings settings = existing.get();
            ensureDefaultRegions(settings);
            return settings;
        }
        AccountSettings settings = new AccountSettings();
        settings.setAccountId(accountId);
        settings.setAccountName(DEFAULT_ACCOUNT_NAME);
        settings.setAccountCreatedDate(DEFAULT_CREATED_DATE);
        settings.setAccountState(DEFAULT_ACCOUNT_STATE);
        settings.setContactInformation(defaultContact());
        ensureDefaultRegions(settings);
        save(accountId, settings);
        return settings;
    }

    private Optional<AccountSettings> load(String accountId) {
        if (store instanceof AccountAwareStorageBackend<AccountSettings> aware) {
            return aware.getForAccount(accountId, SETTINGS_KEY);
        }
        return store.get(accountId);
    }

    private void save(String accountId, AccountSettings settings) {
        if (store instanceof AccountAwareStorageBackend<AccountSettings> aware) {
            aware.putForAccount(accountId, SETTINGS_KEY, settings);
            return;
        }
        store.put(accountId, settings);
    }

    private static ContactInformation readPrimaryContact(JsonNode node) {
        ContactInformation contact = new ContactInformation();
        contact.setFullName(requireLength(node, "FullName", 1, 50));
        contact.setAddressLine1(requireLength(node, "AddressLine1", 1, 60));
        contact.setAddressLine2(optionalLength(node, "AddressLine2", 1, 60));
        contact.setAddressLine3(optionalLength(node, "AddressLine3", 1, 60));
        contact.setCity(requireLength(node, "City", 1, 50));
        contact.setStateOrRegion(optionalLength(node, "StateOrRegion", 1, 50));
        contact.setDistrictOrCounty(optionalLength(node, "DistrictOrCounty", 1, 50));
        contact.setPostalCode(requireLength(node, "PostalCode", 1, 20));
        String countryCode = requireLength(node, "CountryCode", 2, 2);
        if (!COUNTRY.matcher(countryCode).matches()) {
            throw validation("CountryCode must be a two-letter ISO-3166 code.");
        }
        contact.setCountryCode(countryCode);
        contact.setPhoneNumber(requirePhone(node, "PhoneNumber"));
        contact.setCompanyName(optionalLength(node, "CompanyName", 1, 50));
        contact.setWebsiteUrl(optionalLength(node, "WebsiteUrl", 1, 256));
        return contact;
    }

    private static ContactInformation defaultContact() {
        ContactInformation contact = new ContactInformation();
        contact.setFullName("Floci User");
        contact.setAddressLine1("410 Terry Ave N");
        contact.setCity("Seattle");
        contact.setStateOrRegion("WA");
        contact.setPostalCode("98109");
        contact.setCountryCode("US");
        contact.setPhoneNumber("+12025550100");
        contact.setCompanyName("Floci");
        return contact;
    }

    private static void ensureDefaultRegions(AccountSettings settings) {
        Map<String, String> statuses = settings.getRegionOptStatus();
        for (String region : AwsRegions.ALL) {
            statuses.putIfAbsent(region, STATUS_ENABLED_BY_DEFAULT);
        }
        for (String region : OPT_IN_REGIONS) {
            statuses.putIfAbsent(region, STATUS_DISABLED);
        }
    }

    private static String requireRegionName(JsonNode request) {
        String regionName = requireText(request, "RegionName");
        if (!REGION_NAME.matcher(regionName).matches()) {
            throw validation("RegionName is invalid.");
        }
        return regionName;
    }

    private static Set<String> optionalStatusFilter(JsonNode request) {
        JsonNode node = request.get("RegionOptStatusContains");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw validation("RegionOptStatusContains must be a list of statuses.");
        }
        Set<String> statuses = new java.util.LinkedHashSet<>();
        for (JsonNode entry : node) {
            if (!entry.isTextual() || entry.textValue().isBlank()) {
                throw validation("RegionOptStatusContains entries must be strings.");
            }
            statuses.add(entry.textValue());
        }
        return statuses.isEmpty() ? null : statuses;
    }

    private static int optionalMaxResults(JsonNode request) {
        JsonNode node = request.get("MaxResults");
        if (node == null || node.isNull()) {
            return DEFAULT_LIST_MAX_RESULTS;
        }
        int value;
        try {
            value = node.isNumber() ? node.intValue() : Integer.parseInt(node.asText());
        } catch (NumberFormatException e) {
            throw validation("MaxResults must be an integer.");
        }
        if (value < 1 || value > MAX_LIST_MAX_RESULTS) {
            throw validation("MaxResults must be between 1 and " + MAX_LIST_MAX_RESULTS + ".");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String requireAlternateType(JsonNode request) {
        String type = requireText(request, "AlternateContactType");
        if (!ALTERNATE_TYPES.contains(type)) {
            throw validation("AlternateContactType must be BILLING, OPERATIONS, or SECURITY.");
        }
        return type;
    }

    private static String requirePhone(JsonNode parent, String field) {
        String phone = requireLength(parent, field, 1, 20);
        if (!PHONE.matcher(phone).matches()) {
            throw validation("The specified PhoneNumber is not valid.");
        }
        return phone;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String requireLength(JsonNode parent, String field, int min, int max) {
        String value = requireText(parent, field);
        if (value.length() < min || value.length() > max) {
            throw validation(field + " must be between " + min + " and " + max + " characters.");
        }
        return value;
    }

    private static String optionalLength(JsonNode parent, String field, int min, int max) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        return requireLength(parent, field, min, max);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
