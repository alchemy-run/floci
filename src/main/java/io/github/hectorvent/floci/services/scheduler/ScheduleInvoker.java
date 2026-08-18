package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.scheduler.model.EventBridgeParameters;
import io.github.hectorvent.floci.services.scheduler.model.EcsParameters;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sns.SnsMessageAttributes;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers an EventBridge Scheduler target invocation to the underlying service.
 * Supports templated SQS, Lambda, SNS, and EventBridge PutEvents targets, plus
 * universal targets ({@code arn:aws:scheduler:::aws-sdk:<service>:<action>}) for
 * {@code sns:publish} and {@code sqs:sendMessage}. Substitutes the
 * {@code <aws.scheduler.*>} context attributes AWS documents on
 * {@code Target.Input} when invoked with a {@link Schedule}.
 */
@ApplicationScoped
public class ScheduleInvoker {

    private static final Logger LOG = Logger.getLogger(ScheduleInvoker.class);

    private final SqsService sqsService;
    private final LambdaService lambdaService;
    private final SnsService snsService;
    private final EventBridgeService eventBridgeService;
    private final EcsService ecsService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public ScheduleInvoker(SqsService sqsService,
                           LambdaService lambdaService,
                           SnsService snsService,
                           EventBridgeService eventBridgeService,
                           EcsService ecsService,
                           ObjectMapper objectMapper,
                           EmulatorConfig config) {
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.ecsService = ecsService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    public void invoke(Target target, String region) {
        deliver(target, region, target != null && target.getInput() != null ? target.getInput() : "{}");
    }

    /**
     * Fire {@code schedule}'s target, substituting AWS Scheduler context
     * attributes into {@code Target.Input} the way live EventBridge Scheduler does.
     */
    public void invoke(Schedule schedule, Instant scheduledTime) {
        if (schedule == null || schedule.getTarget() == null) {
            return;
        }
        String region = extractRegion(schedule.getArn(), "us-east-1");
        String payload = substituteContextAttributes(
                schedule.getTarget().getInput() != null ? schedule.getTarget().getInput() : "{}",
                schedule,
                scheduledTime != null ? scheduledTime : Instant.now(),
                1);
        deliver(schedule.getTarget(), region, payload);
    }

    static String substituteContextAttributes(String input, Schedule schedule,
                                              Instant scheduledTime, int attemptNumber) {
        if (input == null || input.isEmpty() || !input.contains("<aws.scheduler.")) {
            return input;
        }
        String executionId = UUID.randomUUID().toString();
        String scheduled = DateTimeFormatter.ISO_INSTANT.format(scheduledTime);
        return input
                .replace("<aws.scheduler.schedule-arn>", schedule.getArn() != null ? schedule.getArn() : "")
                .replace("<aws.scheduler.scheduled-time>", scheduled)
                .replace("<aws.scheduler.execution-id>", executionId)
                .replace("<aws.scheduler.attempt-number>", Integer.toString(attemptNumber));
    }

    private void deliver(Target target, String region, String payload) {
        if (target == null || target.getArn() == null) {
            return;
        }
        String arn = target.getArn();

        // Universal targets (arn:aws:scheduler:::aws-sdk:<service>:<action>) carry the
        // real resource identifiers inside Input, not in the target ARN. Detect and
        // dispatch them before the ARN-substring routing below, which would otherwise
        // mis-match (e.g. "aws-sdk:sns:publish" contains ":sns:").
        int sdkIdx = arn.indexOf(":aws-sdk:");
        if (sdkIdx >= 0) {
            invokeUniversalTarget(arn.substring(sdkIdx + ":aws-sdk:".length()), payload, region);
            return;
        }

        String targetRegion = extractRegion(arn, region);
        if (arn.contains(":sqs:")) {
            String queueUrl = AwsArnUtils.arnToQueueUrl(arn, baseUrl);
            String messageGroupId = target.getSqsParameters() != null
                    ? target.getSqsParameters().getMessageGroupId() : null;
            sqsService.sendMessage(queueUrl, payload, 0, messageGroupId, null, targetRegion);
            LOG.debugv("Scheduler delivered to SQS: {0}", arn);
        } else if (arn.contains(":lambda:") || arn.contains(":function:")) {
            String fnName = arn.substring(arn.lastIndexOf(':') + 1);
            lambdaService.invoke(targetRegion, fnName, payload.getBytes(), InvocationType.Event);
            LOG.debugv("Scheduler delivered to Lambda: {0}", arn);
        } else if (arn.contains(":sns:")) {
            snsService.publish(arn, null, payload, "Scheduler", targetRegion);
            LOG.debugv("Scheduler delivered to SNS: {0}", arn);
        } else if (arn.contains(":ecs:") && target.getEcsParameters() != null) {
            deliverToEcsRunTask(target, targetRegion);
            LOG.debugv("Scheduler delivered to ECS RunTask: {0}", arn);
        } else if (isEventBridgePutEventsArn(arn)) {
            deliverToEventBridge(target, payload, targetRegion);
            LOG.debugv("Scheduler delivered to EventBridge: {0}", arn);
        } else {
            LOG.warnv("Scheduler: unsupported target ARN type: {0}", arn);
        }
    }

    private void deliverToEcsRunTask(Target target, String region) {
        EcsParameters ecs = target.getEcsParameters();
        ecsService.runTask(
                target.getArn(),
                ecs.getTaskDefinitionArn(),
                ecs.getTaskCount() != null ? ecs.getTaskCount() : 1,
                parseLaunchType(ecs.getLaunchType()),
                null,
                ecs.getGroup() != null ? ecs.getGroup() : "scheduler",
                List.of(),
                ecsNetworkConfiguration(ecs.getNetworkConfiguration()),
                region);
    }

    private static LaunchType parseLaunchType(String launchType) {
        if (launchType == null || launchType.isBlank()) {
            return null;
        }
        try {
            return LaunchType.valueOf(launchType);
        } catch (IllegalArgumentException e) {
            LOG.warnv("Scheduler: unsupported ECS LaunchType: {0}", launchType);
            return null;
        }
    }

    private static io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration ecsNetworkConfiguration(
            io.github.hectorvent.floci.services.scheduler.model.NetworkConfiguration source) {
        if (source == null || source.getAwsvpcConfiguration() == null) {
            return null;
        }
        io.github.hectorvent.floci.services.scheduler.model.AwsVpcConfiguration sourceVpc = source.getAwsvpcConfiguration();
        io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration targetVpc =
                new io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration();
        targetVpc.setSubnets(sourceVpc.getSubnets());
        targetVpc.setSecurityGroups(sourceVpc.getSecurityGroups());
        targetVpc.setAssignPublicIp(sourceVpc.getAssignPublicIp());

        io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration target =
                new io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration();
        target.setAwsvpcConfiguration(targetVpc);
        return target;
    }

    /**
     * Dispatches an EventBridge Scheduler universal target ({@code aws-sdk:<service>:<action>}),
     * reading the call parameters from the target's {@code Input} payload. Supports the
     * common {@code sns:publish} and {@code sqs:sendMessage} actions; other actions are
     * logged as unsupported.
     */
    private void invokeUniversalTarget(String serviceAction, String input, String region) {
        JsonNode params;
        try {
            params = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        } catch (Exception e) {
            LOG.warnv("Scheduler: universal target {0} has unparseable Input: {1}", serviceAction, e.getMessage());
            return;
        }
        switch (serviceAction) {
            case "sns:publish" -> {
                String topicArn = text(params, "TopicArn");
                String targetArn = text(params, "TargetArn");
                String message = text(params, "Message");
                String subject = text(params, "Subject");
                String messageGroupId = text(params, "MessageGroupId");
                String messageDeduplicationId = text(params, "MessageDeduplicationId");
                Map<String, MessageAttributeValue> messageAttributes = SnsMessageAttributes.parse(params.path("MessageAttributes"));
                String snsRegion = extractRegion(topicArn != null ? topicArn : targetArn, region);
                snsService.publish(topicArn, targetArn, null, message, subject, messageAttributes,
                        messageGroupId, messageDeduplicationId, snsRegion);
                LOG.debugv("Scheduler delivered to SNS (universal target): {0}", topicArn);
            }
            case "sqs:sendMessage" -> {
                String queueUrl = text(params, "QueueUrl");
                String body = text(params, "MessageBody");
                String messageGroupId = text(params, "MessageGroupId");
                String messageDeduplicationId = text(params, "MessageDeduplicationId");
                sqsService.sendMessage(queueUrl, body, 0, messageGroupId, messageDeduplicationId, region);
                LOG.debugv("Scheduler delivered to SQS (universal target): {0}", queueUrl);
            }
            default -> LOG.warnv("Scheduler: unsupported universal target action: {0}", serviceAction);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private boolean isEventBridgePutEventsArn(String arn) {
        return arn.contains(":events:") && arn.contains(":event-bus/");
    }

    private void deliverToEventBridge(Target target, String payload, String region) {
        String busArn = target.getArn();
        String busName = busArn.substring(busArn.indexOf(":event-bus/") + ":event-bus/".length());

        // AWS requires DetailType and Source on the target's EventBridgeParameters for
        // event-bus targets; honor them. Keep the historical defaults as a fallback for
        // schedules created without those parameters.
        EventBridgeParameters ebp = target.getEventBridgeParameters();
        String source = ebp != null && ebp.getSource() != null && !ebp.getSource().isBlank()
                ? ebp.getSource() : "aws.scheduler";
        String detailType = ebp != null && ebp.getDetailType() != null && !ebp.getDetailType().isBlank()
                ? ebp.getDetailType() : "Scheduled Event";

        Map<String, Object> entry = new HashMap<>();
        entry.put("EventBusName", busName);
        entry.put("Source", source);
        entry.put("DetailType", detailType);
        entry.put("Detail", asDetail(payload));
        eventBridgeService.putEvents(List.of(entry), region);
    }

    private String asDetail(String payload) {
        try {
            objectMapper.readTree(payload);
            return payload;
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of("payload", payload));
            } catch (Exception inner) {
                return "{}";
            }
        }
    }

    private static String extractRegion(String arn, String defaultRegion) {
        return AwsArnUtils.regionOrDefault(arn, defaultRegion);
    }
}
