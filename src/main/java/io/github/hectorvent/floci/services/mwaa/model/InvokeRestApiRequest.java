package io.github.hectorvent.floci.services.mwaa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InvokeRestApiRequest {

    @JsonProperty("Method")
    private String method;

    @JsonProperty("Path")
    private String path;

    @JsonProperty("QueryParameters")
    private Object queryParameters;

    @JsonProperty("Body")
    private Object body;

    public InvokeRestApiRequest() {}

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Object getQueryParameters() { return queryParameters; }
    public void setQueryParameters(Object queryParameters) { this.queryParameters = queryParameters; }

    public Object getBody() { return body; }
    public void setBody(Object body) { this.body = body; }
}
