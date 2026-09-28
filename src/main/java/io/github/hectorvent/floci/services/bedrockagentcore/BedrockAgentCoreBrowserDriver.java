package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives a session's headless Chrome over the Chrome DevTools Protocol: readiness, screenshots,
 * input events, and cookie capture and restore for browser profiles.
 *
 * <p>AgentCore's InvokeBrowser acts at the operating-system level; here the same mouse and
 * keyboard actions are delivered as DevTools {@code Input} events to the session's first page,
 * which is what a page observes either way. Every DevTools call is bounded by
 * {@link #CALL_TIMEOUT}.
 */
@ApplicationScoped
public class BedrockAgentCoreBrowserDriver {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreBrowserDriver.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Map<String, Integer> MODIFIERS = Map.of(
            "alt", 1, "ctrl", 2, "control", 2, "meta", 4, "cmd", 4, "command", 4, "shift", 8);
    private static final Map<String, NamedKey> NAMED_KEYS = Map.ofEntries(
            Map.entry("enter", new NamedKey("Enter", "Enter", 13, "\r")),
            Map.entry("return", new NamedKey("Enter", "Enter", 13, "\r")),
            Map.entry("tab", new NamedKey("Tab", "Tab", 9, "\t")),
            Map.entry("space", new NamedKey(" ", "Space", 32, " ")),
            Map.entry("backspace", new NamedKey("Backspace", "Backspace", 8, null)),
            Map.entry("delete", new NamedKey("Delete", "Delete", 46, null)),
            Map.entry("escape", new NamedKey("Escape", "Escape", 27, null)),
            Map.entry("esc", new NamedKey("Escape", "Escape", 27, null)),
            Map.entry("arrowleft", new NamedKey("ArrowLeft", "ArrowLeft", 37, null)),
            Map.entry("arrowup", new NamedKey("ArrowUp", "ArrowUp", 38, null)),
            Map.entry("arrowright", new NamedKey("ArrowRight", "ArrowRight", 39, null)),
            Map.entry("arrowdown", new NamedKey("ArrowDown", "ArrowDown", 40, null)),
            Map.entry("home", new NamedKey("Home", "Home", 36, null)),
            Map.entry("end", new NamedKey("End", "End", 35, null)),
            Map.entry("pageup", new NamedKey("PageUp", "PageUp", 33, null)),
            Map.entry("pagedown", new NamedKey("PageDown", "PageDown", 34, null)));

    private final ObjectMapper objectMapper;
    private final HttpClient http;

    @Inject
    public BedrockAgentCoreBrowserDriver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /** Failure reported by Chrome or by the DevTools transport. */
    public static class BrowserDriverException extends RuntimeException {
        public BrowserDriverException(String message) {
            super(message);
        }

        public BrowserDriverException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record NamedKey(String key, String code, int keyCode, String text) {}

    /**
     * Polls {@code /json/version} until Chrome answers and returns the browser-level DevTools
     * WebSocket URL.
     */
    public String awaitBrowserEndpoint(String host, int port, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        String lastError = "no response";
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode version = getJson(host, port, "/json/version", "GET");
                String url = version.path("webSocketDebuggerUrl").asText(null);
                if (url != null && !url.isBlank()) {
                    return url;
                }
            } catch (BrowserDriverException e) {
                lastError = e.getMessage();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrowserDriverException("Interrupted while waiting for the browser", e);
            }
        }
        throw new BrowserDriverException("The browser did not become ready within " + timeout.toSeconds()
                + "s: " + lastError);
    }

    /** Base64 PNG of the session's page. */
    public String screenshot(String host, int port) {
        try (CdpConnection page = openPage(host, port)) {
            ObjectNode params = objectMapper.createObjectNode().put("format", "png");
            return page.send("Page.captureScreenshot", params).path("data").asText();
        }
    }

    /** Delivers one AgentCore BrowserAction ({@code mouseClick}, {@code keyType}, ...) to the page. */
    public void perform(String host, int port, String action, JsonNode arguments) {
        try (CdpConnection page = openPage(host, port)) {
            switch (action) {
                case "mouseClick" -> mouseClick(page, arguments);
                case "mouseMove" -> mouse(page, "mouseMoved", arguments.path("x").asInt(),
                        arguments.path("y").asInt(), "none", 0, 0);
                case "mouseDrag" -> mouseDrag(page, arguments);
                case "mouseScroll" -> mouseScroll(page, arguments);
                case "keyType" -> page.send("Input.insertText",
                        objectMapper.createObjectNode().put("text", arguments.path("text").asText()));
                case "keyPress" -> keyPress(page, arguments);
                case "keyShortcut" -> keyShortcut(page, arguments);
                default -> throw new BrowserDriverException("Unsupported browser action: " + action);
            }
        }
    }

    /** Every cookie in the browser, in the DevTools {@code Network.Cookie} shape. */
    public ArrayNode cookies(String browserEndpoint) {
        try (CdpConnection browser = open(browserEndpoint)) {
            JsonNode cookies = browser.send("Storage.getCookies", objectMapper.createObjectNode()).path("cookies");
            return cookies.isArray() ? (ArrayNode) cookies.deepCopy() : objectMapper.createArrayNode();
        }
    }

    /** Restores cookies captured by {@link #cookies}; session cookies are restored as session cookies. */
    public void setCookies(String browserEndpoint, ArrayNode cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        ArrayNode params = objectMapper.createArrayNode();
        for (JsonNode cookie : cookies) {
            ObjectNode param = params.addObject();
            for (String field : List.of("name", "value", "domain", "path", "secure", "httpOnly", "sameSite",
                    "priority", "sourceScheme", "sourcePort")) {
                if (cookie.has(field)) {
                    param.set(field, cookie.get(field));
                }
            }
            if (!cookie.path("session").asBoolean(false) && cookie.path("expires").asDouble(-1) > 0) {
                param.set("expires", cookie.get("expires"));
            }
        }
        try (CdpConnection browser = open(browserEndpoint)) {
            browser.send("Storage.setCookies", objectMapper.createObjectNode().set("cookies", params));
        }
    }

    // ── input ────────────────────────────────────────────────────

    private void mouseClick(CdpConnection page, JsonNode arguments) {
        int x = arguments.path("x").asInt();
        int y = arguments.path("y").asInt();
        String button = button(arguments);
        int clicks = arguments.path("clickCount").asInt(1);
        mouse(page, "mouseMoved", x, y, "none", 0, 0);
        for (int click = 1; click <= clicks; click++) {
            mouse(page, "mousePressed", x, y, button, click, buttonMask(button));
            mouse(page, "mouseReleased", x, y, button, click, 0);
        }
    }

    private void mouseDrag(CdpConnection page, JsonNode arguments) {
        String button = button(arguments);
        int mask = buttonMask(button);
        int startX = arguments.path("startX").asInt();
        int startY = arguments.path("startY").asInt();
        int endX = arguments.path("endX").asInt();
        int endY = arguments.path("endY").asInt();
        mouse(page, "mouseMoved", startX, startY, "none", 0, 0);
        mouse(page, "mousePressed", startX, startY, button, 1, mask);
        mouse(page, "mouseMoved", endX, endY, button, 0, mask);
        mouse(page, "mouseReleased", endX, endY, button, 1, 0);
    }

    private void mouseScroll(CdpConnection page, JsonNode arguments) {
        ObjectNode params = objectMapper.createObjectNode()
                .put("type", "mouseWheel")
                .put("x", arguments.path("x").asInt())
                .put("y", arguments.path("y").asInt())
                .put("deltaX", arguments.path("deltaX").asInt(0))
                .put("deltaY", arguments.path("deltaY").asInt(0));
        page.send("Input.dispatchMouseEvent", params);
    }

    private void mouse(CdpConnection page, String type, int x, int y, String button, int clickCount, int buttons) {
        ObjectNode params = objectMapper.createObjectNode()
                .put("type", type)
                .put("x", x)
                .put("y", y)
                .put("button", button)
                .put("buttons", buttons)
                .put("clickCount", clickCount);
        page.send("Input.dispatchMouseEvent", params);
    }

    private void keyPress(CdpConnection page, JsonNode arguments) {
        String key = arguments.path("key").asText();
        int presses = arguments.path("presses").asInt(1);
        for (int i = 0; i < presses; i++) {
            key(page, key, 0);
        }
    }

    private void keyShortcut(CdpConnection page, JsonNode arguments) {
        List<String> modifiers = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (JsonNode item : arguments.path("keys")) {
            String key = item.asText();
            if (MODIFIERS.containsKey(key.toLowerCase(Locale.ROOT))) {
                modifiers.add(key);
            } else {
                keys.add(key);
            }
        }
        int mask = 0;
        for (String modifier : modifiers) {
            mask |= MODIFIERS.get(modifier.toLowerCase(Locale.ROOT));
            page.send("Input.dispatchKeyEvent", keyEvent("rawKeyDown", modifierKey(modifier), mask, null, 0));
        }
        for (String key : keys) {
            key(page, key, mask);
        }
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            String modifier = modifiers.get(i);
            mask &= ~MODIFIERS.get(modifier.toLowerCase(Locale.ROOT));
            page.send("Input.dispatchKeyEvent", keyEvent("keyUp", modifierKey(modifier), mask, null, 0));
        }
    }

    private void key(CdpConnection page, String key, int modifiers) {
        NamedKey named = NAMED_KEYS.get(key.toLowerCase(Locale.ROOT));
        String resolvedKey = named != null ? named.key() : key;
        int keyCode = named != null ? named.keyCode()
                : key.length() == 1 ? Character.toUpperCase(key.charAt(0)) : 0;
        // A modified key (Ctrl+A) is a command, not text to insert.
        String text = modifiers != 0 && modifiers != 8 ? null
                : named != null ? named.text() : key.length() == 1 ? key : null;
        page.send("Input.dispatchKeyEvent", keyEvent(text == null ? "rawKeyDown" : "keyDown",
                resolvedKey, modifiers, text, keyCode));
        page.send("Input.dispatchKeyEvent", keyEvent("keyUp", resolvedKey, modifiers, null, keyCode));
    }

    private ObjectNode keyEvent(String type, String key, int modifiers, String text, int keyCode) {
        ObjectNode params = objectMapper.createObjectNode()
                .put("type", type)
                .put("key", key)
                .put("modifiers", modifiers);
        if (text != null) {
            params.put("text", text);
        }
        if (keyCode > 0) {
            params.put("windowsVirtualKeyCode", keyCode);
        }
        return params;
    }

    private static String modifierKey(String modifier) {
        return switch (modifier.toLowerCase(Locale.ROOT)) {
            case "ctrl", "control" -> "Control";
            case "meta", "cmd", "command" -> "Meta";
            case "shift" -> "Shift";
            default -> "Alt";
        };
    }

    private static String button(JsonNode arguments) {
        return arguments.path("button").asText("LEFT").toLowerCase(Locale.ROOT);
    }

    private static int buttonMask(String button) {
        return switch (button) {
            case "right" -> 2;
            case "middle" -> 4;
            default -> 1;
        };
    }

    // ── transport ────────────────────────────────────────────────

    private CdpConnection openPage(String host, int port) {
        JsonNode targets = getJson(host, port, "/json/list", "GET");
        for (JsonNode target : targets) {
            if ("page".equals(target.path("type").asText()) && target.hasNonNull("webSocketDebuggerUrl")) {
                return open(target.get("webSocketDebuggerUrl").asText());
            }
        }
        JsonNode created = getJson(host, port, "/json/new?about:blank", "PUT");
        return open(created.path("webSocketDebuggerUrl").asText());
    }

    private JsonNode getJson(String host, int port, String path, String method) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
                .timeout(HTTP_TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BrowserDriverException("DevTools " + path + " answered " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new BrowserDriverException("DevTools " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserDriverException("Interrupted calling DevTools " + path, e);
        }
    }

    private CdpConnection open(String url) {
        CdpConnection connection = new CdpConnection();
        try {
            connection.socket = http.newWebSocketBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .buildAsync(URI.create(url), connection)
                    .get(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return connection;
        } catch (ExecutionException | TimeoutException e) {
            throw new BrowserDriverException("Could not open DevTools connection " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserDriverException("Interrupted opening DevTools connection", e);
        }
    }

    /** One DevTools WebSocket with request/response correlation by message id. */
    private final class CdpConnection implements WebSocket.Listener, AutoCloseable {
        private final AtomicInteger ids = new AtomicInteger();
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        JsonNode send(String method, ObjectNode params) {
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> response = new CompletableFuture<>();
            pending.put(id, response);
            ObjectNode message = objectMapper.createObjectNode().put("id", id).put("method", method);
            message.set("params", params);
            try {
                socket.sendText(objectMapper.writeValueAsString(message), true)
                        .get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                JsonNode reply = response.get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (reply.has("error")) {
                    throw new BrowserDriverException(method + " failed: "
                            + reply.path("error").path("message").asText(reply.get("error").toString()));
                }
                return reply.path("result");
            } catch (IOException | ExecutionException | TimeoutException e) {
                throw new BrowserDriverException(method + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrowserDriverException("Interrupted during " + method, e);
            } finally {
                pending.remove(id);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (partial) {
                partial.append(data);
                if (last) {
                    String text = partial.toString();
                    partial.setLength(0);
                    complete(text);
                }
            }
            webSocket.request(1);
            return null;
        }

        private void complete(String text) {
            try {
                JsonNode message = objectMapper.readTree(text);
                if (message.has("id")) {
                    CompletableFuture<JsonNode> response = pending.get(message.get("id").asInt());
                    if (response != null) {
                        response.complete(message);
                    }
                }
            } catch (IOException e) {
                LOG.debugv("Ignoring unparseable DevTools message: {0}", e.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failAll(new BrowserDriverException("DevTools connection closed: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failAll(new BrowserDriverException("DevTools connection failed: " + error.getMessage(), error));
        }

        private void failAll(BrowserDriverException failure) {
            pending.values().forEach(response -> response.completeExceptionally(failure));
        }

        @Override
        public void close() {
            if (socket != null) {
                socket.abort();
            }
        }
    }
}
