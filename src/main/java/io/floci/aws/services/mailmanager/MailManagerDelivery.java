package io.floci.aws.services.mailmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ses.SesService;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs an accepted message through its ingress point's rule set. Every rule whose conditions hold (and whose unless
 * conditions don't) runs its actions in order: archive, write to S3, send through SES, relay over SMTP (with the
 * relay's Secrets Manager credentials), publish to SNS, invoke Lambda, add a header, replace the recipients, bounce, or
 * drop, which ends processing. A failed action ends processing when its failure policy is {@code DROP}. Mailboxes
 * (WorkMail) and Amazon Q Business aren't in Floci, so those actions fail.
 */
@ApplicationScoped
public class MailManagerDelivery {

    private static final Logger LOG = Logger.getLogger(MailManagerDelivery.class);

    private final MailManagerStore store;
    private final MailManagerArchives archives;
    private final ObjectMapper mapper;
    private final Provider<S3Service> s3;
    private final Provider<SesService> ses;
    private final Provider<SnsService> sns;
    private final Provider<LambdaService> lambda;
    private final Provider<SecretsManagerService> secrets;

    @Inject
    public MailManagerDelivery(MailManagerStore store, MailManagerArchives archives, Provider<S3Service> s3,
                               Provider<SesService> ses, Provider<SnsService> sns, Provider<LambdaService> lambda,
                               Provider<SecretsManagerService> secrets) {
        this.store = store;
        this.archives = archives;
        this.mapper = store.mapper();
        this.s3 = s3;
        this.ses = ses;
        this.sns = sns;
        this.lambda = lambda;
        this.secrets = secrets;
    }

    boolean isMember(String account, String region, String listIdOrArn, String address) {
        String id = MailManagerStore.idOf(listIdOrArn);
        return store.getForAccount(account, region, MailManagerStore.MEMBER, id + "/"
                + MailManagerResources.normalize(MailManagerMime.bare(address))).isPresent();
    }

    /** Processes a message and returns its id. */
    String deliver(String account, String region, ObjectNode ingress, MailManagerPolicies.Mail received, byte[] raw) {
        String messageId = UUID.randomUUID().toString();
        ObjectNode ruleSet = store.getForAccount(account, region, MailManagerStore.RULE_SET,
                ingress.path("RuleSetId").asText()).orElse(null);
        if (ruleSet == null) {
            return messageId;
        }
        byte[] message = raw;
        List<String> recipients = new ArrayList<>(received.recipients());
        MailManagerPolicies.Mail mail = received;
        rules:
        for (JsonNode rule : ruleSet.path("Rules")) {
            if (!MailManagerPolicies.applies(rule, mail, (list, address) -> isMember(account, region, list,
                    address))) {
                continue;
            }
            for (JsonNode action : rule.path("Actions")) {
                String kind = action.fieldNames().hasNext() ? action.fieldNames().next() : "";
                JsonNode spec = action.path(kind);
                try {
                    switch (kind) {
                        case "Drop" -> {
                            break rules;
                        }
                        case "AddHeader" -> {
                            message = MailManagerMime.addHeader(message, spec.path("HeaderName").asText(),
                                    spec.path("HeaderValue").asText());
                            mail = withMessage(mail, recipients, message);
                        }
                        case "ReplaceRecipient" -> {
                            recipients.clear();
                            spec.path("ReplaceWith").forEach(r -> recipients.add(r.asText()));
                            mail = withMessage(mail, recipients, message);
                        }
                        case "Archive" -> archives.archive(account, region, spec.path("TargetArchive").asText(),
                                ingress, ruleSet, mail, message, messageId);
                        case "WriteToS3" -> {
                            String prefix = spec.path("S3Prefix").asText("");
                            String key = (prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/")
                                    + messageId;
                            s3.get().putObject(spec.path("S3Bucket").asText(), key, message, "message/rfc822",
                                    Map.of());
                        }
                        case "Send" -> ses.get().sendRawEmail(mail.mailFrom(), recipients,
                                new String(message, StandardCharsets.UTF_8), null, null, null, null, region);
                        case "Relay" -> relay(account, region, spec, mail, recipients, message, ingress);
                        case "PublishToSns" -> sns.get().publish(spec.path("TopicArn").asText(), null,
                                notification(spec, mail, recipients, message, messageId, ingress, ruleSet), null,
                                region);
                        case "InvokeLambda" -> invoke(spec, mail, recipients, message, messageId, ingress, region);
                        case "Bounce" -> {
                            bounce(spec, mail, region);
                            break rules;
                        }
                        case "DeliverToMailbox" -> throw new AwsException("ActionFailed",
                                "WorkMail mailboxes aren't available in Floci.", 400);
                        case "DeliverToQBusiness" -> throw new AwsException("ActionFailed",
                                "Amazon Q Business indexes aren't available to Mail Manager in Floci.", 400);
                        default -> throw new AwsException("ActionFailed", "Unknown action " + kind + ".", 400);
                    }
                } catch (RuntimeException e) {
                    LOG.debugv("Mail Manager action {0} on message {1} failed: {2}", kind, messageId, e.getMessage());
                    if ("DROP".equals(spec.path("ActionFailurePolicy").asText("CONTINUE"))) {
                        break rules;
                    }
                }
            }
        }
        return messageId;
    }

    private static MailManagerPolicies.Mail withMessage(MailManagerPolicies.Mail mail, List<String> recipients,
                                                        byte[] message) {
        return new MailManagerPolicies.Mail(mail.helo(), mail.mailFrom(), List.copyOf(recipients), mail.sourceIp(),
                mail.tls(), mail.tlsProtocol(), MailManagerMime.parse(message), message.length);
    }

    private ObjectNode mailObject(MailManagerPolicies.Mail mail, List<String> recipients, String messageId,
                                  ObjectNode ingress, ObjectNode ruleSet) {
        ObjectNode object = mapper.createObjectNode();
        object.put("messageId", messageId);
        object.put("timestamp", Instant.now().toString());
        object.put("source", mail.mailFrom());
        ArrayNode destinations = object.putArray("destination");
        recipients.forEach(destinations::add);
        object.put("ingressPointId", ingress.path("IngressPointId").asText());
        object.put("ruleSetId", ruleSet.path("RuleSetId").asText());
        object.put("senderIpAddress", mail.sourceIp());
        object.put("helo", mail.helo());
        ArrayNode headers = object.putArray("headers");
        if (mail.message() != null) {
            mail.message().headers().forEach((name, values) -> values.forEach(value -> headers.addObject()
                    .put("name", name).put("value", value)));
            ObjectNode common = object.putObject("commonHeaders");
            if (mail.message().header("Subject") != null) {
                common.put("subject", mail.message().header("Subject"));
            }
            ArrayNode from = common.putArray("from");
            mail.message().addresses("From").forEach(from::add);
            ArrayNode to = common.putArray("to");
            mail.message().addresses("To").forEach(to::add);
        }
        return object;
    }

    private String notification(JsonNode spec, MailManagerPolicies.Mail mail, List<String> recipients, byte[] message,
                                String messageId, ObjectNode ingress, ObjectNode ruleSet) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("notificationType", "Received");
        notification.set("mail", mailObject(mail, recipients, messageId, ingress, ruleSet));
        if ("CONTENT".equals(spec.path("PayloadType").asText("HEADERS"))) {
            notification.put("content", "BASE64".equals(spec.path("Encoding").asText("UTF-8"))
                    ? Base64.getEncoder().encodeToString(message) : new String(message, StandardCharsets.UTF_8));
        }
        return notification.toString();
    }

    private void invoke(JsonNode spec, MailManagerPolicies.Mail mail, List<String> recipients, byte[] message,
                        String messageId, ObjectNode ingress, String region) {
        ObjectNode ruleSet = mapper.createObjectNode().put("RuleSetId", ingress.path("RuleSetId").asText());
        ObjectNode event = mapper.createObjectNode();
        event.put("eventSource", "aws:mailmanager");
        event.set("mail", mailObject(mail, recipients, messageId, ingress, ruleSet));
        event.put("content", Base64.getEncoder().encodeToString(message));
        String function = spec.path("FunctionArn").asText();
        String target = region;
        try {
            target = AwsArnUtils.parse(function).region();
        } catch (RuntimeException e) {
            // a bare name runs in the ingress point's Region
        }
        try {
            lambda.get().invoke(target, function, mapper.writeValueAsBytes(event),
                    "REQUEST_RESPONSE".equals(spec.path("InvocationType").asText()) ? InvocationType.RequestResponse
                            : InvocationType.Event);
        } catch (IOException e) {
            throw new AwsException("ActionFailed", e.getMessage(), 400);
        }
    }

    private void bounce(JsonNode spec, MailManagerPolicies.Mail mail, String region) {
        if (mail.mailFrom() == null || mail.mailFrom().isBlank()) {
            return;
        }
        String original = mail.message() == null ? "" : String.valueOf(mail.message().header("Subject"));
        String body = "From: " + spec.path("Sender").asText() + "\r\nTo: " + mail.mailFrom()
                + "\r\nSubject: Delivery Status Notification (Failure)\r\nContent-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n" + spec.path("Message").asText("Your message couldn't be delivered.") + "\r\n\r\nStatus: "
                + spec.path("StatusCode").asText() + "\r\nDiagnostic-Code: smtp; " + spec.path("SmtpReplyCode").asText()
                + " " + spec.path("DiagnosticMessage").asText() + "\r\nOriginal-Subject: " + original + "\r\n";
        ses.get().sendRawEmail(spec.path("Sender").asText(), List.of(mail.mailFrom()), body, null, null, null, null,
                region);
    }

    /** Relays the message to the relay's SMTP server, with its secret's credentials when it has them. */
    private void relay(String account, String region, JsonNode spec, MailManagerPolicies.Mail mail,
                       List<String> recipients, byte[] message, ObjectNode ingress) {
        ObjectNode relay = store.getForAccount(account, region, MailManagerStore.RELAY,
                        MailManagerStore.idOf(spec.path("Relay").asText()))
                .orElseThrow(() -> new AwsException("ActionFailed", "The relay doesn't exist.", 400));
        String username = null;
        String password = null;
        String secretArn = relay.path("Authentication").path("SecretArn").asText(null);
        if (secretArn != null) {
            String secret = secrets.get().getSecretValue(secretArn, null, null, region).getSecretString();
            try {
                JsonNode credentials = mapper.readTree(secret);
                username = credentials.path("username").asText(null);
                password = credentials.path("password").asText(null);
            } catch (IOException e) {
                throw new AwsException("ActionFailed", "The relay's secret isn't JSON with username and password.",
                        400);
            }
        }
        String from = "REPLACE".equals(spec.path("MailFrom").asText("PRESERVE"))
                ? "mail-manager@" + store.config().smtpHostname() : mail.mailFrom();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(relay.path("ServerName").asText(), relay.path("ServerPort").asInt()),
                    10_000);
            socket.setSoTimeout(30_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            expect(in, "2");
            send(out, "EHLO " + store.config().smtpHostname());
            expect(in, "2");
            if (username != null && password != null) {
                String plain = Base64.getEncoder().encodeToString(("\0" + username + "\0" + password)
                        .getBytes(StandardCharsets.UTF_8));
                send(out, "AUTH PLAIN " + plain);
                expect(in, "2");
            }
            send(out, "MAIL FROM:<" + from + ">");
            expect(in, "2");
            for (String recipient : recipients) {
                send(out, "RCPT TO:<" + recipient + ">");
                expect(in, "2");
            }
            send(out, "DATA");
            expect(in, "3");
            for (String line : new String(message, StandardCharsets.UTF_8).split("\r?\n", -1)) {
                send(out, line.startsWith(".") ? "." + line : line);
            }
            send(out, ".");
            expect(in, "2");
            send(out, "QUIT");
        } catch (IOException e) {
            throw new AwsException("ActionFailed", "The relay " + relay.path("RelayName").asText()
                    + " couldn't be reached: " + e.getMessage(), 400);
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads a (possibly multi-line) reply and checks its class. */
    private static void expect(BufferedReader in, String klass) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("the server closed the connection");
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
        if (!line.startsWith(klass)) {
            throw new IOException("the server replied " + line);
        }
    }
}
