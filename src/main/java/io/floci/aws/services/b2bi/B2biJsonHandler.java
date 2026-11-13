package io.floci.aws.services.b2bi;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** AWS B2B Data Interchange JSON 1.0 handler, under the {@code B2BI.} target prefix. */
@ApplicationScoped
public class B2biJsonHandler {
    private final B2biService service;

    @Inject
    public B2biJsonHandler(B2biService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            Object body = switch (action) {
                case "CreateCapability" -> service.createCapability(request, region);
                case "CreatePartnership" -> service.createPartnership(request, region);
                case "CreateProfile" -> service.createProfile(request, region);
                case "CreateStarterMappingTemplate" -> service.createStarterTemplate(request, region);
                case "CreateTransformer" -> service.createTransformer(request, region);
                case "DeleteCapability" -> service.deleteCapability(request, region);
                case "DeletePartnership" -> service.deletePartnership(request, region);
                case "DeleteProfile" -> service.deleteProfile(request, region);
                case "DeleteTransformer" -> service.deleteTransformer(request, region);
                case "GenerateMapping" -> service.generateMapping(request, region);
                case "GetCapability" -> service.getCapability(request, region);
                case "GetPartnership" -> service.getPartnership(request, region);
                case "GetProfile" -> service.getProfile(request, region);
                case "GetTransformer" -> service.getTransformer(request, region);
                case "GetTransformerJob" -> service.getJob(request, region);
                case "ListCapabilities" -> service.listCapabilities(request, region);
                case "ListPartnerships" -> service.listPartnerships(request, region);
                case "ListProfiles" -> service.listProfiles(request, region);
                case "ListTagsForResource" -> service.listTags(request, region);
                case "ListTransformers" -> service.listTransformers(request, region);
                case "StartTransformerJob" -> service.startJob(request, region);
                case "TagResource" -> service.tag(request, region);
                case "TestConversion" -> service.testConversion(request, region);
                case "TestMapping" -> service.testMapping(request, region);
                case "TestParsing" -> service.testParsing(request, region);
                case "UntagResource" -> service.untag(request, region);
                case "UpdateCapability" -> service.updateCapability(request, region);
                case "UpdatePartnership" -> service.updatePartnership(request, region);
                case "UpdateProfile" -> service.updateProfile(request, region);
                case "UpdateTransformer" -> service.updateTransformer(request, region);
                default -> null;
            };
            if (body == null) {
                return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                        "Operation " + action + " is not supported.")).build();
            }
            return Response.ok(body).build();
        } catch (AwsException e) {
            // B2BI's model gives its errors no HTTP status, so every client error answers 400.
            return JsonErrorResponseUtils.createErrorResponse(e.getHttpStatus() < 500 && e.getHttpStatus() != 400
                    ? new AwsException(e.getErrorCode(), e.getMessage(), 400) : e);
        }
    }
}
