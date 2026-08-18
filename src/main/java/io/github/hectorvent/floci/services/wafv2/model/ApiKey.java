package io.github.hectorvent.floci.services.wafv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CAPTCHA/challenge API key issued by {@code CreateAPIKey}. The token itself is
 * the identifier; {@code GetDecryptedAPIKey} returns the stored token domains.
 */
@RegisterForReflection
public class ApiKey {

    private String apiKey;
    private String scope;
    private List<String> tokenDomains = new ArrayList<>();
    private Instant creationTime;
    private int version = 1;

    public ApiKey() {}

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public List<String> getTokenDomains() { return tokenDomains; }
    public void setTokenDomains(List<String> tokenDomains) { this.tokenDomains = tokenDomains; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
