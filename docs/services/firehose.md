# Data Firehose

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon Data Firehose for streaming data ingestion and delivery to S3.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDeliveryStream` | Creates a new delivery stream |
| `UpdateDestination` | Updates S3 / extended-S3 destination settings in place |
| `DescribeDeliveryStream` | Returns metadata about a stream |
| `ListDeliveryStreams` | Lists all delivery streams |
| `StartDeliveryStreamEncryption` | Enables stream-level SSE (`AWS_OWNED_CMK` or `CUSTOMER_MANAGED_CMK`) |
| `StopDeliveryStreamEncryption` | Disables stream-level SSE |
| `DeleteDeliveryStream` | Deletes a delivery stream |
| `PutRecord` | Writes a single data record to the stream |
| `PutRecordBatch` | Writes multiple data records to the stream |
| `TagDeliveryStream` | Adds or overwrites tags |
| `UntagDeliveryStream` | Removes tags by key |
| `ListTagsForDeliveryStream` | Lists tags on a stream |
<!-- floci:actions:end -->

## How it works

1. **Buffering**: Incoming records are buffered in memory.
2. **Automatic Flush**: Floci automatically flushes the buffer to S3 after every 5 records for immediate local feedback.
3. **Format**: Records are flushed as raw NDJSON (newline-delimited JSON) to the `floci-firehose-results` bucket.

## Server-side encryption

`CreateDeliveryStream` accepts `DeliveryStreamEncryptionConfigurationInput`. `StartDeliveryStreamEncryption` / `StopDeliveryStreamEncryption` converge the same state later. Describe reports `DeliveryStreamEncryptionConfiguration.Status` as `ENABLED` or `DISABLED` immediately (no async `ENABLING` / `DISABLING` window). `AWS_OWNED_CMK` omits `KeyARN`; `CUSTOMER_MANAGED_CMK` requires it. Kinesis-sourced streams reject SSE — AWS inherits encryption from the source stream.

## Kinesis as source

Set `DeliveryStreamType` to `KinesisStreamAsSource` and pass `KinesisStreamSourceConfiguration` (`KinesisStreamARN` + `RoleARN`). Describe returns `Source.KinesisStreamSourceDescription` with those fields plus `DeliveryStartTimestamp`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_FIREHOSE_ENABLED` | `true` | Enable or disable the service |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a stream
aws firehose create-delivery-stream --delivery-stream-name my-stream --endpoint-url $AWS_ENDPOINT_URL

# Put a record
aws firehose put-record \
  --delivery-stream-name my-stream \
  --record '{"Data": "{\"id\": 1, \"amount\": 10.5}"}' \
  --endpoint-url $AWS_ENDPOINT_URL
```
