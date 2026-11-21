package io.floci.aws.services.forecast;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon Forecast over JSON 1.1: a dataset imported from CSV in S3, a predictor trained and backtested on it, a
 * forecast served by the query API and exported to S3, a what-if forecast of a scenario, and the dependencies that
 * hold deletes back until DeleteResourceTree removes everything at once.
 */
@QuarkusTest
class ForecastIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** A JSON number, which RestAssured reads as a float, within a tolerance of a value. */
    private static Matcher<Float> near(double expected, double tolerance) {
        return allOf(greaterThan((float) (expected - tolerance)), lessThan((float) (expected + tolerance)));
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse forecast(String action, String body) {
        return given().header("Authorization", auth("forecast")).header("X-Amz-Target", "AmazonForecast." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static ValidatableResponse query(String action, String body) {
        return given().header("Authorization", auth("forecast"))
                .header("X-Amz-Target", "AmazonForecastRuntime." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"forecast.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    /**
     * Eight weeks of daily demand from Monday 2026-01-05: item a follows a weekly pattern, a unit higher on even
     * weeks and lower on odd ones; item b sells 5 every day.
     */
    private static String demand() {
        StringBuilder csv = new StringBuilder();
        LocalDate start = LocalDate.of(2026, 1, 5);
        for (int day = 0; day < 56; day++) {
            LocalDate date = start.plusDays(day);
            int value = 10 + 2 * (day % 7) + (day / 7 % 2 == 0 ? 1 : -1);
            csv.append(date).append(",a,").append(value).append('\n');
            csv.append(date).append(",b,5\n");
        }
        return csv.toString();
    }

    private static List<String> keys(String bucket, String prefix) {
        return given().header("Authorization", auth("s3")).queryParam("list-type", "2").queryParam("prefix", prefix)
                .get("/" + bucket).then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
    }

    @Test
    void importsTrainsForecastsAndExports() {
        String bucket = "forecast-demand";
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3")).body(demand()).put("/" + bucket + "/input/demand.csv").then()
                .statusCode(200);
        String role = role("forecast-access");
        String outsider = given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", "not-forecast").formParam("AssumeRolePolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                                + "\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}").post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");

        String schema = "{\"Attributes\":[{\"AttributeName\":\"timestamp\",\"AttributeType\":\"timestamp\"},"
                + "{\"AttributeName\":\"item_id\",\"AttributeType\":\"string\"},{\"AttributeName\":\"target_value\","
                + "\"AttributeType\":\"float\"}]}";
        forecast("CreateDataset", "{\"DatasetName\":\"bad\",\"Domain\":\"CUSTOM\",\"DatasetType\":"
                + "\"TARGET_TIME_SERIES\",\"DataFrequency\":\"D\",\"Schema\":{\"Attributes\":[{\"AttributeName\":"
                + "\"timestamp\",\"AttributeType\":\"timestamp\"},{\"AttributeName\":\"item_id\",\"AttributeType\":"
                + "\"string\"}]}}").statusCode(400).body("__type", containsString("InvalidInput"))
                .body("message", containsString("target_value"));
        forecast("CreateDataset", "{\"DatasetName\":\"bad\",\"Domain\":\"CUSTOM\",\"DatasetType\":"
                + "\"TARGET_TIME_SERIES\",\"DataFrequency\":\"2D\",\"Schema\":" + schema + "}").statusCode(400)
                .body("__type", containsString("InvalidInput"));
        String dataset = forecast("CreateDataset", "{\"DatasetName\":\"demand\",\"Domain\":\"CUSTOM\",\"DatasetType\":"
                + "\"TARGET_TIME_SERIES\",\"DataFrequency\":\"D\",\"Schema\":" + schema + ",\"Tags\":[{\"Key\":"
                + "\"team\",\"Value\":\"ops\"}]}").statusCode(200)
                .body("DatasetArn", equalTo("arn:aws:forecast:us-east-1:000000000000:dataset/demand"))
                .extract().path("DatasetArn");
        forecast("CreateDataset", "{\"DatasetName\":\"demand\",\"Domain\":\"CUSTOM\",\"DatasetType\":"
                + "\"TARGET_TIME_SERIES\",\"DataFrequency\":\"D\",\"Schema\":" + schema + "}").statusCode(400)
                .body("__type", containsString("ResourceAlreadyExists"));
        forecast("DescribeDataset", "{\"DatasetArn\":\"" + dataset + "\"}").statusCode(200)
                .body("Status", equalTo("ACTIVE")).body("Schema.Attributes", hasSize(3));
        forecast("ListTagsForResource", "{\"ResourceArn\":\"" + dataset + "\"}").statusCode(200)
                .body("Tags[0].Key", equalTo("team"));

        forecast("CreateDatasetGroup", "{\"DatasetGroupName\":\"retail\",\"Domain\":\"RETAIL\",\"DatasetArns\":[\""
                + dataset + "\"]}").statusCode(400).body("message", containsString("CUSTOM"));
        String group = forecast("CreateDatasetGroup", "{\"DatasetGroupName\":\"demand\",\"Domain\":\"CUSTOM\","
                + "\"DatasetArns\":[\"" + dataset + "\"]}").statusCode(200).extract().path("DatasetGroupArn");
        forecast("DescribeDatasetGroup", "{\"DatasetGroupArn\":\"" + group + "\"}").statusCode(200)
                .body("DatasetArns", hasItem(dataset)).body("Status", equalTo("ACTIVE"));

        String autoBody = "{\"PredictorName\":\"demand\",\"ForecastHorizon\":7,\"ForecastFrequency\":\"D\","
                + "\"DataConfig\":{\"DatasetGroupArn\":\"" + group + "\"}}";
        forecast("CreateAutoPredictor", autoBody).statusCode(400).body("message", containsString("imported data"));

        forecast("CreateDatasetImportJob", "{\"DatasetImportJobName\":\"denied\",\"DatasetArn\":\"" + dataset
                + "\",\"DataSource\":{\"S3Config\":{\"Path\":\"s3://" + bucket + "/input/\",\"RoleArn\":\"" + outsider
                + "\"}}}").statusCode(400).body("message", containsString("forecast.amazonaws.com"));
        String job = forecast("CreateDatasetImportJob", "{\"DatasetImportJobName\":\"first\",\"DatasetArn\":\""
                + dataset + "\",\"DataSource\":{\"S3Config\":{\"Path\":\"s3://" + bucket + "/input/\",\"RoleArn\":\""
                + role + "\"}}}").statusCode(200)
                .body("DatasetImportJobArn", equalTo("arn:aws:forecast:us-east-1:000000000000:dataset-import-job/"
                        + "demand/first")).extract().path("DatasetImportJobArn");
        forecast("DescribeDatasetImportJob", "{\"DatasetImportJobArn\":\"" + job + "\"}").statusCode(200)
                .body("Status", equalTo("ACTIVE")).body("TimestampFormat", equalTo("yyyy-MM-dd"))
                .body("FieldStatistics.item_id.CountDistinct", equalTo(2))
                .body("FieldStatistics.target_value.Count", equalTo(112))
                .body("FieldStatistics.target_value.Min", equalTo("5"))
                .body("FieldStatistics.target_value.Max", equalTo("23"));
        String missing = forecast("CreateDatasetImportJob", "{\"DatasetImportJobName\":\"empty\",\"DatasetArn\":\""
                + dataset + "\",\"DataSource\":{\"S3Config\":{\"Path\":\"s3://" + bucket + "/nothing/\",\"RoleArn\":\""
                + role + "\"}}}").statusCode(200).extract().path("DatasetImportJobArn");
        forecast("DescribeDatasetImportJob", "{\"DatasetImportJobArn\":\"" + missing + "\"}").statusCode(200)
                .body("Status", equalTo("CREATE_FAILED")).body("Message", containsString("No data"));
        forecast("ListDatasetImportJobs", "{\"Filters\":[{\"Key\":\"Status\",\"Value\":\"ACTIVE\",\"Condition\":"
                + "\"IS\"}]}").statusCode(200).body("DatasetImportJobs", hasSize(1));

        String predictor = forecast("CreateAutoPredictor", autoBody.replace("}}", "},\"Tags\":[{\"Key\":\"model\","
                + "\"Value\":\"auto\"}]}")).statusCode(200).extract().path("PredictorArn");
        forecast("DescribeAutoPredictor", "{\"PredictorArn\":\"" + predictor + "\"}").statusCode(200)
                .body("Status", equalTo("ACTIVE")).body("ForecastTypes", equalTo(List.of("0.1", "0.5", "0.9")))
                .body("DatasetImportJobArns", hasItem(job));
        forecast("DescribePredictor", "{\"PredictorArn\":\"" + predictor + "\"}").statusCode(400)
                .body("message", containsString("DescribeAutoPredictor"));
        forecast("GetAccuracyMetrics", "{\"PredictorArn\":\"" + predictor + "\"}").statusCode(200)
                .body("IsAutoPredictor", equalTo(true))
                .body("PredictorEvaluationResults[0].TestWindows[0].ItemCount", equalTo(2))
                .body("PredictorEvaluationResults[0].TestWindows[0].Metrics.WeightedQuantileLosses", hasSize(3))
                .body("PredictorEvaluationResults[0].TestWindows[0].Metrics.ErrorMetrics[0].WAPE",
                        near(0.0, 0.2));

        String fc = forecast("CreateForecast", "{\"ForecastName\":\"week\",\"PredictorArn\":\"" + predictor + "\"}")
                .statusCode(200).extract().path("ForecastArn");
        forecast("DescribeForecast", "{\"ForecastArn\":\"" + fc + "\"}").statusCode(200)
                .body("Status", equalTo("ACTIVE")).body("DatasetGroupArn", equalTo(group));
        ValidatableResponse a = query("QueryForecast", "{\"ForecastArn\":\"" + fc + "\",\"Filters\":{\"item_id\":"
                + "\"a\"}}").statusCode(200).body("Forecast.Predictions.p50", hasSize(7))
                .body("Forecast.Predictions.p50[0].Timestamp", equalTo("2026-03-02T00:00:00"));
        List<Float> p10 = a.extract().path("Forecast.Predictions.p10.Value");
        List<Float> p50 = a.extract().path("Forecast.Predictions.p50.Value");
        List<Float> p90 = a.extract().path("Forecast.Predictions.p90.Value");
        for (int day = 0; day < 7; day++) {
            assertEquals(10 + 2 * day - 1 / 3.0, p50.get(day), 0.01, "the seasonal mean of weekday " + day);
            assertTrue(p10.get(day) < p50.get(day) && p50.get(day) < p90.get(day), "quantiles are ordered");
        }
        query("QueryForecast", "{\"ForecastArn\":\"" + fc + "\",\"Filters\":{\"item_id\":\"b\"},\"StartDate\":"
                + "\"2026-03-04T00:00:00\",\"EndDate\":\"2026-03-05T00:00:00\"}").statusCode(200)
                .body("Forecast.Predictions.p50", hasSize(2)).body("Forecast.Predictions.p50[0].Value",
                        near(5.0, 0.001));
        query("QueryForecast", "{\"ForecastArn\":\"" + fc + "\",\"Filters\":{\"item_id\":\"zzz\"}}").statusCode(400)
                .body("__type", containsString("InvalidInput"));

        forecast("CreateForecastExportJob", "{\"ForecastExportJobName\":\"out\",\"ForecastArn\":\"" + fc
                + "\",\"Destination\":{\"S3Config\":{\"Path\":\"s3://" + bucket + "/exports\",\"RoleArn\":\"" + role
                + "\"}}}").statusCode(200).body("ForecastExportJobArn", containsString("forecast-export-job/week/out"));
        List<String> exports = keys(bucket, "exports/");
        assertEquals(1, exports.size());
        String csv = given().header("Authorization", auth("s3")).get("/" + bucket + "/" + exports.get(0)).then()
                .statusCode(200).extract().asString();
        assertEquals("item_id,date,p10,p50,p90", csv.lines().findFirst().orElseThrow());
        assertEquals(15, csv.lines().count());

        String analysis = forecast("CreateWhatIfAnalysis", "{\"WhatIfAnalysisName\":\"promo\",\"ForecastArn\":\"" + fc
                + "\"}").statusCode(200).extract().path("WhatIfAnalysisArn");
        String scenario = forecast("CreateWhatIfForecast", "{\"WhatIfForecastName\":\"double\",\"WhatIfAnalysisArn\":\""
                + analysis + "\",\"TimeSeriesTransformations\":[{\"Action\":{\"AttributeName\":\"target_value\","
                + "\"Operation\":\"MULTIPLY\",\"Value\":2},\"TimeSeriesConditions\":[{\"AttributeName\":\"item_id\","
                + "\"AttributeValue\":\"b\",\"Condition\":\"EQUALS\"}]}]}").statusCode(200)
                .extract().path("WhatIfForecastArn");
        query("QueryWhatIfForecast", "{\"WhatIfForecastArn\":\"" + scenario + "\",\"Filters\":{\"item_id\":\"b\"}}")
                .statusCode(200).body("Forecast.Predictions.p50[0].Value", near(10.0, 0.001));
        query("QueryWhatIfForecast", "{\"WhatIfForecastArn\":\"" + scenario + "\",\"Filters\":{\"item_id\":\"a\"}}")
                .statusCode(200).body("Forecast.Predictions.p50[0].Value", near(10 - 1 / 3.0, 0.01));

        forecast("DeletePredictor", "{\"PredictorArn\":\"" + predictor + "\"}").statusCode(400)
                .body("__type", containsString("ResourceInUse"));
        forecast("DeleteForecast", "{\"ForecastArn\":\"" + fc + "\"}").statusCode(400)
                .body("__type", containsString("ResourceInUse"));
        forecast("TagResource", "{\"ResourceArn\":\"" + predictor + "\",\"Tags\":[{\"Key\":\"stage\",\"Value\":"
                + "\"test\"}]}").statusCode(200);
        forecast("UntagResource", "{\"ResourceArn\":\"" + predictor + "\",\"TagKeys\":[\"model\"]}").statusCode(200);
        forecast("ListTagsForResource", "{\"ResourceArn\":\"" + predictor + "\"}").statusCode(200)
                .body("Tags", hasSize(1)).body("Tags[0].Key", equalTo("stage"));

        forecast("DeleteResourceTree", "{\"ResourceArn\":\"" + group + "\"}").statusCode(200);
        forecast("DescribeAutoPredictor", "{\"PredictorArn\":\"" + predictor + "\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFound"));
        forecast("DescribeForecast", "{\"ForecastArn\":\"" + fc + "\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFound"));
        forecast("ListWhatIfForecasts", "{}").statusCode(200).body("WhatIfForecasts", hasSize(0));
        forecast("ListDatasetGroups", "{}").statusCode(200).body("DatasetGroups.DatasetGroupArn", not(hasItem(group)));
        forecast("DescribeDataset", "{\"DatasetArn\":\"" + dataset + "\"}").statusCode(200);
        forecast("DeleteDataset", "{\"DatasetArn\":\"" + dataset + "\"}").statusCode(200);
        forecast("ListDatasets", "{}").statusCode(200).body("Datasets.DatasetArn", not(hasItem(dataset)));
    }

    @Test
    void refusesUnknownResourcesAndOperations() {
        forecast("DescribeDataset", "{\"DatasetArn\":\"arn:aws:forecast:us-east-1:000000000000:dataset/nope\"}")
                .statusCode(400).body("__type", containsString("ResourceNotFound"));
        forecast("DescribeDataset", "{\"DatasetArn\":\"arn:aws:s3:::bucket\"}").statusCode(400)
                .body("__type", containsString("InvalidInput"));
        forecast("ListPredictors", "{\"MaxResults\":0}").statusCode(400).body("__type", containsString("InvalidInput"));
        forecast("ListDatasets", "{\"NextToken\":\"garbage\"}").statusCode(400)
                .body("__type", containsString("InvalidNextToken"));
        forecast("Nope", "{}").statusCode(400).body("__type", containsString("UnknownOperation"));
        query("Nope", "{}").statusCode(400).body("__type", containsString("UnknownOperation"));
    }
}
