package io.github.hectorvent.floci.services.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Forecast (control plane) and Forecast Query
 * ({@code AmazonForecastRuntime.*}).
 *
 * @see <a href="https://docs.aws.amazon.com/forecast/latest/dg/API_Operations.html">Forecast API</a>
 */
@ApplicationScoped
public class ForecastJsonHandler {

    private static final Logger LOG = Logger.getLogger(ForecastJsonHandler.class);

    private final ForecastService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ForecastJsonHandler(ForecastService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Forecast action: {0}", action);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "CreateDataset" -> ok(service.createDataset(body, region));
            case "DescribeDataset" -> ok(service.describeDataset(body));
            case "DeleteDataset" -> ok(service.deleteDataset(body));
            case "ListDatasets" -> ok(service.listDatasets());
            case "CreateDatasetGroup" -> ok(service.createDatasetGroup(body, region));
            case "DescribeDatasetGroup" -> ok(service.describeDatasetGroup(body));
            case "UpdateDatasetGroup" -> ok(service.updateDatasetGroup(body));
            case "DeleteDatasetGroup" -> ok(service.deleteDatasetGroup(body));
            case "ListDatasetGroups" -> ok(service.listDatasetGroups());
            case "ListTagsForResource" -> ok(service.listTagsForResource(body));
            case "TagResource" -> ok(service.tagResource(body));
            case "UntagResource" -> ok(service.untagResource(body));
            case "CreateDatasetImportJob" -> ok(service.createDatasetImportJob(body, region));
            case "DescribeDatasetImportJob" -> ok(service.describeDatasetImportJob(body));
            case "CreateAutoPredictor" -> ok(service.createAutoPredictor(body, region));
            case "DescribeAutoPredictor" -> ok(service.describeAutoPredictor(body));
            case "GetAccuracyMetrics" -> ok(service.getAccuracyMetrics(body));
            case "CreateForecast" -> ok(service.createForecast(body, region));
            case "DescribeForecast" -> ok(service.describeForecast(body));
            case "StopResource" -> ok(service.stopResource(body));
            case "ResumeResource" -> ok(service.resumeResource(body));
            case "CreateForecastExportJob" -> ok(service.createForecastExportJob(body, region));
            case "DescribeForecastExportJob" -> ok(service.describeForecastExportJob(body));
            case "CreateWhatIfAnalysis" -> ok(service.createWhatIfAnalysis(body, region));
            case "DescribeWhatIfAnalysis" -> ok(service.describeWhatIfAnalysis(body));
            case "CreateWhatIfForecast" -> ok(service.createWhatIfForecast(body, region));
            case "DescribeWhatIfForecast" -> ok(service.describeWhatIfForecast(body));
            case "CreateWhatIfForecastExport" -> ok(service.createWhatIfForecastExport(body, region));
            case "DescribeWhatIfForecastExport" -> ok(service.describeWhatIfForecastExport(body));
            case "DeleteResourceTree" -> ok(service.deleteResourceTree(body));
            case "QueryForecast" -> ok(service.queryForecast(body));
            case "QueryWhatIfForecast" -> ok(service.queryWhatIfForecast(body));
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                    "AmazonForecast." + action);
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
