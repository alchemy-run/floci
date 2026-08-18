package io.github.hectorvent.floci.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Action {

    private String type;
    private Integer order;

    // forward (simple)
    private String targetGroupArn;

    // forward (weighted)
    private List<TargetGroupTuple> targetGroups = new ArrayList<>();
    private Boolean stickinessEnabled;
    private Integer stickinessDurationSeconds;

    // redirect
    private String redirectProtocol;
    private String redirectPort;
    private String redirectHost;
    private String redirectPath;
    private String redirectQuery;
    private String redirectStatusCode;

    // fixed-response
    private String fixedResponseStatusCode;
    private String fixedResponseContentType;
    private String fixedResponseMessageBody;

    // authenticate-oidc
    private String oidcIssuer;
    private String oidcAuthorizationEndpoint;
    private String oidcTokenEndpoint;
    private String oidcUserInfoEndpoint;
    private String oidcClientId;
    private String oidcClientSecret;
    private String oidcScope;
    private String oidcSessionCookieName;
    private Long oidcSessionTimeout;
    private String oidcOnUnauthenticatedRequest;
    private Boolean oidcUseExistingClientSecret;
    private Map<String, String> oidcAuthenticationRequestExtraParams = new LinkedHashMap<>();

    public Action() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getOrder() { return order; }
    public void setOrder(Integer order) { this.order = order; }

    public String getTargetGroupArn() { return targetGroupArn; }
    public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }

    public List<TargetGroupTuple> getTargetGroups() { return targetGroups; }
    public void setTargetGroups(List<TargetGroupTuple> targetGroups) { this.targetGroups = targetGroups; }

    public Boolean getStickinessEnabled() { return stickinessEnabled; }
    public void setStickinessEnabled(Boolean stickinessEnabled) { this.stickinessEnabled = stickinessEnabled; }

    public Integer getStickinessDurationSeconds() { return stickinessDurationSeconds; }
    public void setStickinessDurationSeconds(Integer stickinessDurationSeconds) { this.stickinessDurationSeconds = stickinessDurationSeconds; }

    public String getRedirectProtocol() { return redirectProtocol; }
    public void setRedirectProtocol(String redirectProtocol) { this.redirectProtocol = redirectProtocol; }

    public String getRedirectPort() { return redirectPort; }
    public void setRedirectPort(String redirectPort) { this.redirectPort = redirectPort; }

    public String getRedirectHost() { return redirectHost; }
    public void setRedirectHost(String redirectHost) { this.redirectHost = redirectHost; }

    public String getRedirectPath() { return redirectPath; }
    public void setRedirectPath(String redirectPath) { this.redirectPath = redirectPath; }

    public String getRedirectQuery() { return redirectQuery; }
    public void setRedirectQuery(String redirectQuery) { this.redirectQuery = redirectQuery; }

    public String getRedirectStatusCode() { return redirectStatusCode; }
    public void setRedirectStatusCode(String redirectStatusCode) { this.redirectStatusCode = redirectStatusCode; }

    public String getFixedResponseStatusCode() { return fixedResponseStatusCode; }
    public void setFixedResponseStatusCode(String fixedResponseStatusCode) { this.fixedResponseStatusCode = fixedResponseStatusCode; }

    public String getFixedResponseContentType() { return fixedResponseContentType; }
    public void setFixedResponseContentType(String fixedResponseContentType) { this.fixedResponseContentType = fixedResponseContentType; }

    public String getFixedResponseMessageBody() { return fixedResponseMessageBody; }
    public void setFixedResponseMessageBody(String fixedResponseMessageBody) { this.fixedResponseMessageBody = fixedResponseMessageBody; }

    public String getOidcIssuer() { return oidcIssuer; }
    public void setOidcIssuer(String oidcIssuer) { this.oidcIssuer = oidcIssuer; }

    public String getOidcAuthorizationEndpoint() { return oidcAuthorizationEndpoint; }
    public void setOidcAuthorizationEndpoint(String oidcAuthorizationEndpoint) {
        this.oidcAuthorizationEndpoint = oidcAuthorizationEndpoint;
    }

    public String getOidcTokenEndpoint() { return oidcTokenEndpoint; }
    public void setOidcTokenEndpoint(String oidcTokenEndpoint) { this.oidcTokenEndpoint = oidcTokenEndpoint; }

    public String getOidcUserInfoEndpoint() { return oidcUserInfoEndpoint; }
    public void setOidcUserInfoEndpoint(String oidcUserInfoEndpoint) { this.oidcUserInfoEndpoint = oidcUserInfoEndpoint; }

    public String getOidcClientId() { return oidcClientId; }
    public void setOidcClientId(String oidcClientId) { this.oidcClientId = oidcClientId; }

    public String getOidcClientSecret() { return oidcClientSecret; }
    public void setOidcClientSecret(String oidcClientSecret) { this.oidcClientSecret = oidcClientSecret; }

    public String getOidcScope() { return oidcScope; }
    public void setOidcScope(String oidcScope) { this.oidcScope = oidcScope; }

    public String getOidcSessionCookieName() { return oidcSessionCookieName; }
    public void setOidcSessionCookieName(String oidcSessionCookieName) { this.oidcSessionCookieName = oidcSessionCookieName; }

    public Long getOidcSessionTimeout() { return oidcSessionTimeout; }
    public void setOidcSessionTimeout(Long oidcSessionTimeout) { this.oidcSessionTimeout = oidcSessionTimeout; }

    public String getOidcOnUnauthenticatedRequest() { return oidcOnUnauthenticatedRequest; }
    public void setOidcOnUnauthenticatedRequest(String oidcOnUnauthenticatedRequest) {
        this.oidcOnUnauthenticatedRequest = oidcOnUnauthenticatedRequest;
    }

    public Boolean getOidcUseExistingClientSecret() { return oidcUseExistingClientSecret; }
    public void setOidcUseExistingClientSecret(Boolean oidcUseExistingClientSecret) {
        this.oidcUseExistingClientSecret = oidcUseExistingClientSecret;
    }

    public Map<String, String> getOidcAuthenticationRequestExtraParams() {
        return oidcAuthenticationRequestExtraParams;
    }
    public void setOidcAuthenticationRequestExtraParams(Map<String, String> oidcAuthenticationRequestExtraParams) {
        this.oidcAuthenticationRequestExtraParams = oidcAuthenticationRequestExtraParams != null
                ? oidcAuthenticationRequestExtraParams
                : new LinkedHashMap<>();
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetGroupTuple {
        private String targetGroupArn;
        private Integer weight;

        public TargetGroupTuple() {}

        public String getTargetGroupArn() { return targetGroupArn; }
        public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }

        public Integer getWeight() { return weight; }
        public void setWeight(Integer weight) { this.weight = weight; }
    }
}
