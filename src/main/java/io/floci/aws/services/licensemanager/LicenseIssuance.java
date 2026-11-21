package io.floci.aws.services.licensemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Seller-issued licenses and what buyers do with them. A license is versioned: each version keeps its entitlements,
 * validity and consumption configuration. Licenses are signed with the issuer account's key (or named by the issuer's
 * KMS key), whose fingerprint checkouts name. Checkouts (provisional or perpetual) and borrows consume entitlements up
 * to their maximum unless overage is allowed and return a PS384-signed JWT; extend and check-in follow the
 * consumption configuration. Grants distribute a license to another account, which accepts or rejects them; tokens
 * are signed refresh tokens that {@code GetAccessToken} exchanges for an hour-long access token.
 */
@ApplicationScoped
public class LicenseIssuance {

    private static final Set<String> OPERATIONS = Set.of("CreateGrant", "CheckoutLicense", "CheckoutBorrowLicense",
            "CheckInLicense", "ExtendConsumptionLicense", "ListPurchasedLicenses", "CreateToken");

    private final LicenseManagerStore store;
    private final LicenseConfigurations configurations;
    private final ObjectMapper mapper;

    @Inject
    public LicenseIssuance(LicenseManagerStore store, LicenseConfigurations configurations) {
        this.store = store;
        this.configurations = configurations;
        this.mapper = store.mapper();
    }

    // ================================================================ signing

    /** The account's signing key, created on first use. */
    private KeyPair keys(String account) {
        ObjectNode record = store.getForAccount(account, LicenseManagerStore.GLOBAL, "signer", "key").orElse(null);
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (record != null) {
                PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder()
                        .decode(record.path("private").asText())));
                PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                        .decode(record.path("public").asText())));
                return new KeyPair(publicKey, privateKey);
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            KeyPair pair = generator.generateKeyPair();
            ObjectNode created = store.record();
            created.put("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            created.put("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            store.putForAccount(account, LicenseManagerStore.GLOBAL, "signer", "key", created);
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Signature pss() throws GeneralSecurityException {
        Signature signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(new PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, 1));
        return signature;
    }

    /** A compact JWS of {@code claims}, PS384-signed with the account's key. */
    String sign(String account, String keyId, ObjectNode claims) {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "PS384");
        header.put("typ", "JWT");
        header.put("kid", keyId);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = pss();
            signature.initSign(keys(account).getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + encoder.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The claims of a JWS this account (or another floci account named in its claims) signed, or empty. */
    private Optional<ObjectNode> verify(String jws) {
        String[] parts = jws.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            ObjectNode claims = (ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String account = claims.path("acct").asText(null);
            if (account == null) {
                return Optional.empty();
            }
            Signature signature = pss();
            signature.initVerify(keys(account).getPublic());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2])) ? Optional.of(claims) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ================================================================ licenses

    private static Instant time(String value, String member) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw LicenseManagerStore.validation(member + " must be an ISO 8601 date and time.");
        }
    }

    private static String iso(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private void checkLicense(JsonNode in) {
        Instant begin = time(in.path("Validity").path("Begin").asText(null), "Validity.Begin");
        if (in.path("Validity").hasNonNull("End")) {
            Instant end = time(in.path("Validity").path("End").asText(), "Validity.End");
            if (!end.isAfter(begin)) {
                throw LicenseManagerStore.validation("Validity.End must be after Validity.Begin.");
            }
        }
        if (in.path("Entitlements").isEmpty()) {
            throw LicenseManagerStore.validation("A license needs at least one entitlement.");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode entitlement : in.path("Entitlements")) {
            if (!names.add(entitlement.path("Name").asText())) {
                throw LicenseManagerStore.validation("The entitlement " + entitlement.path("Name").asText()
                        + " is duplicated.");
            }
            if (!entitlement.hasNonNull("MaxCount") && !entitlement.hasNonNull("Value")
                    && !"None".equals(entitlement.path("Unit").asText())) {
                throw LicenseManagerStore.validation("The entitlement " + entitlement.path("Name").asText()
                        + " needs a MaxCount or a Value.");
            }
        }
        JsonNode consumption = in.path("ConsumptionConfiguration");
        if (!consumption.has("ProvisionalConfiguration") && !consumption.has("BorrowConfiguration")) {
            throw LicenseManagerStore.validation("The consumption configuration needs a provisional or borrow "
                    + "configuration.");
        }
        String home = in.path("HomeRegion").asText();
        if (!AwsRegions.KNOWN_IDS.contains(home)) {
            throw LicenseManagerStore.validation("The home Region " + home + " is not a Region.");
        }
    }

    private String fingerprint(String account, JsonNode issuer) {
        String name = issuer.path("Name").asText();
        if (issuer.hasNonNull("SignKey")) {
            String key = issuer.get("SignKey").asText();
            return "aws:" + account + ":" + name + ":" + key.substring(key.lastIndexOf('/') + 1);
        }
        return "aws:" + account + ":" + name + ":issuer-fingerprint";
    }

    public ObjectNode createLicense(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateLicense", in);
        if (repeated != null) {
            return repeated;
        }
        checkLicense(in);
        String beneficiary = in.path("Beneficiary").asText();
        if (beneficiary.isBlank()) {
            throw LicenseManagerStore.validation("A license needs a beneficiary.");
        }
        String id = "l-" + LicenseManagerStore.hex(16);
        ObjectNode license = store.record();
        license.put("LicenseArn", store.globalArn(region, "license:" + id));
        writeVersion(license, in, "1");
        license.put("ProductSKU", in.path("ProductSKU").asText());
        license.put("Beneficiary", beneficiary);
        license.put("CreateTime", String.valueOf(Instant.now().getEpochSecond()));
        license.put("Status", "PENDING_AVAILABLE");
        LicenseManagerStore.meta(license).put("availableAt", System.currentTimeMillis()
                + store.config().transitionMillis());
        if (in.path("Tags").isArray()) {
            LicenseManagerStore.addTags(license, in.get("Tags"));
        }
        LicenseManagerStore.meta(license).putArray("versions").add(snapshot(license));
        store.put(LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE, id, license);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseArn", license.path("LicenseArn").asText());
        out.put("Status", "PENDING_AVAILABLE");
        out.put("Version", "1");
        store.remember("CreateLicense", in, out);
        return out;
    }

    private void writeVersion(ObjectNode license, JsonNode in, String version) {
        license.put("LicenseName", in.path("LicenseName").asText());
        license.put("ProductName", in.path("ProductName").asText());
        ObjectNode issuer = license.putObject("Issuer");
        issuer.put("Name", in.path("Issuer").path("Name").asText());
        if (in.path("Issuer").hasNonNull("SignKey")) {
            issuer.put("SignKey", in.path("Issuer").get("SignKey").asText());
        }
        issuer.put("KeyFingerprint", fingerprint(store.account(), in.path("Issuer")));
        license.put("HomeRegion", in.path("HomeRegion").asText());
        license.set("Validity", in.path("Validity").deepCopy());
        license.set("Entitlements", in.path("Entitlements").deepCopy());
        license.set("ConsumptionConfiguration", in.path("ConsumptionConfiguration").deepCopy());
        license.set("LicenseMetadata", in.path("LicenseMetadata").isArray() ? in.get("LicenseMetadata").deepCopy()
                : mapper.createArrayNode());
        license.put("Version", version);
    }

    private static ObjectNode snapshot(ObjectNode license) {
        return LicenseManagerStore.view(license);
    }

    /** A license, with a due transition to AVAILABLE applied. */
    private Optional<Located> license(String arn) {
        Optional<AwsArnUtils.Arn> parsed = LicenseManagerStore.parse(arn);
        String resource = parsed.map(AwsArnUtils.Arn::resource).orElse("");
        if (!resource.startsWith("license:")) {
            return Optional.empty();
        }
        String id = resource.substring("license:".length());
        String account = parsed.get().accountId();
        return store.getForAccount(account, LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE, id)
                .map(license -> {
                    settle(account, id, license);
                    return new Located(account, id, license);
                });
    }

    private record Located(String account, String id, ObjectNode record) {
    }

    private void settle(String account, String id, ObjectNode license) {
        ObjectNode meta = LicenseManagerStore.meta(license);
        long now = System.currentTimeMillis();
        boolean changed = false;
        if ("PENDING_AVAILABLE".equals(license.path("Status").asText()) && meta.path("availableAt").asLong() <= now) {
            license.put("Status", "AVAILABLE");
            changed = true;
        }
        if ("PENDING_DELETE".equals(license.path("Status").asText()) && meta.path("deletedAt").asLong() <= now) {
            license.put("Status", "DELETED");
            changed = true;
        }
        if ("AVAILABLE".equals(license.path("Status").asText()) && license.path("Validity").hasNonNull("End")) {
            try {
                if (Instant.parse(license.path("Validity").path("End").asText()).isBefore(Instant.now())) {
                    license.put("Status", "EXPIRED");
                    changed = true;
                }
            } catch (DateTimeParseException e) {
                // validated on create
            }
        }
        if (changed) {
            store.putForAccount(account, LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE, id, license);
        }
    }

    private Located ownedLicense(String arn) {
        Located located = license(arn).orElseThrow(() -> LicenseManagerStore.invalid("The license " + arn
                + " does not exist."));
        if (!store.account().equals(located.account())) {
            throw LicenseManagerStore.authorization("The license " + arn + " was issued by another account.");
        }
        return located;
    }

    public ObjectNode getLicense(JsonNode in, String region) {
        String arn = in.path("LicenseArn").asText();
        Located located = license(arn).orElseThrow(() -> LicenseManagerStore.notFound("The license " + arn
                + " does not exist."));
        if (!readable(located)) {
            throw LicenseManagerStore.authorization("The license " + arn + " is not yours to read.");
        }
        ObjectNode license = LicenseManagerStore.view(located.record());
        if (in.hasNonNull("Version") && !in.get("Version").asText().equals(license.path("Version").asText())) {
            ObjectNode found = null;
            for (JsonNode version : located.record().path("_").path("versions")) {
                if (in.get("Version").asText().equals(version.path("Version").asText())) {
                    found = (ObjectNode) version.deepCopy();
                }
            }
            if (found == null) {
                throw LicenseManagerStore.notFound("The license " + arn + " has no version "
                        + in.get("Version").asText() + ".");
            }
            license = found;
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("License", license);
        return out;
    }

    /** Whether the caller issued, benefits from, or holds an active grant of a license. */
    private boolean readable(Located located) {
        String account = store.account();
        if (account.equals(located.account()) || beneficiary(located.record(), account)) {
            return true;
        }
        return activeGrant(located.record().path("LicenseArn").asText(), account).isPresent();
    }

    private static boolean beneficiary(JsonNode license, String account) {
        String beneficiary = license.path("Beneficiary").asText();
        return beneficiary.equals(account) || beneficiary.endsWith(":" + account + ":root")
                || beneficiary.contains(":" + account + ":");
    }

    public ObjectNode createLicenseVersion(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateLicenseVersion", in);
        if (repeated != null) {
            return repeated;
        }
        Located located = ownedLicense(in.path("LicenseArn").asText());
        ObjectNode license = located.record();
        if (Set.of("DELETED", "PENDING_DELETE").contains(license.path("Status").asText())) {
            throw LicenseManagerStore.conflict("The license is " + license.path("Status").asText() + ".");
        }
        String current = license.path("Version").asText();
        if (in.hasNonNull("SourceVersion") && !current.equals(in.get("SourceVersion").asText())) {
            throw LicenseManagerStore.conflict("The license's version is " + current + ", not "
                    + in.get("SourceVersion").asText() + ".");
        }
        checkLicense(in);
        String status = in.path("Status").asText();
        if (Set.of("DELETED", "PENDING_DELETE", "PENDING_AVAILABLE").contains(status)) {
            throw LicenseManagerStore.validation("A new version can't be " + status + ".");
        }
        String version = String.valueOf(Integer.parseInt(current) + 1);
        writeVersion(license, in, version);
        license.put("Status", status);
        LicenseManagerStore.meta(license).remove("availableAt");
        LicenseManagerStore.meta(license).withArray("versions").add(snapshot(license));
        store.putForAccount(located.account(), LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE,
                located.id(), license);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseArn", license.path("LicenseArn").asText());
        out.put("Version", version);
        out.put("Status", status);
        store.remember("CreateLicenseVersion", in, out);
        return out;
    }

    public ObjectNode deleteLicense(JsonNode in, String region) {
        Located located = ownedLicense(in.path("LicenseArn").asText());
        ObjectNode license = located.record();
        String current = license.path("Version").asText();
        if (!current.equals(in.path("SourceVersion").asText())) {
            throw LicenseManagerStore.conflict("The license's version is " + current + ", not "
                    + in.path("SourceVersion").asText() + ".");
        }
        if (Set.of("DELETED", "PENDING_DELETE").contains(license.path("Status").asText())) {
            throw LicenseManagerStore.invalid("The license is already " + license.path("Status").asText() + ".");
        }
        for (ObjectNode consumption : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.CONSUMPTION)) {
            if (license.path("LicenseArn").asText().equals(consumption.path("LicenseArn").asText())
                    && active(consumption)) {
                throw LicenseManagerStore.conflict("The license has active consumptions; check them in first.");
            }
        }
        boolean granted = false;
        for (ObjectNode grant : store.list(LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT)) {
            if (license.path("LicenseArn").asText().equals(grant.path("LicenseArn").asText())
                    && Set.of("ACTIVE", "PENDING_ACCEPT").contains(grant.path("GrantStatus").asText())) {
                granted = true;
                grant.put("GrantStatus", "DISABLED");
                grant.put("StatusReason", "The license was deleted.");
                store.put(LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT, grant.path("_").path("id").asText(),
                        grant);
            }
        }
        Instant deletion = Instant.now().plus(granted ? 30 : 0, ChronoUnit.DAYS);
        license.put("Status", granted ? "PENDING_DELETE" : "DELETED");
        if (granted) {
            LicenseManagerStore.meta(license).put("deletedAt", deletion.toEpochMilli());
        }
        store.putForAccount(located.account(), LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE,
                located.id(), license);
        ObjectNode out = mapper.createObjectNode();
        out.put("Status", license.path("Status").asText());
        out.put("DeletionDate", iso(deletion));
        return out;
    }

    public ObjectNode listLicenses(JsonNode in, String region) {
        Map<String, Function<JsonNode, String>> fields = Map.of(
                "Beneficiary", l -> l.path("Beneficiary").asText(),
                "ProductSKU", l -> l.path("ProductSKU").asText(),
                "Fingerprint", l -> l.path("Issuer").path("KeyFingerprint").asText(),
                "Status", l -> l.path("Status").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE)) {
            settle(store.account(), record.path("_").path("id").asText(), record);
            ObjectNode license = LicenseManagerStore.view(record);
            if (in.path("LicenseArns").size() > 0 && !LicenseManagerStore.any(in.path("LicenseArns"),
                    arn -> arn.equals(license.path("LicenseArn").asText()))) {
                continue;
            }
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, license)) {
                items.add(license);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "Licenses");
        return out;
    }

    public ObjectNode listLicenseVersions(JsonNode in, String region) {
        String arn = in.path("LicenseArn").asText();
        Located located = license(arn).orElseThrow(() -> LicenseManagerStore.notFound("The license " + arn
                + " does not exist."));
        if (!readable(located)) {
            throw LicenseManagerStore.authorization("The license " + arn + " is not yours to read.");
        }
        List<ObjectNode> items = new ArrayList<>();
        located.record().path("_").path("versions").forEach(v -> items.add((ObjectNode) v.deepCopy()));
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "Licenses");
        return out;
    }

    public ObjectNode listReceivedLicenses(JsonNode in, String region) {
        return receivedLicenses(in, store.account(), false);
    }

    public ObjectNode listReceivedLicensesForOrganization(JsonNode in, String region) {
        if (!configurations.settings(region).path("OrganizationConfiguration").path("EnableIntegration")
                .asBoolean(false)) {
            throw new AwsException("AccessDeniedException", "Turn on the AWS Organizations integration in the "
                    + "License Manager settings first.", 400);
        }
        return receivedLicenses(in, store.account(), true);
    }

    private ObjectNode receivedLicenses(JsonNode in, String account, boolean organization) {
        Map<String, Function<JsonNode, String>> fields = Map.of(
                "ProductSKU", l -> l.path("ProductSKU").asText(),
                "Status", l -> l.path("Status").asText(),
                "Fingerprint", l -> l.path("Issuer").path("KeyFingerprint").asText(),
                "IssuerName", l -> l.path("Issuer").path("Name").asText(),
                "Beneficiary", l -> l.path("Beneficiary").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE)) {
            String arn = record.path("LicenseArn").asText();
            ObjectNode received = LicenseManagerStore.view(record);
            ObjectNode metadata = mapper.createObjectNode();
            Optional<ObjectNode> grant = activeGrant(arn, account);
            boolean byOrganization = grant.map(g -> g.path("GranteePrincipalArn").asText().contains(
                    ":organization/")).orElse(false);
            if (organization ? !byOrganization : !(beneficiary(record, account) || grant.isPresent())) {
                continue;
            }
            metadata.put("ReceivedStatus", grant.map(g -> g.path("GrantStatus").asText()).orElse("ACTIVE"));
            ArrayNode operations = metadata.putArray("AllowedOperations");
            if (grant.isPresent()) {
                grant.get().path("GrantedOperations").forEach(operations::add);
            } else {
                OPERATIONS.stream().sorted().forEach(operations::add);
            }
            received.set("ReceivedMetadata", metadata);
            if (in.path("LicenseArns").size() > 0 && !LicenseManagerStore.any(in.path("LicenseArns"), arn::equals)) {
                continue;
            }
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, received)) {
                items.add(received);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "Licenses");
        return out;
    }

    public ObjectNode getLicenseUsage(JsonNode in, String region) {
        String arn = in.path("LicenseArn").asText();
        Located located = license(arn).orElseThrow(() -> LicenseManagerStore.notFound("The license " + arn
                + " does not exist."));
        if (!readable(located)) {
            throw LicenseManagerStore.authorization("The license " + arn + " is not yours to read.");
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode usages = out.putObject("LicenseUsage").putArray("EntitlementUsages");
        for (JsonNode entitlement : located.record().path("Entitlements")) {
            String name = entitlement.path("Name").asText();
            ObjectNode usage = usages.addObject();
            usage.put("Name", name);
            usage.put("ConsumedValue", String.valueOf(consumed(arn, name)));
            if (entitlement.hasNonNull("MaxCount")) {
                usage.put("MaxCount", entitlement.get("MaxCount").asText());
            }
            usage.put("Unit", entitlement.path("Unit").asText());
        }
        return out;
    }

    private long consumed(String licenseArn, String entitlement) {
        long total = 0;
        for (ObjectNode consumption : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.CONSUMPTION)) {
            if (!licenseArn.equals(consumption.path("LicenseArn").asText()) || !active(consumption)) {
                continue;
            }
            for (JsonNode used : consumption.path("EntitlementsAllowed")) {
                if (entitlement.equals(used.path("Name").asText())) {
                    total += value(used);
                }
            }
        }
        return total;
    }

    private static long value(JsonNode entitlement) {
        try {
            return Long.parseLong(entitlement.path("Value").asText("1"));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static boolean active(JsonNode consumption) {
        if (consumption.path("_").path("checkedIn").asBoolean(false)) {
            return false;
        }
        String expiration = consumption.path("Expiration").asText(null);
        return expiration == null || Instant.parse(expiration).isAfter(Instant.now());
    }

    // ================================================================ consumption

    public ObjectNode checkoutLicense(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CheckoutLicense", in);
        if (repeated != null) {
            return repeated;
        }
        String beneficiary = in.hasNonNull("Beneficiary") ? in.get("Beneficiary").asText() : store.account();
        String sku = in.path("ProductSKU").asText();
        String fingerprint = in.path("KeyFingerprint").asText();
        Located chosen = null;
        for (ObjectNode record : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.LICENSE)) {
            String account = record.path("_").path("account").asText();
            String id = record.path("_").path("id").asText();
            settle(account, id, record);
            if (!sku.equals(record.path("ProductSKU").asText())
                    || !fingerprint.equals(record.path("Issuer").path("KeyFingerprint").asText())
                    || !"AVAILABLE".equals(record.path("Status").asText()) || !valid(record)) {
                continue;
            }
            boolean entitled = beneficiary(record, beneficiary) || activeGrant(record.path("LicenseArn").asText(),
                    store.account()).filter(g -> LicenseManagerStore.any(g.path("GrantedOperations"),
                    "CheckoutLicense"::equals)).isPresent();
            if (entitled) {
                chosen = new Located(account, id, record);
                break;
            }
        }
        if (chosen == null) {
            throw LicenseManagerStore.notFound("No available license for product SKU " + sku + " and key fingerprint "
                    + fingerprint + " is granted to " + beneficiary + ".");
        }
        String type = in.path("CheckoutType").asText();
        JsonNode configuration = chosen.record().path("ConsumptionConfiguration");
        Instant expiration = null;
        if ("PROVISIONAL".equals(type)) {
            if (!configuration.has("ProvisionalConfiguration")) {
                throw LicenseManagerStore.validation("The license doesn't allow provisional checkouts.");
            }
            expiration = Instant.now().plus(configuration.path("ProvisionalConfiguration")
                    .path("MaxTimeToLiveInMinutes").asLong(60), ChronoUnit.MINUTES);
        }
        ArrayNode allowed = allow(chosen.record(), in.path("Entitlements"));
        ObjectNode consumption = consume(chosen.record(), type, allowed, beneficiary, in.path("NodeId").asText(null),
                expiration, null);
        ObjectNode out = mapper.createObjectNode();
        out.put("CheckoutType", type);
        out.put("LicenseConsumptionToken", consumption.path("LicenseConsumptionToken").asText());
        out.set("EntitlementsAllowed", allowed);
        out.put("SignedToken", consumption.path("_").path("signedToken").asText());
        if (in.hasNonNull("NodeId")) {
            out.put("NodeId", in.get("NodeId").asText());
        }
        out.put("IssuedAt", consumption.path("IssuedAt").asText());
        if (expiration != null) {
            out.put("Expiration", iso(expiration));
        }
        out.put("LicenseArn", chosen.record().path("LicenseArn").asText());
        store.remember("CheckoutLicense", in, out);
        return out;
    }

    private static boolean valid(JsonNode license) {
        try {
            Instant now = Instant.now();
            if (Instant.parse(license.path("Validity").path("Begin").asText()).isAfter(now)) {
                return false;
            }
            return !license.path("Validity").hasNonNull("End")
                    || Instant.parse(license.path("Validity").path("End").asText()).isAfter(now);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** The requested entitlements the license allows now; the checkout fails when any can't be had. */
    private ArrayNode allow(ObjectNode license, JsonNode requested) {
        ArrayNode allowed = mapper.createArrayNode();
        String arn = license.path("LicenseArn").asText();
        for (JsonNode want : requested) {
            String name = want.path("Name").asText();
            JsonNode entitlement = null;
            for (JsonNode candidate : license.path("Entitlements")) {
                if (name.equals(candidate.path("Name").asText())) {
                    entitlement = candidate;
                }
            }
            if (entitlement == null) {
                throw new AwsException("NoEntitlementsAllowedException", "The license has no entitlement " + name
                        + ".", 400);
            }
            if (!entitlement.path("Unit").asText().equals(want.path("Unit").asText())) {
                throw new AwsException("NoEntitlementsAllowedException", "The entitlement " + name + " is counted in "
                        + entitlement.path("Unit").asText() + ", not " + want.path("Unit").asText() + ".", 400);
            }
            if (entitlement.hasNonNull("MaxCount") && !entitlement.path("Overage").asBoolean(false)) {
                long max = entitlement.get("MaxCount").asLong();
                if (consumed(arn, name) + value(want) > max) {
                    throw new AwsException("EntitlementNotAllowedException", "Checking out " + value(want) + " of "
                            + name + " would exceed its maximum of " + max + ".", 400);
                }
            }
            allowed.add(want.deepCopy());
        }
        if (allowed.isEmpty()) {
            throw new AwsException("NoEntitlementsAllowedException", "The checkout names no entitlements.", 400);
        }
        return allowed;
    }

    private ObjectNode consume(ObjectNode license, String type, ArrayNode allowed, String beneficiary, String nodeId,
                               Instant expiration, JsonNode metadata) {
        String token = UUID.randomUUID().toString();
        Instant issued = Instant.now();
        ObjectNode consumption = store.record();
        consumption.put("LicenseConsumptionToken", token);
        consumption.put("LicenseArn", license.path("LicenseArn").asText());
        consumption.put("CheckoutType", type);
        consumption.set("EntitlementsAllowed", allowed.deepCopy());
        consumption.put("Beneficiary", beneficiary);
        if (nodeId != null) {
            consumption.put("NodeId", nodeId);
        }
        consumption.put("IssuedAt", iso(issued));
        if (expiration != null) {
            consumption.put("Expiration", iso(expiration));
        }
        if (metadata != null && metadata.isArray()) {
            consumption.set("CheckoutMetadata", metadata.deepCopy());
        }
        String issuer = license.path("_").path("account").asText(store.account());
        ObjectNode claims = mapper.createObjectNode();
        claims.put("acct", issuer);
        claims.put("licenseArn", license.path("LicenseArn").asText());
        claims.put("productSKU", license.path("ProductSKU").asText());
        claims.put("licenseConsumptionToken", token);
        claims.put("checkoutType", type);
        claims.set("entitlementsAllowed", allowed.deepCopy());
        claims.put("beneficiary", beneficiary);
        if (nodeId != null) {
            claims.put("nodeId", nodeId);
        }
        claims.put("iat", issued.getEpochSecond());
        if (expiration != null) {
            claims.put("exp", expiration.getEpochSecond());
        }
        claims.put("keyFingerprint", license.path("Issuer").path("KeyFingerprint").asText());
        LicenseManagerStore.meta(consumption).put("signedToken", sign(issuer, license.path("Issuer")
                .path("KeyFingerprint").asText(), claims));
        store.put(LicenseManagerStore.GLOBAL, LicenseManagerStore.CONSUMPTION, token, consumption);
        return consumption;
    }

    public ObjectNode checkoutBorrowLicense(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CheckoutBorrowLicense", in);
        if (repeated != null) {
            return repeated;
        }
        String arn = in.path("LicenseArn").asText();
        Located located = license(arn).orElseThrow(() -> LicenseManagerStore.notFound("The license " + arn
                + " does not exist."));
        ObjectNode license = located.record();
        boolean entitled = beneficiary(license, store.account()) || store.account().equals(located.account())
                || activeGrant(arn, store.account()).filter(g -> LicenseManagerStore.any(g.path("GrantedOperations"),
                "CheckoutBorrowLicense"::equals)).isPresent();
        if (!entitled) {
            throw LicenseManagerStore.authorization("The license " + arn + " isn't granted to this account.");
        }
        if (!"AVAILABLE".equals(license.path("Status").asText()) || !valid(license)) {
            throw LicenseManagerStore.notFound("The license " + arn + " is not available.");
        }
        JsonNode borrow = license.path("ConsumptionConfiguration").path("BorrowConfiguration");
        if (!borrow.isObject()) {
            throw LicenseManagerStore.validation("The license doesn't allow borrowing.");
        }
        Instant expiration = Instant.now().plus(borrow.path("MaxTimeToLiveInMinutes").asLong(60), ChronoUnit.MINUTES);
        ArrayNode allowed = allow(license, in.path("Entitlements"));
        ObjectNode consumption = consume(license, "BORROW", allowed, store.account(),
                in.path("NodeId").asText(null), expiration, in.get("CheckoutMetadata"));
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseArn", arn);
        out.put("LicenseConsumptionToken", consumption.path("LicenseConsumptionToken").asText());
        out.set("EntitlementsAllowed", allowed);
        if (in.hasNonNull("NodeId")) {
            out.put("NodeId", in.get("NodeId").asText());
        }
        out.put("SignedToken", consumption.path("_").path("signedToken").asText());
        out.put("IssuedAt", consumption.path("IssuedAt").asText());
        out.put("Expiration", iso(expiration));
        if (in.has("CheckoutMetadata")) {
            out.set("CheckoutMetadata", in.get("CheckoutMetadata").deepCopy());
        }
        store.remember("CheckoutBorrowLicense", in, out);
        return out;
    }

    private Located consumption(String token) {
        for (ObjectNode consumption : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.CONSUMPTION)) {
            if (token.equals(consumption.path("LicenseConsumptionToken").asText())) {
                return new Located(consumption.path("_").path("account").asText(), token, consumption);
            }
        }
        throw LicenseManagerStore.notFound("The license consumption token " + token + " does not exist.");
    }

    public ObjectNode checkInLicense(JsonNode in, String region) {
        Located located = consumption(in.path("LicenseConsumptionToken").asText());
        ObjectNode consumption = located.record();
        if (consumption.path("_").path("checkedIn").asBoolean(false)) {
            throw LicenseManagerStore.conflict("The license was already checked in.");
        }
        if ("BORROW".equals(consumption.path("CheckoutType").asText()) && active(consumption)) {
            boolean early = license(consumption.path("LicenseArn").asText())
                    .map(l -> l.record().path("ConsumptionConfiguration").path("BorrowConfiguration")
                            .path("AllowEarlyCheckIn").asBoolean(false)).orElse(true);
            if (!early) {
                throw LicenseManagerStore.conflict("The license doesn't allow checking a borrow in early.");
            }
        }
        LicenseManagerStore.meta(consumption).put("checkedIn", true);
        store.putForAccount(located.account(), LicenseManagerStore.GLOBAL, LicenseManagerStore.CONSUMPTION,
                located.id(), consumption);
        return mapper.createObjectNode();
    }

    public ObjectNode extendLicenseConsumption(JsonNode in, String region) {
        Located located = consumption(in.path("LicenseConsumptionToken").asText());
        ObjectNode consumption = located.record();
        if (!active(consumption)) {
            throw LicenseManagerStore.notFound("The license consumption has ended.");
        }
        if (!"PROVISIONAL".equals(consumption.path("CheckoutType").asText())) {
            throw LicenseManagerStore.validation("Only a provisional checkout can be extended.");
        }
        Located license = license(consumption.path("LicenseArn").asText()).orElseThrow(() ->
                LicenseManagerStore.notFound("The license no longer exists."));
        if (!"AVAILABLE".equals(license.record().path("Status").asText())) {
            throw LicenseManagerStore.validation("The license is " + license.record().path("Status").asText() + ".");
        }
        Instant expiration = Instant.now().plus(license.record().path("ConsumptionConfiguration")
                .path("ProvisionalConfiguration").path("MaxTimeToLiveInMinutes").asLong(60), ChronoUnit.MINUTES);
        if (!in.path("DryRun").asBoolean(false)) {
            consumption.put("Expiration", iso(expiration));
            store.putForAccount(located.account(), LicenseManagerStore.GLOBAL, LicenseManagerStore.CONSUMPTION,
                    located.id(), consumption);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseConsumptionToken", located.id());
        out.put("Expiration", iso(expiration));
        return out;
    }

    // ================================================================ grants

    private static void checkOperations(JsonNode operations) {
        for (JsonNode operation : operations) {
            if (!OPERATIONS.contains(operation.asText())) {
                throw LicenseManagerStore.validation("The operation " + operation.asText() + " can't be granted.");
            }
        }
    }

    public ObjectNode createGrant(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateGrant", in);
        if (repeated != null) {
            return repeated;
        }
        String licenseArn = in.path("LicenseArn").asText();
        Located license = ownedLicense(licenseArn);
        if (!"AVAILABLE".equals(license.record().path("Status").asText())) {
            throw LicenseManagerStore.invalid("The license " + licenseArn + " is "
                    + license.record().path("Status").asText() + ".");
        }
        JsonNode principals = in.path("Principals");
        if (principals.size() != 1) {
            throw LicenseManagerStore.invalid("A grant names exactly one principal.");
        }
        String principal = principals.get(0).asText();
        if (!principal.matches("^arn:[a-z-]+:iam::\\d{12}:root$")
                && !principal.matches("^arn:[a-z-]+:organizations::\\d{12}:organization/o-[a-z0-9]+$")) {
            throw LicenseManagerStore.invalid("The principal " + principal + " must be an account's root ARN or an "
                    + "organization ARN.");
        }
        checkOperations(in.path("AllowedOperations"));
        String id = "g-" + LicenseManagerStore.hex(16);
        ObjectNode grant = store.record();
        grant.put("GrantArn", store.globalArn(region, "grant:" + id));
        grant.put("GrantName", in.path("GrantName").asText());
        grant.put("ParentArn", licenseArn);
        grant.put("LicenseArn", licenseArn);
        grant.put("GranteePrincipalArn", principal);
        grant.put("HomeRegion", in.path("HomeRegion").asText());
        grant.put("GrantStatus", "PENDING_ACCEPT");
        grant.put("Version", "1");
        grant.set("GrantedOperations", in.path("AllowedOperations").deepCopy());
        if (in.path("Tags").isArray()) {
            LicenseManagerStore.addTags(grant, in.get("Tags"));
        }
        LicenseManagerStore.meta(grant).putArray("versions").add(LicenseManagerStore.view(grant));
        store.put(LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT, id, grant);
        ObjectNode out = mapper.createObjectNode();
        out.put("GrantArn", grant.path("GrantArn").asText());
        out.put("Status", "PENDING_ACCEPT");
        out.put("Version", "1");
        store.remember("CreateGrant", in, out);
        return out;
    }

    private Optional<Located> grant(String arn) {
        Optional<AwsArnUtils.Arn> parsed = LicenseManagerStore.parse(arn);
        String resource = parsed.map(AwsArnUtils.Arn::resource).orElse("");
        if (!resource.startsWith("grant:")) {
            return Optional.empty();
        }
        String id = resource.substring("grant:".length());
        return store.getForAccount(parsed.get().accountId(), LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT, id)
                .map(grant -> new Located(parsed.get().accountId(), id, grant));
    }

    private static boolean grantee(JsonNode grant, String account) {
        return grant.path("GranteePrincipalArn").asText().contains(":" + account + ":");
    }

    /** The caller's active grant of a license, if any. */
    private Optional<ObjectNode> activeGrant(String licenseArn, String account) {
        for (ObjectNode grant : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT)) {
            if (licenseArn.equals(grant.path("LicenseArn").asText()) && grantee(grant, account)
                    && "ACTIVE".equals(grant.path("GrantStatus").asText())) {
                return Optional.of(grant);
            }
        }
        return Optional.empty();
    }

    private void saveGrant(Located located) {
        store.putForAccount(located.account(), LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT, located.id(),
                located.record());
    }

    public ObjectNode getGrant(JsonNode in, String region) {
        String arn = in.path("GrantArn").asText();
        Located located = grant(arn).orElseThrow(() -> LicenseManagerStore.notFound("The grant " + arn
                + " does not exist."));
        if (!store.account().equals(located.account()) && !grantee(located.record(), store.account())) {
            throw LicenseManagerStore.authorization("The grant " + arn + " is not yours to read.");
        }
        ObjectNode grant = LicenseManagerStore.view(located.record());
        if (in.hasNonNull("Version") && !in.get("Version").asText().equals(grant.path("Version").asText())) {
            ObjectNode found = null;
            for (JsonNode version : located.record().path("_").path("versions")) {
                if (in.get("Version").asText().equals(version.path("Version").asText())) {
                    found = (ObjectNode) version.deepCopy();
                }
            }
            if (found == null) {
                throw LicenseManagerStore.notFound("The grant " + arn + " has no version " + in.get("Version").asText()
                        + ".");
            }
            grant = found;
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Grant", grant);
        return out;
    }

    private Located receivedGrant(String arn) {
        Located located = grant(arn).orElseThrow(() -> LicenseManagerStore.invalid("The grant " + arn
                + " does not exist."));
        if (!grantee(located.record(), store.account())) {
            throw LicenseManagerStore.authorization("The grant " + arn + " wasn't made to this account.");
        }
        return located;
    }

    public ObjectNode acceptGrant(JsonNode in, String region) {
        Located located = receivedGrant(in.path("GrantArn").asText());
        String status = located.record().path("GrantStatus").asText();
        if (!"PENDING_ACCEPT".equals(status) && !"ACTIVE".equals(status)) {
            throw LicenseManagerStore.invalid("The grant is " + status + " and can't be accepted.");
        }
        located.record().put("GrantStatus", "ACTIVE");
        saveGrant(located);
        return grantResult(located.record());
    }

    public ObjectNode rejectGrant(JsonNode in, String region) {
        Located located = receivedGrant(in.path("GrantArn").asText());
        String status = located.record().path("GrantStatus").asText();
        if (Set.of("DELETED", "REJECTED").contains(status)) {
            throw LicenseManagerStore.invalid("The grant is already " + status + ".");
        }
        located.record().put("GrantStatus", "REJECTED");
        saveGrant(located);
        return grantResult(located.record());
    }

    private ObjectNode grantResult(ObjectNode grant) {
        ObjectNode out = mapper.createObjectNode();
        out.put("GrantArn", grant.path("GrantArn").asText());
        out.put("Status", grant.path("GrantStatus").asText());
        out.put("Version", grant.path("Version").asText());
        return out;
    }

    private Located issuedGrant(String arn) {
        Located located = grant(arn).orElseThrow(() -> LicenseManagerStore.invalid("The grant " + arn
                + " does not exist."));
        if (!store.account().equals(located.account())) {
            throw LicenseManagerStore.authorization("The grant " + arn + " was made by another account.");
        }
        return located;
    }

    public ObjectNode createGrantVersion(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateGrantVersion", in);
        if (repeated != null) {
            return repeated;
        }
        Located located = issuedGrant(in.path("GrantArn").asText());
        ObjectNode grant = located.record();
        String current = grant.path("Version").asText();
        if (in.hasNonNull("SourceVersion") && !current.equals(in.get("SourceVersion").asText())) {
            throw LicenseManagerStore.conflict("The grant's version is " + current + ", not "
                    + in.get("SourceVersion").asText() + ".");
        }
        if ("DELETED".equals(grant.path("GrantStatus").asText())) {
            throw LicenseManagerStore.invalid("The grant is deleted.");
        }
        if (in.hasNonNull("GrantName")) {
            grant.put("GrantName", in.get("GrantName").asText());
        }
        if (in.has("AllowedOperations")) {
            checkOperations(in.get("AllowedOperations"));
            grant.set("GrantedOperations", in.get("AllowedOperations").deepCopy());
        }
        if (in.hasNonNull("Status")) {
            grant.put("GrantStatus", in.get("Status").asText());
        }
        if (in.hasNonNull("StatusReason")) {
            grant.put("StatusReason", in.get("StatusReason").asText());
        }
        if (in.has("Options")) {
            grant.set("Options", in.get("Options").deepCopy());
        }
        grant.put("Version", String.valueOf(Integer.parseInt(current) + 1));
        LicenseManagerStore.meta(grant).withArray("versions").add(LicenseManagerStore.view(grant));
        saveGrant(located);
        ObjectNode out = grantResult(grant);
        store.remember("CreateGrantVersion", in, out);
        return out;
    }

    public ObjectNode deleteGrant(JsonNode in, String region) {
        Located located = issuedGrant(in.path("GrantArn").asText());
        ObjectNode grant = located.record();
        if (!grant.path("Version").asText().equals(in.path("Version").asText())) {
            throw LicenseManagerStore.conflict("The grant's version is " + grant.path("Version").asText() + ", not "
                    + in.path("Version").asText() + ".");
        }
        grant.put("GrantStatus", "DELETED");
        if (in.hasNonNull("StatusReason")) {
            grant.put("StatusReason", in.get("StatusReason").asText());
        }
        saveGrant(located);
        return grantResult(grant);
    }

    private static final Map<String, Function<JsonNode, String>> GRANT_FILTERS = Map.of(
            "LicenseArn", g -> g.path("LicenseArn").asText(),
            "GrantStatus", g -> g.path("GrantStatus").asText(),
            "GranteePrincipalARN", g -> g.path("GranteePrincipalArn").asText(),
            "ProductSKU", g -> g.path("_sku").asText(),
            "LicenseIssuerName", g -> g.path("_issuer").asText(),
            "GranterAccountId", g -> g.path("_granter").asText());

    private ObjectNode listGrants(JsonNode in, List<ObjectNode> grants) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : grants) {
            if (in.path("GrantArns").size() > 0 && !LicenseManagerStore.any(in.path("GrantArns"),
                    arn -> arn.equals(record.path("GrantArn").asText()))) {
                continue;
            }
            ObjectNode probe = LicenseManagerStore.view(record);
            Optional<Located> license = license(record.path("LicenseArn").asText());
            probe.put("_sku", license.map(l -> l.record().path("ProductSKU").asText()).orElse(""));
            probe.put("_issuer", license.map(l -> l.record().path("Issuer").path("Name").asText()).orElse(""));
            probe.put("_granter", record.path("_").path("account").asText());
            if (LicenseManagerStore.filtered(in.path("Filters"), GRANT_FILTERS, probe)) {
                items.add(LicenseManagerStore.view(record));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "Grants");
        return out;
    }

    public ObjectNode listDistributedGrants(JsonNode in, String region) {
        return listGrants(in, store.list(LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT));
    }

    public ObjectNode listReceivedGrants(JsonNode in, String region) {
        List<ObjectNode> received = new ArrayList<>();
        for (ObjectNode grant : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT)) {
            if (grantee(grant, store.account())) {
                received.add(grant);
            }
        }
        return listGrants(in, received);
    }

    public ObjectNode listReceivedGrantsForOrganization(JsonNode in, String region) {
        if (!configurations.settings(region).path("OrganizationConfiguration").path("EnableIntegration")
                .asBoolean(false)) {
            throw new AwsException("AccessDeniedException", "Turn on the AWS Organizations integration in the "
                    + "License Manager settings first.", 400);
        }
        String licenseArn = in.path("LicenseArn").asText();
        List<ObjectNode> received = new ArrayList<>();
        for (ObjectNode grant : store.everywhere(LicenseManagerStore.GLOBAL, LicenseManagerStore.GRANT)) {
            if (licenseArn.equals(grant.path("LicenseArn").asText())
                    && grant.path("GranteePrincipalArn").asText().contains(":organization/")) {
                received.add(grant);
            }
        }
        return listGrants(in, received);
    }

    // ================================================================ tokens

    public ObjectNode createToken(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateToken", in);
        if (repeated != null) {
            return repeated;
        }
        String licenseArn = in.path("LicenseArn").asText();
        Located license = license(licenseArn).orElseThrow(() -> LicenseManagerStore.notFound("The license "
                + licenseArn + " does not exist."));
        if (!readable(license)) {
            throw LicenseManagerStore.authorization("The license " + licenseArn + " is not yours.");
        }
        int days = in.path("ExpirationInDays").asInt(365);
        if (days < 1 || days > 730) {
            throw LicenseManagerStore.validation("ExpirationInDays must be from 1 to 730.");
        }
        for (JsonNode role : in.path("RoleArns")) {
            if (!AwsArnUtils.isArnFor(role.asText(), "iam")) {
                throw LicenseManagerStore.validation("The role " + role.asText() + " is not an IAM role ARN.");
            }
        }
        String tokenId = UUID.randomUUID().toString().replace("-", "");
        Instant expiration = Instant.now().plus(days, ChronoUnit.DAYS);
        ObjectNode token = store.record();
        token.put("TokenId", tokenId);
        token.put("TokenType", "REFRESH_TOKEN");
        token.put("LicenseArn", licenseArn);
        token.put("ExpirationTime", iso(expiration));
        token.set("TokenProperties", in.path("TokenProperties").isArray() ? in.get("TokenProperties").deepCopy()
                : mapper.createArrayNode());
        token.set("RoleArns", in.path("RoleArns").isArray() ? in.get("RoleArns").deepCopy()
                : mapper.createArrayNode());
        token.put("Status", "AVAILABLE");
        store.put(LicenseManagerStore.GLOBAL, LicenseManagerStore.TOKEN, tokenId, token);
        ObjectNode claims = mapper.createObjectNode();
        claims.put("acct", store.account());
        claims.put("jti", tokenId);
        claims.put("sub", licenseArn);
        claims.put("typ", "REFRESH_TOKEN");
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", expiration.getEpochSecond());
        claims.set("roles", token.get("RoleArns").deepCopy());
        claims.set("props", token.get("TokenProperties").deepCopy());
        ObjectNode out = mapper.createObjectNode();
        out.put("TokenId", tokenId);
        out.put("TokenType", "REFRESH_TOKEN");
        out.put("Token", sign(store.account(), "license-manager-token", claims));
        store.remember("CreateToken", in, out);
        return out;
    }

    public ObjectNode deleteToken(JsonNode in, String region) {
        String tokenId = in.path("TokenId").asText();
        ObjectNode token = store.get(LicenseManagerStore.GLOBAL, LicenseManagerStore.TOKEN, tokenId)
                .filter(t -> !"DELETED".equals(t.path("Status").asText()))
                .orElseThrow(() -> LicenseManagerStore.notFound("The token " + tokenId + " does not exist."));
        token.put("Status", "DELETED");
        store.put(LicenseManagerStore.GLOBAL, LicenseManagerStore.TOKEN, tokenId, token);
        return mapper.createObjectNode();
    }

    public ObjectNode listTokens(JsonNode in, String region) {
        Map<String, Function<JsonNode, String>> fields = Map.of("LicenseArns", t -> t.path("LicenseArn").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode record : store.list(LicenseManagerStore.GLOBAL, LicenseManagerStore.TOKEN)) {
            if ("DELETED".equals(record.path("Status").asText())) {
                continue;
            }
            if (in.path("TokenIds").size() > 0 && !LicenseManagerStore.any(in.path("TokenIds"),
                    id -> id.equals(record.path("TokenId").asText()))) {
                continue;
            }
            ObjectNode token = LicenseManagerStore.view(record);
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, token)) {
                items.add(token);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "Tokens");
        return out;
    }

    public ObjectNode getAccessToken(JsonNode in, String region) {
        String jws = in.path("Token").asText();
        ObjectNode claims = verify(jws).filter(c -> "REFRESH_TOKEN".equals(c.path("typ").asText()))
                .orElseThrow(() -> LicenseManagerStore.invalid("The token is not a valid License Manager refresh "
                        + "token."));
        String account = claims.path("acct").asText();
        ObjectNode token = store.getForAccount(account, LicenseManagerStore.GLOBAL, LicenseManagerStore.TOKEN,
                        claims.path("jti").asText())
                .filter(t -> !"DELETED".equals(t.path("Status").asText()))
                .orElseThrow(() -> LicenseManagerStore.authorization("The refresh token was deleted."));
        if (claims.path("exp").asLong() < Instant.now().getEpochSecond()) {
            throw LicenseManagerStore.authorization("The refresh token has expired.");
        }
        Set<String> allowed = new HashSet<>();
        token.path("TokenProperties").forEach(p -> allowed.add(p.asText()));
        for (JsonNode wanted : in.path("TokenProperties")) {
            if (!allowed.contains(wanted.asText())) {
                throw LicenseManagerStore.validation("The token property " + wanted.asText()
                        + " isn't one the refresh token carries.");
            }
        }
        ObjectNode access = mapper.createObjectNode();
        access.put("acct", account);
        access.put("sub", token.path("LicenseArn").asText());
        access.put("typ", "ACCESS_TOKEN");
        access.put("jti", UUID.randomUUID().toString());
        access.put("iat", Instant.now().getEpochSecond());
        access.put("exp", Instant.now().plus(1, ChronoUnit.HOURS).getEpochSecond());
        access.set("roles", token.path("RoleArns").deepCopy());
        access.set("props", in.path("TokenProperties").isArray() && in.path("TokenProperties").size() > 0
                ? in.get("TokenProperties").deepCopy() : token.path("TokenProperties").deepCopy());
        ObjectNode out = mapper.createObjectNode();
        out.put("AccessToken", sign(account, "license-manager-token", access));
        return out;
    }
}
