package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Lex V2 model building REST API, routed here by {@link LexRouteFilter}. Each operation's path, query string and
 * body members are gathered into one request, checked against the API's shapes, and handed to the class that
 * implements it; errors carry {@code message} and {@code x-amzn-ErrorType}. Generated from the botocore model.
 */
@Path(LexRouteFilter.MODELS_PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class LexModelsController {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/lexv2-models-request-shapes.json", "Lex V2 model building request shapes", LexRouteFilter.MESSAGES);

    private final LexBots bots;
    private final LexInsights insights;
    private final LexIntents intents;
    private final LexPorting porting;
    private final LexTesting testing;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public LexModelsController(LexBots bots, LexInsights insights, LexIntents intents, LexPorting porting,
                               LexTesting testing, RegionResolver regions, ObjectMapper mapper) {
        this.bots = bots;
        this.insights = insights;
        this.intents = intents;
        this.porting = porting;
        this.testing = testing;
        this.regions = regions;
        this.mapper = mapper;
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/customvocabulary/DEFAULT/batchcreate")
    public Response batchCreateCustomVocabularyItem(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("BatchCreateCustomVocabularyItem", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.batchCreateCustomVocabularyItem(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/customvocabulary/DEFAULT/batchdelete")
    public Response batchDeleteCustomVocabularyItem(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("BatchDeleteCustomVocabularyItem", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.batchDeleteCustomVocabularyItem(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/customvocabulary/DEFAULT/batchupdate")
    public Response batchUpdateCustomVocabularyItem(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("BatchUpdateCustomVocabularyItem", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.batchUpdateCustomVocabularyItem(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    public Response buildBotLocale(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("BuildBotLocale", 202, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> bots.buildBotLocale(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots")
    public Response createBot(String body) {
        return call("CreateBot", 202, body, in -> {
        }, in -> bots.createBot(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botaliases")
    public Response createBotAlias(@PathParam("botId") String botId, String body) {
        return call("CreateBotAlias", 202, body, in -> {
            in.put("botId", botId);
        }, in -> bots.createBotAlias(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales")
    public Response createBotLocale(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, String body) {
        return call("CreateBotLocale", 202, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
        }, in -> bots.createBotLocale(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/replicas")
    public Response createBotReplica(@PathParam("botId") String botId, String body) {
        return call("CreateBotReplica", 202, body, in -> {
            in.put("botId", botId);
        }, in -> bots.createBotReplica(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions")
    public Response createBotVersion(@PathParam("botId") String botId, String body) {
        return call("CreateBotVersion", 202, body, in -> {
            in.put("botId", botId);
        }, in -> bots.createBotVersion(in, regions.getRegion()));
    }

    @PUT
    @Path("/exports")
    public Response createExport(String body) {
        return call("CreateExport", 202, body, in -> {
        }, in -> porting.createExport(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents")
    public Response createIntent(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("CreateIntent", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.createIntent(in, regions.getRegion()));
    }

    @POST
    @Path("/policy/{resourceArn: .+}")
    public Response createResourcePolicy(@PathParam("resourceArn") String resourceArn, String body) {
        return call("CreateResourcePolicy", 200, body, in -> {
            in.put("resourceArn", resourceArn);
        }, in -> bots.createResourcePolicy(in, regions.getRegion()));
    }

    @POST
    @Path("/policy/{resourceArn: .+}/statements")
    public Response createResourcePolicyStatement(@PathParam("resourceArn") String resourceArn, @QueryParam("expectedRevisionId") String expectedRevisionId, String body) {
        return call("CreateResourcePolicyStatement", 200, body, in -> {
            in.put("resourceArn", resourceArn);
            in.put("expectedRevisionId", expectedRevisionId);
        }, in -> bots.createResourcePolicyStatement(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}/slots")
    public Response createSlot(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("intentId") String intentId, String body) {
        return call("CreateSlot", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("intentId", intentId);
        }, in -> intents.createSlot(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes")
    public Response createSlotType(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("CreateSlotType", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.createSlotType(in, regions.getRegion()));
    }

    @POST
    @Path("/testsets/{testSetId}/testsetdiscrepancy")
    public Response createTestSetDiscrepancyReport(@PathParam("testSetId") String testSetId, String body) {
        return call("CreateTestSetDiscrepancyReport", 202, body, in -> {
            in.put("testSetId", testSetId);
        }, in -> testing.createTestSetDiscrepancyReport(in, regions.getRegion()));
    }

    @POST
    @Path("/createuploadurl")
    public Response createUploadUrl() {
        return call("CreateUploadUrl", 200, null, in -> {
        }, in -> porting.createUploadUrl(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}")
    public Response deleteBot(@PathParam("botId") String botId, @QueryParam("skipResourceInUseCheck") String skipResourceInUseCheck) {
        return call("DeleteBot", 202, null, in -> {
            in.put("botId", botId);
            bool(in, "skipResourceInUseCheck", skipResourceInUseCheck);
        }, in -> bots.deleteBot(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botaliases/{botAliasId}")
    public Response deleteBotAlias(@PathParam("botAliasId") String botAliasId, @PathParam("botId") String botId, @QueryParam("skipResourceInUseCheck") String skipResourceInUseCheck) {
        return call("DeleteBotAlias", 202, null, in -> {
            in.put("botAliasId", botAliasId);
            in.put("botId", botId);
            bool(in, "skipResourceInUseCheck", skipResourceInUseCheck);
        }, in -> bots.deleteBotAlias(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botanalyzer/{botAnalyzerRequestId}")
    public Response deleteBotAnalyzerRecommendation(@PathParam("botId") String botId, @PathParam("botAnalyzerRequestId") String botAnalyzerRequestId) {
        return call("DeleteBotAnalyzerRecommendation", 204, null, in -> {
            in.put("botId", botId);
            in.put("botAnalyzerRequestId", botAnalyzerRequestId);
        }, in -> insights.deleteBotAnalyzerRecommendation(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    public Response deleteBotLocale(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("DeleteBotLocale", 202, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> bots.deleteBotLocale(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/replicas/{replicaRegion}")
    public Response deleteBotReplica(@PathParam("botId") String botId, @PathParam("replicaRegion") String replicaRegion) {
        return call("DeleteBotReplica", 202, null, in -> {
            in.put("botId", botId);
            in.put("replicaRegion", replicaRegion);
        }, in -> bots.deleteBotReplica(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}")
    public Response deleteBotVersion(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @QueryParam("skipResourceInUseCheck") String skipResourceInUseCheck) {
        return call("DeleteBotVersion", 202, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            bool(in, "skipResourceInUseCheck", skipResourceInUseCheck);
        }, in -> bots.deleteBotVersion(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/customvocabulary")
    public Response deleteCustomVocabulary(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("DeleteCustomVocabulary", 202, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.deleteCustomVocabulary(in, regions.getRegion()));
    }

    @DELETE
    @Path("/exports/{exportId}")
    public Response deleteExport(@PathParam("exportId") String exportId) {
        return call("DeleteExport", 202, null, in -> {
            in.put("exportId", exportId);
        }, in -> porting.deleteExport(in, regions.getRegion()));
    }

    @DELETE
    @Path("/imports/{importId}")
    public Response deleteImport(@PathParam("importId") String importId) {
        return call("DeleteImport", 202, null, in -> {
            in.put("importId", importId);
        }, in -> porting.deleteImport(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}")
    public Response deleteIntent(@PathParam("intentId") String intentId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("DeleteIntent", 204, null, in -> {
            in.put("intentId", intentId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.deleteIntent(in, regions.getRegion()));
    }

    @DELETE
    @Path("/policy/{resourceArn: .+}")
    public Response deleteResourcePolicy(@PathParam("resourceArn") String resourceArn, @QueryParam("expectedRevisionId") String expectedRevisionId) {
        return call("DeleteResourcePolicy", 204, null, in -> {
            in.put("resourceArn", resourceArn);
            in.put("expectedRevisionId", expectedRevisionId);
        }, in -> bots.deleteResourcePolicy(in, regions.getRegion()));
    }

    @DELETE
    @Path("/policy/{resourceArn: .+}/statements/{statementId}")
    public Response deleteResourcePolicyStatement(@PathParam("resourceArn") String resourceArn, @PathParam("statementId") String statementId, @QueryParam("expectedRevisionId") String expectedRevisionId) {
        return call("DeleteResourcePolicyStatement", 204, null, in -> {
            in.put("resourceArn", resourceArn);
            in.put("statementId", statementId);
            in.put("expectedRevisionId", expectedRevisionId);
        }, in -> bots.deleteResourcePolicyStatement(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}/slots/{slotId}")
    public Response deleteSlot(@PathParam("slotId") String slotId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("intentId") String intentId) {
        return call("DeleteSlot", 204, null, in -> {
            in.put("slotId", slotId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("intentId", intentId);
        }, in -> intents.deleteSlot(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes/{slotTypeId}")
    public Response deleteSlotType(@PathParam("slotTypeId") String slotTypeId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @QueryParam("skipResourceInUseCheck") String skipResourceInUseCheck) {
        return call("DeleteSlotType", 204, null, in -> {
            in.put("slotTypeId", slotTypeId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            bool(in, "skipResourceInUseCheck", skipResourceInUseCheck);
        }, in -> intents.deleteSlotType(in, regions.getRegion()));
    }

    @DELETE
    @Path("/testsets/{testSetId}")
    public Response deleteTestSet(@PathParam("testSetId") String testSetId) {
        return call("DeleteTestSet", 204, null, in -> {
            in.put("testSetId", testSetId);
        }, in -> testing.deleteTestSet(in, regions.getRegion()));
    }

    @DELETE
    @Path("/bots/{botId}/utterances")
    public Response deleteUtterances(@PathParam("botId") String botId, @QueryParam("localeId") String localeId, @QueryParam("sessionId") String sessionId) {
        return call("DeleteUtterances", 204, null, in -> {
            in.put("botId", botId);
            in.put("localeId", localeId);
            in.put("sessionId", sessionId);
        }, in -> insights.deleteUtterances(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}")
    public Response describeBot(@PathParam("botId") String botId) {
        return call("DescribeBot", 200, null, in -> {
            in.put("botId", botId);
        }, in -> bots.describeBot(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botaliases/{botAliasId}")
    public Response describeBotAlias(@PathParam("botAliasId") String botAliasId, @PathParam("botId") String botId) {
        return call("DescribeBotAlias", 200, null, in -> {
            in.put("botAliasId", botAliasId);
            in.put("botId", botId);
        }, in -> bots.describeBotAlias(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botanalyzer/describe/{botAnalyzerRequestId}")
    public Response describeBotAnalyzerRecommendation(@PathParam("botId") String botId, @PathParam("botAnalyzerRequestId") String botAnalyzerRequestId, String body) {
        return call("DescribeBotAnalyzerRecommendation", 200, body, in -> {
            in.put("botId", botId);
            in.put("botAnalyzerRequestId", botAnalyzerRequestId);
        }, in -> insights.describeBotAnalyzerRecommendation(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    public Response describeBotLocale(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("DescribeBotLocale", 200, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> bots.describeBotLocale(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/botrecommendations/{botRecommendationId}")
    public Response describeBotRecommendation(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("botRecommendationId") String botRecommendationId) {
        return call("DescribeBotRecommendation", 200, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("botRecommendationId", botRecommendationId);
        }, in -> insights.describeBotRecommendation(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/replicas/{replicaRegion}")
    public Response describeBotReplica(@PathParam("botId") String botId, @PathParam("replicaRegion") String replicaRegion) {
        return call("DescribeBotReplica", 200, null, in -> {
            in.put("botId", botId);
            in.put("replicaRegion", replicaRegion);
        }, in -> bots.describeBotReplica(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/generations/{generationId}")
    public Response describeBotResourceGeneration(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("generationId") String generationId) {
        return call("DescribeBotResourceGeneration", 200, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("generationId", generationId);
        }, in -> insights.describeBotResourceGeneration(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}")
    public Response describeBotVersion(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion) {
        return call("DescribeBotVersion", 200, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
        }, in -> bots.describeBotVersion(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/customvocabulary/DEFAULT/metadata")
    public Response describeCustomVocabularyMetadata(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("DescribeCustomVocabularyMetadata", 200, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.describeCustomVocabularyMetadata(in, regions.getRegion()));
    }

    @GET
    @Path("/exports/{exportId}")
    public Response describeExport(@PathParam("exportId") String exportId) {
        return call("DescribeExport", 200, null, in -> {
            in.put("exportId", exportId);
        }, in -> porting.describeExport(in, regions.getRegion()));
    }

    @GET
    @Path("/imports/{importId}")
    public Response describeImport(@PathParam("importId") String importId) {
        return call("DescribeImport", 200, null, in -> {
            in.put("importId", importId);
        }, in -> porting.describeImport(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}")
    public Response describeIntent(@PathParam("intentId") String intentId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("DescribeIntent", 200, null, in -> {
            in.put("intentId", intentId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.describeIntent(in, regions.getRegion()));
    }

    @GET
    @Path("/policy/{resourceArn: .+}")
    public Response describeResourcePolicy(@PathParam("resourceArn") String resourceArn) {
        return call("DescribeResourcePolicy", 200, null, in -> {
            in.put("resourceArn", resourceArn);
        }, in -> bots.describeResourcePolicy(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}/slots/{slotId}")
    public Response describeSlot(@PathParam("slotId") String slotId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("intentId") String intentId) {
        return call("DescribeSlot", 200, null, in -> {
            in.put("slotId", slotId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("intentId", intentId);
        }, in -> intents.describeSlot(in, regions.getRegion()));
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes/{slotTypeId}")
    public Response describeSlotType(@PathParam("slotTypeId") String slotTypeId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId) {
        return call("DescribeSlotType", 200, null, in -> {
            in.put("slotTypeId", slotTypeId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.describeSlotType(in, regions.getRegion()));
    }

    @GET
    @Path("/testexecutions/{testExecutionId}")
    public Response describeTestExecution(@PathParam("testExecutionId") String testExecutionId) {
        return call("DescribeTestExecution", 200, null, in -> {
            in.put("testExecutionId", testExecutionId);
        }, in -> testing.describeTestExecution(in, regions.getRegion()));
    }

    @GET
    @Path("/testsets/{testSetId}")
    public Response describeTestSet(@PathParam("testSetId") String testSetId) {
        return call("DescribeTestSet", 200, null, in -> {
            in.put("testSetId", testSetId);
        }, in -> testing.describeTestSet(in, regions.getRegion()));
    }

    @GET
    @Path("/testsetdiscrepancy/{testSetDiscrepancyReportId}")
    public Response describeTestSetDiscrepancyReport(@PathParam("testSetDiscrepancyReportId") String testSetDiscrepancyReportId) {
        return call("DescribeTestSetDiscrepancyReport", 200, null, in -> {
            in.put("testSetDiscrepancyReportId", testSetDiscrepancyReportId);
        }, in -> testing.describeTestSetDiscrepancyReport(in, regions.getRegion()));
    }

    @GET
    @Path("/testsetgenerations/{testSetGenerationId}")
    public Response describeTestSetGeneration(@PathParam("testSetGenerationId") String testSetGenerationId) {
        return call("DescribeTestSetGeneration", 200, null, in -> {
            in.put("testSetGenerationId", testSetGenerationId);
        }, in -> testing.describeTestSetGeneration(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/generate")
    public Response generateBotElement(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("GenerateBotElement", 202, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> insights.generateBotElement(in, regions.getRegion()));
    }

    @GET
    @Path("/testexecutions/{testExecutionId}/artifacturl")
    public Response getTestExecutionArtifactsUrl(@PathParam("testExecutionId") String testExecutionId) {
        return call("GetTestExecutionArtifactsUrl", 200, null, in -> {
            in.put("testExecutionId", testExecutionId);
        }, in -> testing.getTestExecutionArtifactsUrl(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/aggregatedutterances")
    public Response listAggregatedUtterances(@PathParam("botId") String botId, String body) {
        return call("ListAggregatedUtterances", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listAggregatedUtterances(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/replicas/{replicaRegion}/botaliases")
    public Response listBotAliasReplicas(@PathParam("botId") String botId, @PathParam("replicaRegion") String replicaRegion, String body) {
        return call("ListBotAliasReplicas", 200, body, in -> {
            in.put("botId", botId);
            in.put("replicaRegion", replicaRegion);
        }, in -> bots.listBotAliasReplicas(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botaliases")
    public Response listBotAliases(@PathParam("botId") String botId, String body) {
        return call("ListBotAliases", 200, body, in -> {
            in.put("botId", botId);
        }, in -> bots.listBotAliases(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botanalyzer/history")
    public Response listBotAnalyzerHistory(@PathParam("botId") String botId, String body) {
        return call("ListBotAnalyzerHistory", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listBotAnalyzerHistory(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales")
    public Response listBotLocales(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, String body) {
        return call("ListBotLocales", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
        }, in -> bots.listBotLocales(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/botrecommendations")
    public Response listBotRecommendations(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("ListBotRecommendations", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> insights.listBotRecommendations(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/replicas")
    public Response listBotReplicas(@PathParam("botId") String botId) {
        return call("ListBotReplicas", 200, null, in -> {
            in.put("botId", botId);
        }, in -> bots.listBotReplicas(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/generations")
    public Response listBotResourceGenerations(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("ListBotResourceGenerations", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> insights.listBotResourceGenerations(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/replicas/{replicaRegion}/botversions")
    public Response listBotVersionReplicas(@PathParam("botId") String botId, @PathParam("replicaRegion") String replicaRegion, String body) {
        return call("ListBotVersionReplicas", 200, body, in -> {
            in.put("botId", botId);
            in.put("replicaRegion", replicaRegion);
        }, in -> bots.listBotVersionReplicas(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions")
    public Response listBotVersions(@PathParam("botId") String botId, String body) {
        return call("ListBotVersions", 200, body, in -> {
            in.put("botId", botId);
        }, in -> bots.listBotVersions(in, regions.getRegion()));
    }

    @POST
    @Path("/bots")
    public Response listBots(String body) {
        return call("ListBots", 200, body, in -> {
        }, in -> bots.listBots(in, regions.getRegion()));
    }

    @POST
    @Path("/builtins/locales/{localeId}/intents")
    public Response listBuiltInIntents(@PathParam("localeId") String localeId, String body) {
        return call("ListBuiltInIntents", 200, body, in -> {
            in.put("localeId", localeId);
        }, in -> intents.listBuiltInIntents(in, regions.getRegion()));
    }

    @POST
    @Path("/builtins/locales/{localeId}/slottypes")
    public Response listBuiltInSlotTypes(@PathParam("localeId") String localeId, String body) {
        return call("ListBuiltInSlotTypes", 200, body, in -> {
            in.put("localeId", localeId);
        }, in -> intents.listBuiltInSlotTypes(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/customvocabulary/DEFAULT/list")
    public Response listCustomVocabularyItems(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("ListCustomVocabularyItems", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.listCustomVocabularyItems(in, regions.getRegion()));
    }

    @POST
    @Path("/exports")
    public Response listExports(String body) {
        return call("ListExports", 200, body, in -> {
        }, in -> porting.listExports(in, regions.getRegion()));
    }

    @POST
    @Path("/imports")
    public Response listImports(String body) {
        return call("ListImports", 200, body, in -> {
        }, in -> porting.listImports(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/analytics/intentmetrics")
    public Response listIntentMetrics(@PathParam("botId") String botId, String body) {
        return call("ListIntentMetrics", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listIntentMetrics(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/analytics/intentpaths")
    public Response listIntentPaths(@PathParam("botId") String botId, String body) {
        return call("ListIntentPaths", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listIntentPaths(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/analytics/intentstagemetrics")
    public Response listIntentStageMetrics(@PathParam("botId") String botId, String body) {
        return call("ListIntentStageMetrics", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listIntentStageMetrics(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents")
    public Response listIntents(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("ListIntents", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.listIntents(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/botrecommendations/{botRecommendationId}/intents")
    public Response listRecommendedIntents(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("botRecommendationId") String botRecommendationId, String body) {
        return call("ListRecommendedIntents", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("botRecommendationId", botRecommendationId);
        }, in -> insights.listRecommendedIntents(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/analytics/sessions")
    public Response listSessionAnalyticsData(@PathParam("botId") String botId, String body) {
        return call("ListSessionAnalyticsData", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listSessionAnalyticsData(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/analytics/sessionmetrics")
    public Response listSessionMetrics(@PathParam("botId") String botId, String body) {
        return call("ListSessionMetrics", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listSessionMetrics(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes")
    public Response listSlotTypes(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("ListSlotTypes", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.listSlotTypes(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}/slots")
    public Response listSlots(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("intentId") String intentId, String body) {
        return call("ListSlots", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("intentId", intentId);
        }, in -> intents.listSlots(in, regions.getRegion()));
    }

    @GET
    @Path("/tags/{resourceARN: .+}")
    public Response listTagsForResource(@PathParam("resourceARN") String resourceARN) {
        return call("ListTagsForResource", 200, null, in -> {
            in.put("resourceARN", resourceARN);
        }, in -> bots.listTagsForResource(in, regions.getRegion()));
    }

    @POST
    @Path("/testexecutions/{testExecutionId}/results")
    public Response listTestExecutionResultItems(@PathParam("testExecutionId") String testExecutionId, String body) {
        return call("ListTestExecutionResultItems", 200, body, in -> {
            in.put("testExecutionId", testExecutionId);
        }, in -> testing.listTestExecutionResultItems(in, regions.getRegion()));
    }

    @POST
    @Path("/testexecutions")
    public Response listTestExecutions(String body) {
        return call("ListTestExecutions", 200, body, in -> {
        }, in -> testing.listTestExecutions(in, regions.getRegion()));
    }

    @POST
    @Path("/testsets/{testSetId}/records")
    public Response listTestSetRecords(@PathParam("testSetId") String testSetId, String body) {
        return call("ListTestSetRecords", 200, body, in -> {
            in.put("testSetId", testSetId);
        }, in -> testing.listTestSetRecords(in, regions.getRegion()));
    }

    @POST
    @Path("/testsets")
    public Response listTestSets(String body) {
        return call("ListTestSets", 200, body, in -> {
        }, in -> testing.listTestSets(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/analytics/utterances")
    public Response listUtteranceAnalyticsData(@PathParam("botId") String botId, String body) {
        return call("ListUtteranceAnalyticsData", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listUtteranceAnalyticsData(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/analytics/utterancemetrics")
    public Response listUtteranceMetrics(@PathParam("botId") String botId, String body) {
        return call("ListUtteranceMetrics", 200, body, in -> {
            in.put("botId", botId);
        }, in -> insights.listUtteranceMetrics(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/botrecommendations/{botRecommendationId}/associatedtranscripts")
    public Response searchAssociatedTranscripts(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("botRecommendationId") String botRecommendationId, String body) {
        return call("SearchAssociatedTranscripts", 200, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("botRecommendationId", botRecommendationId);
        }, in -> insights.searchAssociatedTranscripts(in, regions.getRegion()));
    }

    @POST
    @Path("/bots/{botId}/botanalyzer")
    public Response startBotAnalyzer(@PathParam("botId") String botId, String body) {
        return call("StartBotAnalyzer", 202, body, in -> {
            in.put("botId", botId);
        }, in -> insights.startBotAnalyzer(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/botrecommendations")
    public Response startBotRecommendation(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("StartBotRecommendation", 202, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> insights.startBotRecommendation(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/startgeneration")
    public Response startBotResourceGeneration(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("StartBotResourceGeneration", 202, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> insights.startBotResourceGeneration(in, regions.getRegion()));
    }

    @PUT
    @Path("/imports")
    public Response startImport(String body) {
        return call("StartImport", 202, body, in -> {
        }, in -> porting.startImport(in, regions.getRegion()));
    }

    @POST
    @Path("/testsets/{testSetId}/testexecutions")
    public Response startTestExecution(@PathParam("testSetId") String testSetId, String body) {
        return call("StartTestExecution", 202, body, in -> {
            in.put("testSetId", testSetId);
        }, in -> testing.startTestExecution(in, regions.getRegion()));
    }

    @PUT
    @Path("/testsetgenerations")
    public Response startTestSetGeneration(String body) {
        return call("StartTestSetGeneration", 202, body, in -> {
        }, in -> testing.startTestSetGeneration(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botanalyzer/{botAnalyzerRequestId}/stop")
    public Response stopBotAnalyzer(@PathParam("botId") String botId, @PathParam("botAnalyzerRequestId") String botAnalyzerRequestId) {
        return call("StopBotAnalyzer", 200, null, in -> {
            in.put("botId", botId);
            in.put("botAnalyzerRequestId", botAnalyzerRequestId);
        }, in -> insights.stopBotAnalyzer(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/botrecommendations/{botRecommendationId}/stopbotrecommendation")
    public Response stopBotRecommendation(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("botRecommendationId") String botRecommendationId) {
        return call("StopBotRecommendation", 202, null, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("botRecommendationId", botRecommendationId);
        }, in -> insights.stopBotRecommendation(in, regions.getRegion()));
    }

    @POST
    @Path("/tags/{resourceARN: .+}")
    public Response tagResource(@PathParam("resourceARN") String resourceARN, String body) {
        return call("TagResource", 200, body, in -> {
            in.put("resourceARN", resourceARN);
        }, in -> bots.tagResource(in, regions.getRegion()));
    }

    @DELETE
    @Path("/tags/{resourceARN: .+}")
    public Response untagResource(@PathParam("resourceARN") String resourceARN, @QueryParam("tagKeys") List<String> tagKeys) {
        return call("UntagResource", 200, null, in -> {
            in.put("resourceARN", resourceARN);
            list(in, "tagKeys", tagKeys);
        }, in -> bots.untagResource(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}")
    public Response updateBot(@PathParam("botId") String botId, String body) {
        return call("UpdateBot", 202, body, in -> {
            in.put("botId", botId);
        }, in -> bots.updateBot(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botaliases/{botAliasId}")
    public Response updateBotAlias(@PathParam("botAliasId") String botAliasId, @PathParam("botId") String botId, String body) {
        return call("UpdateBotAlias", 202, body, in -> {
            in.put("botAliasId", botAliasId);
            in.put("botId", botId);
        }, in -> bots.updateBotAlias(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    public Response updateBotLocale(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("UpdateBotLocale", 202, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> bots.updateBotLocale(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/botrecommendations/{botRecommendationId}")
    public Response updateBotRecommendation(@PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("botRecommendationId") String botRecommendationId, String body) {
        return call("UpdateBotRecommendation", 202, body, in -> {
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("botRecommendationId", botRecommendationId);
        }, in -> insights.updateBotRecommendation(in, regions.getRegion()));
    }

    @PUT
    @Path("/exports/{exportId}")
    public Response updateExport(@PathParam("exportId") String exportId, String body) {
        return call("UpdateExport", 202, body, in -> {
            in.put("exportId", exportId);
        }, in -> porting.updateExport(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}")
    public Response updateIntent(@PathParam("intentId") String intentId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("UpdateIntent", 200, body, in -> {
            in.put("intentId", intentId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.updateIntent(in, regions.getRegion()));
    }

    @PUT
    @Path("/policy/{resourceArn: .+}")
    public Response updateResourcePolicy(@PathParam("resourceArn") String resourceArn, @QueryParam("expectedRevisionId") String expectedRevisionId, String body) {
        return call("UpdateResourcePolicy", 200, body, in -> {
            in.put("resourceArn", resourceArn);
            in.put("expectedRevisionId", expectedRevisionId);
        }, in -> bots.updateResourcePolicy(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}/slots/{slotId}")
    public Response updateSlot(@PathParam("slotId") String slotId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, @PathParam("intentId") String intentId, String body) {
        return call("UpdateSlot", 200, body, in -> {
            in.put("slotId", slotId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
            in.put("intentId", intentId);
        }, in -> intents.updateSlot(in, regions.getRegion()));
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes/{slotTypeId}")
    public Response updateSlotType(@PathParam("slotTypeId") String slotTypeId, @PathParam("botId") String botId, @PathParam("botVersion") String botVersion, @PathParam("localeId") String localeId, String body) {
        return call("UpdateSlotType", 202, body, in -> {
            in.put("slotTypeId", slotTypeId);
            in.put("botId", botId);
            in.put("botVersion", botVersion);
            in.put("localeId", localeId);
        }, in -> intents.updateSlotType(in, regions.getRegion()));
    }

    @PUT
    @Path("/testsets/{testSetId}")
    public Response updateTestSet(@PathParam("testSetId") String testSetId, String body) {
        return call("UpdateTestSet", 200, body, in -> {
            in.put("testSetId", testSetId);
        }, in -> testing.updateTestSet(in, regions.getRegion()));
    }
    // ---------------------------------------------------------------- plumbing

    private ObjectNode request(String operation, String body, Consumer<ObjectNode> members) {
        ObjectNode in = parse(body);
        members.accept(in);
        List<String> absent = new ArrayList<>();
        in.fields().forEachRemaining(field -> {
            if (field.getValue().isNull()) {
                absent.add(field.getKey());
            }
        });
        in.remove(absent);
        SHAPES.check(operation, in);
        return in;
    }

    private Response call(String operation, int status, String body, Consumer<ObjectNode> members,
                          Function<ObjectNode, ObjectNode> handler) {
        try {
            ObjectNode out = handler.apply(request(operation, body, members));
            if (status == 204) {
                return Response.noContent().build();
            }
            return Response.status(status).type(MediaType.APPLICATION_JSON).entity(out.toString()).build();
        } catch (AwsException e) {
            return LexRouteFilter.error(mapper, e);
        }
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw LexStore.validation("The request body must be a JSON object.");
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException e) {
            throw LexStore.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static void integer(ObjectNode in, String name, String value) {
        if (value == null) {
            return;
        }
        try {
            in.put(name, Long.parseLong(value));
        } catch (NumberFormatException e) {
            in.put(name, value);
        }
    }

    private static void bool(ObjectNode in, String name, String value) {
        if (value != null) {
            in.put(name, Boolean.parseBoolean(value));
        }
    }

    private static void list(ObjectNode in, String name, List<String> values) {
        if (values != null && !values.isEmpty()) {
            values.forEach(in.putArray(name)::add);
        }
    }
}
