# EventBridge Pipes

**Protocol:** REST-JSON
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
| `CreatePipe` | Create a new pipe with source, target, and optional enrichment |
| `DescribePipe` | Get pipe details including state and configuration |
| `UpdatePipe` | Update pipe configuration (source, target, role, enrichment, desired state) |
| `DeletePipe` | Delete a pipe |
| `ListPipes` | List all pipes with optional filtering by state and prefix |
| `StartPipe` | Start a stopped pipe |
| `StopPipe` | Stop a running pipe |
| `TagResource` | `POST /tags/{resourceArn}` — add tags to a pipe |
| `UntagResource` | `DELETE /tags/{resourceArn}?tagKeys=...` — remove tags from a pipe |
| `ListTagsForResource` | `GET /tags/{resourceArn}` — list tags on a pipe |

Create/update store and describe returns `LogConfiguration` and
`KmsKeyIdentifier` when supplied.

Lambda targets receive a **bare JSON array** of source records (not the
`{ "Records": [...] }` Lambda event-source-mapping envelope). SQS source
records use camelCase (`body`, `eventSource: "aws:sqs"`).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PIPES_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a pipe (SQS to Lambda)
aws pipes create-pipe \
  --name my-pipe \
  --source "arn:aws:sqs:us-east-1:000000000000:source-queue" \
  --target "arn:aws:lambda:us-east-1:000000000000:function:my-function" \
  --role-arn "arn:aws:iam::000000000000:role/pipe-role" \
  --endpoint-url $AWS_ENDPOINT_URL

# Describe a pipe
aws pipes describe-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# List all pipes
aws pipes list-pipes \
  --endpoint-url $AWS_ENDPOINT_URL

# Start a pipe
aws pipes start-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# Stop a pipe
aws pipes stop-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# Update a pipe
aws pipes update-pipe \
  --name my-pipe \
  --target "arn:aws:lambda:us-east-1:000000000000:function:new-function" \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete a pipe
aws pipes delete-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Pipe States

- `STARTING` - Pipe is being started
- `RUNNING` - Pipe is actively processing events
- `STOPPING` - Pipe is being stopped
- `STOPPED` - Pipe is stopped and not processing events
- `DELETED` - Pipe has been deleted

## Supported Sources and Targets

Floci emulates EventBridge Pipes with the following supported source and target types:

**Sources:**
- Amazon SQS queues
- Amazon Kinesis streams
- Amazon DynamoDB streams
- Kafka topics (MSK and self-managed via `smk://`)

**Targets:**
- Lambda functions
- SQS queues
- SNS topics
- Kinesis streams
- Step Functions state machines

## Enrichment

A pipe's optional enrichment step (`source → filter → enrichment → target`) is emulated for
**Lambda** enrichments: the filtered batch is invoked synchronously (`RequestResponse`) and the
response becomes the target input.

- **Empty responses skip the target**, matching AWS: an empty body, `null`, `{}`, or `[]` consumes
  the source records without invoking the target. A non-empty array such as `[{}]` still invokes the
  target (with an empty-payload element).
- **A Lambda enrichment `FunctionError` fails the batch** — the source records are routed to the
  pipe's dead-letter queue rather than silently consumed.
- **Non-Lambda enrichment types** (API destinations, API Gateway, Step Functions Express) are valid
  on AWS but not emulated; a pipe configured with one fails the batch to the DLQ rather than
  delivering the unenriched payload.
- **Enrichment is currently applied only on the SQS source path.** Kinesis, DynamoDB Streams and
  Kafka sources deliver filtered records straight to the target; an enrichment configured on those
  sources is not yet applied.
