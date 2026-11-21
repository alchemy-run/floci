package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * The Data Transformation Agent's synchronous conversion, {@code POST /transform-data} at
 * {@code datatransformation.healthlake.<region>}: one C-CDA document, or a set of CSV tables, converted with a
 * profile's latest published version and returned as a FHIR Bundle.
 */
@Path(HealthLakeTransformDataController.PATH)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class HealthLakeTransformDataController {

    static final String PATH = "/_floci/healthlake-transform-data";

    private final HealthLakeTransformService transform;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public HealthLakeTransformDataController(HealthLakeTransformService transform, RegionResolver regions,
                                             ObjectMapper mapper) {
        this.transform = transform;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    public Response transformData(String body) {
        try {
            JsonNode input;
            try {
                input = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            } catch (JsonProcessingException e) {
                throw HealthLakeService.validation("The request body is not valid JSON: " + e.getOriginalMessage());
            }
            return Response.ok(transform.transformData(input, regions.getRegion()).toString()).build();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("message", e.getMessage());
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
    }

    /** Routes {@code POST /transform-data} signed for {@code healthlake} to the conversion. */
    @Provider
    @PreMatching
    @Priority(4975)
    public static class Routing implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext context) {
            String path = context.getUriInfo().getRequestUri().getRawPath();
            boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                    .map("healthlake"::equals).orElse(false);
            if (signed && "/transform-data".equals(path) && "POST".equals(context.getMethod())) {
                context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PATH)
                        .buildFromEncoded());
            }
        }
    }
}
