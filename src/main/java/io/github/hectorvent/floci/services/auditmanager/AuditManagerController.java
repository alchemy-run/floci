package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.auditmanager.model.Assessment;
import io.github.hectorvent.floci.services.auditmanager.model.Control;
import io.github.hectorvent.floci.services.auditmanager.model.Framework;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * AWS Audit Manager restJson1.
 *
 * <p>Literal {@code /account/status}, {@code /controls}, {@code /insights}, {@code /services}
 * and {@code /assessments} paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all.
 * Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditManagerController {

    private final AuditManagerService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AuditManagerController(
            AuditManagerService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/account/status")
    @Consumes(MediaType.WILDCARD)
    public Response getAccountStatus() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", service.getAccountStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/account/registerAccount")
    @Consumes(MediaType.WILDCARD)
    public Response registerAccount(String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", service.registerAccount());
        return Response.ok(response).build();
    }

    @POST
    @Path("/account/deregisterAccount")
    @Consumes(MediaType.WILDCARD)
    public Response deregisterAccount(String body) {
        parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", service.deregisterAccount());
        return Response.ok(response).build();
    }

    @GET
    @Path("/services")
    @Consumes(MediaType.WILDCARD)
    public Response getServicesInScope() {
        return Response.ok(service.getServicesInScope()).build();
    }

    @GET
    @Path("/insights")
    @Consumes(MediaType.WILDCARD)
    public Response getInsights(@Context HttpHeaders headers) {
        return Response.ok(service.getInsights(regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path("/insights/control-domains")
    @Consumes(MediaType.WILDCARD)
    public Response listControlDomainInsights() {
        return Response.ok(service.listControlDomainInsights()).build();
    }

    @GET
    @Path("/insights/controls")
    @Consumes(MediaType.WILDCARD)
    public Response listControlInsightsByControlDomain(@QueryParam("controlDomainId") String controlDomainId) {
        return Response.ok(service.listControlInsightsByControlDomain(controlDomainId)).build();
    }

    @GET
    @Path("/delegations")
    @Consumes(MediaType.WILDCARD)
    public Response getDelegations() {
        return Response.ok(service.getDelegations()).build();
    }

    @GET
    @Path("/evidenceFileUploadUrl")
    @Consumes(MediaType.WILDCARD)
    public Response getEvidenceFileUploadUrl(@QueryParam("fileName") String fileName) {
        return Response.ok(service.getEvidenceFileUploadUrl(fileName, "http://localhost:4566")).build();
    }

    @GET
    @Path("/assessmentReports")
    @Consumes(MediaType.WILDCARD)
    public Response listAssessmentReports() {
        return Response.ok(service.listAssessmentReports()).build();
    }

    @POST
    @Path("/assessmentReports/integrity")
    public Response validateAssessmentReportIntegrity(String body) {
        service.validateAssessmentReportIntegrity(parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/dataSourceKeywords")
    @Consumes(MediaType.WILDCARD)
    public Response listKeywordsForDataSource(@QueryParam("source") String source) {
        return Response.ok(service.listKeywordsForDataSource(source)).build();
    }

    @GET
    @Path("/notifications")
    @Consumes(MediaType.WILDCARD)
    public Response listNotifications() {
        return Response.ok(service.listNotifications()).build();
    }

    @POST
    @Path("/controls")
    public Response createControl(@Context HttpHeaders headers, String body) {
        Control control = service.createControl(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("control", toControl(control));
        return Response.ok(response).build();
    }

    @GET
    @Path("/controls")
    @Consumes(MediaType.WILDCARD)
    public Response listControls(
            @Context HttpHeaders headers,
            @QueryParam("controlType") String controlType,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AuditManagerService.Page<Control> page = service.listControls(
                regionResolver.resolveRegion(headers), controlType, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("controlMetadataList");
        for (Control control : page.items()) {
            list.add(toControlMetadata(control));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/controls/{controlId}")
    @Consumes(MediaType.WILDCARD)
    public Response getControl(@Context HttpHeaders headers, @PathParam("controlId") String controlId) {
        Control control = service.getControl(regionResolver.resolveRegion(headers), controlId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("control", toControl(control));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/controls/{controlId}")
    public Response updateControl(
            @Context HttpHeaders headers, @PathParam("controlId") String controlId, String body) {
        Control control = service.updateControl(regionResolver.resolveRegion(headers), controlId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("control", toControl(control));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/controls/{controlId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteControl(@Context HttpHeaders headers, @PathParam("controlId") String controlId) {
        service.deleteControl(regionResolver.resolveRegion(headers), controlId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/assessmentFrameworks")
    public Response createFramework(@Context HttpHeaders headers, String body) {
        Framework framework = service.createFramework(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("framework", toFramework(framework));
        return Response.ok(response).build();
    }

    @GET
    @Path("/assessmentFrameworks")
    @Consumes(MediaType.WILDCARD)
    public Response listFrameworks(
            @Context HttpHeaders headers,
            @QueryParam("frameworkType") String frameworkType,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AuditManagerService.Page<Framework> page = service.listFrameworks(
                regionResolver.resolveRegion(headers), frameworkType, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("frameworkMetadataList");
        for (Framework framework : page.items()) {
            list.add(toFrameworkMetadata(framework));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/assessmentFrameworks/{frameworkId}")
    @Consumes(MediaType.WILDCARD)
    public Response getFramework(@Context HttpHeaders headers, @PathParam("frameworkId") String frameworkId) {
        Framework framework = service.getFramework(regionResolver.resolveRegion(headers), frameworkId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("framework", toFramework(framework));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/assessmentFrameworks/{frameworkId}")
    public Response updateFramework(
            @Context HttpHeaders headers, @PathParam("frameworkId") String frameworkId, String body) {
        Framework framework = service.updateFramework(
                regionResolver.resolveRegion(headers), frameworkId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("framework", toFramework(framework));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/assessmentFrameworks/{frameworkId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFramework(
            @Context HttpHeaders headers, @PathParam("frameworkId") String frameworkId) {
        service.deleteFramework(regionResolver.resolveRegion(headers), frameworkId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/assessments")
    public Response createAssessment(@Context HttpHeaders headers, String body) {
        Assessment assessment = service.createAssessment(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("assessment", toAssessment(assessment));
        return Response.ok(response).build();
    }

    @GET
    @Path("/assessments")
    @Consumes(MediaType.WILDCARD)
    public Response listAssessments(
            @Context HttpHeaders headers,
            @QueryParam("status") String status,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AuditManagerService.Page<Assessment> page = service.listAssessments(
                regionResolver.resolveRegion(headers), status, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("assessmentMetadata");
        for (Assessment assessment : page.items()) {
            list.add(toAssessmentMetadataItem(assessment));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/assessments/{assessmentId}")
    @Consumes(MediaType.WILDCARD)
    public Response getAssessment(
            @Context HttpHeaders headers, @PathParam("assessmentId") String assessmentId) {
        Assessment assessment = service.getAssessment(regionResolver.resolveRegion(headers), assessmentId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("assessment", toAssessment(assessment));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/assessments/{assessmentId}")
    public Response updateAssessment(
            @Context HttpHeaders headers, @PathParam("assessmentId") String assessmentId, String body) {
        Assessment assessment = service.updateAssessment(
                regionResolver.resolveRegion(headers), assessmentId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("assessment", toAssessment(assessment));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/assessments/{assessmentId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAssessment(
            @Context HttpHeaders headers, @PathParam("assessmentId") String assessmentId) {
        service.deleteAssessment(regionResolver.resolveRegion(headers), assessmentId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw AuditManagerService.validation("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw AuditManagerService.validation("Request body is not valid JSON.");
        }
    }

    private ObjectNode toControl(Control control) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", control.getArn());
        node.put("id", control.getId());
        node.put("type", control.getType());
        node.put("name", control.getName());
        putText(node, "description", control.getDescription());
        putText(node, "testingInformation", control.getTestingInformation());
        putText(node, "actionPlanTitle", control.getActionPlanTitle());
        putText(node, "actionPlanInstructions", control.getActionPlanInstructions());
        putText(node, "controlSources", control.getControlSources());
        if (control.getControlMappingSources() != null) {
            node.set("controlMappingSources", control.getControlMappingSources());
        }
        node.put("createdAt", control.getCreatedAt());
        node.put("lastUpdatedAt", control.getLastUpdatedAt());
        putText(node, "createdBy", control.getCreatedBy());
        putText(node, "lastUpdatedBy", control.getLastUpdatedBy());
        node.set("tags", tagsNode(control.getTags()));
        node.put("state", control.getState());
        return node;
    }

    private ObjectNode toControlMetadata(Control control) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", control.getArn());
        node.put("id", control.getId());
        node.put("name", control.getName());
        putText(node, "controlSources", control.getControlSources());
        node.put("createdAt", control.getCreatedAt());
        node.put("lastUpdatedAt", control.getLastUpdatedAt());
        return node;
    }

    private ObjectNode toFramework(Framework framework) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", framework.getArn());
        node.put("id", framework.getId());
        node.put("name", framework.getName());
        node.put("type", framework.getType());
        putText(node, "complianceType", framework.getComplianceType());
        putText(node, "description", framework.getDescription());
        if (framework.getControlSets() != null) {
            node.set("controlSets", framework.getControlSets());
        }
        node.put("createdAt", framework.getCreatedAt());
        node.put("lastUpdatedAt", framework.getLastUpdatedAt());
        putText(node, "createdBy", framework.getCreatedBy());
        putText(node, "lastUpdatedBy", framework.getLastUpdatedBy());
        node.set("tags", tagsNode(framework.getTags()));
        return node;
    }

    private ObjectNode toFrameworkMetadata(Framework framework) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", framework.getArn());
        node.put("id", framework.getId());
        node.put("type", framework.getType());
        node.put("name", framework.getName());
        putText(node, "description", framework.getDescription());
        putText(node, "complianceType", framework.getComplianceType());
        int controlSets = framework.getControlSets() == null ? 0 : framework.getControlSets().size();
        int controls = 0;
        if (framework.getControlSets() != null && framework.getControlSets().isArray()) {
            for (JsonNode set : framework.getControlSets()) {
                if (set.has("controls") && set.get("controls").isArray()) {
                    controls += set.get("controls").size();
                }
            }
        }
        node.put("controlSetsCount", controlSets);
        node.put("controlsCount", controls);
        node.put("createdAt", framework.getCreatedAt());
        node.put("lastUpdatedAt", framework.getLastUpdatedAt());
        return node;
    }

    private ObjectNode toAssessment(Assessment assessment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", assessment.getArn());
        ObjectNode account = node.putObject("awsAccount");
        account.put("id", regionResolver.getAccountId());
        node.set("metadata", toAssessmentMetadata(assessment));
        ObjectNode framework = node.putObject("framework");
        framework.put("id", assessment.getFrameworkId());
        putText(framework, "arn", assessment.getFrameworkArn());
        node.set("tags", tagsNode(assessment.getTags()));
        return node;
    }

    private ObjectNode toAssessmentMetadata(Assessment assessment) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("name", assessment.getName());
        metadata.put("id", assessment.getId());
        putText(metadata, "description", assessment.getDescription());
        metadata.put("status", assessment.getStatus());
        if (assessment.getAssessmentReportsDestination() != null) {
            metadata.set("assessmentReportsDestination", assessment.getAssessmentReportsDestination());
        }
        if (assessment.getScope() != null) {
            metadata.set("scope", assessment.getScope());
        }
        if (assessment.getRoles() != null) {
            metadata.set("roles", assessment.getRoles());
        }
        metadata.put("creationTime", assessment.getCreatedAt());
        metadata.put("lastUpdated", assessment.getLastUpdated());
        return metadata;
    }

    private ObjectNode toAssessmentMetadataItem(Assessment assessment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", assessment.getName());
        node.put("id", assessment.getId());
        node.put("status", assessment.getStatus());
        if (assessment.getRoles() != null) {
            node.set("roles", assessment.getRoles());
        }
        node.put("creationTime", assessment.getCreatedAt());
        node.put("lastUpdated", assessment.getLastUpdated());
        return node;
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
