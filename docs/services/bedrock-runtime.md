# Bedrock Runtime

**Protocol:** REST JSON
**Endpoint:** `POST http://localhost:4566/model/{modelId}/...`

Floci emulates the AWS Bedrock Runtime data-plane API. The response shape matches the real AWS Converse and InvokeModel contracts so AWS SDK and CLI clients accept the reply without error.

Two backends are available, selected via `floci.services.bedrock-runtime.backend`:

- `stub` (default) — no model inference, but deterministic turns that follow the request: a reply naming the model and the request, cut at `maxTokens` (`max_tokens`), streamed one word per delta; a call to an offered tool with arguments taken from the prompt; and an answer built from a tool result. See [Stub Backend](#stub-backend).
- `proxy` — forwards `Converse` and `ConverseStream` requests to any OpenAI-compatible `/chat/completions` endpoint (Ollama, OpenRouter, LiteLLM, vLLM, ...), translating request/response shapes including tool use. `InvokeModel` still falls back to the stub response in this mode. See [Proxy Backend](#proxy-backend) below.

The Bedrock management plane (`aws bedrock ...`: `ListFoundationModels`, `GetFoundationModel`, customization) is not yet emulated.

## Supported Operations

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| `Converse` | `POST /model/{modelId}/converse` | `stub`: the turn the [stub](#stub-backend) plans. `proxy`: forwards to the configured OpenAI-compatible backend and translates the reply. |
| `InvokeModel` | `POST /model/{modelId}/invoke` | The stub's turn in the model family's native body: Amazon Nova, Anthropic Messages, Amazon Titan, Meta Llama, Mistral, Cohere, or a generic `{"outputs": [...]}` shape. Not proxied yet — always the stub, regardless of backend. |
| `ConverseStream` | `POST /model/{modelId}/converse-stream` | `stub`: emits the planned turn as `messageStart`/`contentBlockDelta` (one per word, or tool-input fragments after a `contentBlockStart`)/`contentBlockStop`/`messageStop`/`metadata` events. `proxy`: forwards a streaming request to the configured OpenAI-compatible backend and translates each SSE chunk into a Bedrock event as it arrives. Text deltas reach the client incrementally, not all at once. Response body is `application/vnd.amazon.eventstream`-framed. If the upstream stream ends without a `finish_reason` or `"[DONE]"` (e.g. a dropped connection), the failure is reported as an in-band `modelStreamErrorException` event, since the HTTP 200 is already committed by the time streaming starts, matching real Bedrock's own behavior for a mid-stream failure. |
| `InvokeModelWithResponseStream` | `POST /model/{modelId}/invoke-with-response-stream` | `chunk` events carrying the family's native stream events (Nova `contentBlockDelta`s, Anthropic `content_block_delta`s; one full body for the others). `X-Amzn-Bedrock-Content-Type` is `application/json`. |
| `CountTokens` | `POST /model/{modelId}/count-tokens` | Counts a `converse` input's system prompts, messages, and tools, or an `invokeModel` body, as words and punctuation marks — the stub's own token unit, not the model's tokenizer. |

`modelId` is URL-decoded by JAX-RS and echoed verbatim. Plain model ids (e.g. `anthropic.claude-3-haiku-20240307-v1:0`), inference-profile ids (e.g. `us.anthropic.claude-3-5-sonnet-20241022-v2:0`), and full ARNs containing slashes (e.g. `arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-3-5-sonnet-20241022-v2:0`) are all accepted.

Converse accepts `messages`, `system`, `inferenceConfig`, and `toolConfig` fields. Only `messages` is validated (non-empty array). With the `stub` backend, `inferenceConfig.maxTokens`, `toolConfig.tools`, and `toolConfig.toolChoice` shape the turn and other fields are accepted and ignored. With the `proxy` backend, `system`, `toolConfig.tools` and `toolConfig.toolChoice`, and `inferenceConfig` (`maxTokens`, `temperature`, `topP`, `stopSequences`) are translated and forwarded (see below).

InvokeModel bodies are passed through as opaque bytes; neither backend parses request payloads.

## Stub Backend

The stub runs no model. It plans each turn from the request, deterministically:

- **Tools offered** (and `toolChoice` isn't `none`) and the last user turn isn't a tool result: it calls the
  tool `toolChoice` names, else the first, and fills the required arguments from the user's text: strings
  get the last proper-noun phrase ("What's the weather in San Francisco?" → `{"city": "San Francisco"}`),
  numbers the first number, booleans `true`, enums their first value. Stop reason `tool_use`.
- **A tool result** in the last turn: it answers "Here is the result: …" with the result's text.
- **Otherwise:** a reply that names the model and quotes the request, long enough to stream many deltas.

A reply longer than `inferenceConfig.maxTokens` is cut there with stop reason `max_tokens`. Tokens are words and
punctuation marks, the same unit `usage` and `CountTokens` report.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_RUNTIME_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_BACKEND` | `stub` | `stub` or `proxy` |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_URL` | _(none)_ | Base URL of the OpenAI-compatible backend, e.g. `http://localhost:11434/v1`. Required when `backend=proxy`. |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_API_KEY` | _(none)_ | Sent as `Authorization: Bearer {value}` when set |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_DEFAULT_MODEL` | _(none)_ | Fallback OpenAI-side model id when no mapping matches and passthrough is disabled |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_MODEL_MAPPING` | _(none)_ | Comma-separated `bedrockModelId=openaiModelId` pairs, e.g. `anthropic.claude-3-sonnet-20240229-v1:0=claude-3-sonnet` |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_PASSTHROUGH` | `false` | When true, forward the raw Bedrock model id as-is if no mapping matches |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_ERROR_BODY_TIMEOUT_MILLIS` | `1000` | How long to collect an upstream error body before returning the error without its details. Must be positive. |

## Proxy Backend

Model resolution order for an incoming `modelId`: explicit `model-mapping` entry, then (if `passthrough=true`) the raw `modelId` as-is, then `default-model`, else a `400 ValidationException`.

```bash
# Ollama (local, no API key needed)
export FLOCI_SERVICES_BEDROCK_RUNTIME_BACKEND=proxy
export FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_URL=http://localhost:11434/v1
export FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_DEFAULT_MODEL=llama3.2
```

Useful for integration testing: applications that call Bedrock directly don't need to stand up their own stub/mock of Bedrock — point the existing SDK client at Floci and get real responses from whatever OpenAI-compatible backend is already available, with the stubbing concern handled by Floci instead of the application under test.

### Upstream failures and caller cancellation

When a caller disconnects during `Converse` or `ConverseStream`, the proxy cancels
pending upstream requests and closes streaming response bodies, including streams
that stall between tokens. The model server controls how it reacts to that signal.

Operational HTTP-error warnings retain the upstream status code and omit the
backend response body. A connected caller receives `ModelErrorException` with a
bounded backend error description. Error-body reads, for both `Converse` and
`ConverseStream`, are limited to 8 KiB with a one-second collection deadline
(`FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_ERROR_BODY_TIMEOUT_MILLIS`). If the body
stalls, the proxy closes it and returns the known model error without body details.

## Examples

### Structured outputs through the proxy

Both `Converse` and `ConverseStream` translate
`outputConfig.textFormat` with `type: "json_schema"` into OpenAI
`response_format.json_schema`. The Bedrock
`structure.jsonSchema.schema` value is a JSON string; the proxy parses it into
the schema object expected by the backend, without rewriting its properties.
Optional `name` and `description` are preserved. If the name is omitted, the
proxy uses `response`, since the OpenAI wire shape requires a name.

```python
import json

response = client.converse(
    modelId="your-mapped-model-id",
    messages=[{"role": "user", "content": [{"text": "Return a readiness label."}]}],
    outputConfig={"textFormat": {
        "type": "json_schema",
        "structure": {"jsonSchema": {
            "name": "readiness",
            "schema": json.dumps({
                "type": "object",
                "properties": {"ready": {"type": "boolean"}},
                "required": ["ready"],
                "additionalProperties": False,
            }),
        }},
    }},
)
```

The backend and model must support this response format and the supplied schema.
The proxy does not inject OpenAI's `strict` flag: that mode imposes extra schema
restrictions, such as requiring every property, that valid Bedrock schemas need
not satisfy. Schema enforcement therefore follows the backend's behavior; Floci
does not emulate Bedrock's guaranteed schema adherence. Callers should validate
returned data when testing through the proxy.
The proxy rejects malformed configuration or a schema that is not a JSON object
with `400 ValidationException`; it does not implement a JSON Schema validator or
validate generated responses. Requests without a text format keep their existing
behavior. The `stub` backend still ignores output constraints.

`outputConfig.effort` is translated to OpenAI `reasoning_effort` on both operations,
with or without `textFormat`. The supported Bedrock levels are `low`, `medium`,
`high`, `xhigh`, and `max`; the requested value is preserved without downgrading it.
The configured backend and model must support the requested effort level. In
particular, `max` is not a standard OpenAI Chat Completions level and requires a
backend that accepts it. A backend rejection is returned as `ModelErrorException`.
Malformed or unrecognized effort values produce a
`400 ValidationException` before contacting the backend.

### Inline images through the proxy

Both `Converse` and `ConverseStream` forward inline `image` blocks as OpenAI
`image_url` data URLs, preserving the order of text and images in each message.
The supported image formats are `png`, `jpeg`, `gif`, and `webp`. The configured
backend and model must support image input. Text-only messages keep their
existing string representation.

```python
with open("image.png", "rb") as image:
    response = client.converse(
        modelId="your-mapped-model-id",
        messages=[{"role": "user", "content": [
            {"text": "Describe this image."},
            {"image": {"format": "png", "source": {"bytes": image.read()}}},
        ]}],
    )
```

Supply raw bytes to the AWS SDK; it performs the wire-level Base64 encoding.
The proxy rejects unsupported formats, malformed Base64, empty byte sources,
and S3 image sources with `400 ValidationException` before contacting the backend.
Only `text` and `json` blocks are supported inside `toolResult.content`, because
OpenAI tool messages accept text and the proxy serializes JSON results to text.
Other content, including images, documents, videos, and search results, is rejected
with `400 ValidationException` before contacting the backend, rather than being
silently discarded.

It does not decode image files or enforce model-specific image dimensions and
size limits; those remain the responsibility of the configured backend.

### Text requests

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Converse
aws bedrock-runtime converse \
  --model-id anthropic.claude-3-haiku-20240307-v1:0 \
  --messages '[{"role":"user","content":[{"text":"hi"}]}]'

# InvokeModel (Anthropic Claude)
aws bedrock-runtime invoke-model \
  --model-id anthropic.claude-3-haiku-20240307-v1:0 \
  --body '{"anthropic_version":"bedrock-2023-05-31","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/response.json
cat /tmp/response.json
```

```python
import boto3
client = boto3.client("bedrock-runtime", endpoint_url="http://localhost:4566")
resp = client.converse(
    modelId="anthropic.claude-3-haiku-20240307-v1:0",
    messages=[{"role": "user", "content": [{"text": "hi"}]}],
)
print(resp["output"]["message"]["content"][0]["text"])
```

## Out of Scope

- Real model inference with the `stub` backend.
- `InvokeModel` and `InvokeModelWithResponseStream` against the `proxy` backend (still the stub).
- `ConverseStream` tool calls are still accumulated across chunks and emitted as a single complete `contentBlockDelta` once the stream ends, rather than as incremental argument fragments — SDKs consume both the same way, since they concatenate `delta.toolUse.input` across events for a given `contentBlockIndex` before parsing it as JSON.
- Bedrock management plane (`ListFoundationModels`, `GetFoundationModel`, model customisation).
- Bedrock Agents, Knowledge Bases, Guardrails, provisioned throughput.
