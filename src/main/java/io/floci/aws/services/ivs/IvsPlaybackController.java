package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays IVS channels. A channel's playback URL, {@code /api/video/v1/<region>.<account>.channel.<id>.m3u8}, answers
 * with its multivariant playlist while it is live; the playlists and segments it names are served under
 * {@code /_floci/ivs/hls/<region>/<account>/<id>/<grant>/}, from the media server. A private ({@code authorized})
 * channel's playback URL takes a playback token, {@code ?token=<JWT>}, signed ES384 with the private key of one of
 * the account's playback key pairs, for the channel's ARN and not expired, whose viewer session has not been
 * revoked. A playback restriction policy limits the origins (and, with {@code floci.services.ivs.viewer-country}
 * set, the countries) that may play.
 */
@Path(IvsPlaybackController.HLS_ROOT)
public class IvsPlaybackController {

    static final String PLAYBACK_PREFIX = "/api/video/v1/";
    static final String HLS_ROOT = "/_floci/ivs/hls";
    static final String HLS_PREFIX = HLS_ROOT + "/";
    private static final Logger LOG = Logger.getLogger(IvsPlaybackController.class);
    private static final Pattern PLAYBACK = Pattern.compile("([a-z0-9-]+)\\.(\\d{12})\\.channel\\.([A-Za-z0-9]+)"
            + "\\.m3u8");
    private static final byte[] GRANT_KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(GRANT_KEY);
    }

    private final IvsService service;
    private final IvsMediaServer media;
    private final IvsLive live;
    private final IvsStore store;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();

    @Inject
    public IvsPlaybackController(IvsService service, IvsMediaServer media, IvsLive live, IvsStore store,
                                 ObjectMapper mapper) {
        this.service = service;
        this.media = media;
        this.live = live;
        this.store = store;
        this.mapper = mapper;
    }

    @GET
    @Path("play/{playback}")
    public Response playback(@PathParam("playback") String playback, @QueryParam("token") String token,
                             @HeaderParam("Origin") String origin) {
        Matcher matcher = PLAYBACK.matcher(playback);
        if (!matcher.matches()) {
            return text(404, "Not found");
        }
        String region = matcher.group(1);
        String account = matcher.group(2);
        String channelId = matcher.group(3);
        ObjectNode channel = service.channelFor(account, region, channelId);
        if (channel == null) {
            return text(404, "Channel not found");
        }
        String denied = restrict(channel, account, region, origin);
        if (denied != null) {
            return text(403, denied);
        }
        Grant grant = Grant.PUBLIC;
        if (channel.path("authorized").asBoolean(false)) {
            Object verified = verify(token, channel, account, region, origin);
            if (verified instanceof String reason) {
                return text(403, reason);
            }
            grant = (Grant) verified;
        }
        String path = currentPath(account, region, channelId);
        if (path == null || media.endpoint() == null) {
            return text(404, "Channel " + channel.path("arn").asText() + " is not broadcasting.");
        }
        HttpResponse<byte[]> index = fetch(path + "/index.m3u8");
        if (index == null || index.statusCode() != 200) {
            return text(404, "Channel " + channel.path("arn").asText() + " is not broadcasting.");
        }
        String base = HLS_PREFIX + region + "/" + account + "/" + channelId + "/" + grant.encode() + "/";
        return playlist(index, base);
    }

    @GET
    @Path("{region}/{account}/{channelId}/{grant}/{rest: .+}")
    public Response media(@PathParam("region") String region, @PathParam("account") String account,
                          @PathParam("channelId") String channelId, @PathParam("grant") String encoded,
                          @PathParam("rest") String rest, @HeaderParam("Origin") String origin,
                          @Context UriInfo uriInfo) {
        ObjectNode channel = service.channelFor(account, region, channelId);
        if (channel == null) {
            return text(404, "Channel not found");
        }
        Grant grant = Grant.decode(encoded);
        if (grant == null) {
            return text(403, "The playback grant is not valid.");
        }
        if (channel.path("authorized").asBoolean(false)) {
            if (grant == Grant.PUBLIC || grant.expires() < System.currentTimeMillis() / 1000) {
                return text(403, "The playback token has expired.");
            }
            if (grant.viewerId() != null && grant.version() <= service.revokedVersion(account, region, channelId,
                    grant.viewerId())) {
                return text(403, "The viewer session has been revoked.");
            }
        }
        String denied = restrict(channel, account, region, origin);
        if (denied != null) {
            return text(403, denied);
        }
        String path = currentPath(account, region, channelId);
        if (path == null) {
            return text(404, "Channel " + channel.path("arn").asText() + " is not broadcasting.");
        }
        String query = uriInfo.getRequestUri().getRawQuery();
        HttpResponse<byte[]> response = fetch(path + "/" + rest + (query == null ? "" : "?" + query));
        if (response == null) {
            return text(502, "The media server is unreachable.");
        }
        if (rest.endsWith(".m3u8") && response.statusCode() == 200) {
            return playlist(response, "");
        }
        Response.ResponseBuilder out = Response.status(response.statusCode()).entity(response.body());
        response.headers().firstValue("Content-Type").ifPresent(type -> out.header("Content-Type", type));
        return cors(out, origin).build();
    }

    /** A playlist as the media server wrote it, its relative URIs under {@code base}. */
    private Response playlist(HttpResponse<byte[]> response, String base) {
        StringBuilder out = new StringBuilder();
        for (String line : new String(response.body(), StandardCharsets.UTF_8).split("\n")) {
            out.append(line.isBlank() || line.startsWith("#") || base.isEmpty() ? line : base + line).append('\n');
        }
        return Response.ok(out.toString()).header("Content-Type", "application/vnd.apple.mpegurl")
                .header("Access-Control-Allow-Origin", "*").header("Cache-Control", "no-cache").build();
    }

    private static Response.ResponseBuilder cors(Response.ResponseBuilder builder, String origin) {
        return builder.header("Access-Control-Allow-Origin", origin == null ? "*" : origin);
    }

    private String currentPath(String account, String region, String channelId) {
        IvsLive.Live stream = media.endpoint() == null ? null : live.stream(account, region, channelId);
        return stream == null ? null : stream.path;
    }

    private HttpResponse<byte[]> fetch(String path) {
        IvsMediaServer.Endpoint server = media.endpoint();
        if (server == null) {
            return null;
        }
        try {
            return http.send(HttpRequest.newBuilder(URI.create(server.hls() + "/" + path))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            LOG.debugv("The media server did not answer {0}: {1}", path, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---------------------------------------------------------------- restrictions

    /** Why a playback restriction policy refuses a viewer, or null. */
    private String restrict(ObjectNode channel, String account, String region, String origin) {
        ObjectNode policy = service.restrictionPolicy(account, region, channel.path("playbackRestrictionPolicyArn")
                .asText(""));
        if (policy == null) {
            return null;
        }
        JsonNode origins = policy.path("allowedOrigins");
        if (!origins.isEmpty()) {
            boolean strict = policy.path("enableStrictOriginEnforcement").asBoolean(false);
            if (origin == null ? strict : !contains(origins, origin)) {
                return "The origin " + origin + " may not play this channel.";
            }
        }
        JsonNode countries = policy.path("allowedCountries");
        Optional<String> country = store.config().services().ivs().viewerCountry();
        if (!countries.isEmpty() && country.isPresent() && !contains(countries, country.get())) {
            return "Viewers in " + country.get() + " may not play this channel.";
        }
        return null;
    }

    private static boolean contains(JsonNode list, String value) {
        for (JsonNode entry : list) {
            if (entry.asText().equals(value) || "*".equals(entry.asText())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- playback tokens

    /** A verified playback token's grant, or why it does not play. */
    private Object verify(String token, ObjectNode channel, String account, String region, String origin) {
        if (token == null || token.isEmpty()) {
            return "This channel is private: its playback URL needs a playback token.";
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return "The playback token is not a JWT.";
        }
        JsonNode header;
        JsonNode claims;
        try {
            header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IOException | IllegalArgumentException e) {
            return "The playback token is not a JWT.";
        }
        if (!"ES384".equals(header.path("alg").asText())) {
            return "The playback token must be signed with ES384.";
        }
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return "The playback token's signature is not valid.";
        }
        boolean valid = false;
        for (byte[] der : service.playbackKeys(account, region)) {
            try {
                PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
                Signature verifier = Signature.getInstance("SHA384withECDSAinP1363Format");
                verifier.initVerify(key);
                verifier.update(signed);
                valid |= verifier.verify(signature);
            } catch (GeneralSecurityException e) {
                LOG.debugv("A playback key could not verify the token: {0}", e.getMessage());
            }
        }
        if (!valid) {
            return "The playback token is not signed by a playback key pair of this account.";
        }
        if (!channel.path("arn").asText().equals(claims.path("aws:channel-arn").asText())) {
            return "The playback token is for another channel.";
        }
        long expires = claims.path("exp").asLong(0);
        if (expires <= System.currentTimeMillis() / 1000) {
            return "The playback token has expired.";
        }
        String allowedOrigin = claims.path("aws:access-control-allow-origin").asText("*");
        boolean strict = claims.path("aws:strict-origin-enforcement").asBoolean(false);
        if (!"*".equals(allowedOrigin) && (origin == null ? strict : !allowedOrigin.equals(origin))) {
            return "The playback token does not allow the origin " + origin + ".";
        }
        String viewerId = claims.hasNonNull("aws:viewer-id") ? claims.get("aws:viewer-id").asText() : null;
        int version = claims.path("aws:viewer-session-version").asInt(0);
        if (viewerId != null && version <= service.revokedVersion(account, region, AwsArnUtils.parse(
                channel.path("arn").asText()).resource().substring("channel/".length()), viewerId)) {
            return "The viewer session has been revoked.";
        }
        return new Grant(viewerId, version, expires);
    }

    /** What a played token allows, carried in the paths of the playlists and segments it plays. */
    private record Grant(String viewerId, int version, long expires) {
        static final Grant PUBLIC = new Grant(null, 0, Long.MAX_VALUE);

        String encode() {
            if (this == PUBLIC) {
                return "public";
            }
            String body = (viewerId == null ? "" : viewerId) + "|" + version + "|" + expires;
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(
                    StandardCharsets.UTF_8));
            return payload + "." + sign(payload);
        }

        static Grant decode(String encoded) {
            if ("public".equals(encoded)) {
                return PUBLIC;
            }
            String[] parts = encoded.split("\\.");
            if (parts.length != 2 || !sign(parts[0]).equals(parts[1])) {
                return null;
            }
            String[] fields = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (fields.length != 3) {
                return null;
            }
            return new Grant(fields[0].isEmpty() ? null : fields[0], Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]));
        }

        private static String sign(String payload) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(GRANT_KEY, "HmacSHA256"));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(
                        StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Response text(int status, String message) {
        return Response.status(status).type("text/plain").header("Access-Control-Allow-Origin", "*")
                .entity(message).build();
    }
}
