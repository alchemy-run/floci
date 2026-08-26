package io.github.hectorvent.floci.services.paymentcryptography.model;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory Payment Cryptography key: TR-31 attributes plus the material used
 * for data-plane operations (symmetric bytes or an ECC/RSA key pair).
 */
public class PaymentCryptographyKey {

    private String keyId;
    private String keyArn;
    private String region;
    private String keyUsage;
    private String keyClass;
    private String keyAlgorithm;
    private boolean encrypt;
    private boolean decrypt;
    private boolean wrap;
    private boolean unwrap;
    private boolean generate;
    private boolean sign;
    private boolean verify;
    private boolean deriveKey;
    private boolean noRestrictions;
    private String keyCheckValue;
    private String keyCheckValueAlgorithm;
    private boolean enabled;
    private boolean exportable;
    private String keyState;
    private String keyOrigin;
    private long createTimestamp;
    private Long usageStartTimestamp;
    private Long usageStopTimestamp;
    private Long deletePendingTimestamp;
    private String deriveKeyUsage;
    private final Map<String, String> tags = new LinkedHashMap<>();
    private byte[] keyMaterial;
    private KeyPair keyPair;
    private String keyCertificate;
    private String keyCertificateChain;

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKeyArn() {
        return keyArn;
    }

    public void setKeyArn(String keyArn) {
        this.keyArn = keyArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getKeyUsage() {
        return keyUsage;
    }

    public void setKeyUsage(String keyUsage) {
        this.keyUsage = keyUsage;
    }

    public String getKeyClass() {
        return keyClass;
    }

    public void setKeyClass(String keyClass) {
        this.keyClass = keyClass;
    }

    public String getKeyAlgorithm() {
        return keyAlgorithm;
    }

    public void setKeyAlgorithm(String keyAlgorithm) {
        this.keyAlgorithm = keyAlgorithm;
    }

    public boolean isEncrypt() {
        return encrypt;
    }

    public void setEncrypt(boolean encrypt) {
        this.encrypt = encrypt;
    }

    public boolean isDecrypt() {
        return decrypt;
    }

    public void setDecrypt(boolean decrypt) {
        this.decrypt = decrypt;
    }

    public boolean isWrap() {
        return wrap;
    }

    public void setWrap(boolean wrap) {
        this.wrap = wrap;
    }

    public boolean isUnwrap() {
        return unwrap;
    }

    public void setUnwrap(boolean unwrap) {
        this.unwrap = unwrap;
    }

    public boolean isGenerate() {
        return generate;
    }

    public void setGenerate(boolean generate) {
        this.generate = generate;
    }

    public boolean isSign() {
        return sign;
    }

    public void setSign(boolean sign) {
        this.sign = sign;
    }

    public boolean isVerify() {
        return verify;
    }

    public void setVerify(boolean verify) {
        this.verify = verify;
    }

    public boolean isDeriveKey() {
        return deriveKey;
    }

    public void setDeriveKey(boolean deriveKey) {
        this.deriveKey = deriveKey;
    }

    public boolean isNoRestrictions() {
        return noRestrictions;
    }

    public void setNoRestrictions(boolean noRestrictions) {
        this.noRestrictions = noRestrictions;
    }

    public String getKeyCheckValue() {
        return keyCheckValue;
    }

    public void setKeyCheckValue(String keyCheckValue) {
        this.keyCheckValue = keyCheckValue;
    }

    public String getKeyCheckValueAlgorithm() {
        return keyCheckValueAlgorithm;
    }

    public void setKeyCheckValueAlgorithm(String keyCheckValueAlgorithm) {
        this.keyCheckValueAlgorithm = keyCheckValueAlgorithm;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExportable() {
        return exportable;
    }

    public void setExportable(boolean exportable) {
        this.exportable = exportable;
    }

    public String getKeyState() {
        return keyState;
    }

    public void setKeyState(String keyState) {
        this.keyState = keyState;
    }

    public String getKeyOrigin() {
        return keyOrigin;
    }

    public void setKeyOrigin(String keyOrigin) {
        this.keyOrigin = keyOrigin;
    }

    public long getCreateTimestamp() {
        return createTimestamp;
    }

    public void setCreateTimestamp(long createTimestamp) {
        this.createTimestamp = createTimestamp;
    }

    public Long getUsageStartTimestamp() {
        return usageStartTimestamp;
    }

    public void setUsageStartTimestamp(Long usageStartTimestamp) {
        this.usageStartTimestamp = usageStartTimestamp;
    }

    public Long getUsageStopTimestamp() {
        return usageStopTimestamp;
    }

    public void setUsageStopTimestamp(Long usageStopTimestamp) {
        this.usageStopTimestamp = usageStopTimestamp;
    }

    public Long getDeletePendingTimestamp() {
        return deletePendingTimestamp;
    }

    public void setDeletePendingTimestamp(Long deletePendingTimestamp) {
        this.deletePendingTimestamp = deletePendingTimestamp;
    }

    public String getDeriveKeyUsage() {
        return deriveKeyUsage;
    }

    public void setDeriveKeyUsage(String deriveKeyUsage) {
        this.deriveKeyUsage = deriveKeyUsage;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public byte[] getKeyMaterial() {
        return keyMaterial;
    }

    public void setKeyMaterial(byte[] keyMaterial) {
        this.keyMaterial = keyMaterial;
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public void setKeyPair(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public String getKeyCertificate() {
        return keyCertificate;
    }

    public void setKeyCertificate(String keyCertificate) {
        this.keyCertificate = keyCertificate;
    }

    public String getKeyCertificateChain() {
        return keyCertificateChain;
    }

    public void setKeyCertificateChain(String keyCertificateChain) {
        this.keyCertificateChain = keyCertificateChain;
    }
}
