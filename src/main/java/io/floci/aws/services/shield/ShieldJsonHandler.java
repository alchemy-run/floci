package io.floci.aws.services.shield;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/** JSON 1.1 dispatch for AWS Shield ({@code X-Amz-Target: AWSShield_20160616.<Action>}). */
@ApplicationScoped
public class ShieldJsonHandler {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/shield-request-shapes.json", "Shield request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long", "float", "double")
                            .contains(type) ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private final ShieldService service;

    @Inject
    public ShieldJsonHandler(ShieldService service) {
        this.service = service;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return new AwsException("ValidationException", "1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint, 400);
    }

    public Response handle(String action, JsonNode request, String region) {
        service.checkGroup(action, request);
        SHAPES.check(action, request);
        return Response.ok(service.handle(action, request, region)).build();
    }
}
