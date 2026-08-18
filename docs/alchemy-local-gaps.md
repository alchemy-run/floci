# Alchemy local-dev gaps

Alchemy runs its live AWS suites against Floci with `ALCHEMY_TEST_DEV=1`
(`ALCHEMY_FLOCI_IMAGE=floci:dev` while iterating). This page is the order
book for emulator gaps those suites surface. Control-plane CRUD that already
works is not listed.

## Policy

- **`UnknownOperationException` / CloudWatch `UnsupportedOperation` = implement the operation.** These are missing handler cases, not environment problems. Add the action to the service handler, store enough in-memory state to satisfy AWS-shaped describe/list/update, write a Floci integration test, and update `docs/services/{service}.md` plus the count in `docs/services/index.md`. Do **not** paper them over with `isLocalEmulator` skips in Alchemy.
- **Wrong-shape responses** (extra fields, dropped `Condition`, leftover `StreamViewType`) are also patches — match live AWS.
- **Services with no Floci implementation at all** stay remote-only in Alchemy (`Alchemy.remote()` / live provider). Do not invent a new service here.
- Alchemy `isLocalEmulator` skips stay only for host/environment issues (port 80, advertised Function URL hostname) until the data plane is actually reachable.

## S3

Patched in this tree (tagging `NoSuchTagSet`, singleton policy collapse,
accelerate, replication, intelligent-tiering including list). Remaining:

| Gap | Evidence | Notes |
|---|---|---|
| Website data plane on `*.s3-website-*.amazonaws.com:80` | Alchemy `ownership controls and website hosting serves index.html` | Gateway already serves website Host headers on `:4566`. Host port 80 + public DNS is an environment problem; alchemy rewrites those URLs onto the gateway under `ALCHEMY_TEST_DEV`. |

## DynamoDB

| Gap | Evidence | Notes |
|---|---|---|
| Disable stream leaves `StreamViewType` | `Table.test.ts` stream-binding case: after `StreamEnabled=false`, describe still returns `KEYS_ONLY` | AWS describe after disable omits the view type (alchemy expects `undefined`). |
| `DescribeContributorInsights` / `UpdateContributorInsights` | `UnknownOperationException` | Not in the DynamoDB action table. CloudWatch `DescribeInsightRules` is also missing (`UnsupportedOperation`). |

## Lambda

| Gap | Evidence | Notes |
|---|---|---|
| Function URL HTTP from the host | `Function.test.ts` `create, update, delete function` hangs on `GET functionUrl` | Create/delete work. Invoke via SDK timeout test is fine. Data-plane URL may not be reachable at the advertised hostname/port. |
| IAM inline policy `Condition` | Same test: `GetRolePolicy` omits `lambda:InvokedViaFunctionUrl` | Alchemy asserts the Function URL invoke condition. |
| CloudWatch Logs log-group reap | Prior destroy hangs | Logs describe/delete is slow or unimplemented enough that alchemy now skips reap on the emulator account. |

## IAM

| Gap | Evidence | Notes |
|---|---|---|
| `GetRolePolicy` drops `Condition` | Lambda Function URL policy | Store and return the full statement, including `Condition`. |

## CloudWatch / CloudWatch Logs

| Gap | Evidence | Notes |
|---|---|---|
| `DescribeInsightRules` | DynamoDB Contributor Insights teardown | Returns `UnsupportedOperation`. |
| Log group lifecycle used by Lambda delete | Destroy hang before alchemy skip | Need cheap no-op or fast not-found for `/aws/lambda/*`. |

## How to reproduce

```bash
# from alchemy-effect, after `pnpm floci:build`
ALCHEMY_TEST_DEV=1 ALCHEMY_FLOCI_IMAGE=floci:dev \
  pnpm test test/AWS/S3/Bucket.test.ts --profile testing --retry 0
ALCHEMY_TEST_DEV=1 ALCHEMY_FLOCI_IMAGE=floci:dev \
  pnpm test test/AWS/{DynamoDB/Table,Lambda/Function,Kinesis/Stream,SQS/Queue,SNS/Topic}.test.ts \
  --profile testing --retry 0
```
