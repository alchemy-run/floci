package io.floci.aws.services.polly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamDecoder;
import io.floci.aws.core.common.AwsEventStreamEncoder;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Amazon Polly REST API, under {@code /v1}. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class PollyController {
    private static final int AUDIO_CHUNK = 8192;

    private final PollyService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public PollyController(PollyService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @PUT
    @Path("/v1/lexicons/{LexiconName}")
    public Response putLexicon(@Context HttpHeaders headers, @PathParam("LexiconName") String name, String body) {
        service.putLexicon(name, parse(body), regions.resolveRegion(headers));
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/v1/lexicons/{LexiconName}")
    public Response getLexicon(@Context HttpHeaders headers, @PathParam("LexiconName") String name) {
        return Response.ok(service.getLexicon(name, regions.resolveRegion(headers))).build();
    }

    @DELETE
    @Path("/v1/lexicons/{LexiconName}")
    public Response deleteLexicon(@Context HttpHeaders headers, @PathParam("LexiconName") String name) {
        service.deleteLexicon(name, regions.resolveRegion(headers));
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/v1/lexicons")
    public Response listLexicons(@Context HttpHeaders headers, @QueryParam("NextToken") String nextToken) {
        return Response.ok(service.listLexicons(nextToken, regions.resolveRegion(headers))).build();
    }

    @GET
    @Path("/v1/voices")
    public Response describeVoices(@QueryParam("Engine") String engine, @QueryParam("LanguageCode") String languageCode,
                                   @QueryParam("IncludeAdditionalLanguageCodes") Boolean includeAdditional,
                                   @QueryParam("NextToken") String nextToken) {
        return Response.ok(service.describeVoices(engine, languageCode, includeAdditional, nextToken)).build();
    }

    @POST
    @Path("/v1/speech")
    @Produces(MediaType.WILDCARD)
    public Response synthesizeSpeech(@Context HttpHeaders headers, String body) {
        PollyService.Output output = service.synthesizeSpeech(parse(body), regions.resolveRegion(headers));
        return Response.ok(output.bytes(), output.contentType())
                .header("x-amzn-RequestCharacters", output.characters()).build();
    }

    @POST
    @Path("/v1/synthesisTasks")
    public Response startSpeechSynthesisTask(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("SynthesisTask", service.startTask(parse(body), regions.resolveRegion(headers))))
                .build();
    }

    @GET
    @Path("/v1/synthesisTasks/{TaskId}")
    public Response getSpeechSynthesisTask(@Context HttpHeaders headers, @PathParam("TaskId") String taskId) {
        return Response.ok(Map.of("SynthesisTask", service.getTask(taskId, regions.resolveRegion(headers)))).build();
    }

    @GET
    @Path("/v1/synthesisTasks")
    public Response listSpeechSynthesisTasks(@Context HttpHeaders headers, @QueryParam("MaxResults") Integer maxResults,
                                             @QueryParam("NextToken") String nextToken,
                                             @QueryParam("Status") String status) {
        return Response.ok(service.listTasks(maxResults, nextToken, status, regions.resolveRegion(headers))).build();
    }

    /**
     * Reads the {@code TextEvent}s of the action stream, synthesizing each sentence's audio as it is
     * complete (or at once on {@code FlushStreamConfiguration.Force}) and answering with
     * {@code AudioEvent}s, then a {@code StreamClosedEvent} once the {@code CloseStreamEvent} arrives.
     * The request body is read to its end before the answer starts, as HTTP/1.1 carries it.
     */
    @POST
    @Path("/v1/synthesisStream")
    @Produces(MediaType.WILDCARD)
    public Response startSpeechSynthesisStream(@Context HttpHeaders headers,
                                               @HeaderParam("x-amzn-Engine") String engine,
                                               @HeaderParam("x-amzn-LanguageCode") String languageCode,
                                               @HeaderParam("x-amzn-LexiconNames") String lexiconNames,
                                               @HeaderParam("x-amzn-OutputFormat") String format,
                                               @HeaderParam("x-amzn-SampleRate") String sampleRate,
                                               @HeaderParam("x-amzn-VoiceId") String voiceId,
                                               byte[] body) {
        if (engine == null) {
            throw PollyService.validation("Engine is required.");
        }
        if ("json".equals(format)) {
            throw PollyService.validation("The json output format is not available on a synthesis stream.");
        }
        List<String> names = lexiconNames == null || lexiconNames.isBlank() ? List.of()
                : Arrays.stream(lexiconNames.split(",")).map(String::trim).toList();
        PollyService.Plan plan = service.plan(voiceId, engine, languageCode, format, sampleRate, Set.of(), "text",
                names, regions.resolveRegion(headers));
        List<AwsEventStreamDecoder.Message> actions = new ArrayList<>();
        try {
            AwsEventStreamDecoder decoder = new AwsEventStreamDecoder(new ByteArrayInputStream(
                    body == null ? new byte[0] : body));
            AwsEventStreamDecoder.Message message;
            while ((message = decoder.next()) != null) {
                actions.add(message);
            }
        } catch (IOException e) {
            throw PollyService.validation("The action stream is not a valid event stream: " + e.getMessage());
        }
        StreamingOutput stream = out -> relay(plan, actions, out);
        return Response.ok(stream, "application/vnd.amazon.eventstream").build();
    }

    private void relay(PollyService.Plan plan, List<AwsEventStreamDecoder.Message> actions, OutputStream out) {
        StringBuilder pending = new StringBuilder();
        int characters = 0;
        try {
            for (AwsEventStreamDecoder.Message action : actions) {
                String type = action.header(":event-type");
                if ("TextEvent".equals(type)) {
                    JsonNode event = mapper.readTree(action.payload());
                    pending.append(event.path("Text").asText(""));
                    boolean force = event.path("FlushStreamConfiguration").path("Force").asBoolean(false);
                    if (force || endsSentence(pending)) {
                        characters += speak(plan, pending, out);
                    }
                } else if ("CloseStreamEvent".equals(type)) {
                    break;
                }
            }
            characters += speak(plan, pending, out);
            ObjectNode closed = mapper.createObjectNode().put("RequestCharacters", characters);
            AwsEventStreamWriter.writeEvent(mapper, out, "StreamClosedEvent", closed);
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode().put("message", e.getMessage());
            AwsEventStreamWriter.writeException(mapper, out, e.getErrorCode(), error);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean endsSentence(StringBuilder text) {
        String trimmed = text.toString().stripTrailing();
        return !trimmed.isEmpty() && ".!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }

    /** Writes the audio of the pending text as AudioEvents and returns the characters spoken. */
    private int speak(PollyService.Plan plan, StringBuilder pending, OutputStream out) throws IOException {
        if (pending.toString().isBlank()) {
            pending.setLength(0);
            return 0;
        }
        PollyService.Output output = service.synthesize(plan, pending.toString(), PollyService.SYNTHESIZE_MAX_BILLED);
        pending.setLength(0);
        byte[] audio = output.bytes();
        for (int offset = 0; offset < audio.length; offset += AUDIO_CHUNK) {
            LinkedHashMap<String, String> frame = new LinkedHashMap<>();
            frame.put(":message-type", "event");
            frame.put(":event-type", "AudioEvent");
            frame.put(":content-type", "application/octet-stream");
            out.write(AwsEventStreamEncoder.encodeMessage(frame,
                    Arrays.copyOfRange(audio, offset, Math.min(audio.length, offset + AUDIO_CHUNK))));
        }
        out.flush();
        return output.characters();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw PollyService.validation("The request body must be a JSON object.");
            }
            return node;
        } catch (IOException e) {
            throw PollyService.validation("The request body must be a JSON object.");
        }
    }
}
