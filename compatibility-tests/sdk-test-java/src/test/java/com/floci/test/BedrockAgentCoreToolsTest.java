package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bedrock AgentCore tools")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreToolsTest {

    private static BedrockAgentCoreControlClient client;
    private static String browserName;
    private static String browserId;
    private static String profileName;
    private static String profileId;
    private static String codeInterpreterName;
    private static String codeInterpreterId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        browserName = "browser" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        profileName = "profile" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        codeInterpreterName = "code" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Custom browser tags survive mutation and disappear with the browser")
    void browserTagLifecycle() {
        CreateBrowserResponse created = client.createBrowser(CreateBrowserRequest.builder()
                .name("tag" + browserName)
                .networkConfiguration(BrowserNetworkConfiguration.builder().networkMode(BrowserNetworkMode.PUBLIC).build())
                .tags(Map.of("env", "test", "alchemy::id", "Tool")).build());
        try {
            assertToolTagRoundTrip(created.browserArn());
        } finally {
            client.deleteBrowser(DeleteBrowserRequest.builder().browserId(created.browserId()).build());
        }
        assertTagResourceNotFound(created.browserArn());
        assertThatThrownBy(() -> client.getBrowser(GetBrowserRequest.builder().browserId(created.browserId()).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Browser profile tags use the shared tagging endpoint")
    void browserProfileTagLifecycle() {
        CreateBrowserProfileResponse created = client.createBrowserProfile(CreateBrowserProfileRequest.builder()
                .name("tag" + profileName).tags(Map.of("env", "test", "alchemy::id", "Tool")).build());
        try {
            assertToolTagRoundTrip(created.profileArn());
        } finally {
            client.deleteBrowserProfile(DeleteBrowserProfileRequest.builder().profileId(created.profileId()).build());
        }
        assertTagResourceNotFound(created.profileArn());
    }

    @Test
    @DisplayName("Custom interpreter tags survive mutation and disappear with the interpreter")
    void codeInterpreterTagLifecycle() {
        CreateCodeInterpreterResponse created = client.createCodeInterpreter(CreateCodeInterpreterRequest.builder()
                .name("tag" + codeInterpreterName)
                .networkConfiguration(CodeInterpreterNetworkConfiguration.builder()
                        .networkMode(CodeInterpreterNetworkMode.SANDBOX).build())
                .tags(Map.of("env", "test", "alchemy::id", "Tool")).build());
        try {
            assertToolTagRoundTrip(created.codeInterpreterArn());
        } finally {
            client.deleteCodeInterpreter(DeleteCodeInterpreterRequest.builder()
                    .codeInterpreterId(created.codeInterpreterId()).build());
        }
        assertTagResourceNotFound(created.codeInterpreterArn());
        assertThatThrownBy(() -> client.getCodeInterpreter(GetCodeInterpreterRequest.builder()
                .codeInterpreterId(created.codeInterpreterId()).build())).isInstanceOf(ResourceNotFoundException.class);
    }

    private void assertToolTagRoundTrip(String arn) {
        assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder().resourceArn(arn).build()).tags())
                .isEqualTo(Map.of("env", "test", "alchemy::id", "Tool"));
        String[] parts = arn.split(":", 6);
        assertTagResourceNotFound(arn.replace(":" + parts[4] + ":",
                ":" + ("111111111111".equals(parts[4]) ? "222222222222" : "111111111111") + ":"));
        assertTagResourceNotFound(arn.replace(":" + parts[3] + ":",
                ":" + ("eu-west-1".equals(parts[3]) ? "us-east-1" : "eu-west-1") + ":"));
        assertThatThrownBy(() -> client.tagResource(TagResourceRequest.builder()
                .resourceArn(arn).tags(Map.of("env", "invalid*")).build())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> client.untagResource(UntagResourceRequest.builder()
                .resourceArn(arn).tagKeys("env", "invalid*").build())).isInstanceOf(ValidationException.class);
        assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder().resourceArn(arn).build()).tags())
                .isEqualTo(Map.of("env", "test", "alchemy::id", "Tool"));

        client.tagResource(TagResourceRequest.builder().resourceArn(arn)
                .tags(Map.of("env", "prod", "team/name", "core")).build());
        assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder().resourceArn(arn).build()).tags())
                .isEqualTo(Map.of("env", "prod", "alchemy::id", "Tool", "team/name", "core"));
        client.untagResource(UntagResourceRequest.builder().resourceArn(arn)
                .tagKeys(List.of("env", "team/name", "missing")).build());
        assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder().resourceArn(arn).build()).tags())
                .isEqualTo(Map.of("alchemy::id", "Tool"));
        client.untagResource(UntagResourceRequest.builder().resourceArn(arn).tagKeys("alchemy::id").build());
        assertThat(client.listTagsForResource(ListTagsForResourceRequest.builder().resourceArn(arn).build()).tags())
                .isEmpty();
    }

    private void assertTagResourceNotFound(String arn) {
        assertThatThrownBy(() -> client.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(arn).build())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> client.tagResource(TagResourceRequest.builder()
                .resourceArn(arn).tags(Map.of("env", "stolen")).build())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> client.untagResource(UntagResourceRequest.builder()
                .resourceArn(arn).tagKeys("env").build())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(1)
    void createBrowser() {
        CreateBrowserResponse response = client.createBrowser(CreateBrowserRequest.builder()
                .name(browserName)
                .networkConfiguration(BrowserNetworkConfiguration.builder()
                        .networkMode(BrowserNetworkMode.PUBLIC)
                        .build())
                .build());

        browserId = response.browserId();
        assertThat(browserId).startsWith(browserName + "-");
        assertThat(response.browserArn()).contains(":bedrock-agentcore:").contains(":browser-custom/");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @Order(2)
    void getBrowser() {
        GetBrowserResponse response = client.getBrowser(GetBrowserRequest.builder()
                .browserId(browserId)
                .build());

        assertThat(response.browserId()).isEqualTo(browserId);
        assertThat(response.name()).isEqualTo(browserName);
        assertThat(response.browserArn()).contains(":browser-custom/");
        assertThat(response.networkConfiguration().networkModeAsString()).isEqualTo("PUBLIC");
        assertThat(response.statusAsString()).isEqualTo("READY");
    }

    @Test
    @Order(3)
    void listBrowsers() {
        ListBrowsersResponse response = client.listBrowsers(ListBrowsersRequest.builder()
                .type(ResourceType.CUSTOM)
                .maxResults(100)
                .build());

        assertThat(response.browserSummaries())
                .anyMatch(summary -> browserId.equals(summary.browserId()));
    }

    @Test
    @Order(4)
    void deleteBrowser() {
        String clientToken = UUID.randomUUID().toString();
        DeleteBrowserRequest request = DeleteBrowserRequest.builder()
                .browserId(browserId)
                .clientToken(clientToken)
                .build();
        DeleteBrowserResponse response = client.deleteBrowser(request);
        DeleteBrowserResponse replay = client.deleteBrowser(request);

        assertThat(response.browserId()).isEqualTo(browserId);
        assertThat(response.statusAsString()).isEqualTo("DELETING");
        assertThat(response.lastUpdatedAt()).isNotNull();
        assertThat(replay.browserId()).isEqualTo(browserId);
        assertThat(replay.statusAsString()).isEqualTo("DELETING");
    }

    @Test
    @Order(5)
    void createBrowserProfile() {
        CreateBrowserProfileResponse response = client.createBrowserProfile(CreateBrowserProfileRequest.builder()
                .name(profileName)
                .description("profile")
                .build());

        profileId = response.profileId();
        assertThat(profileId).startsWith(profileName + "-");
        assertThat(response.profileArn()).contains(":browser-profile/");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @Order(6)
    void getBrowserProfile() {
        GetBrowserProfileResponse response = client.getBrowserProfile(GetBrowserProfileRequest.builder()
                .profileId(profileId)
                .build());

        assertThat(response.profileId()).isEqualTo(profileId);
        assertThat(response.name()).isEqualTo(profileName);
        assertThat(response.description()).isEqualTo("profile");
        assertThat(response.statusAsString()).isEqualTo("READY");
    }

    @Test
    @Order(7)
    void listBrowserProfiles() {
        ListBrowserProfilesResponse response = client.listBrowserProfiles(ListBrowserProfilesRequest.builder()
                .name(profileName)
                .maxResults(100)
                .build());

        assertThat(response.profileSummaries())
                .anyMatch(summary -> profileId.equals(summary.profileId()));
    }

    @Test
    @Order(8)
    void deleteBrowserProfile() {
        String clientToken = UUID.randomUUID().toString();
        DeleteBrowserProfileRequest request = DeleteBrowserProfileRequest.builder()
                .profileId(profileId)
                .clientToken(clientToken)
                .build();
        DeleteBrowserProfileResponse response = client.deleteBrowserProfile(request);
        DeleteBrowserProfileResponse replay = client.deleteBrowserProfile(request);

        assertThat(response.profileId()).isEqualTo(profileId);
        assertThat(response.statusAsString()).isEqualTo("DELETING");
        assertThat(response.lastUpdatedAt()).isNotNull();
        assertThat(replay.profileId()).isEqualTo(profileId);
        assertThat(replay.statusAsString()).isEqualTo("DELETING");
    }

    @Test
    @Order(9)
    void createCodeInterpreter() {
        CreateCodeInterpreterResponse response = client.createCodeInterpreter(CreateCodeInterpreterRequest.builder()
                .name(codeInterpreterName)
                .networkConfiguration(CodeInterpreterNetworkConfiguration.builder()
                        .networkMode(CodeInterpreterNetworkMode.PUBLIC)
                        .build())
                .build());

        codeInterpreterId = response.codeInterpreterId();
        assertThat(codeInterpreterId).startsWith(codeInterpreterName + "-");
        assertThat(response.codeInterpreterArn()).contains(":code-interpreter-custom/");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @Order(10)
    void getCodeInterpreter() {
        GetCodeInterpreterResponse response = client.getCodeInterpreter(GetCodeInterpreterRequest.builder()
                .codeInterpreterId(codeInterpreterId)
                .build());

        assertThat(response.codeInterpreterId()).isEqualTo(codeInterpreterId);
        assertThat(response.name()).isEqualTo(codeInterpreterName);
        assertThat(response.networkConfiguration().networkModeAsString()).isEqualTo("PUBLIC");
        assertThat(response.statusAsString()).isEqualTo("READY");
    }

    @Test
    @Order(11)
    void listCodeInterpreters() {
        ListCodeInterpretersResponse response = client.listCodeInterpreters(ListCodeInterpretersRequest.builder()
                .type(ResourceType.CUSTOM)
                .maxResults(100)
                .build());

        assertThat(response.codeInterpreterSummaries())
                .anyMatch(summary -> codeInterpreterId.equals(summary.codeInterpreterId()));
    }

    @Test
    @Order(12)
    void deleteCodeInterpreter() {
        String clientToken = UUID.randomUUID().toString();
        DeleteCodeInterpreterRequest request = DeleteCodeInterpreterRequest.builder()
                .codeInterpreterId(codeInterpreterId)
                .clientToken(clientToken)
                .build();
        DeleteCodeInterpreterResponse response = client.deleteCodeInterpreter(request);
        DeleteCodeInterpreterResponse replay = client.deleteCodeInterpreter(request);

        assertThat(response.codeInterpreterId()).isEqualTo(codeInterpreterId);
        assertThat(response.statusAsString()).isEqualTo("DELETING");
        assertThat(response.lastUpdatedAt()).isNotNull();
        assertThat(replay.codeInterpreterId()).isEqualTo(codeInterpreterId);
        assertThat(replay.statusAsString()).isEqualTo("DELETING");
    }
}
