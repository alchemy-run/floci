# EC2 Image Builder

**Protocol:** REST JSON (`PUT`/`GET`/`POST`/`DELETE /<Operation>`, `/tags/<arn>`)
**Endpoint:** `http://localhost:4566/`, signed for `imagebuilder`.

Components, recipes, infrastructure and distribution configurations, workflows, pipelines and lifecycle policies are
managed over the REST API, and images are built for real. An AMI build runs its build workflow on an EC2 instance
backed by a Docker container (with `floci.services.ec2.mock` off): it launches the instance from the recipe's parent
image, runs the recipe's components with AWSTOE through SSM `SendCommand`, and creates an AMI. The test workflow
launches a second instance from that AMI and runs the components' test phase. Distribution copies the AMI into each
Region of the distribution configuration with its name (`{{ imagebuilder:buildDate }}` and
`{{ imagebuilder:buildVersion }}` rendered), tags, launch template and SSM parameter updates, then deregisters the
build AMI. AWSTOE is downloaded once from `floci.services.imagebuilder.awstoe-url` and cached.

A container build renders the recipe's Dockerfile template (`{{{ imagebuilder:parentImage }}}`,
`{{{ imagebuilder:environments }}}`, `{{{ imagebuilder:components }}}`), runs the components with AWSTOE inside a
local Docker build, tests the image by running the test phase in a container from it, and pushes it to the recipe's
ECR target repository.

Custom workflows run step by step with Image Builder's actions, conditions (`if`), JSONPath inputs, retries, timeouts
and rollback. A `WaitForAction` step pauses the build until `SendWorkflowStepAction` resumes or stops it. Every build
records workflow executions and step executions, sends an "EC2 Image Builder Image State Change" event to
EventBridge, publishes to the infrastructure configuration's SNS topic and writes logs to CloudWatch Logs.

Pipelines start on `StartImagePipelineExecution` and on their cron or rate schedule, honouring
`EXPRESSION_MATCH_AND_DEPENDENCY_UPDATES_AVAILABLE`. Lifecycle policies are evaluated every
`floci.services.imagebuilder.lifecycle-interval-seconds` (3600) and on demand, applying count and age rules with
their exclusions to images and their AMIs or container images. Scan findings come from Inspector.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CancelImageCreation` | Cancel a build that has not finished |
| `CancelLifecycleExecution` | Cancel a running lifecycle policy execution |
| `CreateComponent` | Create a component version from YAML data or an S3 URI |
| `CreateContainerRecipe` | Create a container recipe with a Dockerfile template and an ECR target repository |
| `CreateDistributionConfiguration` | Create per-Region AMI and container distribution settings |
| `CreateImage` | Build an image now from a recipe and infrastructure configuration |
| `CreateImagePipeline` | Create a pipeline that builds on a schedule or on demand |
| `CreateImageRecipe` | Create an image recipe from a parent image and components |
| `CreateInfrastructureConfiguration` | Create the instance type, profile, network, logging and SNS settings for builds |
| `CreateLifecyclePolicy` | Create a count or age policy that disables, deprecates or deletes images |
| `CreateWorkflow` | Create a validated build, test or distribution workflow |
| `DeleteComponent` | Delete a component build version |
| `DeleteContainerRecipe` | Delete a container recipe |
| `DeleteDistributionConfiguration` | Delete a distribution configuration |
| `DeleteImage` | Delete a finished image build version |
| `DeleteImagePipeline` | Delete a pipeline |
| `DeleteImageRecipe` | Delete an image recipe |
| `DeleteInfrastructureConfiguration` | Delete an infrastructure configuration |
| `DeleteLifecyclePolicy` | Delete a lifecycle policy |
| `DeleteWorkflow` | Delete a workflow build version |
| `DistributeImage` | Distribute an existing AMI with a distribution configuration |
| `GetComponent` | Get a component build version |
| `GetComponentPolicy` | Get a component's resource policy |
| `GetContainerRecipe` | Get a container recipe |
| `GetContainerRecipePolicy` | Get a container recipe's resource policy |
| `GetDistributionConfiguration` | Get a distribution configuration |
| `GetImage` | Get an image build version with its state and outputs |
| `GetImagePipeline` | Get a pipeline |
| `GetImagePolicy` | Get an image's resource policy |
| `GetImageRecipe` | Get an image recipe |
| `GetImageRecipePolicy` | Get an image recipe's resource policy |
| `GetInfrastructureConfiguration` | Get an infrastructure configuration |
| `GetLifecycleExecution` | Get a lifecycle policy execution |
| `GetLifecyclePolicy` | Get a lifecycle policy |
| `GetMarketplaceResource` | Fails with `ResourceNotFoundException`; there are no Marketplace components |
| `GetWorkflow` | Get a workflow build version |
| `GetWorkflowExecution` | Get a workflow execution of a build |
| `GetWorkflowStepExecution` | Get a step execution with its inputs and outputs |
| `ImportComponent` | Import a shell script as a component |
| `ImportDiskImage` | Record a disk image import, which fails since ISOs are not converted |
| `ImportVmImage` | Import an EC2 image as an Image Builder image |
| `ListComponentBuildVersions` | List a component version's build versions |
| `ListComponents` | List components, owned or Amazon-managed |
| `ListContainerRecipes` | List container recipes |
| `ListDistributionConfigurations` | List distribution configurations |
| `ListImageBuildVersions` | List an image version's build versions |
| `ListImagePackages` | List the packages installed in an image |
| `ListImagePipelineImages` | List the images a pipeline built |
| `ListImagePipelines` | List pipelines |
| `ListImageRecipes` | List image recipes |
| `ListImageScanFindingAggregations` | Aggregate an image's Inspector findings by severity |
| `ListImageScanFindings` | List an image's Inspector findings |
| `ListImages` | List images, owned or Amazon-managed |
| `ListInfrastructureConfigurations` | List infrastructure configurations |
| `ListLifecycleExecutionResources` | List the resources a lifecycle execution acted on |
| `ListLifecycleExecutions` | List a policy's or resource's lifecycle executions |
| `ListLifecyclePolicies` | List lifecycle policies |
| `ListWaitingWorkflowSteps` | List `WaitForAction` steps waiting for an action |
| `ListWorkflowBuildVersions` | List a workflow version's build versions |
| `ListWorkflowExecutions` | List a build's workflow executions |
| `ListWorkflowStepExecutions` | List a workflow execution's steps |
| `ListWorkflows` | List workflows, owned or Amazon-managed |
| `PutComponentPolicy` | Set a component's resource policy |
| `PutContainerRecipePolicy` | Set a container recipe's resource policy |
| `PutImagePolicy` | Set an image's resource policy |
| `PutImageRecipePolicy` | Set an image recipe's resource policy |
| `RetryImage` | Rebuild a failed or cancelled image with its original settings |
| `SendWorkflowStepAction` | Resume or stop a build waiting at a `WaitForAction` step |
| `StartImagePipelineExecution` | Start a pipeline build now |
| `StartResourceStateUpdate` | Disable, deprecate or delete an image and its resources |
| `UpdateDistributionConfiguration` | Update a distribution configuration |
| `UpdateImagePipeline` | Update a pipeline |
| `UpdateInfrastructureConfiguration` | Update an infrastructure configuration |
| `UpdateLifecyclePolicy` | Update a lifecycle policy |
| `ListTagsForResource` | List a resource's tags |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    imagebuilder:
      enabled: true
      awstoe-url: https://awstoe-{region}.s3.{region}.amazonaws.com/latest/linux/{arch}/awstoe
      lifecycle-interval-seconds: 3600
```
