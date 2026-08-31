package io.github.hectorvent.floci.services.smsvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsVoiceOptedOutNumber {

    private String optedOutNumber;
    private long optedOutTimestamp;
    private boolean endUserOptedOut;

    public SmsVoiceOptedOutNumber() {
    }

    public String getOptedOutNumber() {
        return optedOutNumber;
    }

    public void setOptedOutNumber(String optedOutNumber) {
        this.optedOutNumber = optedOutNumber;
    }

    public long getOptedOutTimestamp() {
        return optedOutTimestamp;
    }

    public void setOptedOutTimestamp(long optedOutTimestamp) {
        this.optedOutTimestamp = optedOutTimestamp;
    }

    public boolean isEndUserOptedOut() {
        return endUserOptedOut;
    }

    public void setEndUserOptedOut(boolean endUserOptedOut) {
        this.endUserOptedOut = endUserOptedOut;
    }
}
