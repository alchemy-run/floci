package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Tagging for AgentCore resources, dispatched from the shared {@code /tags/{resourceArn}}
 * route. All AgentCore resources share the ARN service segment {@code bedrock-agentcore},
 * so this handler further dispatches by the resource type in the ARN. AWS supports tagging
 * for runtimes, gateways, memories, custom browsers, browser profiles, and custom code interpreters.
 */
@ApplicationScoped
public class BedrockAgentCoreTagHandler implements TagHandler {

    private final BedrockAgentCoreControlService runtimeService;
    private final BedrockAgentCoreGatewayService gatewayService;
    private final BedrockAgentCoreMemoryService memoryService;
    private final BedrockAgentCoreToolsService toolsService;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreTagHandler(BedrockAgentCoreControlService runtimeService,
                                      BedrockAgentCoreGatewayService gatewayService,
                                      BedrockAgentCoreMemoryService memoryService,
                                      BedrockAgentCoreToolsService toolsService,
                                      RegionResolver regionResolver) {
        this.runtimeService = runtimeService;
        this.gatewayService = gatewayService;
        this.memoryService = memoryService;
        this.toolsService = toolsService;
        this.regionResolver = regionResolver;
    }

    @Override
    public String serviceKey() {
        return "bedrock-agentcore";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.getTagsByArn(region, arn);
            case MEMORY -> memoryService.getTagsByArn(region, arn);
            case RUNTIME -> runtimeService.getTagsByArn(region, arn);
            case TOOL -> toolsService.getTagsByArn(region, arn);
        };
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.tagByArn(region, arn, tags);
            case MEMORY -> memoryService.tagByArn(region, arn, tags);
            case RUNTIME -> runtimeService.tagByArn(region, arn, tags);
            case TOOL -> toolsService.tagByArn(region, arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        switch (kind(region, arn)) {
            case GATEWAY -> gatewayService.untagByArn(region, arn, tagKeys);
            case MEMORY -> memoryService.untagByArn(region, arn, tagKeys);
            case RUNTIME -> runtimeService.untagByArn(region, arn, tagKeys);
            case TOOL -> toolsService.untagByArn(region, arn, tagKeys);
        }
    }

    private enum ResourceKind { RUNTIME, GATEWAY, MEMORY, TOOL }

    private ResourceKind kind(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid AgentCore resource ARN: " + arn, 400);
        }
        if (!serviceKey().equals(parsed.service()) || !parsed.resource().contains("/")) {
            throw new AwsException("ValidationException", "Invalid AgentCore resource ARN: " + arn, 400);
        }
        if (!region.equals(parsed.region()) || !regionResolver.getAccountId().equals(parsed.accountId())
                || !arn.equals(regionResolver.buildArn(serviceKey(), region, parsed.resource()))) {
            throw new AwsException("ResourceNotFoundException", "AgentCore resource not found: " + arn, 404);
        }
        ResourceKind kind = switch (parsed.resource().substring(0, parsed.resource().indexOf('/'))) {
            case "gateway" -> ResourceKind.GATEWAY;
            case "agent" -> ResourceKind.RUNTIME;
            case "memory" -> ResourceKind.MEMORY;
            case "browser-custom", "browser-profile", "code-interpreter-custom" -> ResourceKind.TOOL;
            default -> throw new AwsException("ValidationException",
                    "Tagging is not supported for this AgentCore resource: " + arn, 400);
        };
        // Legacy resource stores retain their configured-account boundary.
        if (kind != ResourceKind.TOOL && !regionResolver.getDefaultAccountId().equals(parsed.accountId())) {
            throw new AwsException("ResourceNotFoundException", "AgentCore resource not found: " + arn, 404);
        }
        return kind;
    }
}
