# Amazon Forecast

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonForecast.<Action>`, and `AmazonForecastRuntime.<Action>` for queries)
**Endpoint:** `POST http://localhost:4566/`, signed for `forecast`.

Floci trains and serves forecasts for real. An import job reads a dataset's CSV from S3, in the schema's column
order. A predictor sums each series per forecast step and forecasts each step as the mean of the same point in its
last three seasons. Its quantiles spread by the in-sample residuals and widen with the horizon. A predictor is
backtested on the last horizon of each series, and `GetAccuracyMetrics` reports that backtest. Forecasts and
what-if forecasts serve `p10`/`p50`/`p90` (or the forecast types you ask for) through the query API, and exports
write CSV to S3. A what-if forecast applies its transformations of the target to the baseline forecast;
transformations of related time series do not change it, because floci's predictor forecasts from the target alone.

Each job runs `CREATE_PENDING`, `CREATE_IN_PROGRESS`, then `ACTIVE` (or `CREATE_FAILED` with a message), one
`floci.services.forecast.transition-millis` apart. A role Forecast assumes must exist and trust
`forecast.amazonaws.com`. Amazon Forecast is closed to new customers. floci treats its accounts as existing
customers; set `floci.services.forecast.existing-customer: false` to refuse every create, as AWS refuses a new customer.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAutoPredictor` | Train an AutoPredictor on a dataset group, or retrain from a reference predictor |
| `CreateDataset` | Create a dataset with its schema and frequency |
| `CreateDatasetGroup` | Create a dataset group of one domain |
| `CreateDatasetImportJob` | Import a dataset's CSV from S3 |
| `CreateExplainability` | Explain a predictor or forecast |
| `CreateExplainabilityExport` | Export an explainability as CSV to S3 |
| `CreateForecast` | Forecast every series of a predictor, or the selected ones |
| `CreateForecastExportJob` | Export a forecast as CSV to S3 |
| `CreateMonitor` | Monitor an AutoPredictor against its backtest baseline |
| `CreatePredictor` | Train a legacy predictor with an algorithm or AutoML |
| `CreatePredictorBacktestExportJob` | Export a predictor's backtest as CSV to S3 |
| `CreateWhatIfAnalysis` | Create a what-if analysis of a forecast |
| `CreateWhatIfForecast` | Forecast a scenario with transformations of the target |
| `CreateWhatIfForecastExport` | Export what-if forecasts as CSV to S3 |
| `DeleteDataset` | Delete a dataset |
| `DeleteDatasetGroup` | Delete a dataset group |
| `DeleteDatasetImportJob` | Delete an import job |
| `DeleteExplainability` | Delete an explainability |
| `DeleteExplainabilityExport` | Delete an explainability export |
| `DeleteForecast` | Delete a forecast |
| `DeleteForecastExportJob` | Delete a forecast export job |
| `DeleteMonitor` | Delete a monitor |
| `DeletePredictor` | Delete a predictor |
| `DeletePredictorBacktestExportJob` | Delete a backtest export job |
| `DeleteResourceTree` | Delete a resource and every resource made from it |
| `DeleteWhatIfAnalysis` | Delete a what-if analysis |
| `DeleteWhatIfForecast` | Delete a what-if forecast |
| `DeleteWhatIfForecastExport` | Delete a what-if forecast export |
| `DescribeAutoPredictor` | Describe an AutoPredictor |
| `DescribeDataset` | Describe a dataset |
| `DescribeDatasetGroup` | Describe a dataset group |
| `DescribeDatasetImportJob` | Describe an import job |
| `DescribeExplainability` | Describe an explainability |
| `DescribeExplainabilityExport` | Describe an explainability export |
| `DescribeForecast` | Describe a forecast |
| `DescribeForecastExportJob` | Describe a forecast export job |
| `DescribeMonitor` | Describe a monitor |
| `DescribePredictor` | Describe a predictor |
| `DescribePredictorBacktestExportJob` | Describe a backtest export job |
| `DescribeWhatIfAnalysis` | Describe a what-if analysis |
| `DescribeWhatIfForecast` | Describe a what-if forecast |
| `DescribeWhatIfForecastExport` | Describe a what-if forecast export |
| `GetAccuracyMetrics` | Read a predictor's backtest accuracy |
| `ListDatasetGroups` | List dataset groups |
| `ListDatasetImportJobs` | List import jobs |
| `ListDatasets` | List datasets |
| `ListExplainabilities` | List explainabilities |
| `ListExplainabilityExports` | List explainability exports |
| `ListForecastExportJobs` | List forecast export jobs |
| `ListForecasts` | List forecasts |
| `ListMonitorEvaluations` | List a monitor's evaluations |
| `ListMonitors` | List monitors |
| `ListPredictorBacktestExportJobs` | List backtest export jobs |
| `ListPredictors` | List predictors |
| `ListTagsForResource` | List a resource's tags |
| `ListWhatIfAnalyses` | List what-if analyses |
| `ListWhatIfForecastExports` | List what-if forecast exports |
| `ListWhatIfForecasts` | List what-if forecasts |
| `ResumeResource` | Resume a stopped monitor |
| `StopResource` | Stop a job being created, or a monitor |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `UpdateDatasetGroup` | Replace a dataset group's datasets |
| `QueryForecast` | Query a forecast's predictions for one series |
| `QueryWhatIfForecast` | Query a what-if forecast's predictions for one series |
<!-- floci:actions:end -->
