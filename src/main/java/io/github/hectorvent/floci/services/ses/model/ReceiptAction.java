package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A single SES v1 receipt-rule action. Exactly one action type is set, matching
 * the AWS {@code ReceiptAction} shape.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReceiptAction {

    @JsonProperty("S3Action")
    private S3Action s3Action;

    @JsonProperty("BounceAction")
    private BounceAction bounceAction;

    @JsonProperty("WorkmailAction")
    private WorkmailAction workmailAction;

    @JsonProperty("LambdaAction")
    private LambdaAction lambdaAction;

    @JsonProperty("StopAction")
    private StopAction stopAction;

    @JsonProperty("AddHeaderAction")
    private AddHeaderAction addHeaderAction;

    @JsonProperty("SNSAction")
    private SnsAction snsAction;

    @JsonProperty("ConnectAction")
    private ConnectAction connectAction;

    public ReceiptAction() {}

    public S3Action getS3Action() { return s3Action; }
    public void setS3Action(S3Action s3Action) { this.s3Action = s3Action; }

    public BounceAction getBounceAction() { return bounceAction; }
    public void setBounceAction(BounceAction bounceAction) { this.bounceAction = bounceAction; }

    public WorkmailAction getWorkmailAction() { return workmailAction; }
    public void setWorkmailAction(WorkmailAction workmailAction) { this.workmailAction = workmailAction; }

    public LambdaAction getLambdaAction() { return lambdaAction; }
    public void setLambdaAction(LambdaAction lambdaAction) { this.lambdaAction = lambdaAction; }

    public StopAction getStopAction() { return stopAction; }
    public void setStopAction(StopAction stopAction) { this.stopAction = stopAction; }

    public AddHeaderAction getAddHeaderAction() { return addHeaderAction; }
    public void setAddHeaderAction(AddHeaderAction addHeaderAction) { this.addHeaderAction = addHeaderAction; }

    public SnsAction getSnsAction() { return snsAction; }
    public void setSnsAction(SnsAction snsAction) { this.snsAction = snsAction; }

    public ConnectAction getConnectAction() { return connectAction; }
    public void setConnectAction(ConnectAction connectAction) { this.connectAction = connectAction; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class S3Action {
        @JsonProperty("TopicArn")
        private String topicArn;
        @JsonProperty("BucketName")
        private String bucketName;
        @JsonProperty("ObjectKeyPrefix")
        private String objectKeyPrefix;
        @JsonProperty("KmsKeyArn")
        private String kmsKeyArn;
        @JsonProperty("IamRoleArn")
        private String iamRoleArn;

        public String getTopicArn() { return topicArn; }
        public void setTopicArn(String topicArn) { this.topicArn = topicArn; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String bucketName) { this.bucketName = bucketName; }
        public String getObjectKeyPrefix() { return objectKeyPrefix; }
        public void setObjectKeyPrefix(String objectKeyPrefix) { this.objectKeyPrefix = objectKeyPrefix; }
        public String getKmsKeyArn() { return kmsKeyArn; }
        public void setKmsKeyArn(String kmsKeyArn) { this.kmsKeyArn = kmsKeyArn; }
        public String getIamRoleArn() { return iamRoleArn; }
        public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BounceAction {
        @JsonProperty("TopicArn")
        private String topicArn;
        @JsonProperty("SmtpReplyCode")
        private String smtpReplyCode;
        @JsonProperty("StatusCode")
        private String statusCode;
        @JsonProperty("Message")
        private String message;
        @JsonProperty("Sender")
        private String sender;

        public String getTopicArn() { return topicArn; }
        public void setTopicArn(String topicArn) { this.topicArn = topicArn; }
        public String getSmtpReplyCode() { return smtpReplyCode; }
        public void setSmtpReplyCode(String smtpReplyCode) { this.smtpReplyCode = smtpReplyCode; }
        public String getStatusCode() { return statusCode; }
        public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkmailAction {
        @JsonProperty("TopicArn")
        private String topicArn;
        @JsonProperty("OrganizationArn")
        private String organizationArn;

        public String getTopicArn() { return topicArn; }
        public void setTopicArn(String topicArn) { this.topicArn = topicArn; }
        public String getOrganizationArn() { return organizationArn; }
        public void setOrganizationArn(String organizationArn) { this.organizationArn = organizationArn; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LambdaAction {
        @JsonProperty("TopicArn")
        private String topicArn;
        @JsonProperty("FunctionArn")
        private String functionArn;
        @JsonProperty("InvocationType")
        private String invocationType;

        public String getTopicArn() { return topicArn; }
        public void setTopicArn(String topicArn) { this.topicArn = topicArn; }
        public String getFunctionArn() { return functionArn; }
        public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }
        public String getInvocationType() { return invocationType; }
        public void setInvocationType(String invocationType) { this.invocationType = invocationType; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StopAction {
        @JsonProperty("Scope")
        private String scope;
        @JsonProperty("TopicArn")
        private String topicArn;

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getTopicArn() { return topicArn; }
        public void setTopicArn(String topicArn) { this.topicArn = topicArn; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AddHeaderAction {
        @JsonProperty("HeaderName")
        private String headerName;
        @JsonProperty("HeaderValue")
        private String headerValue;

        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public String getHeaderValue() { return headerValue; }
        public void setHeaderValue(String headerValue) { this.headerValue = headerValue; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SnsAction {
        @JsonProperty("TopicArn")
        private String topicArn;
        @JsonProperty("Encoding")
        private String encoding;

        public String getTopicArn() { return topicArn; }
        public void setTopicArn(String topicArn) { this.topicArn = topicArn; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String encoding) { this.encoding = encoding; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConnectAction {
        @JsonProperty("InstanceARN")
        private String instanceArn;
        @JsonProperty("IAMRoleARN")
        private String iamRoleArn;

        public String getInstanceArn() { return instanceArn; }
        public void setInstanceArn(String instanceArn) { this.instanceArn = instanceArn; }
        public String getIamRoleArn() { return iamRoleArn; }
        public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }
    }
}
