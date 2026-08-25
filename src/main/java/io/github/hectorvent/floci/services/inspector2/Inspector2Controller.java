package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.inspector2.model.AccountStatus;
import io.github.hectorvent.floci.services.inspector2.model.Inspector2Filter;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Amazon Inspector2 restJson1.
 *
 * <p>Literal {@code /status/batch/get}, {@code /filters/*}, {@code /enable} and
 * {@code /disable} paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Inspector2Controller {

    private final Inspector2Service service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public Inspector2Controller(
            Inspector2Service service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/status/batch/get")
    @Consumes(MediaType.WILDCARD)
    public Response batchGetAccountStatus(@Context HttpHeaders headers, String body) {
        List<AccountStatus> accounts =
                service.batchGetAccountStatus(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("accounts");
        for (AccountStatus account : accounts) {
            list.add(toAccountState(account));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/enable")
    @Consumes(MediaType.WILDCARD)
    public Response enable(@Context HttpHeaders headers, String body) {
        List<AccountStatus> accounts = service.enable(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(accountsResponse(accounts)).build();
    }

    @POST
    @Path("/disable")
    @Consumes(MediaType.WILDCARD)
    public Response disable(@Context HttpHeaders headers, String body) {
        List<AccountStatus> accounts = service.disable(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(accountsResponse(accounts)).build();
    }

    @POST
    @Path("/filters/list")
    @Consumes(MediaType.WILDCARD)
    public Response listFilters(@Context HttpHeaders headers, String body) {
        List<Inspector2Filter> filters = service.listFilters(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("filters");
        for (Inspector2Filter filter : filters) {
            list.add(toFilter(filter));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/filters/create")
    @Consumes(MediaType.WILDCARD)
    public Response createFilter(@Context HttpHeaders headers, String body) {
        Inspector2Filter filter = service.createFilter(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", filter.getArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/filters/update")
    @Consumes(MediaType.WILDCARD)
    public Response updateFilter(@Context HttpHeaders headers, String body) {
        Inspector2Filter filter = service.updateFilter(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", filter.getArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/filters/delete")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFilter(@Context HttpHeaders headers, String body) {
        Inspector2Filter filter = service.deleteFilter(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", filter.getArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/cis/scan-configuration/list")
    @Consumes(MediaType.WILDCARD)
    public Response listCisScanConfigurations(@Context HttpHeaders headers, String body) {
        parse(body);
        service.requireCisApis(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("scanConfigurations");
        return Response.ok(response).build();
    }

    private ObjectNode accountsResponse(List<AccountStatus> accounts) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("accounts");
        for (AccountStatus account : accounts) {
            list.add(toAccount(account));
        }
        return response;
    }

    private ObjectNode toAccountState(AccountStatus account) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("accountId", account.getAccountId());
        node.putObject("state").put("status", Inspector2Service.overallStatus(account));
        ObjectNode resourceState = node.putObject("resourceState");
        for (String type : Inspector2Service.RESOURCE_TYPES) {
            String status = account.getResourceStatus().getOrDefault(type, Inspector2Service.STATUS_DISABLED);
            resourceState.putObject(Inspector2Service.jsonKey(type)).put("status", status);
        }
        return node;
    }

    private ObjectNode toFilter(Inspector2Filter filter) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", filter.getArn());
        node.put("ownerId", filter.getOwnerId());
        node.put("name", filter.getName());
        node.set("criteria", objectMapper.valueToTree(filter.getCriteria()));
        node.put("action", filter.getAction());
        node.put("createdAt", filter.getCreatedAt());
        node.put("updatedAt", filter.getUpdatedAt());
        if (filter.getDescription() != null) {
            node.put("description", filter.getDescription());
        }
        if (filter.getReason() != null) {
            node.put("reason", filter.getReason());
        }
        ObjectNode tags = node.putObject("tags");
        filter.getTags().forEach(tags::put);
        return node;
    }

    private ObjectNode toAccount(AccountStatus account) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("accountId", account.getAccountId());
        node.put("status", Inspector2Service.overallStatus(account));
        ObjectNode resourceStatus = node.putObject("resourceStatus");
        for (String type : Inspector2Service.RESOURCE_TYPES) {
            resourceStatus.put(
                    Inspector2Service.jsonKey(type),
                    account.getResourceStatus().getOrDefault(type, Inspector2Service.STATUS_DISABLED));
        }
        return node;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw Inspector2Service.validation("Request body must be a JSON object.", "fieldValidationFailed");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw Inspector2Service.validation("Request body is not valid JSON.", "cannotParse");
        }
    }
}
