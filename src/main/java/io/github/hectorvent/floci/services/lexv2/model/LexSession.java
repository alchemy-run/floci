package io.github.hectorvent.floci.services.lexv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Lex Runtime V2 conversation session (PutSession / RecognizeText state).
 */
@RegisterForReflection
public class LexSession {

    private String botId;
    private String botAliasId;
    private String localeId;
    private String sessionId;
    private Map<String, Object> sessionState = new LinkedHashMap<>();
    private List<Map<String, Object>> messages = new ArrayList<>();
    private Map<String, String> requestAttributes = new LinkedHashMap<>();

    public LexSession() {
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getBotAliasId() {
        return botAliasId;
    }

    public void setBotAliasId(String botAliasId) {
        this.botAliasId = botAliasId;
    }

    public String getLocaleId() {
        return localeId;
    }

    public void setLocaleId(String localeId) {
        this.localeId = localeId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Map<String, Object> getSessionState() {
        return sessionState;
    }

    public void setSessionState(Map<String, Object> sessionState) {
        this.sessionState = sessionState == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sessionState);
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
    }

    public Map<String, String> getRequestAttributes() {
        return requestAttributes;
    }

    public void setRequestAttributes(Map<String, String> requestAttributes) {
        this.requestAttributes = requestAttributes == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(requestAttributes);
    }
}
