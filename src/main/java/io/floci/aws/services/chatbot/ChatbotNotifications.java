package io.floci.aws.services.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * How AWS Chatbot renders the SNS messages it supports: CloudWatch alarm state changes, EventBridge events
 * (each AWS service's event, with its detail type and source), and AWS Chatbot custom notifications
 * ({@code {"version": "1.0", "source": "custom", "content": {...}}}). Anything else, plain text included, isn't
 * delivered, as AWS doesn't deliver it.
 */
final class ChatbotNotifications {
    private ChatbotNotifications() {
    }

    record Rendered(String kind, String title, String text) {
    }

    static Rendered render(String subject, String message, ObjectMapper mapper) {
        JsonNode body;
        try {
            body = mapper.readTree(message);
        } catch (Exception e) {
            return null;
        }
        if (body == null || !body.isObject()) {
            return null;
        }
        if (body.has("AlarmName") && body.has("NewStateValue")) {
            String state = body.path("NewStateValue").asText();
            return new Rendered("CloudWatchAlarm", (alarmEmoji(state) + " CloudWatch Alarm | "
                    + body.path("AlarmName").asText() + " | " + body.path("Region").asText("") + " | Account: "
                    + body.path("AWSAccountId").asText("")).replace(" |  |", " |"),
                    body.path("NewStateReason").asText("") + (body.hasNonNull("AlarmDescription")
                            ? "\n" + body.path("AlarmDescription").asText() : ""));
        }
        if ("custom".equals(body.path("source").asText()) && body.path("content").isObject()) {
            JsonNode content = body.path("content");
            StringBuilder text = new StringBuilder(content.path("description").asText(""));
            for (JsonNode step : content.path("nextSteps")) {
                text.append("\n• ").append(step.asText());
            }
            return new Rendered("Custom", content.path("title").asText(subject == null ? "Notification" : subject),
                    text.toString().strip());
        }
        if (body.has("detail-type") && body.has("source") && body.path("source").asText().startsWith("aws.")) {
            String source = body.path("source").asText().substring(4);
            return new Rendered("EventBridge", body.path("detail-type").asText() + " | " + source + " | "
                    + body.path("region").asText("") + " | Account: " + body.path("account").asText(""),
                    body.path("detail").toString());
        }
        return null;
    }

    private static String alarmEmoji(String state) {
        return switch (state) {
            case "ALARM" -> "🔴";
            case "OK" -> "✅";
            default -> "⚪";
        };
    }
}
