package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Control Tower {@code TagResource}, {@code UntagResource} and {@code ListTagsForResource} on
 * {@code /tags/{resourceArn}} for enabled controls, enabled baselines and landing zones.
 */
@ApplicationScoped
public class ControlTowerTagHandler implements TagHandler {
    private final ControlTowerControlService controlService;
    private final ControlTowerService service;
    private final RequestContext requestContext;

    @Inject
    public ControlTowerTagHandler(ControlTowerControlService controlService, ControlTowerService service,
                                  RequestContext requestContext) {
        this.controlService = controlService;
        this.service = service;
        this.requestContext = requestContext;
    }

    @Override
    public String serviceKey() {
        return "controltower";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return switch (resourceType(arn)) {
            case "enabledcontrol" -> controlService.listTags(region, arn);
            default -> service.listTags(requestContext.getAccountId(), region, arn);
        };
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        switch (resourceType(arn)) {
            case "enabledcontrol" -> controlService.tagResource(region, arn, tags);
            default -> service.tagResource(requestContext.getAccountId(), region, arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        switch (resourceType(arn)) {
            case "enabledcontrol" -> controlService.untagResource(region, arn, tagKeys);
            default -> service.untagResource(requestContext.getAccountId(), region, arn, tagKeys);
        }
    }

    private static String resourceType(String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":", 6);
        if (parts.length == 6) {
            String resource = parts[5];
            int slash = resource.indexOf('/');
            String type = slash < 0 ? resource : resource.substring(0, slash);
            if (List.of("enabledcontrol", "enabledbaseline", "landingzone").contains(type)) {
                return type;
            }
        }
        throw new AwsException("ResourceNotFoundException",
                "The request references a resource that does not exist.", 404);
    }
}
