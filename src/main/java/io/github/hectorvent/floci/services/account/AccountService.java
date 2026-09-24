package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AccountMetadata;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class AccountService implements Resettable {
    private static final String ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL = "account.amazonaws.com";
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern EMAIL = Pattern.compile("\\s*[\\w+=.#|!&-]+@[\\w.-]+\\.[\\w]+\\s*");
    private static final Pattern PHONE = Pattern.compile("[\\s0-9()+-]+");
    private static final Set<String> CONTACT_TYPES = Set.of("BILLING", "OPERATIONS", "SECURITY");

    private static final Pattern ACCOUNT_NAME = Pattern.compile("^[ -;=?-~]+$");
    private static final Pattern PRIMARY_PHONE = Pattern.compile("^[+][\\s0-9()-]+$");
    private static final Set<String> COUNTRY_CODES = Set.of(Locale.getISOCountries());
    private static final Set<String> STATE_REQUIRED = Set.of("US", "CA", "GB", "DE", "JP", "IN", "BR");
    private static final Set<String> REGION_STATUSES = Set.of(
            "ENABLED", "ENABLING", "DISABLING", "DISABLED", "ENABLED_BY_DEFAULT");

    private final AccountAwareStorageBackend<AlternateContact> contacts;
    private final AccountAwareStorageBackend<AccountMetadata> metadata;
    private final AccountAwareStorageBackend<Map<String, String>> primaryContacts;
    private final StorageFactory storageFactory;
    private final OrganizationsService organizationsService;

    @Inject
    public AccountService(StorageFactory storageFactory, OrganizationsService organizationsService) {
        this.contacts = storageFactory.create("account", "account-alternate-contacts.json",
                new TypeReference<Map<String, AlternateContact>>() {});
        this.metadata = storageFactory.create("account", "account-metadata.json",
                new TypeReference<Map<String, AccountMetadata>>() {});
        this.primaryContacts = storageFactory.create("account", "account-primary-contacts.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.storageFactory = storageFactory;
        this.organizationsService = organizationsService;
    }

    public synchronized Map<String, Object> getAccountInformation(String callerAccountId, JsonNode request) {
        String accountId = resolveTargetAccount(callerAccountId, request);
        OrganizationAccount organizationAccount = organizationsService.findAccountForPortal(accountId).orElse(null);
        AccountMetadata account = ensureMetadata(accountId, organizationAccount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("AccountId", accountId);
        result.put("AccountName", account.accountName());
        result.put("AccountState", organizationAccount == null ? "ACTIVE" : organizationAccount.getStatus());
        if (account.accountCreatedDate() != null) {
            result.put("AccountCreatedDate", account.accountCreatedDate());
        }
        return result;
    }

    public synchronized void putAccountName(String callerAccountId, JsonNode request) {
        String accountId = resolveTargetAccount(callerAccountId, request);
        String name = requirePattern(request, "AccountName", 1, 50, ACCOUNT_NAME);
        OrganizationAccount organizationAccount = organizationsService.findAccountForPortal(accountId).orElse(null);
        AccountMetadata account = ensureMetadata(accountId, organizationAccount);
        if (organizationAccount != null) {
            Organization organization = organizationsService.describeOrganization(accountId);
            // StorageFactory reuses Organizations' canonical backend, including its persistence lifecycle.
            AccountAwareStorageBackend<OrganizationAccount> accounts = storageFactory.create(
                    "organizations", "organizations-accounts.json",
                    new TypeReference<Map<String, OrganizationAccount>>() {});
            organizationAccount.setName(name);
            accounts.putForAccount(organization.getMasterAccountId(), accountId, organizationAccount);
        }
        metadata.putForAccount(accountId, "identity", new AccountMetadata(name, account.accountCreatedDate()));
    }

    private AccountMetadata ensureMetadata(String accountId, OrganizationAccount organizationAccount) {
        AccountMetadata current = metadata.getForAccount(accountId, "identity").orElse(null);
        if (organizationAccount != null) {
            // Joining an organization does not establish an invited account's creation date.
            String created = "CREATED".equals(organizationAccount.getJoinedMethod())
                    && organizationAccount.getJoinedTimestamp() != null
                    ? organizationAccount.getJoinedTimestamp().toString()
                    : current == null ? null : current.accountCreatedDate();
            AccountMetadata observed = new AccountMetadata(organizationAccount.getName(), created);
            if (!observed.equals(current)) {
                metadata.putForAccount(accountId, "identity", observed);
            }
            return observed;
        }
        if (current == null) {
            // Standalone identities are provisioned on first use; the account ID is their initial display name.
            current = new AccountMetadata(accountId, Instant.now().toString());
            metadata.putForAccount(accountId, "identity", current);
        }
        return current;
    }

    public Map<String, String> getContactInformation(String callerAccountId, JsonNode request) {
        String accountId = resolveTargetAccount(callerAccountId, request);
        return primaryContacts.getForAccount(accountId, "contact").map(Map::copyOf)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No primary contact has been configured for this emulator account. "
                                + "Use PutContactInformation to supply the account owner's contact information.", 404));
    }

    public void putContactInformation(String callerAccountId, JsonNode request) {
        String accountId = resolveTargetAccount(callerAccountId, request);
        JsonNode contact = request.get("ContactInformation");
        if (contact == null || !contact.isObject()) {
            throw validation("ContactInformation must be an object.");
        }
        Map<String, String> value = new LinkedHashMap<>();
        value.put("FullName", requireLength(contact, "FullName", 1, 50));
        value.put("AddressLine1", requireLength(contact, "AddressLine1", 1, 60));
        value.put("City", requireLength(contact, "City", 1, 50));
        value.put("PostalCode", requireLength(contact, "PostalCode", 1, 20));
        String country = requireLength(contact, "CountryCode", 2, 2);
        if (!COUNTRY_CODES.contains(country)) {
            throw validation("CountryCode must be an ISO-3166 two-letter country code.");
        }
        value.put("CountryCode", country);
        value.put("PhoneNumber", requirePattern(contact, "PhoneNumber", 1, 20, PRIMARY_PHONE));
        for (String field : List.of("AddressLine2", "AddressLine3")) {
            copyOptional(contact, value, field, 60);
        }
        for (String field : List.of("StateOrRegion", "DistrictOrCounty", "CompanyName")) {
            copyOptional(contact, value, field, 50);
        }
        copyOptional(contact, value, "WebsiteUrl", 256);
        if (STATE_REQUIRED.contains(country) && !value.containsKey("StateOrRegion")) {
            throw validation("StateOrRegion is required for the specified CountryCode.");
        }
        primaryContacts.putForAccount(accountId, "contact", value);
    }

    private static void copyOptional(JsonNode request, Map<String, String> value, String field, int max) {
        if (request.hasNonNull(field)) {
            value.put(field, requireLength(request, field, 1, max));
        }
    }

    public Map<String, String> getRegionOptStatus(String callerAccountId, JsonNode request) {
        resolveTargetAccount(callerAccountId, request);
        String region = requireAdvertisedRegion(request);
        return regionStatus(region);
    }

    public Map<String, Object> listRegions(String callerAccountId, JsonNode request) {
        resolveTargetAccount(callerAccountId, request);
        Set<String> statuses = new HashSet<>();
        JsonNode filter = request.get("RegionOptStatusContains");
        if (filter != null && !filter.isNull()) {
            if (!filter.isArray()) {
                throw validation("RegionOptStatusContains must be a list of region statuses.");
            }
            for (JsonNode status : filter) {
                if (!status.isTextual() || !REGION_STATUSES.contains(status.textValue())) {
                    throw validation("RegionOptStatusContains contains an invalid region status.");
                }
                statuses.add(status.textValue());
            }
        }
        Integer maxResults = null;
        if (request.hasNonNull("MaxResults")) {
            JsonNode max = request.get("MaxResults");
            if (!max.isIntegralNumber() || !max.canConvertToInt()) {
                throw validation("MaxResults must be an integer between 1 and 50.");
            }
            maxResults = max.intValue();
        }
        String nextToken = request.hasNonNull("NextToken")
                ? requireLength(request, "NextToken", 0, 1000) : null;
        List<String> regions = statuses.isEmpty() || statuses.contains("ENABLED_BY_DEFAULT")
                ? AwsRegions.ALL : List.of();
        PaginatedResult<String> page = Pagination.paginate(regions, region -> region, maxResults, nextToken,
                20, 50, "ValidationException");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Regions", page.items().stream().map(AccountService::regionStatus).toList());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public void rejectRegionChange(String callerAccountId, JsonNode request) {
        resolveTargetAccount(callerAccountId, request);
        requireAdvertisedRegion(request);
        throw validation("Regions enabled by default cannot be enabled or disabled.");
    }

    private static String requireAdvertisedRegion(JsonNode request) {
        String region = requireLength(request, "RegionName", 1, 50);
        if (!AwsRegions.ALL.contains(region)) {
            throw validation("The region is not in the emulator's supported region catalog. "
                    + "Per-account region activation is not supported.");
        }
        return region;
    }

    private static Map<String, String> regionStatus(String region) {
        return Map.of("RegionName", region, "RegionOptStatus", "ENABLED_BY_DEFAULT");
    }

    public void putAlternateContact(String callerAccountId, JsonNode request) {
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = requireContactType(request);
        AlternateContact contact = new AlternateContact(
                type,
                requirePattern(request, "EmailAddress", 1, 254, EMAIL),
                requireLength(request, "Name", 1, 64),
                requirePattern(request, "PhoneNumber", 1, 25, PHONE),
                requireLength(request, "Title", 1, 50));
        contacts.putForAccount(targetAccountId, type, contact);
    }

    public AlternateContact getAlternateContact(String callerAccountId, JsonNode request) {
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = requireContactType(request);
        return contacts.getForAccount(targetAccountId, type)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The alternate contact does not exist for the specified account and contact type.", 404));
    }

    public void deleteAlternateContact(String callerAccountId, JsonNode request) {
        String targetAccountId = resolveTargetAccount(callerAccountId, request);
        String type = requireContactType(request);
        if (contacts.getForAccount(targetAccountId, type).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "The alternate contact does not exist for the specified account and contact type.", 404);
        }
        contacts.deleteForAccount(targetAccountId, type);
    }

    private String resolveTargetAccount(String callerAccountId, JsonNode request) {
        JsonNode accountIdNode = request == null ? null : request.get("AccountId");
        if (accountIdNode == null || accountIdNode.isNull()) {
            return callerAccountId;
        }
        if (!accountIdNode.isTextual()) {
            throw new AwsException("SerializationException",
                    "AccountId must be a string.", 400);
        }
        String requestedAccountId = accountIdNode.textValue();
        if (!ACCOUNT_ID.matcher(requestedAccountId).matches()) {
            throw validation("AccountId must be a 12 digit account ID.");
        }

        Organization organization;
        OrganizationAccount caller;
        try {
            organization = organizationsService.describeOrganization(callerAccountId);
            caller = organizationsService.describeAccount(callerAccountId, callerAccountId);
            organizationsService.describeAccount(callerAccountId, requestedAccountId);
        } catch (AwsException e) {
            throw new AwsException("AccessDeniedException",
                    "The specified account cannot be accessed by the calling account.", 403);
        }

        if (!"ALL".equals(organization.getFeatureSet())) {
            throw new AwsException("AccessDeniedException",
                    "The organization must have all features enabled.", 403);
        }
        if (!organization.getEnabledServicePrincipals().containsKey(ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL)) {
            throw new AwsException("AccessDeniedException",
                    "Trusted access for AWS Account Management is not enabled for the organization.", 403);
        }

        boolean managementAccount = callerAccountId.equals(organization.getMasterAccountId());
        boolean delegatedAdministrator = caller.getDelegatedServices()
                .containsKey(ACCOUNT_MANAGEMENT_SERVICE_PRINCIPAL);
        if (!managementAccount && !delegatedAdministrator) {
            throw new AwsException("AccessDeniedException",
                    "The calling account is not the management account or a delegated administrator.", 403);
        }
        if (managementAccount && requestedAccountId.equals(callerAccountId)) {
            throw validation("The management account cannot specify its own AccountId.");
        }
        return requestedAccountId;
    }

    @Override
    public void clear() {
        contacts.clear();
        metadata.clear();
        primaryContacts.clear();
    }

    private static String requireContactType(JsonNode request) {
        String value = requireLength(request, "AlternateContactType", 1, 32);
        if (!CONTACT_TYPES.contains(value)) {
            throw validation("AlternateContactType must be BILLING, OPERATIONS, or SECURITY.");
        }
        return value;
    }

    private static String requirePattern(JsonNode request, String field, int min, int max, Pattern pattern) {
        String value = requireLength(request, field, min, max);
        if (!pattern.matcher(value).matches()) {
            throw validation(field + " does not satisfy the required pattern.");
        }
        return value;
    }

    private static String requireLength(JsonNode request, String field, int min, int max) {
        String value = text(request, field);
        if (value == null || value.length() < min || value.length() > max) {
            throw validation(field + " must be between " + min + " and " + max + " characters.");
        }
        return value;
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
