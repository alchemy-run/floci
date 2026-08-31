package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.hectorvent.floci.core.common.ReservedTags.rejectUnknownReservedTags;

/**
 * Local AWS Payment Cryptography control + data plane. Keys live in memory;
 * cryptographic operations are internally consistent (encrypt/decrypt,
 * generate/verify) rather than HSM-identical.
 */
@ApplicationScoped
public class PaymentCryptographyService implements Resettable {

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final SecureRandom secureRandom;
    private final ConcurrentHashMap<String, PaymentCryptographyKey> keysByArn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentCryptographyAlias> aliasesByName = new ConcurrentHashMap<>();

    @Inject
    public PaymentCryptographyService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this(objectMapper, regionResolver, new SecureRandom());
    }

    PaymentCryptographyService(ObjectMapper objectMapper, RegionResolver regionResolver, SecureRandom secureRandom) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.secureRandom = secureRandom;
    }

    @Override
    public void clear() {
        keysByArn.clear();
        aliasesByName.clear();
    }

    public ObjectNode createKey(JsonNode request, String region) {
        JsonNode attributes = request.path("KeyAttributes");
        if (attributes.isMissingNode() || !attributes.isObject()) {
            throw new AwsException("ValidationException", "KeyAttributes is required.", 400);
        }
        String keyClass = requiredText(attributes, "KeyClass");
        String keyAlgorithm = requiredText(attributes, "KeyAlgorithm");
        String keyUsage = requiredText(attributes, "KeyUsage");
        JsonNode modes = attributes.path("KeyModesOfUse");
        if (modes.isMissingNode() || !modes.isObject()) {
            throw new AwsException("ValidationException", "KeyModesOfUse is required.", 400);
        }

        PaymentCryptographyKey key = new PaymentCryptographyKey();
        key.setKeyId(UUID.randomUUID().toString());
        key.setKeyArn(regionResolver.buildArn("payment-cryptography", region, "key/" + key.getKeyId()));
        key.setRegion(region);
        key.setKeyClass(keyClass);
        key.setKeyAlgorithm(keyAlgorithm);
        key.setKeyUsage(keyUsage);
        key.setEncrypt(modes.path("Encrypt").asBoolean(false));
        key.setDecrypt(modes.path("Decrypt").asBoolean(false));
        key.setWrap(modes.path("Wrap").asBoolean(false));
        key.setUnwrap(modes.path("Unwrap").asBoolean(false));
        key.setGenerate(modes.path("Generate").asBoolean(false));
        key.setSign(modes.path("Sign").asBoolean(false));
        key.setVerify(modes.path("Verify").asBoolean(false));
        key.setDeriveKey(modes.path("DeriveKey").asBoolean(false));
        key.setNoRestrictions(modes.path("NoRestrictions").asBoolean(false));
        key.setExportable(request.path("Exportable").asBoolean(false));
        key.setEnabled(request.path("Enabled").isMissingNode() || request.path("Enabled").asBoolean(true));
        key.setKeyState("CREATE_COMPLETE");
        key.setKeyOrigin("AWS_PAYMENT_CRYPTOGRAPHY");
        long now = Instant.now().getEpochSecond();
        key.setCreateTimestamp(now);
        if (key.isEnabled()) {
            key.setUsageStartTimestamp(now);
        }
        if (request.hasNonNull("DeriveKeyUsage")) {
            key.setDeriveKeyUsage(request.get("DeriveKeyUsage").asText());
        }
        parseTags(request.path("Tags")).forEach(key.getTags()::put);

        boolean tdes = PaymentCryptographyCrypto.isTdes(keyAlgorithm);
        String kcvAlgorithm = request.path("KeyCheckValueAlgorithm").asText(tdes ? "ANSI_X9_24" : "CMAC");
        key.setKeyCheckValueAlgorithm(kcvAlgorithm);

        if ("ASYMMETRIC_KEY_PAIR".equals(keyClass) || "PRIVATE_KEY".equals(keyClass) || "PUBLIC_KEY".equals(keyClass)) {
            generateAsymmetricMaterial(key);
            key.setKeyCheckValue("000000");
        } else {
            byte[] material = PaymentCryptographyCrypto.randomBytes(
                    secureRandom, PaymentCryptographyCrypto.keyMaterialLength(keyAlgorithm));
            key.setKeyMaterial(material);
            key.setKeyCheckValue(PaymentCryptographyCrypto.keyCheckValue(material, keyAlgorithm, kcvAlgorithm));
        }

        keysByArn.put(key.getKeyArn(), key);
        return wrapKey(key);
    }

    public ObjectNode getKey(JsonNode request) {
        PaymentCryptographyKey key = requireKey(text(request, "KeyIdentifier"), false);
        return wrapKey(key);
    }

    public ObjectNode listKeys(JsonNode request) {
        String stateFilter = request.path("KeyState").asText(null);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode keys = response.putArray("Keys");
        for (PaymentCryptographyKey key : keysByArn.values()) {
            if ("DELETE_COMPLETE".equals(key.getKeyState())) {
                continue;
            }
            if (stateFilter != null && !stateFilter.isBlank() && !stateFilter.equals(key.getKeyState())) {
                continue;
            }
            keys.add(keySummary(key));
        }
        return response;
    }

    public ObjectNode deleteKey(JsonNode request) {
        PaymentCryptographyKey key = requireKey(text(request, "KeyIdentifier"), true);
        if ("DELETE_PENDING".equals(key.getKeyState())) {
            throw new AwsException("ConflictException",
                    "Key is already scheduled for deletion.", 409);
        }
        int days = request.path("DeleteKeyInDays").isMissingNode() ? 7 : request.path("DeleteKeyInDays").asInt();
        if (days < 3) {
            days = 3;
        }
        key.setEnabled(false);
        key.setKeyState("DELETE_PENDING");
        key.setDeletePendingTimestamp(Instant.now().plus(days, ChronoUnit.DAYS).getEpochSecond());
        key.setUsageStopTimestamp(Instant.now().getEpochSecond());
        return wrapKey(key);
    }

    public ObjectNode restoreKey(JsonNode request) {
        PaymentCryptographyKey key = requireKey(text(request, "KeyIdentifier"), true);
        if (!"DELETE_PENDING".equals(key.getKeyState())) {
            throw new AwsException("ValidationException",
                    "Key is not in DELETE_PENDING state.", 400);
        }
        key.setKeyState("CREATE_COMPLETE");
        key.setDeletePendingTimestamp(null);
        key.setEnabled(true);
        key.setUsageStartTimestamp(Instant.now().getEpochSecond());
        key.setUsageStopTimestamp(null);
        return wrapKey(key);
    }

    public ObjectNode startKeyUsage(JsonNode request) {
        PaymentCryptographyKey key = requireKey(text(request, "KeyIdentifier"), false);
        key.setEnabled(true);
        key.setUsageStartTimestamp(Instant.now().getEpochSecond());
        key.setUsageStopTimestamp(null);
        return wrapKey(key);
    }

    public ObjectNode stopKeyUsage(JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(text(request, "KeyIdentifier"));
        key.setEnabled(false);
        key.setUsageStopTimestamp(Instant.now().getEpochSecond());
        return wrapKey(key);
    }

    public ObjectNode createAlias(JsonNode request, String region) {
        String aliasName = requiredText(request, "AliasName");
        if (!aliasName.startsWith("alias/") || !aliasName.substring("alias/".length()).matches("[0-9A-Za-z/_-]+")) {
            throw new AwsException("ValidationException",
                    "AliasName must match alias/[0-9A-Za-z/_-]+.", 400);
        }
        if (aliasesByName.containsKey(aliasName)) {
            throw new AwsException("ConflictException", "Alias " + aliasName + " already exists.", 409);
        }
        String keyArn = text(request, "KeyArn");
        if (keyArn != null && !keyArn.isBlank()) {
            requireKey(keyArn, true);
        }
        PaymentCryptographyAlias alias = newAlias(aliasName, region, keyArn);
        aliasesByName.put(aliasName, alias);
        return wrapAlias(alias);
    }

    public ObjectNode getAlias(JsonNode request) {
        return wrapAlias(requireAlias(requiredText(request, "AliasName")));
    }

    public ObjectNode updateAlias(JsonNode request) {
        PaymentCryptographyAlias alias = requireAlias(requiredText(request, "AliasName"));
        String keyArn = text(request, "KeyArn");
        if (keyArn != null && !keyArn.isBlank()) {
            requireKey(keyArn, true);
            alias.setKeyArn(keyArn);
        } else {
            alias.setKeyArn(null);
        }
        return wrapAlias(alias);
    }

    public ObjectNode deleteAlias(JsonNode request) {
        String aliasName = requiredText(request, "AliasName");
        requireAlias(aliasName);
        aliasesByName.remove(aliasName);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listAliases(JsonNode request) {
        String keyArn = text(request, "KeyArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode aliases = response.putArray("Aliases");
        for (PaymentCryptographyAlias alias : aliasesByName.values()) {
            if (keyArn == null || keyArn.isBlank() || keyArn.equals(alias.getKeyArn())) {
                aliases.add(aliasNode(alias));
            }
        }
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        PaymentCryptographyKey key = requireKey(text(request, "ResourceArn"), true);
        Map<String, String> incoming = parseTags(request.path("Tags"));
        key.getTags().putAll(incoming);
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        PaymentCryptographyKey key = requireKey(text(request, "ResourceArn"), true);
        JsonNode tagKeys = request.path("TagKeys");
        if (tagKeys.isArray()) {
            for (JsonNode tagKey : tagKeys) {
                key.getTags().remove(tagKey.asText());
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        PaymentCryptographyKey key = requireKey(text(request, "ResourceArn"), true);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        for (Map.Entry<String, String> entry : key.getTags().entrySet()) {
            ObjectNode tag = tags.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return response;
    }

    public ObjectNode getPublicKeyCertificate(JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(text(request, "KeyIdentifier"));
        if (key.getKeyCertificate() == null || key.getKeyCertificate().isBlank()) {
            throw new AwsException("ValidationException",
                    "Key does not have a public key certificate.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyCertificate", key.getKeyCertificate());
        response.put("KeyCertificateChain", key.getKeyCertificateChain());
        return response;
    }

    public ObjectNode encryptData(String keyIdentifier, JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(keyIdentifier);
        byte[] plaintext = PaymentCryptographyCrypto.hex(requiredText(request, "PlainText"));
        byte[] ciphertext = transform(key, request.path("EncryptionAttributes"), plaintext, true);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", key.getKeyArn());
        response.put("KeyCheckValue", key.getKeyCheckValue());
        response.put("CipherText", PaymentCryptographyCrypto.hex(ciphertext));
        return response;
    }

    public ObjectNode decryptData(String keyIdentifier, JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(keyIdentifier);
        byte[] ciphertext = PaymentCryptographyCrypto.hex(requiredText(request, "CipherText"));
        byte[] plaintext = transform(key, request.path("DecryptionAttributes"), ciphertext, false);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", key.getKeyArn());
        response.put("KeyCheckValue", key.getKeyCheckValue());
        response.put("PlainText", PaymentCryptographyCrypto.hex(plaintext));
        return response;
    }

    public ObjectNode reEncryptData(String incomingKeyIdentifier, JsonNode request) {
        PaymentCryptographyKey incoming = requireUsableKey(incomingKeyIdentifier);
        PaymentCryptographyKey outgoing = requireUsableKey(requiredText(request, "OutgoingKeyIdentifier"));
        byte[] ciphertext = PaymentCryptographyCrypto.hex(requiredText(request, "CipherText"));
        byte[] plaintext = transform(incoming, request.path("IncomingEncryptionAttributes"), ciphertext, false);
        byte[] reencrypted = transform(outgoing, request.path("OutgoingEncryptionAttributes"), plaintext, true);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", outgoing.getKeyArn());
        response.put("KeyCheckValue", outgoing.getKeyCheckValue());
        response.put("CipherText", PaymentCryptographyCrypto.hex(reencrypted));
        return response;
    }

    public ObjectNode generateMac(JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(requiredText(request, "KeyIdentifier"));
        byte[] mac = macBytes(key, request.path("GenerationAttributes"),
                PaymentCryptographyCrypto.hex(requiredText(request, "MessageData")),
                request.path("MacLength"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", key.getKeyArn());
        response.put("KeyCheckValue", key.getKeyCheckValue());
        response.put("Mac", PaymentCryptographyCrypto.hex(mac));
        return response;
    }

    public ObjectNode verifyMac(JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(requiredText(request, "KeyIdentifier"));
        byte[] expected = macBytes(key, request.path("VerificationAttributes"),
                PaymentCryptographyCrypto.hex(requiredText(request, "MessageData")),
                request.path("MacLength"));
        byte[] provided = PaymentCryptographyCrypto.hex(requiredText(request, "Mac"));
        if (!PaymentCryptographyCrypto.constantEquals(expected, provided)) {
            throw verificationFailed("INVALID_MAC", "MAC verification failed");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", key.getKeyArn());
        response.put("KeyCheckValue", key.getKeyCheckValue());
        return response;
    }

    public ObjectNode generateCardValidationData(JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(requiredText(request, "KeyIdentifier"));
        String pan = requiredText(request, "PrimaryAccountNumber");
        String expiry = cardExpiry(request.path("GenerationAttributes"));
        String cvv2 = PaymentCryptographyCrypto.cvv2(key.getKeyMaterial(), pan, expiry);
        int length = request.path("ValidationDataLength").asInt(3);
        if (length > 0 && length < cvv2.length()) {
            cvv2 = cvv2.substring(0, length);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", key.getKeyArn());
        response.put("KeyCheckValue", key.getKeyCheckValue());
        response.put("ValidationData", cvv2);
        return response;
    }

    public ObjectNode verifyCardValidationData(JsonNode request) {
        PaymentCryptographyKey key = requireUsableKey(requiredText(request, "KeyIdentifier"));
        String pan = requiredText(request, "PrimaryAccountNumber");
        String expiry = cardExpiry(request.path("VerificationAttributes"));
        String expected = PaymentCryptographyCrypto.cvv2(key.getKeyMaterial(), pan, expiry);
        String provided = requiredText(request, "ValidationData");
        if (!expected.equals(provided)) {
            throw verificationFailed("INVALID_VALIDATION_DATA", "Card validation data verification failed");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", key.getKeyArn());
        response.put("KeyCheckValue", key.getKeyCheckValue());
        return response;
    }

    public ObjectNode generatePinData(JsonNode request) {
        PaymentCryptographyKey pvk = requireUsableKey(requiredText(request, "GenerationKeyIdentifier"));
        PaymentCryptographyKey pek = requireUsableKey(requiredText(request, "EncryptionKeyIdentifier"));
        String pan = requiredText(request, "PrimaryAccountNumber");
        String format = requiredText(request, "PinBlockFormat");
        if (!"ISO_FORMAT_0".equals(format)) {
            throw new AwsException("ValidationException", "Only ISO_FORMAT_0 is supported.", 400);
        }
        JsonNode visa = request.path("GenerationAttributes").path("VisaPin");
        if (visa.isMissingNode()) {
            throw new AwsException("ValidationException", "VisaPin generation attributes are required.", 400);
        }
        int pvki = visa.path("PinVerificationKeyIndex").asInt(1);
        int pinLength = request.path("PinDataLength").asInt(4);
        String pin = PaymentCryptographyCrypto.randomPin(secureRandom, pinLength);
        String pvv = PaymentCryptographyCrypto.visaPvv(pvk.getKeyMaterial(), pan, pin, pvki);
        byte[] pinBlock = PaymentCryptographyCrypto.isoFormat0PinBlock(pin, pan);
        byte[] encrypted = PaymentCryptographyCrypto.encryptSymmetric(
                pek.getKeyMaterial(), pek.getKeyAlgorithm(), "ECB", pinBlock, null);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("GenerationKeyArn", pvk.getKeyArn());
        response.put("GenerationKeyCheckValue", pvk.getKeyCheckValue());
        response.put("EncryptionKeyArn", pek.getKeyArn());
        response.put("EncryptionKeyCheckValue", pek.getKeyCheckValue());
        response.put("EncryptedPinBlock", PaymentCryptographyCrypto.hex(encrypted));
        response.putObject("PinData").put("VerificationValue", pvv);
        return response;
    }

    public ObjectNode verifyPinData(JsonNode request) {
        PaymentCryptographyKey pvk = requireUsableKey(requiredText(request, "VerificationKeyIdentifier"));
        PaymentCryptographyKey pek = requireUsableKey(requiredText(request, "EncryptionKeyIdentifier"));
        String pan = requiredText(request, "PrimaryAccountNumber");
        String format = requiredText(request, "PinBlockFormat");
        if (!"ISO_FORMAT_0".equals(format)) {
            throw new AwsException("ValidationException", "Only ISO_FORMAT_0 is supported.", 400);
        }
        JsonNode visa = request.path("VerificationAttributes").path("VisaPin");
        if (visa.isMissingNode()) {
            throw new AwsException("ValidationException", "VisaPin verification attributes are required.", 400);
        }
        int pvki = visa.path("PinVerificationKeyIndex").asInt(1);
        String expectedPvv = requiredText(visa, "VerificationValue");
        byte[] encrypted = PaymentCryptographyCrypto.hex(requiredText(request, "EncryptedPinBlock"));
        byte[] clear = PaymentCryptographyCrypto.decryptSymmetric(
                pek.getKeyMaterial(), pek.getKeyAlgorithm(), "ECB", encrypted, null);
        String pin = PaymentCryptographyCrypto.pinFromIsoFormat0(clear, pan);
        String actualPvv = PaymentCryptographyCrypto.visaPvv(pvk.getKeyMaterial(), pan, pin, pvki);
        if (!expectedPvv.equals(actualPvv)) {
            throw verificationFailed("INVALID_PIN", "PIN verification failed");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("VerificationKeyArn", pvk.getKeyArn());
        response.put("VerificationKeyCheckValue", pvk.getKeyCheckValue());
        response.put("EncryptionKeyArn", pek.getKeyArn());
        response.put("EncryptionKeyCheckValue", pek.getKeyCheckValue());
        return response;
    }

    public ObjectNode translatePinData(JsonNode request) {
        PaymentCryptographyKey incoming = requireUsableKey(requiredText(request, "IncomingKeyIdentifier"));
        PaymentCryptographyKey outgoing = requireUsableKey(requiredText(request, "OutgoingKeyIdentifier"));
        String incomingPan = translationPan(request.path("IncomingTranslationAttributes"));
        String outgoingPan = translationPan(request.path("OutgoingTranslationAttributes"));
        byte[] encrypted = PaymentCryptographyCrypto.hex(requiredText(request, "EncryptedPinBlock"));
        byte[] clear = PaymentCryptographyCrypto.decryptSymmetric(
                incoming.getKeyMaterial(), incoming.getKeyAlgorithm(), "ECB", encrypted, null);
        if (!incomingPan.equals(outgoingPan)) {
            String pin = PaymentCryptographyCrypto.pinFromIsoFormat0(clear, incomingPan);
            clear = PaymentCryptographyCrypto.isoFormat0PinBlock(pin, outgoingPan);
        }
        byte[] translated = PaymentCryptographyCrypto.encryptSymmetric(
                outgoing.getKeyMaterial(), outgoing.getKeyAlgorithm(), "ECB", clear, null);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PinBlock", PaymentCryptographyCrypto.hex(translated));
        response.put("KeyArn", outgoing.getKeyArn());
        response.put("KeyCheckValue", outgoing.getKeyCheckValue());
        return response;
    }

    private byte[] transform(PaymentCryptographyKey key, JsonNode attributes, byte[] data, boolean encrypt) {
        if (attributes == null || attributes.isMissingNode() || !attributes.isObject()) {
            throw new AwsException("ValidationException", "Encryption attributes are required.", 400);
        }
        if (attributes.has("Dukpt")) {
            JsonNode dukpt = attributes.get("Dukpt");
            byte[] ksn = PaymentCryptographyCrypto.hex(requiredText(dukpt, "KeySerialNumber"));
            if (ksn.length != 10) {
                throw new AwsException("ValidationException", "KeySerialNumber must be 20 hex characters.", 400);
            }
            byte[] session = PaymentCryptographyCrypto.dukptDataKey(key.getKeyMaterial(), ksn);
            String mode = dukpt.path("Mode").asText("CBC");
            byte[] iv = dukpt.hasNonNull("InitializationVector")
                    ? PaymentCryptographyCrypto.hex(dukpt.get("InitializationVector").asText())
                    : null;
            return encrypt
                    ? PaymentCryptographyCrypto.encryptSymmetric(session, "TDES_2KEY", mode, data, iv)
                    : PaymentCryptographyCrypto.decryptSymmetric(session, "TDES_2KEY", mode, data, iv);
        }
        if (attributes.has("Symmetric")) {
            JsonNode symmetric = attributes.get("Symmetric");
            String mode = symmetric.path("Mode").asText("CBC");
            byte[] iv = symmetric.hasNonNull("InitializationVector")
                    ? PaymentCryptographyCrypto.hex(symmetric.get("InitializationVector").asText())
                    : null;
            return encrypt
                    ? PaymentCryptographyCrypto.encryptSymmetric(key.getKeyMaterial(), key.getKeyAlgorithm(), mode, data, iv)
                    : PaymentCryptographyCrypto.decryptSymmetric(key.getKeyMaterial(), key.getKeyAlgorithm(), mode, data, iv);
        }
        throw new AwsException("ValidationException", "Unsupported encryption attributes.", 400);
    }

    private byte[] macBytes(PaymentCryptographyKey key, JsonNode attributes, byte[] message, JsonNode macLength) {
        String algorithm = attributes.path("Algorithm").asText("HMAC");
        if (!algorithm.startsWith("HMAC")) {
            throw new AwsException("ValidationException", "Only HMAC MAC algorithms are supported.", 400);
        }
        byte[] mac = PaymentCryptographyCrypto.hmac(key.getKeyMaterial(), key.getKeyAlgorithm(), message);
        if (macLength != null && !macLength.isMissingNode() && macLength.asInt() > 0) {
            int bytes = Math.min(mac.length, macLength.asInt());
            mac = Arrays.copyOf(mac, bytes);
        }
        return mac;
    }

    private String cardExpiry(JsonNode attributes) {
        JsonNode cvv2 = attributes.path("CardVerificationValue2");
        if (cvv2.isMissingNode()) {
            throw new AwsException("ValidationException", "CardVerificationValue2 is required.", 400);
        }
        return requiredText(cvv2, "CardExpiryDate");
    }

    private String translationPan(JsonNode attributes) {
        JsonNode iso0 = attributes.path("IsoFormat0");
        if (iso0.isMissingNode()) {
            throw new AwsException("ValidationException", "IsoFormat0 translation attributes are required.", 400);
        }
        return requiredText(iso0, "PrimaryAccountNumber");
    }

    private PaymentCryptographyKey requireUsableKey(String identifier) {
        PaymentCryptographyKey key = requireKey(identifier, false);
        if (!key.isEnabled()) {
            throw new AwsException("ValidationException", "Key is not enabled for cryptographic operations.", 400);
        }
        return key;
    }

    private PaymentCryptographyKey requireKey(String identifier, boolean allowPending) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "KeyIdentifier is required.", 400);
        }
        PaymentCryptographyKey key = findKey(identifier);
        if (key == null || "DELETE_COMPLETE".equals(key.getKeyState())) {
            throw notFound(identifier);
        }
        if (!allowPending && "DELETE_PENDING".equals(key.getKeyState())) {
            throw new AwsException("ValidationException", "Key is scheduled for deletion.", 400);
        }
        return key;
    }

    private PaymentCryptographyKey findKey(String identifier) {
        if (identifier.startsWith("alias/") || identifier.contains(":alias/")) {
            String aliasName = identifier.contains(":alias/")
                    ? "alias/" + identifier.substring(identifier.indexOf(":alias/") + ":alias/".length())
                    : identifier;
            PaymentCryptographyAlias alias = aliasesByName.get(aliasName);
            if (alias == null || alias.getKeyArn() == null || alias.getKeyArn().isBlank()) {
                return null;
            }
            return keysByArn.get(alias.getKeyArn());
        }
        PaymentCryptographyKey exact = keysByArn.get(identifier);
        if (exact != null) {
            return exact;
        }
        for (PaymentCryptographyKey key : keysByArn.values()) {
            if (identifier.equals(key.getKeyId()) || key.getKeyArn().endsWith("/" + identifier)
                    || key.getKeyArn().endsWith(identifier)) {
                return key;
            }
        }
        return null;
    }

    private PaymentCryptographyAlias requireAlias(String aliasName) {
        PaymentCryptographyAlias alias = aliasesByName.get(aliasName);
        if (alias == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Alias '" + aliasName + "' does not exist.", 404,
                    Map.of("ResourceId", aliasName));
        }
        return alias;
    }

    private PaymentCryptographyAlias newAlias(String aliasName, String region, String keyArn) {
        PaymentCryptographyAlias alias = new PaymentCryptographyAlias();
        alias.setAliasName(aliasName);
        alias.setRegion(region);
        alias.setKeyArn(keyArn == null || keyArn.isBlank() ? null : keyArn);
        return alias;
    }

    private ObjectNode wrapAlias(PaymentCryptographyAlias alias) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Alias", aliasNode(alias));
        return response;
    }

    private ObjectNode aliasNode(PaymentCryptographyAlias alias) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AliasName", alias.getAliasName());
        if (alias.getKeyArn() != null) {
            node.put("KeyArn", alias.getKeyArn());
        }
        return node;
    }

    private ObjectNode wrapKey(PaymentCryptographyKey key) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Key", keyNode(key));
        return response;
    }

    private ObjectNode keySummary(PaymentCryptographyKey key) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("KeyArn", key.getKeyArn());
        node.put("KeyState", key.getKeyState());
        node.set("KeyAttributes", keyAttributes(key));
        node.put("KeyCheckValue", key.getKeyCheckValue());
        node.put("Exportable", key.isExportable());
        node.put("Enabled", key.isEnabled());
        return node;
    }

    private ObjectNode keyNode(PaymentCryptographyKey key) {
        ObjectNode node = keySummary(key);
        node.put("KeyCheckValueAlgorithm", key.getKeyCheckValueAlgorithm());
        node.put("KeyOrigin", key.getKeyOrigin());
        node.put("CreateTimestamp", key.getCreateTimestamp());
        if (key.getUsageStartTimestamp() != null) {
            node.put("UsageStartTimestamp", key.getUsageStartTimestamp());
        }
        if (key.getUsageStopTimestamp() != null) {
            node.put("UsageStopTimestamp", key.getUsageStopTimestamp());
        }
        if (key.getDeletePendingTimestamp() != null) {
            node.put("DeletePendingTimestamp", key.getDeletePendingTimestamp());
        }
        if (key.getDeriveKeyUsage() != null) {
            node.put("DeriveKeyUsage", key.getDeriveKeyUsage());
        }
        return node;
    }

    private ObjectNode keyAttributes(PaymentCryptographyKey key) {
        ObjectNode attributes = objectMapper.createObjectNode();
        attributes.put("KeyUsage", key.getKeyUsage());
        attributes.put("KeyClass", key.getKeyClass());
        attributes.put("KeyAlgorithm", key.getKeyAlgorithm());
        ObjectNode modes = attributes.putObject("KeyModesOfUse");
        modes.put("Encrypt", key.isEncrypt());
        modes.put("Decrypt", key.isDecrypt());
        modes.put("Wrap", key.isWrap());
        modes.put("Unwrap", key.isUnwrap());
        modes.put("Generate", key.isGenerate());
        modes.put("Sign", key.isSign());
        modes.put("Verify", key.isVerify());
        modes.put("DeriveKey", key.isDeriveKey());
        modes.put("NoRestrictions", key.isNoRestrictions());
        return attributes;
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String tagKey = tag.path("Key").asText(null);
                if (tagKey != null) {
                    tags.put(tagKey, tag.path("Value").asText(""));
                }
            }
        }
        rejectUnknownReservedTags(tags, "ValidationException");
        return tags;
    }

    private void generateAsymmetricMaterial(PaymentCryptographyKey key) {
        try {
            String specName = switch (key.getKeyAlgorithm()) {
                case "ECC_NIST_P384" -> "secp384r1";
                case "ECC_NIST_P521" -> "secp521r1";
                default -> "secp256r1";
            };
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(new ECGenParameterSpec(specName), secureRandom);
            KeyPair keyPair = generator.generateKeyPair();
            key.setKeyPair(keyPair);

            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=Payment Cryptography " + key.getKeyId());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    new BigInteger(128, secureRandom),
                    Date.from(now),
                    Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    subject,
                    keyPair.getPublic());
            String sigAlg = "secp384r1".equals(specName) || "secp521r1".equals(specName)
                    ? "SHA384withECDSA" : "SHA256withECDSA";
            ContentSigner signer = new JcaContentSignerBuilder(sigAlg)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(keyPair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(holder);
            String pem = toPem(cert);
            key.setKeyCertificate(pem);
            key.setKeyCertificateChain(pem);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalServerException",
                    "Unable to generate asymmetric key material: " + e.getMessage(), 500);
        }
    }

    private static String toPem(Object obj) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(obj);
        }
        return writer.toString();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return value.asText();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException notFound(String identifier) {
        return new AwsException("ResourceNotFoundException",
                "Key '" + identifier + "' does not exist.", 404,
                Map.of("ResourceId", identifier == null ? "" : identifier));
    }

    private static AwsException verificationFailed(String reason, String message) {
        return new AwsException("VerificationFailedException", message, 400,
                Map.of("Reason", reason));
    }

    List<PaymentCryptographyKey> keys() {
        return new ArrayList<>(keysByArn.values());
    }
}
