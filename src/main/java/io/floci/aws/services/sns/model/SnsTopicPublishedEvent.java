package io.floci.aws.services.sns.model;

/**
 * A message published to an SNS topic, fired after it is delivered to the topic's subscriptions, for the AWS
 * services that receive a topic's messages without a subscription Floci can deliver to (AWS Chatbot's channels).
 */
public record SnsTopicPublishedEvent(String accountId, String region, String topicArn, String messageId,
                                     String subject, String message) {
}
