package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.inspector2.model.AccountStatus;
import io.github.hectorvent.floci.services.inspector2.model.Inspector2Account;
import io.github.hectorvent.floci.services.inspector2.model.Inspector2Filter;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Amazon Inspector2 restJson1.
 *
 * <p>{@link Inspector2RoutingFilter} prefixes SigV4 {@code inspector2} requests
 * so literal {@code /members}, {@code /findings}, {@code /coverage} and
 * {@code /encryptionkey} paths do not collide with S3's {@code /{bucket}}
 * catch-all. Tag APIs share {@code /tags/{arn}} and are dispatched by
 * {@code SharedTagsController}.
 */
@Path(Inspector2RoutingFilter.INTERNAL_PREFIX)
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

    @POST
    @Path("/findings/list")
    @Consumes(MediaType.WILDCARD)
    public Response listFindings(@Context HttpHeaders headers, String body) {
        return emptyList("findings", body);
    }

    @POST
    @Path("/coverage/list")
    @Consumes(MediaType.WILDCARD)
    public Response listCoverage(@Context HttpHeaders headers, String body) {
        return emptyList("coveredResources", body);
    }

    @POST
    @Path("/vulnerabilities/search")
    @Consumes(MediaType.WILDCARD)
    public Response searchVulnerabilities(@Context HttpHeaders headers, String body) {
        List<Map<String, Object>> vulnerabilities = service.searchVulnerabilities(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("vulnerabilities", objectMapper.valueToTree(vulnerabilities));
        return Response.ok(response).build();
    }

    @POST
    @Path("/usage/list")
    @Consumes(MediaType.WILDCARD)
    public Response listUsageTotals(@Context HttpHeaders headers, String body) {
        return emptyList("totals", body);
    }

    @POST
    @Path("/accountpermissions/list")
    @Consumes(MediaType.WILDCARD)
    public Response listAccountPermissions(@Context HttpHeaders headers, String body) {
        return emptyList("permissions", body);
    }

    @POST
    @Path("/freetrialinfo/batchget")
    @Consumes(MediaType.WILDCARD)
    public Response batchGetFreeTrialInfo(@Context HttpHeaders headers, String body) {
        List<String> accountIds = service.freeTrialAccountIds(parse(body));
        List<Map<String, Object>> info = service.freeTrialInfo();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accounts = response.putArray("accounts");
        for (String accountId : accountIds) {
            ObjectNode account = accounts.addObject();
            account.put("accountId", accountId);
            account.set("freeTrialInfo", objectMapper.valueToTree(info));
        }
        response.putArray("failedAccounts");
        return Response.ok(response).build();
    }

    @POST
    @Path("/configuration/get")
    @Consumes(MediaType.WILDCARD)
    public Response getConfiguration(@Context HttpHeaders headers, String body) {
        parse(body);
        Inspector2Account account = service.configuration();
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("ecrConfiguration")
                .putObject("rescanDurationState")
                .put("rescanDuration", account.getEcrRescanDuration())
                .put("status", "SUCCESS");
        response.putObject("ec2Configuration")
                .putObject("scanModeState")
                .put("scanMode", account.getEc2ScanMode())
                .put("scanModeStatus", "SUCCESS");
        return Response.ok(response).build();
    }

    @POST
    @Path("/ec2deepinspectionconfiguration/get")
    @Consumes(MediaType.WILDCARD)
    public Response getEc2DeepInspectionConfiguration(@Context HttpHeaders headers, String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", service.configuration().getDeepInspectionStatus());
        response.putArray("packagePaths");
        response.putArray("orgPackagePaths");
        return Response.ok(response).build();
    }

    @GET
    @Path("/encryptionkey/get")
    @Consumes(MediaType.WILDCARD)
    public Response getEncryptionKey(
            @Context HttpHeaders headers,
            @QueryParam("scanType") String scanType,
            @QueryParam("resourceType") String resourceType) {
        String kmsKeyId = service.getEncryptionKey(scanType, resourceType);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("kmsKeyId", kmsKeyId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/cis/scan/list")
    @Consumes(MediaType.WILDCARD)
    public Response listCisScans(@Context HttpHeaders headers, String body) {
        return emptyList("scans", body);
    }

    @POST
    @Path("/members/list")
    @Consumes(MediaType.WILDCARD)
    public Response listMembers(@Context HttpHeaders headers, String body) {
        return emptyList("members", body);
    }

    @POST
    @Path("/organizationconfiguration/describe")
    @Consumes(MediaType.WILDCARD)
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        parse(body);
        Inspector2Account account = service.configuration();
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode autoEnable = response.putObject("autoEnable");
        autoEnable.put("ec2", false);
        autoEnable.put("ecr", false);
        autoEnable.put("lambda", false);
        autoEnable.put("lambdaCode", false);
        autoEnable.put("codeRepository", false);
        response.put("maxAccountLimitReached", account.isMaxAccountLimitReached());
        return Response.ok(response).build();
    }

    @POST
    @Path("/delegatedadminaccounts/get")
    @Consumes(MediaType.WILDCARD)
    public Response getDelegatedAdminAccount(@Context HttpHeaders headers, String body) {
        parse(body);
        service.requireDelegatedAdminAccount();
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("delegatedAdmin").put("accountId", service.configuration().getDelegatedAdminAccountId());
        return Response.ok(response).build();
    }

    @POST
    @Path("/reporting/status/get")
    @Consumes(MediaType.WILDCARD)
    public Response getFindingsReportStatus(@Context HttpHeaders headers, String body) {
        service.requireFindingsReport(parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response emptyList(String field, String body) {
        service.requireBody(parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(field);
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
