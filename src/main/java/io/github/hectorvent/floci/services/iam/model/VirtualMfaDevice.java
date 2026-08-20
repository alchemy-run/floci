package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VirtualMfaDevice {

    private String serialNumber;
    private String name;
    private String path;
    private String userName;
    private Instant enableDate;
    private String base32StringSeed;
    private String qrCodePng;
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public VirtualMfaDevice() {}

    public VirtualMfaDevice(String serialNumber, String name, String path,
                            String base32StringSeed, String qrCodePng) {
        this.serialNumber = serialNumber;
        this.name = name;
        this.path = path;
        this.base32StringSeed = base32StringSeed;
        this.qrCodePng = qrCodePng;
    }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public Instant getEnableDate() { return enableDate; }
    public void setEnableDate(Instant enableDate) { this.enableDate = enableDate; }

    public String getBase32StringSeed() { return base32StringSeed; }
    public void setBase32StringSeed(String base32StringSeed) { this.base32StringSeed = base32StringSeed; }

    public String getQrCodePng() { return qrCodePng; }
    public void setQrCodePng(String qrCodePng) { this.qrCodePng = qrCodePng; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = new ConcurrentHashMap<>(tags);
    }

    public boolean isAssigned() {
        return userName != null && !userName.isBlank();
    }
}
