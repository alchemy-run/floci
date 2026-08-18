package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ResultConfiguration {

    @JsonProperty("OutputLocation")
    private String outputLocation;

    @JsonProperty("EncryptionConfiguration")
    private EncryptionConfiguration encryptionConfiguration;

    public ResultConfiguration() {}

    public ResultConfiguration(String outputLocation) {
        this.outputLocation = outputLocation;
    }

    public String getOutputLocation() { return outputLocation; }
    public void setOutputLocation(String outputLocation) { this.outputLocation = outputLocation; }
    public EncryptionConfiguration getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(EncryptionConfiguration encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }

    @RegisterForReflection
    public static class EncryptionConfiguration {
        @JsonProperty("EncryptionOption")
        private String encryptionOption;
        @JsonProperty("KmsKey")
        private String kmsKey;

        public String getEncryptionOption() { return encryptionOption; }
        public void setEncryptionOption(String encryptionOption) { this.encryptionOption = encryptionOption; }
        public String getKmsKey() { return kmsKey; }
        public void setKmsKey(String kmsKey) { this.kmsKey = kmsKey; }
    }
}
