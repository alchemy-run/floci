package io.floci.aws.services.bedrockagent;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tags for Agents for Amazon Bedrock on the shared {@code /tags/{resourceArn}}
 * path: agents, agent aliases, and knowledge bases, all under the
 * {@code bedrock} ARN service. Guardrail ARNs aren't served here, as AWS
 * defines no {@code /tags/{arn}} route for them.
 */
@ApplicationScoped
public class BedrockAgentTagHandler implements TagHandler {
    private static final Pattern TAGGABLE = Pattern.compile(
            "^arn:" + AwsArnUtils.PARTITION_REGEX + ":bedrock:[^:]*:[0-9]{12}:(agent|agent-alias|knowledge-base|flow|prompt)/");


    private final BedrockAgentService agents;
    private final BedrockKnowledgeBaseService knowledgeBases;

    @Inject
    public BedrockAgentTagHandler(BedrockAgentService agents, BedrockKnowledgeBaseService knowledgeBases) {
        this.agents = agents;
        this.knowledgeBases = knowledgeBases;
    }

    @Override
    public String serviceKey() {
        return "bedrock";
    }

    /** Agents for Amazon Bedrock answers TagResource and UntagResource with 200, not 204. */
    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    /** Agents for Amazon Bedrock tags agents, aliases, knowledge bases, flows, and prompts. */
    @Override
    public boolean handles(String arn) {
        return TAGGABLE.matcher(arn).find();
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        requireOwned(region, arn);
        return agents.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        requireOwned(region, arn);
        agents.tag(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        requireOwned(region, arn);
        agents.untag(arn, tagKeys);
    }

    private void requireOwned(String region, String arn) {
        if (!agents.owns(arn, region) && !knowledgeBases.owns(arn, region)) {
            throw new AwsException("ResourceNotFoundException", "The resource " + arn + " was not found.", 404);
        }
    }
}
