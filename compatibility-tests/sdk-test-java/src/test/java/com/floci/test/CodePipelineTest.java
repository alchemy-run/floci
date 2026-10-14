package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.ActionCategory;
import software.amazon.awssdk.services.codepipeline.model.ActionDeclaration;
import software.amazon.awssdk.services.codepipeline.model.ActionExecutionNotFoundException;
import software.amazon.awssdk.services.codepipeline.model.ActionNotFoundException;
import software.amazon.awssdk.services.codepipeline.model.ActionOwner;
import software.amazon.awssdk.services.codepipeline.model.ActionRevision;
import software.amazon.awssdk.services.codepipeline.model.ActionTypeId;
import software.amazon.awssdk.services.codepipeline.model.ActionTypeNotFoundException;
import software.amazon.awssdk.services.codepipeline.model.ArtifactStore;
import software.amazon.awssdk.services.codepipeline.model.ArtifactStoreType;
import software.amazon.awssdk.services.codepipeline.model.ConditionNotOverridableException;
import software.amazon.awssdk.services.codepipeline.model.CreatePipelineResponse;
import software.amazon.awssdk.services.codepipeline.model.DeleteWebhookResponse;
import software.amazon.awssdk.services.codepipeline.model.FailureDetails;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineResponse;
import software.amazon.awssdk.services.codepipeline.model.JobNotFoundException;
import software.amazon.awssdk.services.codepipeline.model.ListPipelinesResponse;
import software.amazon.awssdk.services.codepipeline.model.ListWebhooksResponse;
import software.amazon.awssdk.services.codepipeline.model.NotLatestPipelineExecutionException;
import software.amazon.awssdk.services.codepipeline.model.OutputArtifact;
import software.amazon.awssdk.services.codepipeline.model.PipelineDeclaration;
import software.amazon.awssdk.services.codepipeline.model.PipelineExecutionNotFoundException;
import software.amazon.awssdk.services.codepipeline.model.PutWebhookResponse;
import software.amazon.awssdk.services.codepipeline.model.StageDeclaration;
import software.amazon.awssdk.services.codepipeline.model.UpdatePipelineResponse;
import software.amazon.awssdk.services.codepipeline.model.WebhookAuthenticationType;
import software.amazon.awssdk.services.codepipeline.model.WebhookDefinition;
import software.amazon.awssdk.services.codepipeline.model.WebhookFilterRule;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodePipelineTest {
    private static final String PIPELINE = "sdk-codepipeline";
    private static CodePipelineClient codepipeline;

    @BeforeAll
    static void setup() {
        codepipeline = TestFixtures.codePipelineClient();
    }

    @AfterAll
    static void teardown() {
        codepipeline.close();
    }

    @Test
    @Order(1)
    void createAndGetPipeline() {
        CreatePipelineResponse created = codepipeline.createPipeline(r -> r.pipeline(declaration("Deploy")));
        assertThat(created.pipeline().name()).isEqualTo(PIPELINE);
        assertThat(created.pipeline().version()).isEqualTo(1);

        GetPipelineResponse fetched = codepipeline.getPipeline(r -> r.name(PIPELINE));
        assertThat(fetched.pipeline().stages()).hasSize(2);
        assertThat(fetched.metadata().pipelineArn()).endsWith(":" + PIPELINE);
    }

    @Test
    @Order(2)
    void updateAndListPipeline() {
        UpdatePipelineResponse updated = codepipeline.updatePipeline(r -> r.pipeline(declaration("Release")));
        assertThat(updated.pipeline().version()).isEqualTo(2);
        assertThat(updated.pipeline().stages().get(1).name()).isEqualTo("Release");

        ListPipelinesResponse listed = codepipeline.listPipelines();
        assertThat(listed.pipelines()).extracting(p -> p.name()).contains(PIPELINE);
    }

    @Test
    @Order(3)
    void webhookResponsesUseSdkModelShape() {
        WebhookDefinition definition = WebhookDefinition.builder()
                .name("sdk-webhook")
                .targetPipeline(PIPELINE)
                .targetAction("Source")
                .authentication(WebhookAuthenticationType.UNAUTHENTICATED)
                .filters(WebhookFilterRule.builder()
                        .jsonPath("$.ref")
                        .matchEquals("refs/heads/main")
                        .build())
                .build();

        PutWebhookResponse created = codepipeline.putWebhook(r -> r.webhook(definition));
        assertThat(created.webhook().definition()).isEqualTo(definition);
        assertThat(created.webhook().arn()).endsWith(":webhook/sdk-webhook");

        ListWebhooksResponse listed = codepipeline.listWebhooks();
        assertThat(listed.webhooks()).singleElement()
                .extracting(webhook -> webhook.definition().name())
                .isEqualTo("sdk-webhook");

        DeleteWebhookResponse deleted = codepipeline.deleteWebhook(r -> r.name("sdk-webhook"));
        assertThat(deleted.sdkFields()).isEmpty();
    }

    @Test
    @Order(3)
    void invalidStageAndWorkerTargetsDecodeAsSdkExceptions() {
        String missingId = "00000000-0000-0000-0000-000000000000";
        assertThatThrownBy(() -> codepipeline.retryStageExecution(r -> r.pipelineName(PIPELINE)
                .stageName("Release").pipelineExecutionId(missingId).retryMode("FAILED_ACTIONS")))
                .isInstanceOf(NotLatestPipelineExecutionException.class);
        assertThatThrownBy(() -> codepipeline.rollbackStage(r -> r.pipelineName(PIPELINE)
                .stageName("Release").targetPipelineExecutionId(missingId)))
                .isInstanceOf(PipelineExecutionNotFoundException.class);
        assertThatThrownBy(() -> codepipeline.overrideStageCondition(r -> r.pipelineName(PIPELINE)
                .stageName("Release").pipelineExecutionId(missingId).conditionType("BEFORE_ENTRY")))
                .isInstanceOf(ConditionNotOverridableException.class);
        assertThatThrownBy(() -> codepipeline.putActionRevision(r -> r.pipelineName(PIPELINE)
                .stageName("Source").actionName("MissingAction").actionRevision(ActionRevision.builder()
                        .revisionId("revision").revisionChangeId("change").created(Instant.EPOCH).build())))
                .isInstanceOf(ActionNotFoundException.class);
        assertThatThrownBy(() -> codepipeline.listDeployActionExecutionTargets(r -> r.pipelineName(PIPELINE)
                .actionExecutionId(missingId))).isInstanceOf(ActionExecutionNotFoundException.class);
        assertThatThrownBy(() -> codepipeline.getJobDetails(r -> r.jobId(missingId)))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> codepipeline.putJobSuccessResult(r -> r.jobId(missingId)))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> codepipeline.putJobFailureResult(r -> r.jobId(missingId)
                .failureDetails(FailureDetails.builder().type("JobFailed").message("missing job").build())))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> codepipeline.acknowledgeJob(r -> r.jobId(missingId).nonce(missingId)))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> codepipeline.pollForJobs(r -> r.actionTypeId(ActionTypeId.builder()
                .category(ActionCategory.BUILD).owner(ActionOwner.CUSTOM).provider("MissingSdkWorker")
                .version("1").build()))).isInstanceOf(ActionTypeNotFoundException.class);
    }

    @Test
    @Order(4)
    void deletePipeline() {
        codepipeline.deletePipeline(r -> r.name(PIPELINE));
        assertThat(codepipeline.listPipelines().pipelines())
                .extracting(p -> p.name())
                .doesNotContain(PIPELINE);
    }

    private static PipelineDeclaration declaration(String secondStage) {
        return PipelineDeclaration.builder()
                .name(PIPELINE)
                .roleArn("arn:aws:iam::000000000000:role/codepipeline-role")
                .artifactStore(ArtifactStore.builder()
                        .type(ArtifactStoreType.S3)
                        .location("codepipeline-artifacts")
                        .build())
                .stages(
                        StageDeclaration.builder()
                                .name("Source")
                                .actions(ActionDeclaration.builder()
                                        .name("Source")
                                        .actionTypeId(ActionTypeId.builder()
                                                .category(ActionCategory.SOURCE)
                                                .owner(ActionOwner.AWS)
                                                .provider("S3")
                                                .version("1")
                                                .build())
                                        .configuration(Map.of(
                                                "S3Bucket", "codepipeline-source",
                                                "S3ObjectKey", "source.zip"))
                                        .outputArtifacts(OutputArtifact.builder().name("SourceOutput").build())
                                        .build())
                                .build(),
                        StageDeclaration.builder()
                                .name(secondStage)
                                .actions(ActionDeclaration.builder()
                                        .name("Approval")
                                        .actionTypeId(ActionTypeId.builder()
                                                .category(ActionCategory.APPROVAL)
                                                .owner(ActionOwner.AWS)
                                                .provider("Manual")
                                                .version("1")
                                                .build())
                                        .build())
                                .build())
                .build();
    }
}
