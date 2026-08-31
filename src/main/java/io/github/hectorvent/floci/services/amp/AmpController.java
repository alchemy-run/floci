package io.github.hectorvent.floci.services.amp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.amp.model.Scraper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Managed Service for Prometheus (Smithy restJson1) scraper APIs.
 *
 * <p>Literal {@code /scrapers} and {@code /scraperconfiguration} paths take JAX-RS
 * precedence over S3's {@code /{bucket}} catch-all. Requests are signed as {@code aps}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmpController {

    private final AmpService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AmpController(AmpService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/scraperconfiguration")
    @Consumes(MediaType.WILDCARD)
    public Response getDefaultScraperConfiguration() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("configuration", service.defaultScraperConfiguration());
        return Response.ok(response).build();
    }

    @POST
    @Path("/scrapers")
    public Response createScraper(@Context HttpHeaders headers, String body) {
        Scraper scraper = service.createScraper(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(service.createResponse(scraper)).build();
    }

    @GET
    @Path("/scrapers")
    @Consumes(MediaType.WILDCARD)
    public Response listScrapers(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode scrapers = response.putArray("scrapers");
        for (Scraper scraper : service.listScrapers(regionResolver.resolveRegion(headers))) {
            scrapers.add(service.toSummary(scraper));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/scrapers/{scraperId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeScraper(@Context HttpHeaders headers, @PathParam("scraperId") String scraperId) {
        Scraper scraper = service.describeScraper(regionResolver.resolveRegion(headers), scraperId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("scraper", service.toDescription(scraper));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/scrapers/{scraperId}")
    public Response updateScraper(
            @Context HttpHeaders headers, @PathParam("scraperId") String scraperId, String body) {
        Scraper scraper = service.updateScraper(regionResolver.resolveRegion(headers), scraperId, parse(body));
        return Response.ok(service.createResponse(scraper)).build();
    }

    @DELETE
    @Path("/scrapers/{scraperId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteScraper(@Context HttpHeaders headers, @PathParam("scraperId") String scraperId) {
        Scraper scraper = service.deleteScraper(regionResolver.resolveRegion(headers), scraperId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("scraperId", scraper.getScraperId());
        ObjectNode status = response.putObject("status");
        status.put("statusCode", scraper.getStatusCode());
        return Response.ok(response).build();
    }

    @GET
    @Path("/scrapers/{scraperId}/logging-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response describeLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("scraperId") String scraperId) {
        Scraper scraper = service.describeScraperLoggingConfiguration(
                regionResolver.resolveRegion(headers), scraperId);
        return Response.ok(service.loggingDescription(scraper)).build();
    }

    @PUT
    @Path("/scrapers/{scraperId}/logging-configuration")
    public Response updateLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("scraperId") String scraperId, String body) {
        Scraper scraper = service.updateScraperLoggingConfiguration(
                regionResolver.resolveRegion(headers), scraperId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode status = response.putObject("status");
        status.put("statusCode", scraper.getLoggingStatusCode());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/scrapers/{scraperId}/logging-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLoggingConfiguration(
            @Context HttpHeaders headers, @PathParam("scraperId") String scraperId) {
        service.deleteScraperLoggingConfiguration(regionResolver.resolveRegion(headers), scraperId);
        return Response.ok(objectMapper.createObjectNode()).build();
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
}
