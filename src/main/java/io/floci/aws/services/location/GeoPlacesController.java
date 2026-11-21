package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;

/** Amazon Location's Places API (signing name {@code geo-places}) over {@link LocationPlaces}. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class GeoPlacesController {
    private final LocationPlaces places;
    private final ObjectMapper mapper;

    @Inject
    public GeoPlacesController(LocationPlaces places, ObjectMapper mapper) {
        this.places = places;
        this.mapper = mapper;
    }

    @POST
    @Path("/v2/autocomplete")
    public Response autocomplete(String body) {
        return priced(places.autocomplete(check("Autocomplete", body)));
    }

    @POST
    @Path("/v2/geocode")
    public Response geocode(String body) {
        return priced(places.geocode(check("Geocode", body)));
    }

    @GET
    @Path("/v2/place/{PlaceId}")
    public Response getPlace(@PathParam("PlaceId") String placeId) {
        LocationRequestShapes.check("geo-places", "GetPlace",
                JsonNodeFactory.instance.objectNode().put("PlaceId", placeId));
        return priced(places.getPlaceV2(placeId));
    }

    @POST
    @Path("/v2/reverse-geocode")
    public Response reverseGeocode(String body) {
        return priced(places.reverseGeocode(check("ReverseGeocode", body)));
    }

    @POST
    @Path("/v2/search-nearby")
    public Response searchNearby(String body) {
        return priced(places.searchNearby(check("SearchNearby", body)));
    }

    @POST
    @Path("/v2/search-text")
    public Response searchText(String body) {
        return priced(places.searchText(check("SearchText", body)));
    }

    @POST
    @Path("/v2/suggest")
    public Response suggest(String body) {
        return priced(places.suggest(check("Suggest", body)));
    }

    static Response priced(Object body) {
        return Response.ok(body).header("x-amz-geo-pricing-bucket", "Bucket1").build();
    }

    private JsonNode check(String operation, String body) {
        JsonNode request;
        try {
            request = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw LocationService.validation("The request body must be a JSON object.");
        }
        if (request == null || !request.isObject()) {
            throw LocationService.validation("The request body must be a JSON object.");
        }
        LocationRequestShapes.check("geo-places", operation, request);
        return request;
    }
}
