package io.floci.aws.services.auditmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
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

/**
 * The AWS Audit Manager REST API, routed here by {@link AuditManagerRouteFilter}; tags go through the shared
 * {@code /tags/{resourceArn}} routes ({@link AuditManagerTagHandler}).
 */
@Path(AuditManagerRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class AuditManagerController {
    private static final String ASSESSMENT = "/assessments/{assessmentId}";
    private static final String CONTROL_SET = ASSESSMENT + "/controlSets/{controlSetId}";
    private static final String FOLDER = CONTROL_SET + "/evidenceFolders/{evidenceFolderId}";

    private final AuditManagerService service;
    private final AuditManagerAssessments assessments;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public AuditManagerController(AuditManagerService service, AuditManagerAssessments assessments,
                                  RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.assessments = assessments;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ── Account ────────────────────────────────────────────────────────────

    @GET
    @Path("/account/status")
    public Response getAccountStatus(@Context HttpHeaders headers) {
        return ok(service.accountStatus(region(headers)));
    }

    @POST
    @Path("/account/registerAccount")
    public Response registerAccount(@Context HttpHeaders headers, String body) {
        return ok(service.register(parse(body), region(headers)));
    }

    @POST
    @Path("/account/deregisterAccount")
    public Response deregisterAccount(@Context HttpHeaders headers) {
        String region = region(headers);
        return ok(service.deregister(region, () -> assessments.deleteAll(region)));
    }

    @POST
    @Path("/account/registerOrganizationAdminAccount")
    public Response registerOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        return ok(service.registerOrganizationAdmin(parse(body), region(headers)));
    }

    @POST
    @Path("/account/deregisterOrganizationAdminAccount")
    public Response deregisterOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.deregisterOrganizationAdmin(parse(body), region(headers));
        return empty();
    }

    @GET
    @Path("/account/organizationAdminAccount")
    public Response getOrganizationAdminAccount(@Context HttpHeaders headers) {
        return ok(service.organizationAdmin(region(headers)));
    }

    @GET
    @Path("/settings/{attribute}")
    public Response getSettings(@Context HttpHeaders headers, @PathParam("attribute") String attribute) {
        return ok(service.getSettings(attribute, region(headers)));
    }

    @PUT
    @Path("/settings")
    public Response updateSettings(@Context HttpHeaders headers, String body) {
        return ok(service.updateSettings(parse(body), region(headers)));
    }

    @GET
    @Path("/services")
    public Response getServicesInScope(@Context HttpHeaders headers) {
        return ok(service.servicesInScope(region(headers)));
    }

    @GET
    @Path("/dataSourceKeywords")
    public Response listKeywordsForDataSource(@Context HttpHeaders headers, @QueryParam("source") String source,
                                              @QueryParam("maxResults") Integer maxResults,
                                              @QueryParam("nextToken") String nextToken) {
        return ok(service.keywords(source, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/evidenceFileUploadUrl")
    public Response getEvidenceFileUploadUrl(@Context HttpHeaders headers, @QueryParam("fileName") String fileName) {
        return ok(service.uploadUrl(fileName, region(headers)));
    }

    // ── Controls ───────────────────────────────────────────────────────────

    @POST
    @Path("/controls")
    public Response createControl(@Context HttpHeaders headers, String body) {
        return ok(service.createControl(parse(body), region(headers)));
    }

    @GET
    @Path("/controls")
    public Response listControls(@Context HttpHeaders headers, @QueryParam("controlType") String controlType,
                                 @QueryParam("controlCatalogId") String controlCatalogId,
                                 @QueryParam("maxResults") Integer maxResults,
                                 @QueryParam("nextToken") String nextToken) {
        return ok(service.listControls(controlType, controlCatalogId, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/controls/{controlId}")
    public Response getControl(@Context HttpHeaders headers, @PathParam("controlId") String controlId) {
        return ok(service.getControl(controlId, region(headers)));
    }

    @PUT
    @Path("/controls/{controlId}")
    public Response updateControl(@Context HttpHeaders headers, @PathParam("controlId") String controlId,
                                  String body) {
        return ok(service.updateControl(controlId, parse(body), region(headers)));
    }

    @DELETE
    @Path("/controls/{controlId}")
    public Response deleteControl(@Context HttpHeaders headers, @PathParam("controlId") String controlId) {
        service.deleteControl(controlId, region(headers));
        return empty();
    }

    // ── Frameworks ─────────────────────────────────────────────────────────

    @POST
    @Path("/assessmentFrameworks")
    public Response createAssessmentFramework(@Context HttpHeaders headers, String body) {
        return ok(service.createFramework(parse(body), region(headers)));
    }

    @GET
    @Path("/assessmentFrameworks")
    public Response listAssessmentFrameworks(@Context HttpHeaders headers,
                                             @QueryParam("frameworkType") String frameworkType,
                                             @QueryParam("maxResults") Integer maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        return ok(service.listFrameworks(frameworkType, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/assessmentFrameworks/{frameworkId}")
    public Response getAssessmentFramework(@Context HttpHeaders headers, @PathParam("frameworkId") String id) {
        return ok(service.getFramework(id, region(headers)));
    }

    @PUT
    @Path("/assessmentFrameworks/{frameworkId}")
    public Response updateAssessmentFramework(@Context HttpHeaders headers, @PathParam("frameworkId") String id,
                                              String body) {
        return ok(service.updateFramework(id, parse(body), region(headers)));
    }

    @DELETE
    @Path("/assessmentFrameworks/{frameworkId}")
    public Response deleteAssessmentFramework(@Context HttpHeaders headers, @PathParam("frameworkId") String id) {
        service.deleteFramework(id, region(headers));
        return empty();
    }

    @POST
    @Path("/assessmentFrameworks/{frameworkId}/shareRequests")
    public Response startAssessmentFrameworkShare(@Context HttpHeaders headers, @PathParam("frameworkId") String id,
                                                  String body) {
        return ok(service.startShare(id, parse(body), region(headers)));
    }

    @GET
    @Path("/assessmentFrameworkShareRequests")
    public Response listAssessmentFrameworkShareRequests(@Context HttpHeaders headers,
                                                         @QueryParam("requestType") String requestType,
                                                         @QueryParam("maxResults") Integer maxResults,
                                                         @QueryParam("nextToken") String nextToken) {
        return ok(service.listShares(requestType, maxResults, nextToken, region(headers)));
    }

    @PUT
    @Path("/assessmentFrameworkShareRequests/{requestId}")
    public Response updateAssessmentFrameworkShare(@Context HttpHeaders headers, @PathParam("requestId") String id,
                                                   String body) {
        return ok(service.updateShare(id, parse(body), region(headers)));
    }

    @DELETE
    @Path("/assessmentFrameworkShareRequests/{requestId}")
    public Response deleteAssessmentFrameworkShare(@Context HttpHeaders headers, @PathParam("requestId") String id,
                                                   @QueryParam("requestType") String requestType) {
        service.deleteShare(id, requestType, region(headers));
        return empty();
    }

    // ── Assessments ────────────────────────────────────────────────────────

    @POST
    @Path("/assessments")
    public Response createAssessment(@Context HttpHeaders headers, String body) {
        return ok(assessments.create(parse(body), region(headers)));
    }

    @GET
    @Path("/assessments")
    public Response listAssessments(@Context HttpHeaders headers, @QueryParam("status") String status,
                                    @QueryParam("maxResults") Integer maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        return ok(assessments.list(status, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(ASSESSMENT)
    public Response getAssessment(@Context HttpHeaders headers, @PathParam("assessmentId") String id) {
        return ok(assessments.get(id, region(headers)));
    }

    @PUT
    @Path(ASSESSMENT)
    public Response updateAssessment(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                     String body) {
        return ok(assessments.update(id, parse(body), region(headers)));
    }

    @DELETE
    @Path(ASSESSMENT)
    public Response deleteAssessment(@Context HttpHeaders headers, @PathParam("assessmentId") String id) {
        assessments.delete(id, region(headers));
        return empty();
    }

    @PUT
    @Path(ASSESSMENT + "/status")
    public Response updateAssessmentStatus(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                           String body) {
        return ok(assessments.updateStatus(id, parse(body), region(headers)));
    }

    @PUT
    @Path(CONTROL_SET + "/status")
    public Response updateAssessmentControlSetStatus(@Context HttpHeaders headers,
                                                     @PathParam("assessmentId") String id,
                                                     @PathParam("controlSetId") String controlSetId, String body) {
        return ok(assessments.updateControlSetStatus(id, controlSetId, parse(body), region(headers)));
    }

    @PUT
    @Path(CONTROL_SET + "/controls/{controlId}")
    public Response updateAssessmentControl(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                            @PathParam("controlSetId") String controlSetId,
                                            @PathParam("controlId") String controlId, String body) {
        return ok(assessments.updateControl(id, controlSetId, controlId, parse(body), region(headers)));
    }

    @POST
    @Path(CONTROL_SET + "/controls/{controlId}/evidence")
    public Response batchImportEvidenceToAssessmentControl(@Context HttpHeaders headers,
                                                           @PathParam("assessmentId") String id,
                                                           @PathParam("controlSetId") String controlSetId,
                                                           @PathParam("controlId") String controlId, String body) {
        return ok(assessments.importEvidence(id, controlSetId, controlId, parse(body), region(headers)));
    }

    @POST
    @Path(ASSESSMENT + "/delegations")
    public Response batchCreateDelegationByAssessment(@Context HttpHeaders headers,
                                                      @PathParam("assessmentId") String id, String body) {
        return ok(assessments.createDelegations(id, parse(body), region(headers)));
    }

    @PUT
    @Path(ASSESSMENT + "/delegations")
    public Response batchDeleteDelegationByAssessment(@Context HttpHeaders headers,
                                                      @PathParam("assessmentId") String id, String body) {
        return ok(assessments.deleteDelegations(id, parse(body), region(headers)));
    }

    @GET
    @Path("/delegations")
    public Response getDelegations(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                                   @QueryParam("nextToken") String nextToken) {
        return ok(assessments.delegations(maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/notifications")
    public Response listNotifications(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                                      @QueryParam("nextToken") String nextToken) {
        return ok(assessments.notifications(maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(ASSESSMENT + "/changelogs")
    public Response getChangeLogs(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                  @QueryParam("controlSetId") String controlSetId,
                                  @QueryParam("controlId") String controlId,
                                  @QueryParam("maxResults") Integer maxResults,
                                  @QueryParam("nextToken") String nextToken) {
        return ok(assessments.changeLogs(id, controlSetId, controlId, maxResults, nextToken, region(headers)));
    }

    // ── Evidence ───────────────────────────────────────────────────────────

    @GET
    @Path(ASSESSMENT + "/evidenceFolders")
    public Response getEvidenceFoldersByAssessment(@Context HttpHeaders headers,
                                                   @PathParam("assessmentId") String id,
                                                   @QueryParam("maxResults") Integer maxResults,
                                                   @QueryParam("nextToken") String nextToken) {
        return ok(assessments.foldersByAssessment(id, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(ASSESSMENT + "/evidenceFolders-by-assessment-control/{controlSetId}/{controlId}")
    public Response getEvidenceFoldersByAssessmentControl(@Context HttpHeaders headers,
                                                          @PathParam("assessmentId") String id,
                                                          @PathParam("controlSetId") String controlSetId,
                                                          @PathParam("controlId") String controlId,
                                                          @QueryParam("maxResults") Integer maxResults,
                                                          @QueryParam("nextToken") String nextToken) {
        return ok(assessments.foldersByControl(id, controlSetId, controlId, maxResults, nextToken,
                region(headers)));
    }

    @GET
    @Path(FOLDER)
    public Response getEvidenceFolder(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                      @PathParam("controlSetId") String controlSetId,
                                      @PathParam("evidenceFolderId") String folderId) {
        return ok(assessments.folder(id, controlSetId, folderId, region(headers)));
    }

    @GET
    @Path(FOLDER + "/evidence")
    public Response getEvidenceByEvidenceFolder(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                                @PathParam("controlSetId") String controlSetId,
                                                @PathParam("evidenceFolderId") String folderId,
                                                @QueryParam("maxResults") Integer maxResults,
                                                @QueryParam("nextToken") String nextToken) {
        return ok(assessments.evidenceByFolder(id, controlSetId, folderId, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(FOLDER + "/evidence/{evidenceId}")
    public Response getEvidence(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                @PathParam("controlSetId") String controlSetId,
                                @PathParam("evidenceFolderId") String folderId,
                                @PathParam("evidenceId") String evidenceId) {
        return ok(assessments.evidence(id, controlSetId, folderId, evidenceId, region(headers)));
    }

    @PUT
    @Path(ASSESSMENT + "/associateToAssessmentReport")
    public Response associateAssessmentReportEvidenceFolder(@Context HttpHeaders headers,
                                                            @PathParam("assessmentId") String id, String body) {
        assessments.selectFolder(id, parse(body), true, region(headers));
        return empty();
    }

    @PUT
    @Path(ASSESSMENT + "/disassociateFromAssessmentReport")
    public Response disassociateAssessmentReportEvidenceFolder(@Context HttpHeaders headers,
                                                               @PathParam("assessmentId") String id, String body) {
        assessments.selectFolder(id, parse(body), false, region(headers));
        return empty();
    }

    @PUT
    @Path(ASSESSMENT + "/batchAssociateToAssessmentReport")
    public Response batchAssociateAssessmentReportEvidence(@Context HttpHeaders headers,
                                                           @PathParam("assessmentId") String id, String body) {
        return ok(assessments.selectEvidence(id, parse(body), true, region(headers)));
    }

    @PUT
    @Path(ASSESSMENT + "/batchDisassociateFromAssessmentReport")
    public Response batchDisassociateAssessmentReportEvidence(@Context HttpHeaders headers,
                                                              @PathParam("assessmentId") String id, String body) {
        return ok(assessments.selectEvidence(id, parse(body), false, region(headers)));
    }

    // ── Reports ────────────────────────────────────────────────────────────

    @POST
    @Path(ASSESSMENT + "/reports")
    public Response createAssessmentReport(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                           String body) {
        return ok(assessments.createReport(id, parse(body), region(headers)));
    }

    @GET
    @Path("/assessmentReports")
    public Response listAssessmentReports(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                                          @QueryParam("nextToken") String nextToken) {
        return ok(assessments.listReports(maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(ASSESSMENT + "/reports/{assessmentReportId}/url")
    public Response getAssessmentReportUrl(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                           @PathParam("assessmentReportId") String reportId) {
        return ok(assessments.reportUrl(id, reportId, region(headers)));
    }

    @DELETE
    @Path(ASSESSMENT + "/reports/{assessmentReportId}")
    public Response deleteAssessmentReport(@Context HttpHeaders headers, @PathParam("assessmentId") String id,
                                           @PathParam("assessmentReportId") String reportId) {
        assessments.deleteReport(id, reportId, region(headers));
        return empty();
    }

    @POST
    @Path("/assessmentReports/integrity")
    public Response validateAssessmentReportIntegrity(@Context HttpHeaders headers, String body) {
        return ok(assessments.validateReport(parse(body), region(headers)));
    }

    // ── Insights ───────────────────────────────────────────────────────────

    @GET
    @Path("/insights")
    public Response getInsights(@Context HttpHeaders headers) {
        return ok(assessments.insights(region(headers)));
    }

    @GET
    @Path("/insights/assessments/{assessmentId}")
    public Response getInsightsByAssessment(@Context HttpHeaders headers, @PathParam("assessmentId") String id) {
        return ok(assessments.insightsByAssessment(id, region(headers)));
    }

    @GET
    @Path("/insights/control-domains")
    public Response listControlDomainInsights(@Context HttpHeaders headers,
                                              @QueryParam("maxResults") Integer maxResults,
                                              @QueryParam("nextToken") String nextToken) {
        return ok(assessments.domainInsights(maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/insights/control-domains-by-assessment")
    public Response listControlDomainInsightsByAssessment(@Context HttpHeaders headers,
                                                          @QueryParam("assessmentId") String id,
                                                          @QueryParam("maxResults") Integer maxResults,
                                                          @QueryParam("nextToken") String nextToken) {
        return ok(assessments.domainInsightsByAssessment(id, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/insights/controls")
    public Response listControlInsightsByControlDomain(@Context HttpHeaders headers,
                                                       @QueryParam("controlDomainId") String domainId,
                                                       @QueryParam("maxResults") Integer maxResults,
                                                       @QueryParam("nextToken") String nextToken) {
        return ok(assessments.controlInsights(domainId, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/insights/controls-by-assessment")
    public Response listAssessmentControlInsightsByControlDomain(@Context HttpHeaders headers,
                                                                 @QueryParam("controlDomainId") String domainId,
                                                                 @QueryParam("assessmentId") String id,
                                                                 @QueryParam("maxResults") Integer maxResults,
                                                                 @QueryParam("nextToken") String nextToken) {
        return ok(assessments.assessmentControlInsights(domainId, id, maxResults, nextToken, region(headers)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String region(HttpHeaders headers) {
        return regions.resolveRegion(headers);
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private Response empty() {
        return Response.ok(mapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode parsed = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            if (!parsed.isObject()) {
                throw new AwsException("ValidationException", "The request body must be a JSON object.", 400);
            }
            return parsed;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }
}
