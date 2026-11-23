# Lambda Customer-Managed Runtimes

**Protocol:** REST JSON
**Endpoints:** `http://localhost:4566/2026-10-01/runtimes/...` (runtimes, versions, aliases) and `http://localhost:4566/2026-07-09/resource-policy/...` (runtime sharing policies)

Customer-managed runtimes are part of the `Lambda Core` service model (apiVersion 2026-04-30, `aws lambda-core` in the AWS CLI), which signs as `lambda`, so they are served under Floci's existing lambda service and are enabled by it. A runtime holds immutable versions, each made from one container image in ECR, and aliases that point at a version. A Lambda Event Function with `Runtime: custom` and a `RuntimeConfig` runs its .zip code on the image of the version its alias points to. Tagging rides the classic `/2017-03-31/tags/` routes; a runtime's resource-based policy uses Lambda's generic `/2026-07-09/resource-policy/` routes.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRuntime` | Create a runtime with tags, description, license and lifecycle |
| `ListRuntimes` | List runtimes, including those scheduled for deletion |
| `GetRuntime` | Runtime detail, by name or ARN |
| `UpdateRuntime` | Replace the description, license and lifecycle |
| `DeleteRuntime` | Schedule a runtime with no live versions or aliases for deletion |
| `RestoreRuntime` | Restore a runtime, version or alias scheduled for deletion |
| `CreateRuntimeVersion` | Create an immutable version from an ECR image, resolved to a digest |
| `ListRuntimeVersions` | List a runtime's versions |
| `GetRuntimeVersion` | Version detail with its resolved image and compatibility |
| `UpdateRuntimeVersion` | Replace a version's release notes |
| `DeleteRuntimeVersion` | Schedule a version no live alias points to for deletion |
| `CreateRuntimeAlias` | Create an alias that points at a live version |
| `ListRuntimeAliases` | List a runtime's aliases |
| `GetRuntimeAlias` | Alias detail with its version, compatibility and lifecycle |
| `UpdateRuntimeAlias` | Point an alias at another version |
| `DeleteRuntimeAlias` | Schedule an alias for deletion |
| `PutResourcePolicy` | Set a runtime's sharing policy with an optional `RevisionId` |
| `GetResourcePolicy` | A runtime's sharing policy |
| `DeleteResourcePolicy` | Remove a runtime's sharing policy |
<!-- floci:actions:end -->

## Behavior

- **Runtime functions:** `CreateFunction` and `UpdateFunctionConfiguration` take `Runtime: custom` with `RuntimeConfig` (`RuntimeArn`, which may be the runtime's name, and `RuntimeAlias`). The alias must exist and point at a live version, or the request is `400 InvalidParameterValueException`. The response carries `RuntimeConfig` with the full runtime ARN and `RuntimeVersionConfig.RuntimeVersionArn` naming the version the alias points to now. Each cold start resolves the alias again, so pointing the alias at a new version reaches the function's next execution environment without updating the function. Switching back to a managed runtime drops `RuntimeConfig`.
- **Images:** `CreateRuntimeVersion` resolves `ImageUri` to a digest through Floci's ECR (a digest-pinned URI is taken as is) and fails with `400 InvalidParameterValueException` when the image can't be found. Versions settle at once: the create response reports `CREATING` and every read after it `ACTIVE`. Floci doesn't read the image's platform, so a version advertises `x86_64` and function architectures are not checked against it.
- **Deletion:** a delete schedules the resource for deletion (`PENDING_DELETION`, `PendingDeletionAt`) after a 7–30 day window (`x-amzn-runtime-recovery-window-in-days`, default 30); a value outside that range is `400`. The name stays reserved until the window ends, and deleting a resource already scheduled keeps its window. Lambda's rules apply: a runtime with live versions or aliases, and a version a live alias points to, can't be deleted (`409 ResourceConflictException`).
- **Restore:** `RestoreRuntime` restores the runtime, or with `RuntimeVersion` or `RuntimeAlias` one of its children. A child restores only after its runtime ("Could not restore! Restore the runtime first."), and an alias only after the version it points to ("Could not restore! Restore the runtime version first.").
- **Tags and policies:** tags apply to the runtime only; a version or alias ARN is `400`. `PutResourcePolicy`, `GetResourcePolicy` and `DeleteResourcePolicy` take the runtime ARN with an optional `RevisionId` (`412 PreconditionFailedException` on a mismatch). Floci stores the policy but doesn't evaluate it: cross-account use isn't checked.
