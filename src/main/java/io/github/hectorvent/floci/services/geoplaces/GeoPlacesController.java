package io.github.hectorvent.floci.services.geoplaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Amazon Location Service Places API v2 (Smithy restJson1).
 *
 * <p>Literal {@code /v2/...} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} and {@code /{bucket}/{key}} templates, so these routes
 * win with no extra routing wiring. SigV4 scope is {@code geo-places};
 * the default hostname is {@code places.geo.<region>.amazonaws.com}.
 *
 * @see <a href="https://docs.aws.amazon.com/location/latest/APIReference/API_Operations_Amazon_Location_Service_Places_V2.html">Places V2 API</a>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeoPlacesController {

    private static final Logger LOG = Logger.getLogger(GeoPlacesController.class);

    private final GeoPlacesService service;
    private final ObjectMapper objectMapper;

    @Inject
    public GeoPlacesController(GeoPlacesService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/v2/autocomplete")
    public Response autocomplete(String body) {
        LOG.debug("GeoPlaces Autocomplete");
        return priced(service.autocomplete(parse(body)));
    }

    @POST
    @Path("/v2/geocode")
    public Response geocode(String body) {
        LOG.debug("GeoPlaces Geocode");
        return priced(service.geocode(parse(body)));
    }

    @GET
    @Path("/v2/place/{PlaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response getPlace(@PathParam("PlaceId") String placeId) {
        LOG.debugv("GeoPlaces GetPlace {0}", placeId);
        return priced(service.getPlace(placeId));
    }

    @POST
    @Path("/v2/reverse-geocode")
    public Response reverseGeocode(String body) {
        LOG.debug("GeoPlaces ReverseGeocode");
        return priced(service.reverseGeocode(parse(body)));
    }

    @POST
    @Path("/v2/search-nearby")
    public Response searchNearby(String body) {
        LOG.debug("GeoPlaces SearchNearby");
        return priced(service.searchNearby(parse(body)));
    }

    @POST
    @Path("/v2/search-text")
    public Response searchText(String body) {
        LOG.debug("GeoPlaces SearchText");
        return priced(service.searchText(parse(body)));
    }

    @POST
    @Path("/v2/suggest")
    public Response suggest(String body) {
        LOG.debug("GeoPlaces Suggest");
        return priced(service.suggest(parse(body)));
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private Response priced(ObjectNode body) {
        return Response.ok(body)
                .header("x-amz-geo-pricing-bucket", GeoPlacesService.PRICING_BUCKET)
                .build();
    }
}
