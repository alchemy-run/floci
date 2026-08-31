package io.github.hectorvent.floci.services.smsvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsVoiceOptOutList {

    private String optOutListName;
    private String optOutListArn;
    private String region;
    private long createdTimestamp;
    private boolean serviceManaged;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, SmsVoiceOptedOutNumber> numbers = new LinkedHashMap<>();

    public SmsVoiceOptOutList() {
    }

    public String getOptOutListName() {
        return optOutListName;
    }

    public void setOptOutListName(String optOutListName) {
        this.optOutListName = optOutListName;
    }

    public String getOptOutListArn() {
        return optOutListArn;
    }

    public void setOptOutListArn(String optOutListArn) {
        this.optOutListArn = optOutListArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public boolean isServiceManaged() {
        return serviceManaged;
    }

    public void setServiceManaged(boolean serviceManaged) {
        this.serviceManaged = serviceManaged;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public Map<String, SmsVoiceOptedOutNumber> getNumbers() {
        if (numbers == null) {
            numbers = new LinkedHashMap<>();
        }
        return numbers;
    }

    public void setNumbers(Map<String, SmsVoiceOptedOutNumber> numbers) {
        this.numbers = numbers == null ? new LinkedHashMap<>() : numbers;
    }
}
