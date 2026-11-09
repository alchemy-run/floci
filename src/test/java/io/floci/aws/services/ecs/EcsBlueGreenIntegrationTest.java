package io.floci.aws.services.ecs;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ECS native blue/green: each new deployment of a {@code BLUE_GREEN} service points the production
 * listener rule at the target group that was not serving, and the next one points it back.
 */
@QuarkusTest
class EcsBlueGreenIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String RULE_PATH = "DescribeRulesResponse.DescribeRulesResult.Rules.member.Actions.member"
            + ".ForwardConfig.TargetGroups.member";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response ecs(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action).body(body)
                .when().post("/").then().statusCode(200).extract().response();
    }

    private static XmlPath elb(String action, String... params) {
        RequestSpecification request = given().header("Authorization", ELB_AUTH).formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String targetGroup(String name) {
        return elb("CreateTargetGroup", "Name", name, "Protocol", "HTTP", "Port", "80",
                "VpcId", Ec2Service.defaultVpcId("us-east-1"), "TargetType", "ip")
                .getString("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    /** The weight each target group carries in the rule's forward action. */
    private static Map<String, String> weights(String ruleArn) {
        XmlPath rules = elb("DescribeRules", "RuleArns.member.1", ruleArn);
        List<String> arns = rules.getList(RULE_PATH + ".TargetGroupArn", String.class);
        List<String> values = rules.getList(RULE_PATH + ".Weight", String.class);
        Map<String, String> weights = new HashMap<>();
        for (int i = 0; i < arns.size(); i++) {
            weights.put(arns.get(i), values.get(i));
        }
        return weights;
    }

    @Test
    void eachDeploymentMovesProductionTrafficToTheOtherTargetGroup() {
        String lb = elb("CreateLoadBalancer", "Name", "bg-alb", "Type", "application",
                "Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"),
                "Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .getString("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
        String blue = targetGroup("bg-blue");
        String green = targetGroup("bg-green");
        String listener = elb("CreateListener", "LoadBalancerArn", lb, "Protocol", "HTTP", "Port", "8081",
                "DefaultActions.member.1.Type", "fixed-response",
                "DefaultActions.member.1.FixedResponseConfig.StatusCode", "404")
                .getString("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
        String rule = elb("CreateRule", "ListenerArn", listener, "Priority", "10",
                "Conditions.member.1.Field", "path-pattern",
                "Conditions.member.1.PathPatternConfig.Values.member.1", "/*",
                "Actions.member.1.Type", "forward",
                "Actions.member.1.ForwardConfig.TargetGroups.member.1.TargetGroupArn", blue,
                "Actions.member.1.ForwardConfig.TargetGroups.member.1.Weight", "100",
                "Actions.member.1.ForwardConfig.TargetGroups.member.2.TargetGroupArn", green,
                "Actions.member.1.ForwardConfig.TargetGroups.member.2.Weight", "0")
                .getString("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");

        ecs("CreateCluster", "{\"clusterName\":\"bg-cluster\"}");
        ecs("RegisterTaskDefinition", "{\"family\":\"bg-app\",\"networkMode\":\"awsvpc\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"portMappings\":[{\"containerPort\":80}]}]}");
        ecs("CreateService", "{\"cluster\":\"bg-cluster\",\"serviceName\":\"bg-svc\",\"taskDefinition\":\"bg-app\","
                + "\"desiredCount\":0,\"launchType\":\"FARGATE\","
                + "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":[\""
                + Ec2Service.defaultSubnetId("us-east-1", "a") + "\"]}},"
                + "\"deploymentConfiguration\":{\"strategy\":\"BLUE_GREEN\",\"bakeTimeInMinutes\":0},"
                + "\"loadBalancers\":[{\"targetGroupArn\":\"" + blue + "\",\"containerName\":\"app\","
                + "\"containerPort\":80,\"advancedConfiguration\":{\"alternateTargetGroupArn\":\"" + green + "\","
                + "\"productionListenerRule\":\"" + rule + "\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/ecs-infra\"}}]}");
        assertEquals(Map.of(blue, "100", green, "0"), weights(rule));

        String update = "{\"cluster\":\"bg-cluster\",\"service\":\"bg-svc\",\"forceNewDeployment\":true}";
        ecs("UpdateService", update);
        assertEquals(Map.of(blue, "0", green, "100"), weights(rule));

        ecs("UpdateService", update);
        assertEquals(Map.of(blue, "100", green, "0"), weights(rule));

        // A client that resends the whole load balancer configuration with each deployment, as
        // declarative tools do, still alternates: the rule, not the request, says which group serves.
        String resend = "{\"cluster\":\"bg-cluster\",\"service\":\"bg-svc\",\"forceNewDeployment\":true,"
                + "\"loadBalancers\":[{\"targetGroupArn\":\"" + blue + "\",\"containerName\":\"app\","
                + "\"containerPort\":80,\"advancedConfiguration\":{\"alternateTargetGroupArn\":\"" + green + "\","
                + "\"productionListenerRule\":\"" + rule + "\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/ecs-infra\"}}]}";
        ecs("UpdateService", resend);
        assertEquals(Map.of(blue, "0", green, "100"), weights(rule));
        ecs("UpdateService", resend);
        assertEquals(Map.of(blue, "100", green, "0"), weights(rule));
    }
}
