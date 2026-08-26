package io.github.hectorvent.floci.services.smsvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsVoiceKeyword {

    private String keyword;
    private String keywordMessage;
    private String keywordAction;

    public SmsVoiceKeyword() {
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getKeywordMessage() {
        return keywordMessage;
    }

    public void setKeywordMessage(String keywordMessage) {
        this.keywordMessage = keywordMessage;
    }

    public String getKeywordAction() {
        return keywordAction;
    }

    public void setKeywordAction(String keywordAction) {
        this.keywordAction = keywordAction;
    }
}
