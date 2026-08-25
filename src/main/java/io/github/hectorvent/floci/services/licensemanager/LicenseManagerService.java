package io.github.hectorvent.floci.services.licensemanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.licensemanager.model.LicenseConfiguration;
import io.github.hectorvent.floci.services.licensemanager.model.LicenseConsumption;
import io.github.hectorvent.floci.services.licensemanager.model.SellerGrant;
import io.github.hectorvent.floci.services.licensemanager.model.SellerLicense;
import io.github.hectorvent.floci.services.licensemanager.model.SellerToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AWS License Manager JSON 1.1 ({@code AWSLicenseManager.*}).
 *
 * <p>{@code Get}/{@code Update}/{@code Delete} of an unknown configuration ARN
 * surface as {@code InvalidParameterValueException} whose message includes
 * {@code Invalid license configuration ARN} — the live error Alchemy maps to
 * the synthetic {@code LicenseConfigurationNotFound} tag.
 *
 * <p>Seller-issued licenses settle immediately to {@code AVAILABLE} so local
 * reconcilers do not wait on the live issuance window.
 */
@ApplicationScoped
public class LicenseManagerService implements Resettable {

    static final String SERVICE = "license-manager";
    static final String STATUS_AVAILABLE = "AVAILABLE";
    static final String STATUS_DELETED = "DELETED";
    static final String GRANT_ACTIVE = "ACTIVE";
    static final String GRANT_DELETED = "DELETED";
    static final Set<String> COUNTING_TYPES = Set.of("vCPU", "Instance", "Core", "Socket");
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS_CAP = 100;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE = new TypeReference<>() {
    };

    private final StorageBackend<String, LicenseConfiguration> store;
    private final StorageBackend<String, SellerLicense> licenses;
    private final StorageBackend<String, SellerGrant> grants;
    private final StorageBackend<String, SellerToken> tokens;
    private final StorageBackend<String, LicenseConsumption> consumptions;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public LicenseManagerService(StorageFactory storageFactory, RegionResolver regionResolver,
                                 ObjectMapper objectMapper) {
        this(storageFactory.create(SERVICE, "license-manager-configurations.json",
                        new TypeReference<Map<String, LicenseConfiguration>>() {
                        }),
                storageFactory.create(SERVICE, "license-manager-licenses.json",
                        new TypeReference<Map<String, SellerLicense>>() {
                        }),
                storageFactory.create(SERVICE, "license-manager-grants.json",
                        new TypeReference<Map<String, SellerGrant>>() {
                        }),
                storageFactory.create(SERVICE, "license-manager-tokens.json",
                        new TypeReference<Map<String, SellerToken>>() {
                        }),
                storageFactory.create(SERVICE, "license-manager-consumptions.json",
                        new TypeReference<Map<String, LicenseConsumption>>() {
                        }),
                regionResolver, objectMapper);
    }

    LicenseManagerService(StorageBackend<String, LicenseConfiguration> store, RegionResolver regionResolver) {
        this(store, new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), regionResolver, new ObjectMapper());
    }

    LicenseManagerService(StorageBackend<String, LicenseConfiguration> store,
                          StorageBackend<String, SellerLicense> licenses,
                          StorageBackend<String, SellerGrant> grants,
                          StorageBackend<String, SellerToken> tokens,
                          StorageBackend<String, LicenseConsumption> consumptions,
                          RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.store = store;
        this.licenses = licenses;
        this.grants = grants;
        this.tokens = tokens;
        this.consumptions = consumptions;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized LicenseConfiguration create(
            String region,
            String name,
            String description,
            String countingType,
            Long licenseCount,
            Boolean licenseCountHardLimit,
            Boolean disassociateWhenNotFound,
            List<String> licenseRules,
            Map<String, String> tags,
            Long licenseExpiry) {
        if (name == null || name.isBlank()) {
            throw invalid("Name is a required parameter.");
        }
        if (countingType == null || countingType.isBlank()) {
            throw invalid("LicenseCountingType is a required parameter.");
        }
        if (!COUNTING_TYPES.contains(countingType)) {
            throw invalid("LicenseCountingType is invalid.");
        }

        String id = "lic-" + HexFormat.of().formatHex(randomBytes(16));
        String resolvedRegion = region == null || region.isBlank() ? "us-east-1" : region;
        String accountId = accountId();
        String arn = "arn:aws:license-manager:" + resolvedRegion + ":" + accountId
                + ":license-configuration:" + id;

        LicenseConfiguration config = new LicenseConfiguration();
        config.setLicenseConfigurationId(id);
        config.setLicenseConfigurationArn(arn);
        config.setName(name);
        config.setDescription(description);
        config.setLicenseCountingType(countingType);
        config.setLicenseCount(licenseCount);
        config.setLicenseCountHardLimit(Boolean.TRUE.equals(licenseCountHardLimit));
        config.setDisassociateWhenNotFound(Boolean.TRUE.equals(disassociateWhenNotFound));
        if (licenseRules != null) {
            config.setLicenseRules(new ArrayList<>(licenseRules));
        }
        if (tags != null) {
            config.getTags().putAll(tags);
        }
        config.setStatus(STATUS_AVAILABLE);
        config.setOwnerAccountId(accountId);
        config.setRegion(resolvedRegion);
        config.setConsumedLicenses(0);
        config.setLicenseExpiry(licenseExpiry);
        store.put(arn, config);
        return config;
    }

    public LicenseConfiguration get(String arn) {
        return require(arn);
    }

    public synchronized LicenseConfiguration update(
            String arn,
            String name,
            String description,
            Long licenseCount,
            Boolean licenseCountHardLimit,
            Boolean disassociateWhenNotFound,
            List<String> licenseRules,
            String status,
            Long licenseExpiry) {
        LicenseConfiguration config = requireLive(arn);
        if (name != null) {
            config.setName(name);
        }
        if (description != null) {
            config.setDescription(description);
        }
        if (licenseCount != null) {
            config.setLicenseCount(licenseCount);
        }
        if (licenseCountHardLimit != null) {
            config.setLicenseCountHardLimit(licenseCountHardLimit);
        }
        if (disassociateWhenNotFound != null) {
            config.setDisassociateWhenNotFound(disassociateWhenNotFound);
        }
        if (licenseRules != null) {
            config.setLicenseRules(new ArrayList<>(licenseRules));
        }
        if (status != null && !status.isBlank()) {
            config.setStatus(status);
        }
        if (licenseExpiry != null) {
            config.setLicenseExpiry(licenseExpiry);
        }
        store.put(config.getLicenseConfigurationArn(), config);
        return config;
    }

    public synchronized void delete(String arn) {
        LicenseConfiguration config = requireLive(arn);
        config.setStatus(STATUS_DELETED);
        store.put(config.getLicenseConfigurationArn(), config);
    }

    public List<LicenseConfiguration> list(String region, List<String> arns, String nextToken, Integer maxResults) {
        List<LicenseConfiguration> matches = new ArrayList<>();
        for (LicenseConfiguration config : store.values()) {
            if (!STATUS_AVAILABLE.equals(config.getStatus())) {
                continue;
            }
            if (region != null && !region.isBlank() && config.getRegion() != null
                    && !region.equals(config.getRegion())) {
                continue;
            }
            if (arns != null && !arns.isEmpty() && !arns.contains(config.getLicenseConfigurationArn())) {
                continue;
            }
            matches.add(config);
        }
        matches.sort(Comparator.comparing(LicenseConfiguration::getLicenseConfigurationArn,
                Comparator.nullsLast(String::compareTo)));

        int limit = maxResults == null || maxResults <= 0
                ? DEFAULT_MAX_RESULTS
                : Math.min(maxResults, MAX_RESULTS_CAP);
        int offset = parseOffset(nextToken);
        if (offset < 0 || offset > matches.size()) {
            offset = 0;
        }
        int end = Math.min(offset + limit, matches.size());
        if (offset >= matches.size()) {
            return List.of();
        }
        return new ArrayList<>(matches.subList(offset, end));
    }

    public String nextToken(String region, List<String> arns, String nextToken, Integer maxResults) {
        List<LicenseConfiguration> page = list(region, arns, nextToken, maxResults);
        int limit = maxResults == null || maxResults <= 0
                ? DEFAULT_MAX_RESULTS
                : Math.min(maxResults, MAX_RESULTS_CAP);
        if (page.size() < limit) {
            return null;
        }
        int next = parseOffset(nextToken) + page.size();
        List<LicenseConfiguration> peek = list(region, arns, String.valueOf(next), 1);
        return peek.isEmpty() ? null : String.valueOf(next);
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        LicenseConfiguration config = requireLive(arn);
        if (tags != null) {
            config.getTags().putAll(tags);
        }
        store.put(config.getLicenseConfigurationArn(), config);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        LicenseConfiguration config = requireLive(arn);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                config.getTags().remove(key);
            }
        }
        store.put(config.getLicenseConfigurationArn(), config);
    }

    public Map<String, String> listTags(String arn) {
        return new LinkedHashMap<>(require(arn).getTags());
    }

    public synchronized SellerLicense createLicense(String region, JsonNode request) {
        String clientToken = requireText(request, "ClientToken");
        for (SellerLicense existing : licenses.values()) {
            if (clientToken.equals(existing.getClientToken())) {
                return existing;
            }
        }
        String licenseName = requireText(request, "LicenseName");
        String productName = requireText(request, "ProductName");
        String productSku = requireText(request, "ProductSKU");
        String beneficiary = requireText(request, "Beneficiary");
        String homeRegion = textOr(request, "HomeRegion", region);
        JsonNode issuer = request.path("Issuer");
        if (!issuer.isObject() || !issuer.hasNonNull("Name")) {
            throw invalid("Issuer.Name is a required parameter.");
        }
        JsonNode validity = request.path("Validity");
        if (!validity.isObject() || !validity.hasNonNull("Begin")) {
            throw invalid("Validity.Begin is a required parameter.");
        }
        if (!request.has("Entitlements") || !request.get("Entitlements").isArray()
                || request.get("Entitlements").isEmpty()) {
            throw invalid("Entitlements is a required parameter.");
        }

        String id = "l-" + HexFormat.of().formatHex(randomBytes(16));
        String arn = "arn:aws:license-manager::" + accountId() + ":license:" + id;
        String issuerName = issuer.get("Name").asText();

        SellerLicense license = new SellerLicense();
        license.setLicenseArn(arn);
        license.setLicenseName(licenseName);
        license.setProductName(productName);
        license.setProductSku(productSku);
        license.setHomeRegion(homeRegion == null || homeRegion.isBlank() ? "us-east-1" : homeRegion);
        license.setStatus(STATUS_AVAILABLE);
        license.setVersion("1");
        license.setBeneficiary(beneficiary);
        license.setCreateTime(Instant.now().toString());
        license.setIssuerName(issuerName);
        license.setIssuerSignKey(textOrNull(issuer, "SignKey"));
        license.setKeyFingerprint(fingerprint(issuerName));
        license.setValidityBegin(validity.get("Begin").asText());
        license.setValidityEnd(textOrNull(validity, "End"));
        license.setClientToken(clientToken);
        license.setEntitlements(objectList(request.get("Entitlements")));
        if (request.has("ConsumptionConfiguration") && request.get("ConsumptionConfiguration").isObject()) {
            license.setConsumptionConfiguration(objectMapper.convertValue(
                    request.get("ConsumptionConfiguration"), MAP_TYPE));
        }
        if (request.has("LicenseMetadata") && request.get("LicenseMetadata").isArray()) {
            license.setLicenseMetadata(objectList(request.get("LicenseMetadata")));
        }
        licenses.put(arn, license);
        return license;
    }

    public synchronized SellerLicense createLicenseVersion(JsonNode request) {
        SellerLicense license = requireLicense(textOrNull(request, "LicenseArn"));
        if (STATUS_DELETED.equals(license.getStatus())) {
            throw invalid("License is deleted.");
        }
        String sourceVersion = textOrNull(request, "SourceVersion");
        if (sourceVersion != null && !sourceVersion.equals(license.getVersion())) {
            throw invalid("SourceVersion does not match the current license version.");
        }
        int next = parseVersion(license.getVersion()) + 1;
        license.setVersion(String.valueOf(next));
        if (request.hasNonNull("LicenseName")) {
            license.setLicenseName(request.get("LicenseName").asText());
        }
        if (request.hasNonNull("ProductName")) {
            license.setProductName(request.get("ProductName").asText());
        }
        JsonNode issuer = request.path("Issuer");
        if (issuer.isObject() && issuer.hasNonNull("Name")) {
            license.setIssuerName(issuer.get("Name").asText());
            license.setIssuerSignKey(textOrNull(issuer, "SignKey"));
            license.setKeyFingerprint(fingerprint(license.getIssuerName()));
        }
        if (request.hasNonNull("HomeRegion")) {
            license.setHomeRegion(request.get("HomeRegion").asText());
        }
        JsonNode validity = request.path("Validity");
        if (validity.isObject()) {
            if (validity.hasNonNull("Begin")) {
                license.setValidityBegin(validity.get("Begin").asText());
            }
            if (validity.hasNonNull("End")) {
                license.setValidityEnd(validity.get("End").asText());
            }
        }
        if (request.has("Entitlements") && request.get("Entitlements").isArray()) {
            license.setEntitlements(objectList(request.get("Entitlements")));
        }
        if (request.has("ConsumptionConfiguration") && request.get("ConsumptionConfiguration").isObject()) {
            license.setConsumptionConfiguration(objectMapper.convertValue(
                    request.get("ConsumptionConfiguration"), MAP_TYPE));
        }
        if (request.has("LicenseMetadata") && request.get("LicenseMetadata").isArray()) {
            license.setLicenseMetadata(objectList(request.get("LicenseMetadata")));
        }
        if (request.hasNonNull("Status")) {
            license.setStatus(request.get("Status").asText());
        }
        licenses.put(license.getLicenseArn(), license);
        return license;
    }

    public SellerLicense getLicense(String arn) {
        return requireLicense(arn);
    }

    public List<SellerLicense> listLicenses(List<String> arns) {
        List<SellerLicense> matches = new ArrayList<>();
        for (SellerLicense license : licenses.values()) {
            if (arns != null && !arns.isEmpty() && !arns.contains(license.getLicenseArn())) {
                continue;
            }
            matches.add(license);
        }
        matches.sort(Comparator.comparing(SellerLicense::getLicenseArn, Comparator.nullsLast(String::compareTo)));
        return matches;
    }

    public synchronized Map<String, String> deleteLicense(String arn, String sourceVersion) {
        SellerLicense license = requireLicense(arn);
        if (sourceVersion != null && !sourceVersion.equals(license.getVersion())) {
            throw invalid("SourceVersion does not match the current license version.");
        }
        for (LicenseConsumption consumption : consumptions.values()) {
            if (arn.equals(consumption.getLicenseArn())) {
                throw new AwsException(
                        "ConflictException",
                        "License has outstanding consumption and cannot be deleted.",
                        409);
            }
        }
        license.setStatus(STATUS_DELETED);
        licenses.put(arn, license);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("Status", STATUS_DELETED);
        result.put("DeletionDate", Instant.now().toString());
        return result;
    }

    public synchronized SellerToken createToken(JsonNode request) {
        SellerLicense license = requireLicense(textOrNull(request, "LicenseArn"));
        if (!STATUS_AVAILABLE.equals(license.getStatus())) {
            throw invalid("License is not available.");
        }
        String tokenId = UUID.randomUUID().toString();
        String token = "lmrt-" + UUID.randomUUID();
        SellerToken created = new SellerToken();
        created.setTokenId(tokenId);
        created.setToken(token);
        created.setTokenType("REFRESH_TOKEN");
        created.setLicenseArn(license.getLicenseArn());
        created.setExpirationTime(Instant.now().plus(365, ChronoUnit.DAYS).toString());
        created.setStatus("AVAILABLE");
        created.setTokenProperties(stringList(request.path("TokenProperties")));
        created.setRoleArns(stringList(request.path("RoleArns")));
        tokens.put(tokenId, created);
        return created;
    }

    public List<SellerToken> listTokens(List<String> tokenIds) {
        List<SellerToken> matches = new ArrayList<>();
        for (SellerToken token : tokens.values()) {
            if (tokenIds != null && !tokenIds.isEmpty() && !tokenIds.contains(token.getTokenId())) {
                continue;
            }
            matches.add(token);
        }
        matches.sort(Comparator.comparing(SellerToken::getTokenId, Comparator.nullsLast(String::compareTo)));
        return matches;
    }

    public String getAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw invalid("The specified token is not valid.");
        }
        for (SellerToken stored : tokens.values()) {
            if (token.equals(stored.getToken())) {
                return "lmat-" + stored.getTokenId();
            }
        }
        throw invalid("The specified token is not valid.");
    }

    public synchronized void deleteToken(String tokenId) {
        if (tokenId == null || tokenId.isBlank() || tokens.get(tokenId).isEmpty()) {
            throw invalid("The specified token does not exist.");
        }
        tokens.delete(tokenId);
    }

    public synchronized LicenseConsumption checkoutLicense(JsonNode request) {
        String productSku = requireText(request, "ProductSKU");
        String fingerprint = requireText(request, "KeyFingerprint");
        String checkoutType = textOr(request, "CheckoutType", "PROVISIONAL");
        SellerLicense match = null;
        for (SellerLicense license : licenses.values()) {
            if (!STATUS_AVAILABLE.equals(license.getStatus())) {
                continue;
            }
            if (productSku.equals(license.getProductSku()) && fingerprint.equals(license.getKeyFingerprint())) {
                match = license;
                break;
            }
        }
        if (match == null) {
            throw new AwsException("ResourceNotFoundException", "Requested license was not found.", 400);
        }
        return checkout(match, checkoutType, request);
    }

    public synchronized LicenseConsumption checkoutBorrowLicense(JsonNode request) {
        SellerLicense license = requireLicense(textOrNull(request, "LicenseArn"));
        if (!STATUS_AVAILABLE.equals(license.getStatus())) {
            throw new AwsException("ResourceNotFoundException", "Requested license was not found.", 400);
        }
        return checkout(license, "PERPETUAL", request);
    }

    public synchronized LicenseConsumption extendConsumption(String consumptionToken) {
        LicenseConsumption consumption = consumptions.get(consumptionToken)
                .orElseThrow(() -> new AwsException(
                        "ResourceNotFoundException",
                        "License consumption token was not found.",
                        400));
        int ttl = ttlMinutes(licenses.get(consumption.getLicenseArn()).orElse(null));
        consumption.setExpiration(Instant.now().plus(ttl, ChronoUnit.MINUTES).toString());
        consumptions.put(consumption.getLicenseConsumptionToken(), consumption);
        return consumption;
    }

    public synchronized void checkInLicense(String consumptionToken) {
        if (consumptionToken == null || consumptionToken.isBlank()
                || consumptions.get(consumptionToken).isEmpty()) {
            throw new AwsException(
                    "ResourceNotFoundException",
                    "License consumption token was not found.",
                    400);
        }
        consumptions.delete(consumptionToken);
    }

    public synchronized SellerGrant createGrant(JsonNode request) {
        SellerLicense license = requireLicense(textOrNull(request, "LicenseArn"));
        if (!STATUS_AVAILABLE.equals(license.getStatus())) {
            throw invalid("License is not available.");
        }
        List<String> principals = stringList(request.path("Principals"));
        if (principals.isEmpty()) {
            throw invalid("Principals is a required parameter.");
        }
        String grantName = requireText(request, "GrantName");
        String id = "g-" + HexFormat.of().formatHex(randomBytes(16));
        String arn = "arn:aws:license-manager::" + accountId() + ":grant:" + id;
        SellerGrant grant = new SellerGrant();
        grant.setGrantArn(arn);
        grant.setGrantName(grantName);
        grant.setLicenseArn(license.getLicenseArn());
        grant.setParentArn(license.getLicenseArn());
        grant.setGranteePrincipalArn(principals.getFirst());
        grant.setHomeRegion(textOr(request, "HomeRegion", license.getHomeRegion()));
        grant.setGrantStatus(GRANT_ACTIVE);
        grant.setVersion("1");
        grant.setClientToken(textOrNull(request, "ClientToken"));
        grant.setGrantedOperations(stringList(request.path("AllowedOperations")));
        grants.put(arn, grant);
        return grant;
    }

    public synchronized SellerGrant createGrantVersion(JsonNode request) {
        String arn = textOrNull(request, "GrantArn");
        if (arn == null || grants.get(arn).isEmpty()) {
            throw invalid("Invalid grant ARN.");
        }
        SellerGrant grant = grants.get(arn).get();
        String sourceVersion = textOrNull(request, "SourceVersion");
        if (sourceVersion != null && !sourceVersion.equals(grant.getVersion())) {
            throw invalid("SourceVersion does not match the current grant version.");
        }
        grant.setVersion(String.valueOf(parseVersion(grant.getVersion()) + 1));
        if (request.hasNonNull("GrantName")) {
            grant.setGrantName(request.get("GrantName").asText());
        }
        if (request.has("AllowedOperations") && request.get("AllowedOperations").isArray()) {
            grant.setGrantedOperations(stringList(request.path("AllowedOperations")));
        }
        if (request.hasNonNull("Status")) {
            grant.setGrantStatus(request.get("Status").asText());
        }
        if (request.hasNonNull("StatusReason")) {
            grant.setStatusReason(request.get("StatusReason").asText());
        }
        grants.put(grant.getGrantArn(), grant);
        return grant;
    }

    public SellerGrant getGrant(String arn) {
        if (arn == null || arn.isBlank() || grants.get(arn).isEmpty()) {
            throw invalid("Invalid grant ARN.");
        }
        return grants.get(arn).get();
    }

    public synchronized SellerGrant deleteGrant(String arn, String version) {
        SellerGrant grant = requireGrant(arn);
        if (version != null && !version.equals(grant.getVersion())) {
            throw invalid("Version does not match the current grant version.");
        }
        grant.setGrantStatus(GRANT_DELETED);
        grant.setVersion(String.valueOf(parseVersion(grant.getVersion()) + 1));
        grants.put(arn, grant);
        return grant;
    }

    public synchronized SellerGrant acceptGrant(String arn) {
        SellerGrant grant = requireGrant(arn);
        grant.setGrantStatus(GRANT_ACTIVE);
        grants.put(arn, grant);
        return grant;
    }

    public synchronized SellerGrant rejectGrant(String arn) {
        SellerGrant grant = requireGrant(arn);
        grant.setGrantStatus("REJECTED");
        grants.put(arn, grant);
        return grant;
    }

    public List<SellerGrant> listGrants(List<String> arns) {
        List<SellerGrant> matches = new ArrayList<>();
        for (SellerGrant grant : grants.values()) {
            if (arns != null && !arns.isEmpty() && !arns.contains(grant.getGrantArn())) {
                continue;
            }
            matches.add(grant);
        }
        matches.sort(Comparator.comparing(SellerGrant::getGrantArn, Comparator.nullsLast(String::compareTo)));
        return matches;
    }

    @Override
    public void clear() {
        store.clear();
        licenses.clear();
        grants.clear();
        tokens.clear();
        consumptions.clear();
    }

    private LicenseConsumption checkout(SellerLicense license, String checkoutType, JsonNode request) {
        String token = UUID.randomUUID().toString();
        int ttl = ttlMinutes(license);
        Instant now = Instant.now();
        LicenseConsumption consumption = new LicenseConsumption();
        consumption.setLicenseConsumptionToken(token);
        consumption.setLicenseArn(license.getLicenseArn());
        consumption.setCheckoutType(checkoutType);
        consumption.setIssuedAt(now.toString());
        consumption.setExpiration(now.plus(ttl, ChronoUnit.MINUTES).toString());
        consumption.setNodeId(textOrNull(request, "NodeId"));
        consumption.setBeneficiary(textOrNull(request, "Beneficiary"));
        if (request.has("Entitlements") && request.get("Entitlements").isArray()) {
            consumption.setEntitlements(objectList(request.get("Entitlements")));
        } else {
            consumption.setEntitlements(new ArrayList<>(license.getEntitlements()));
        }
        consumptions.put(token, consumption);
        return consumption;
    }

    private int ttlMinutes(SellerLicense license) {
        if (license == null) {
            return 60;
        }
        Object provisional = license.getConsumptionConfiguration().get("ProvisionalConfiguration");
        if (provisional instanceof Map<?, ?> map) {
            Object ttl = map.get("MaxTimeToLiveInMinutes");
            if (ttl instanceof Number number) {
                return Math.max(1, number.intValue());
            }
        }
        return 60;
    }

    private LicenseConfiguration require(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalidArn();
        }
        return store.get(arn).orElseThrow(LicenseManagerService::invalidArn);
    }

    private LicenseConfiguration requireLive(String arn) {
        LicenseConfiguration config = require(arn);
        if (STATUS_DELETED.equals(config.getStatus())) {
            throw invalidArn();
        }
        return config;
    }

    private SellerLicense requireLicense(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ResourceNotFoundException", "Requested license was not found.", 400);
        }
        return licenses.get(arn).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "Requested license was not found.", 400));
    }

    private SellerGrant requireGrant(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalid("Invalid grant ARN.");
        }
        Optional<SellerGrant> grant = grants.get(arn);
        if (grant.isEmpty()) {
            throw invalid("Invalid grant ARN.");
        }
        return grant.get();
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private List<Map<String, Object>> objectList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        return objectMapper.convertValue(node, MAP_LIST_TYPE);
    }

    private static String fingerprint(String issuerName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((issuerName == null ? "" : issuerName).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int parseOffset(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(nextToken);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String requireText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null) {
            throw invalid(field + " is a required parameter.");
        }
        return value;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value != null ? value : fallback;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    static AwsException invalidArn() {
        return new AwsException(
                "InvalidParameterValueException",
                "Invalid license configuration ARN.",
                400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }
}
