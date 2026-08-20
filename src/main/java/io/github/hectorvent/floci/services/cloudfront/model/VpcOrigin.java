package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class VpcOrigin {

    private String id;
    private String arn;
    private String accountId;
    private String status;
    private Instant createdTime;
    private Instant lastModifiedTime;
    private String etag;
    private String name;
    private String originEndpointArn;
    private int httpPort = 80;
    private int httpsPort = 443;
    private String originProtocolPolicy = "https-only";
    private List<String> originSslProtocols = new ArrayList<>(List.of("TLSv1.2"));

    public VpcOrigin() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOriginEndpointArn() { return originEndpointArn; }
    public void setOriginEndpointArn(String originEndpointArn) { this.originEndpointArn = originEndpointArn; }

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

    public int getHttpsPort() { return httpsPort; }
    public void setHttpsPort(int httpsPort) { this.httpsPort = httpsPort; }

    public String getOriginProtocolPolicy() { return originProtocolPolicy; }
    public void setOriginProtocolPolicy(String originProtocolPolicy) { this.originProtocolPolicy = originProtocolPolicy; }

    public List<String> getOriginSslProtocols() { return originSslProtocols; }
    public void setOriginSslProtocols(List<String> originSslProtocols) { this.originSslProtocols = originSslProtocols; }
}
