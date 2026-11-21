package io.floci.aws.services.mailmanager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SMTP side of ingress points: each active ingress point listens on its own port from the configured range
 * ({@code SmtpPort} in GetIngressPoint). A connection speaks ESMTP: AUTH PLAIN and LOGIN for AUTH ingress points,
 * each recipient checked against the traffic policy as it is named, the size limit at MAIL FROM and after DATA, and
 * the accepted message handed to {@link MailManagerDelivery} for the rule set. Floci offers no STARTTLS, so the TLS
 * policy isn't enforced.
 */
@ApplicationScoped
public class MailManagerSmtp {

    private static final Logger LOG = Logger.getLogger(MailManagerSmtp.class);
    private static final Pattern PATH = Pattern.compile("(?i)^(MAIL FROM|RCPT TO):\\s*<([^>]*)>(.*)$");
    private static final Pattern SIZE = Pattern.compile("(?i)SIZE=(\\d+)");

    private final Vertx vertx;
    private final MailManagerStore store;
    private final Provider<MailManagerDelivery> delivery;
    private final Provider<SecretsManagerService> secrets;
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();

    @Inject
    public MailManagerSmtp(Vertx vertx, MailManagerStore store, Provider<MailManagerDelivery> delivery,
                           Provider<SecretsManagerService> secrets) {
        this.secrets = secrets;
        this.vertx = vertx;
        this.store = store;
        this.delivery = delivery;
    }

    private record Listener(NetServer server, int port) {
    }

    /** Starts (or keeps) an ingress point's listener; its port, or 0 when none could be opened. */
    synchronized int listen(String account, String region, String ingressPointId) {
        Listener existing = listeners.get(ingressPointId);
        if (existing != null) {
            return existing.port();
        }
        int start = store.config().smtpPortStart();
        int end = start + store.config().smtpPortCount();
        for (int port = start; port < end; port++) {
            final int candidate = port;
            if (listeners.values().stream().anyMatch(l -> l.port() == candidate)) {
                continue;
            }
            NetServer server = vertx.createNetServer(new NetServerOptions().setHost(store.config().smtpBindHost())
                    .setPort(port));
            server.connectHandler(socket -> new Session(socket, account, region, ingressPointId).start());
            CompletableFuture<Boolean> bound = new CompletableFuture<>();
            server.listen().onComplete(result -> bound.complete(result.succeeded()));
            try {
                if (bound.get(5, TimeUnit.SECONDS)) {
                    listeners.put(ingressPointId, new Listener(server, port));
                    LOG.infov("Mail Manager ingress point {0} listens for SMTP on port {1}", ingressPointId,
                            String.valueOf(port));
                    return port;
                }
            } catch (Exception e) {
                LOG.debugv("SMTP port {0} is unavailable: {1}", String.valueOf(port), e.getMessage());
            }
            server.close();
        }
        LOG.warnv("No SMTP port in {0}..{1} is free for ingress point {2}", String.valueOf(start),
                String.valueOf(end - 1), ingressPointId);
        return 0;
    }

    synchronized void close(String ingressPointId) {
        Listener listener = listeners.remove(ingressPointId);
        if (listener != null) {
            listener.server().close();
        }
    }

    @PreDestroy
    void shutdown() {
        listeners.values().forEach(listener -> listener.server().close());
        listeners.clear();
    }

    /** One SMTP connection. */
    private final class Session {
        private final NetSocket socket;
        private final String account;
        private final String region;
        private final String ingressPointId;
        private final RecordParser parser;
        private String helo;
        private String mailFrom;
        private final List<String> recipients = new ArrayList<>();
        private boolean authenticated;
        private String authStep;
        private boolean data;
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();

        Session(NetSocket socket, String account, String region, String ingressPointId) {
            this.socket = socket;
            this.account = account;
            this.region = region;
            this.ingressPointId = ingressPointId;
            this.parser = RecordParser.newDelimited("\r\n", this::line);
        }

        void start() {
            socket.handler(parser);
            reply("220 " + store.config().smtpHostname() + " ESMTP Floci Mail Manager");
        }

        private void reply(String line) {
            socket.write(line + "\r\n");
        }

        private ObjectNode ingress() {
            return RequestScopes.callAs(account, region, () -> store.getForAccount(account, region,
                    MailManagerStore.INGRESS_POINT, ingressPointId).orElse(null));
        }

        private ObjectNode policy(ObjectNode ingress) {
            return store.getForAccount(account, region, MailManagerStore.TRAFFIC_POLICY,
                    ingress.path("TrafficPolicyId").asText()).orElse(null);
        }

        private long maxSize(ObjectNode policy) {
            return policy == null || !policy.has("MaxMessageSizeBytes") ? Long.MAX_VALUE
                    : policy.get("MaxMessageSizeBytes").asLong();
        }

        private String sourceIp() {
            return socket.remoteAddress() == null ? "127.0.0.1" : socket.remoteAddress().hostAddress();
        }

        private void line(Buffer buffer) {
            if (data) {
                String line = buffer.toString(StandardCharsets.UTF_8);
                if (".".equals(line)) {
                    data = false;
                    finish();
                    return;
                }
                byte[] bytes = buffer.getBytes();
                if (bytes.length > 0 && bytes[0] == '.') {
                    message.write(bytes, 1, bytes.length - 1);
                } else {
                    message.writeBytes(bytes);
                }
                message.writeBytes(new byte[] {'\r', '\n'});
                return;
            }
            String line = buffer.toString(StandardCharsets.UTF_8);
            if (authStep != null) {
                auth(line);
                return;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            ObjectNode ingress = ingress();
            if (ingress == null || !"ACTIVE".equals(ingress.path("Status").asText())) {
                reply("421 4.3.2 The ingress point is not active");
                socket.close();
                return;
            }
            if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                helo = line.length() > 5 ? line.substring(5).trim() : "";
                reset();
                if (upper.startsWith("HELO")) {
                    reply("250 " + store.config().smtpHostname());
                    return;
                }
                ObjectNode policy = policy(ingress);
                reply("250-" + store.config().smtpHostname());
                if (maxSize(policy) != Long.MAX_VALUE) {
                    reply("250-SIZE " + maxSize(policy));
                }
                reply("250-8BITMIME");
                if ("AUTH".equals(ingress.path("Type").asText())) {
                    reply("250-AUTH PLAIN LOGIN");
                }
                reply("250 ENHANCEDSTATUSCODES");
            } else if (upper.startsWith("AUTH ")) {
                if (!"AUTH".equals(ingress.path("Type").asText())) {
                    reply("503 5.5.1 This ingress point doesn't take AUTH");
                    return;
                }
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && "PLAIN".equalsIgnoreCase(parts[1])) {
                    if (parts.length == 3) {
                        plain(parts[2], ingress);
                    } else {
                        authStep = "PLAIN";
                        reply("334 ");
                    }
                } else if (parts.length >= 2 && "LOGIN".equalsIgnoreCase(parts[1])) {
                    authStep = "LOGIN_USER";
                    reply("334 " + Base64.getEncoder().encodeToString("Username:".getBytes(StandardCharsets.UTF_8)));
                } else {
                    reply("504 5.5.4 Unrecognized authentication type");
                }
            } else if (upper.startsWith("MAIL FROM")) {
                Matcher path = PATH.matcher(line);
                if (!path.matches()) {
                    reply("501 5.5.4 Syntax: MAIL FROM:<address>");
                    return;
                }
                if ("AUTH".equals(ingress.path("Type").asText()) && !authenticated) {
                    reply("530 5.7.0 Authentication required");
                    return;
                }
                Matcher size = SIZE.matcher(path.group(3));
                if (size.find() && Long.parseLong(size.group(1)) > maxSize(policy(ingress))) {
                    reply("552 5.3.4 Message size exceeds the traffic policy's maximum");
                    return;
                }
                mailFrom = path.group(2);
                recipients.clear();
                reply("250 2.1.0 OK");
            } else if (upper.startsWith("RCPT TO")) {
                Matcher path = PATH.matcher(line);
                if (mailFrom == null) {
                    reply("503 5.5.1 MAIL FROM first");
                    return;
                }
                if (!path.matches() || path.group(2).isBlank()) {
                    reply("501 5.5.4 Syntax: RCPT TO:<address>");
                    return;
                }
                String recipient = path.group(2);
                ObjectNode policy = policy(ingress);
                MailManagerPolicies.Mail mail = new MailManagerPolicies.Mail(helo, mailFrom, List.of(recipient),
                        sourceIp(), false, "", null, 0);
                boolean accepted = policy == null || RequestScopes.callAs(account, region, () ->
                        MailManagerPolicies.accepts(policy, mail, recipient, (list, address) ->
                                delivery.get().isMember(account, region, list, address)));
                if (!accepted) {
                    reply("550 5.7.1 Recipient rejected by the traffic policy");
                    return;
                }
                recipients.add(recipient);
                reply("250 2.1.5 OK");
            } else if (upper.equals("DATA")) {
                if (recipients.isEmpty()) {
                    reply("554 5.5.1 No valid recipients");
                    return;
                }
                data = true;
                message.reset();
                reply("354 End data with <CR><LF>.<CR><LF>");
            } else if (upper.equals("RSET")) {
                reset();
                reply("250 2.0.0 OK");
            } else if (upper.equals("NOOP")) {
                reply("250 2.0.0 OK");
            } else if (upper.startsWith("VRFY")) {
                reply("252 2.5.2 Cannot VRFY user");
            } else if (upper.equals("QUIT")) {
                reply("221 2.0.0 Bye");
                socket.close();
            } else if (upper.equals("STARTTLS")) {
                reply("454 4.7.0 TLS not available");
            } else {
                reply("500 5.5.2 Command not recognized");
            }
        }

        private void reset() {
            mailFrom = null;
            recipients.clear();
            message.reset();
        }

        private boolean password(String candidate, ObjectNode ingress) {
            String secretArn = ingress.path("IngressPointAuthConfiguration").path("SecretArn").asText("");
            if (!secretArn.isEmpty()) {
                return candidate != null && candidate.equals(secretPassword(secretArn));
            }
            String current = ingress.path("_").path("password").asText(null);
            String previous = ingress.path("_").path("previousPassword").asText(null);
            return candidate != null && (candidate.equals(current) || candidate.equals(previous));
        }

        /** The password a secret holds: its {@code password} key when it is JSON, or the whole string. */
        private String secretPassword(String secretArn) {
            try {
                String value = RequestScopes.callAs(account, region, () -> secrets.get().getSecretValue(secretArn,
                        null, null, region).getSecretString());
                if (value != null && value.trim().startsWith("{")) {
                    return store.mapper().readTree(value).path("password").asText(null);
                }
                return value;
            } catch (JsonProcessingException | RuntimeException e) {
                LOG.debugv("Mail Manager could not read the ingress point secret {0}: {1}", secretArn, e.getMessage());
                return null;
            }
        }

        private void plain(String encoded, ObjectNode ingress) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                reply("501 5.5.2 Cannot decode response");
                return;
            }
            String[] parts = decoded.split("\0", -1);
            accept(password(parts.length >= 3 ? parts[2] : null, ingress));
        }

        private void auth(String line) {
            ObjectNode ingress = ingress();
            String step = authStep;
            authStep = null;
            if (ingress == null) {
                reply("421 4.3.2 The ingress point is not active");
                return;
            }
            if ("PLAIN".equals(step)) {
                plain(line, ingress);
            } else if ("LOGIN_USER".equals(step)) {
                authStep = "LOGIN_PASSWORD";
                reply("334 " + Base64.getEncoder().encodeToString("Password:".getBytes(StandardCharsets.UTF_8)));
            } else {
                String candidate;
                try {
                    candidate = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    reply("501 5.5.2 Cannot decode response");
                    return;
                }
                accept(password(candidate, ingress));
            }
        }

        private void accept(boolean ok) {
            authenticated = ok;
            reply(ok ? "235 2.7.0 Authentication successful" : "535 5.7.8 Authentication credentials invalid");
        }

        private void finish() {
            byte[] raw = message.toByteArray();
            String from = mailFrom;
            List<String> to = List.copyOf(recipients);
            String greeting = helo;
            String ip = sourceIp();
            reset();
            vertx.executeBlocking(() -> RequestScopes.callAs(account, region, () -> {
                ObjectNode ingress = store.getForAccount(account, region, MailManagerStore.INGRESS_POINT,
                        ingressPointId).orElse(null);
                if (ingress == null) {
                    return "421 4.3.2 The ingress point is gone";
                }
                ObjectNode policy = policy(ingress);
                if (raw.length > maxSize(policy)) {
                    return "552 5.3.4 Message size exceeds the traffic policy's maximum";
                }
                String messageId = delivery.get().deliver(account, region, ingress, new MailManagerPolicies.Mail(
                        greeting, from, to, ip, false, "", MailManagerMime.parse(raw), raw.length), raw);
                return "250 2.0.0 OK " + messageId;
            }), false).onComplete(result -> reply(result.succeeded() ? result.result()
                    : "451 4.3.0 The message could not be processed: " + result.cause().getMessage()));
        }
    }
}
