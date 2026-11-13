package io.floci.aws.services.ecrpublic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/** JSON 1.1 dispatch for Amazon ECR Public ({@code X-Amz-Target: SpencerFrontendService.<Action>}). */
@ApplicationScoped
public class EcrPublicJsonHandler {

    /** ECR Public's request constraints; it reports a violation as InvalidParameterException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/ecrpublic-request-shapes.json",
            "ECR Public request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long").contains(type)
                            ? "value" : "size";
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

    private final EcrPublicService service;

    @Inject
    public EcrPublicJsonHandler(EcrPublicService service) {
        this.service = service;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return EcrPublicService.invalid("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "CreateRepository" -> service.createRepository(request);
            case "DescribeRepositories" -> service.describeRepositories(request);
            case "DeleteRepository" -> service.deleteRepository(request);
            case "GetRepositoryCatalogData" -> service.getRepositoryCatalogData(request);
            case "PutRepositoryCatalogData" -> service.putRepositoryCatalogData(request);
            case "SetRepositoryPolicy" -> service.setRepositoryPolicy(request);
            case "GetRepositoryPolicy" -> service.getRepositoryPolicy(request);
            case "DeleteRepositoryPolicy" -> service.deleteRepositoryPolicy(request);
            case "TagResource" -> service.tagResource(request);
            case "UntagResource" -> service.untagResource(request);
            case "ListTagsForResource" -> service.listTagsForResource(request);
            case "DescribeRegistries" -> service.describeRegistries(request);
            case "GetRegistryCatalogData" -> service.getRegistryCatalogData(request);
            case "PutRegistryCatalogData" -> service.putRegistryCatalogData(request);
            case "GetAuthorizationToken" -> service.getAuthorizationToken(request);
            case "DescribeImageTags" -> service.describeImageTags(request);
            case "DescribeImages" -> service.images(action, request);
            case "BatchDeleteImage" -> service.images(action, request);
            case "PutImage" -> service.images(action, request);
            case "BatchCheckLayerAvailability" -> service.images(action, request);
            case "InitiateLayerUpload" -> service.images(action, request);
            case "UploadLayerPart" -> service.images(action, request);
            case "CompleteLayerUpload" -> service.images(action, request);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: SpencerFrontendService." + action)).build();
        }
        return Response.ok(result).build();
    }
}
