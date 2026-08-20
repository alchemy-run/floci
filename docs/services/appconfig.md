# AppConfig

Floci supports AWS AppConfig and AppConfigData for local configuration management.

## Management Plane (AppConfig)

The management plane allows you to create and manage applications, environments, configuration profiles, and hosted configuration versions.

### Supported Operations

- `CreateApplication`
- `GetApplication`
- `ListApplications`
- `UpdateApplication`
- `DeleteApplication`
- `CreateEnvironment`
- `GetEnvironment`
- `ListEnvironments`
- `UpdateEnvironment`
- `DeleteEnvironment`
- `CreateConfigurationProfile`
- `GetConfigurationProfile`
- `ListConfigurationProfiles`
- `UpdateConfigurationProfile`
- `DeleteConfigurationProfile`
- `ValidateConfiguration`
- `CreateHostedConfigurationVersion`
- `GetHostedConfigurationVersion`
- `ListHostedConfigurationVersions`
- `DeleteHostedConfigurationVersion`
- `CreateDeploymentStrategy`
- `GetDeploymentStrategy`
- `ListDeploymentStrategies`
- `UpdateDeploymentStrategy`
- `DeleteDeploymentStrategy`
- `StartDeployment` — honors `DeploymentDurationInMinutes`. A strategy with duration `0` completes immediately (`COMPLETE`); a positive duration stays `DEPLOYING` until the duration elapses or `StopDeployment` rolls it back. Instant completions still fire `ON_DEPLOYMENT_START` then `ON_DEPLOYMENT_COMPLETE`.
- `GetDeployment`
- `StopDeployment` — in-flight (`DEPLOYING` / `BAKING` / `VALIDATING`) deployments transition to `ROLLED_BACK` and fire `ON_DEPLOYMENT_ROLLED_BACK`. Terminal states return `BadRequestException`.
- `CreateExtension`
- `GetExtension`
- `ListExtensions`
- `UpdateExtension`
- `DeleteExtension`
- `CreateExtensionAssociation`
- `GetExtensionAssociation`
- `ListExtensionAssociations`
- `UpdateExtensionAssociation`
- `DeleteExtensionAssociation`

On deployment start / complete / rollback, Floci fires associated extension actions:

- **Lambda** (`arn:aws:lambda:…:function:…`) — async `InvocationType.Event` with the AWS extension payload (`InvocationId`, `Type` such as `OnDeploymentComplete`, `Application` / `Environment` / `ConfigurationProfile` objects, `DeploymentNumber`).
- **EventBridge** (`arn:aws:events:…:event-bus/…`) — `PutEvents` with `Source=aws.appconfig` and `DetailType` like `On Deployment Complete`.

Associations on the application, environment, or configuration profile all match. `ON_*` actions are fire-and-forget and run after `StartDeployment` / `StopDeployment` returns so a Lambda that started the rollout can finish before it is invoked again.
- `TagResource` / `UntagResource` / `ListTagsForResource` (applications, environments, profiles, strategies, extensions, associations)

## Data Plane (AppConfigData) {#data-plane}

The data plane is used by applications to retrieve the active configuration for an environment and profile.

### Supported Operations

- `StartConfigurationSession`
- `GetLatestConfiguration`

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPCONFIG_ENABLED` | `true` | Enable or disable the AppConfig management plane |
| `FLOCI_SERVICES_APPCONFIGDATA_ENABLED` | `true` | Enable or disable the AppConfigData retrieval plane |

## Example Usage

### 1. Create an Application and Environment

```bash
# Create application
aws appconfig create-application --name my-app --endpoint-url http://localhost:4566

# Create environment
aws appconfig create-environment --application-id <app-id> --name dev --endpoint-url http://localhost:4566
```

### 2. Create a Hosted Configuration

```bash
# Create configuration profile
aws appconfig create-configuration-profile \
  --application-id <app-id> \
  --name my-profile \
  --location-uri hosted \
  --type AWS.Freeform \
  --endpoint-url http://localhost:4566

# Create hosted configuration version
aws appconfig create-hosted-configuration-version \
  --application-id <app-id> \
  --configuration-profile-id <profile-id> \
  --content "{\"foo\": \"bar\"}" \
  --content-type application/json \
  --endpoint-url http://localhost:4566
```

### 3. Deploy the Configuration

```bash
# Create immediate deployment strategy
aws appconfig create-deployment-strategy \
  --name immediate \
  --deployment-duration-in-minutes 0 \
  --growth-factor 100 \
  --final-bake-time-in-minutes 0 \
  --endpoint-url http://localhost:4566

# Start deployment
aws appconfig start-deployment \
  --application-id <app-id> \
  --environment-id <env-id> \
  --configuration-profile-id <profile-id> \
  --configuration-version 1 \
  --deployment-strategy-id <strategy-id> \
  --endpoint-url http://localhost:4566
```

### 4. Retrieve Configuration via Data Plane

```bash
# Start configuration session
TOKEN=$(aws appconfigdata start-configuration-session \
  --application-identifier <app-id> \
  --environment-identifier <env-id> \
  --configuration-profile-identifier <profile-id> \
  --query "InitialConfigurationToken" --output text \
  --endpoint-url http://localhost:4566)

# Get latest configuration
aws appconfigdata get-latest-configuration \
  --configuration-token $TOKEN \
  --endpoint-url http://localhost:4566
```
