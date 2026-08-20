package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.microvm.MicrovmImageService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Lambda tag endpoints — use the /2017-03-31 API version prefix.
 *
 * TagResource:   POST   /2017-03-31/tags/{ARN}
 * ListTags:      GET    /2017-03-31/tags/{ARN}
 * UntagResource: DELETE /2017-03-31/tags/{ARN}?tagKeys=...
 */
@Path("/2017-03-31")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaTagController {

    private final LambdaService lambdaService;
    private final MicrovmImageService microvmImageService;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaTagController(LambdaService lambdaService, MicrovmImageService microvmImageService,
                               ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.microvmImageService = microvmImageService;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/tags/{arn}")
    public Response listTags(@PathParam("arn") String arn) {
        Map<String, String> tags = microvmImageService.isMicrovmImageArn(arn)
                ? microvmImageService.listTags(regionFromArn(arn), arn)
                : lambdaService.listTags(arn);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode tagsNode = root.putObject("Tags");
        tags.forEach(tagsNode::put);
        return Response.ok(root).build();
    }

    @POST
    @Path("/tags/{arn}")
    public Response tagResource(@PathParam("arn") String arn, String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> tags = (Map<String, String>) request.get("Tags");
            if (tags == null) {
                throw new AwsException("InvalidParameterValueException", "Tags is required", 400);
            }
            if (microvmImageService.isMicrovmImageArn(arn)) {
                microvmImageService.tagResource(regionFromArn(arn), arn, tags);
            } else {
                lambdaService.tagResource(arn, tags);
            }
            return Response.noContent().build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/tags/{arn}")
    public Response untagResource(@PathParam("arn") String arn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        if (microvmImageService.isMicrovmImageArn(arn)) {
            microvmImageService.untagResource(regionFromArn(arn), arn, tagKeys);
        } else {
            lambdaService.untagResource(arn, tagKeys);
        }
        return Response.noContent().build();
    }

    /** {@code arn:aws:lambda:us-east-1:...} → {@code us-east-1}. */
    private static String regionFromArn(String arn) {
        String[] parts = arn.split(":");
        if (parts.length < 4 || parts[3].isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Invalid ARN: " + arn, 400);
        }
        return parts[3];
    }
}
