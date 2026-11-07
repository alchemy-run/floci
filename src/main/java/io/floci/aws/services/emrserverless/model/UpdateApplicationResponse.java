package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class UpdateApplicationResponse {
    // AWS answers with the updated application, as GetApplication does; the top-level fields stay for
    // callers that read them. The clientToken is storage-only.
    @JsonIgnoreProperties("clientToken")
    private Application application;
    private String applicationId;
    private String arn;
    private String name;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Application getApplication() { return application; }
    public void setApplication(Application application) { this.application = application; }
}
