package io.github.hectorvent.floci.services.forecast;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Forecast coverage used by Alchemy Dataset / DatasetGroup:
 * DescribeDataset typed not-found, dataset+group CRUD, attach/update, tags.
 */
@QuarkusTest
class ForecastIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/forecast/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeDataset_missingArn_returnsResourceNotFoundException() {
        forecast("DescribeDataset",
                "{\"DatasetArn\":\"arn:aws:forecast:us-east-1:000000000000:dataset/does_not_exist\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDatasetGroup_roundTripAndDelete() {
        String name = "alchemy_forecast_entitlement_probe";
        String arn = forecast("CreateDatasetGroup",
                "{\"DatasetGroupName\":\"" + name + "\",\"Domain\":\"CUSTOM\"}")
                .then()
                .statusCode(200)
                .body("DatasetGroupArn", notNullValue())
                .extract().path("DatasetGroupArn");

        forecast("DescribeDatasetGroup", "{\"DatasetGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("DatasetGroupName", equalTo(name))
                .body("Domain", equalTo("CUSTOM"))
                .body("Status", equalTo("ACTIVE"));

        forecast("DeleteDatasetGroup", "{\"DatasetGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200);

        forecast("DescribeDatasetGroup", "{\"DatasetGroupArn\":\"" + arn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void datasetAndGroupLifecycle_attachUpdateTagsAndDelete() {
        String datasetArn = forecast("CreateDataset", "{"
                + "\"DatasetName\":\"Demand\","
                + "\"Domain\":\"CUSTOM\","
                + "\"DatasetType\":\"TARGET_TIME_SERIES\","
                + "\"DataFrequency\":\"D\","
                + "\"Schema\":{\"Attributes\":["
                + "{\"AttributeName\":\"item_id\",\"AttributeType\":\"string\"},"
                + "{\"AttributeName\":\"timestamp\",\"AttributeType\":\"timestamp\"},"
                + "{\"AttributeName\":\"target_value\",\"AttributeType\":\"float\"}"
                + "]},"
                + "\"Tags\":[{\"Key\":\"Environment\",\"Value\":\"test\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("DatasetArn", notNullValue())
                .extract().path("DatasetArn");

        forecast("DescribeDataset", "{\"DatasetArn\":\"" + datasetArn + "\"}")
                .then()
                .statusCode(200)
                .body("Domain", equalTo("CUSTOM"))
                .body("DatasetType", equalTo("TARGET_TIME_SERIES"))
                .body("DataFrequency", equalTo("D"))
                .body("Status", equalTo("ACTIVE"));

        String groupArn = forecast("CreateDatasetGroup", "{"
                + "\"DatasetGroupName\":\"Sales\","
                + "\"Domain\":\"CUSTOM\","
                + "\"DatasetArns\":[\"" + datasetArn + "\"],"
                + "\"Tags\":[{\"Key\":\"Environment\",\"Value\":\"test\"},{\"Key\":\"alchemy::id\",\"Value\":\"Sales\"}]"
                + "}")
                .then()
                .statusCode(200)
                .extract().path("DatasetGroupArn");

        forecast("DescribeDatasetGroup", "{\"DatasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("DatasetArns", contains(datasetArn));

        forecast("ListTagsForResource", "{\"ResourceArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"));

        forecast("UpdateDatasetGroup",
                "{\"DatasetGroupArn\":\"" + groupArn + "\",\"DatasetArns\":[]}")
                .then()
                .statusCode(200);

        forecast("DescribeDatasetGroup", "{\"DatasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("DatasetArns", not(hasItem(datasetArn)));

        forecast("TagResource",
                "{\"ResourceArn\":\"" + groupArn + "\",\"Tags\":[{\"Key\":\"Extra\",\"Value\":\"yes\"}]}")
                .then()
                .statusCode(200);

        forecast("ListTagsForResource", "{\"ResourceArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("Extra"));

        forecast("DeleteDatasetGroup", "{\"DatasetGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200);
        forecast("DeleteDataset", "{\"DatasetArn\":\"" + datasetArn + "\"}")
                .then()
                .statusCode(200);

        forecast("DescribeDataset", "{\"DatasetArn\":\"" + datasetArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void bindingProbes_missingArns_returnResourceNotFoundException() {
        String predictor = "arn:aws:forecast:us-east-1:000000000000:predictor/alchemy_probe";
        String forecastArn = "arn:aws:forecast:us-east-1:000000000000:forecast/alchemy_probe";
        String importJob = "arn:aws:forecast:us-east-1:000000000000:dataset-import-job/alchemy_probe/alchemy_probe";
        String exportJob = "arn:aws:forecast:us-east-1:000000000000:forecast-export-job/alchemy_probe/alchemy_probe";
        String analysis = "arn:aws:forecast:us-east-1:000000000000:what-if-analysis/alchemy_probe";
        String whatIf = "arn:aws:forecast:us-east-1:000000000000:what-if-forecast/alchemy_probe";
        String whatIfExport = "arn:aws:forecast:us-east-1:000000000000:what-if-forecast-export/alchemy_probe/alchemy_probe";

        assertNotFound("DescribeDatasetImportJob", "{\"DatasetImportJobArn\":\"" + importJob + "\"}");
        assertNotFound("DescribeAutoPredictor", "{\"PredictorArn\":\"" + predictor + "\"}");
        assertNotFound("GetAccuracyMetrics", "{\"PredictorArn\":\"" + predictor + "\"}");
        assertNotFound("DescribeForecast", "{\"ForecastArn\":\"" + forecastArn + "\"}");
        assertNotFound("CreateForecast",
                "{\"ForecastName\":\"alchemy_forecast_bindings_probe\",\"PredictorArn\":\"" + predictor + "\"}");
        assertNotFound("StopResource", "{\"ResourceArn\":\"" + predictor + "\"}");
        assertNotFound("ResumeResource", "{\"ResourceArn\":\"" + predictor + "\"}");
        assertNotFound("CreateForecastExportJob", "{"
                + "\"ForecastExportJobName\":\"alchemy_forecast_export_probe\","
                + "\"ForecastArn\":\"" + forecastArn + "\","
                + "\"Destination\":{\"S3Config\":{"
                + "\"Path\":\"s3://alchemy-nonexistent-probe-bucket/exports/\","
                + "\"RoleArn\":\"arn:aws:iam::000000000000:role/alchemy_probe\"}}}");
        assertNotFound("DescribeForecastExportJob", "{\"ForecastExportJobArn\":\"" + exportJob + "\"}");
        assertNotFound("CreateWhatIfAnalysis",
                "{\"WhatIfAnalysisName\":\"alchemy_whatif_analysis_probe\",\"ForecastArn\":\"" + forecastArn + "\"}");
        assertNotFound("DescribeWhatIfAnalysis", "{\"WhatIfAnalysisArn\":\"" + analysis + "\"}");
        assertNotFound("CreateWhatIfForecast",
                "{\"WhatIfForecastName\":\"alchemy_whatif_forecast_probe\",\"WhatIfAnalysisArn\":\"" + analysis + "\"}");
        assertNotFound("DescribeWhatIfForecast", "{\"WhatIfForecastArn\":\"" + whatIf + "\"}");
        assertNotFound("CreateWhatIfForecastExport", "{"
                + "\"WhatIfForecastExportName\":\"alchemy_whatif_export_probe\","
                + "\"WhatIfForecastArns\":[\"" + whatIf + "\"],"
                + "\"Destination\":{\"S3Config\":{"
                + "\"Path\":\"s3://alchemy-nonexistent-probe-bucket/whatif/\","
                + "\"RoleArn\":\"arn:aws:iam::000000000000:role/alchemy_probe\"}}}");
        assertNotFound("DescribeWhatIfForecastExport", "{\"WhatIfForecastExportArn\":\"" + whatIfExport + "\"}");
        assertNotFound("DeleteResourceTree", "{\"ResourceArn\":\"" + predictor + "\"}");
    }

    @Test
    void queryForecast_missingArn_returnsResourceNotFoundException() {
        String forecastArn = "arn:aws:forecast:us-east-1:000000000000:forecast/alchemy_probe";
        query("QueryForecast",
                "{\"ForecastArn\":\"" + forecastArn + "\",\"Filters\":{\"item_id\":\"alchemy_probe\"}}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        query("QueryWhatIfForecast",
                "{\"WhatIfForecastArn\":\"arn:aws:forecast:us-east-1:000000000000:what-if-forecast/alchemy_probe\","
                        + "\"Filters\":{\"item_id\":\"alchemy_probe\"}}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void predictorForecastAndQuery_roundTrip() {
        String groupArn = forecast("CreateDatasetGroup",
                "{\"DatasetGroupName\":\"BindingsGroup\",\"Domain\":\"CUSTOM\"}")
                .then()
                .statusCode(200)
                .extract().path("DatasetGroupArn");

        String predictorArn = forecast("CreateAutoPredictor", "{"
                + "\"PredictorName\":\"BindingsPredictor\","
                + "\"ForecastHorizon\":3,"
                + "\"ForecastFrequency\":\"D\","
                + "\"DataConfig\":{\"DatasetGroupArn\":\"" + groupArn + "\"}"
                + "}")
                .then()
                .statusCode(200)
                .body("PredictorArn", notNullValue())
                .extract().path("PredictorArn");

        forecast("DescribeAutoPredictor", "{\"PredictorArn\":\"" + predictorArn + "\"}")
                .then()
                .statusCode(200)
                .body("Status", equalTo("ACTIVE"))
                .body("PredictorName", equalTo("BindingsPredictor"));

        forecast("GetAccuracyMetrics", "{\"PredictorArn\":\"" + predictorArn + "\"}")
                .then()
                .statusCode(200)
                .body("PredictorEvaluationResults", notNullValue());

        String forecastArn = forecast("CreateForecast", "{"
                + "\"ForecastName\":\"BindingsForecast\","
                + "\"PredictorArn\":\"" + predictorArn + "\"}")
                .then()
                .statusCode(200)
                .extract().path("ForecastArn");

        query("QueryForecast",
                "{\"ForecastArn\":\"" + forecastArn + "\",\"Filters\":{\"item_id\":\"sku_1\"}}")
                .then()
                .statusCode(200)
                .body("Forecast.Predictions.p50[0].Value", notNullValue());

        forecast("DeleteResourceTree", "{\"ResourceArn\":\"" + predictorArn + "\"}")
                .then()
                .statusCode(200);
        assertNotFound("DescribeForecast", "{\"ForecastArn\":\"" + forecastArn + "\"}");
        assertNotFound("DescribeAutoPredictor", "{\"PredictorArn\":\"" + predictorArn + "\"}");
    }

    private static void assertNotFound(String action, String body) {
        forecast(action, body)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response forecast(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonForecast." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }

    private static Response query(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonForecastRuntime." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
