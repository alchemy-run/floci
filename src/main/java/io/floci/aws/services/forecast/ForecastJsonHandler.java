package io.floci.aws.services.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/**
 * JSON 1.1 dispatch for Amazon Forecast ({@code X-Amz-Target: AmazonForecast.<Action>}) and its query API
 * ({@code AmazonForecastRuntime.<Action>}).
 */
@ApplicationScoped
public class ForecastJsonHandler {

    /**
     * Forecast's request constraints; it reports a violation as InvalidInputException. The model's Arn pattern
     * admits only Forecast ARNs, but the same shape carries the IAM role ARNs of S3 configs and the KMS key ARNs
     * of encryption configs, so the service checks ARNs itself.
     */
    private static final RequestConstraintCheck SHAPES = shapes("aws/forecast-request-shapes.json", "Forecast")
            .withLenientShapes(Set.of("Arn"));
    private static final RequestConstraintCheck QUERY_SHAPES = shapes("aws/forecastquery-request-shapes.json",
            "Forecast Query");

    private static RequestConstraintCheck shapes(String resource, String name) {
        return RequestConstraintCheck.load(resource, name + " request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long").contains(type)
                            ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });
    }

    private final ForecastService service;

    @Inject
    public ForecastJsonHandler(ForecastService service) {
        this.service = service;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return ForecastStore.invalid("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "CreateAutoPredictor" -> service.createAutoPredictor(request, region);
            case "CreateDataset" -> service.createDataset(request, region);
            case "CreateDatasetGroup" -> service.createDatasetGroup(request, region);
            case "CreateDatasetImportJob" -> service.createDatasetImportJob(request, region);
            case "CreateExplainability" -> service.createExplainability(request, region);
            case "CreateExplainabilityExport" -> service.createExplainabilityExport(request, region);
            case "CreateForecast" -> service.createForecast(request, region);
            case "CreateForecastExportJob" -> service.createForecastExportJob(request, region);
            case "CreateMonitor" -> service.createMonitor(request, region);
            case "CreatePredictor" -> service.createPredictor(request, region);
            case "CreatePredictorBacktestExportJob" -> service.createPredictorBacktestExportJob(request, region);
            case "CreateWhatIfAnalysis" -> service.createWhatIfAnalysis(request, region);
            case "CreateWhatIfForecast" -> service.createWhatIfForecast(request, region);
            case "CreateWhatIfForecastExport" -> service.createWhatIfForecastExport(request, region);
            case "DeleteDataset" -> service.deleteDataset(request, region);
            case "DeleteDatasetGroup" -> service.deleteDatasetGroup(request, region);
            case "DeleteDatasetImportJob" -> service.deleteDatasetImportJob(request, region);
            case "DeleteExplainability" -> service.deleteExplainability(request, region);
            case "DeleteExplainabilityExport" -> service.deleteExplainabilityExport(request, region);
            case "DeleteForecast" -> service.deleteForecast(request, region);
            case "DeleteForecastExportJob" -> service.deleteForecastExportJob(request, region);
            case "DeleteMonitor" -> service.deleteMonitor(request, region);
            case "DeletePredictor" -> service.deletePredictor(request, region);
            case "DeletePredictorBacktestExportJob" -> service.deletePredictorBacktestExportJob(request, region);
            case "DeleteResourceTree" -> service.deleteResourceTree(request, region);
            case "DeleteWhatIfAnalysis" -> service.deleteWhatIfAnalysis(request, region);
            case "DeleteWhatIfForecast" -> service.deleteWhatIfForecast(request, region);
            case "DeleteWhatIfForecastExport" -> service.deleteWhatIfForecastExport(request, region);
            case "DescribeAutoPredictor" -> service.describeAutoPredictor(request, region);
            case "DescribeDataset" -> service.describeDataset(request, region);
            case "DescribeDatasetGroup" -> service.describeDatasetGroup(request, region);
            case "DescribeDatasetImportJob" -> service.describeDatasetImportJob(request, region);
            case "DescribeExplainability" -> service.describeExplainability(request, region);
            case "DescribeExplainabilityExport" -> service.describeExplainabilityExport(request, region);
            case "DescribeForecast" -> service.describeForecast(request, region);
            case "DescribeForecastExportJob" -> service.describeForecastExportJob(request, region);
            case "DescribeMonitor" -> service.describeMonitor(request, region);
            case "DescribePredictor" -> service.describePredictor(request, region);
            case "DescribePredictorBacktestExportJob" -> service.describePredictorBacktestExportJob(request, region);
            case "DescribeWhatIfAnalysis" -> service.describeWhatIfAnalysis(request, region);
            case "DescribeWhatIfForecast" -> service.describeWhatIfForecast(request, region);
            case "DescribeWhatIfForecastExport" -> service.describeWhatIfForecastExport(request, region);
            case "GetAccuracyMetrics" -> service.getAccuracyMetrics(request, region);
            case "ListDatasetGroups" -> service.listDatasetGroups(request, region);
            case "ListDatasetImportJobs" -> service.listDatasetImportJobs(request, region);
            case "ListDatasets" -> service.listDatasets(request, region);
            case "ListExplainabilities" -> service.listExplainabilities(request, region);
            case "ListExplainabilityExports" -> service.listExplainabilityExports(request, region);
            case "ListForecastExportJobs" -> service.listForecastExportJobs(request, region);
            case "ListForecasts" -> service.listForecasts(request, region);
            case "ListMonitorEvaluations" -> service.listMonitorEvaluations(request, region);
            case "ListMonitors" -> service.listMonitors(request, region);
            case "ListPredictorBacktestExportJobs" -> service.listPredictorBacktestExportJobs(request, region);
            case "ListPredictors" -> service.listPredictors(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "ListWhatIfAnalyses" -> service.listWhatIfAnalyses(request, region);
            case "ListWhatIfForecastExports" -> service.listWhatIfForecastExports(request, region);
            case "ListWhatIfForecasts" -> service.listWhatIfForecasts(request, region);
            case "ResumeResource" -> service.resumeResource(request, region);
            case "StopResource" -> service.stopResource(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            case "UpdateDatasetGroup" -> service.updateDatasetGroup(request, region);
            default -> null;
        };
        if (result == null) {
            return unknown("AmazonForecast." + action);
        }
        return Response.ok(result).build();
    }

    public Response handleQuery(String action, JsonNode request, String region) {
        QUERY_SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "QueryForecast" -> service.queryForecast(request, region);
            case "QueryWhatIfForecast" -> service.queryWhatIfForecast(request, region);
            default -> null;
        };
        if (result == null) {
            return unknown("AmazonForecastRuntime." + action);
        }
        return Response.ok(result).build();
    }

    private static Response unknown(String target) {
        return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                "Unknown operation: " + target)).build();
    }
}
