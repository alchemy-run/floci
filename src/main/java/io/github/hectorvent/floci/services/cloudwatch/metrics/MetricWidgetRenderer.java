package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetadataService.invalid;

/** Rasterizes stored metric series without AWT, including in the native executable. */
@ApplicationScoped
public class MetricWidgetRenderer {
    private final ObjectMapper mapper;
    private final CloudWatchMetricsService metrics;

    @Inject
    public MetricWidgetRenderer(ObjectMapper mapper, CloudWatchMetricsService metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public byte[] render(String definition, String region) {
        JsonNode widget;
        try {
            widget = mapper.readTree(definition);
        } catch (JsonProcessingException e) {
            throw invalid("MetricWidget must be valid JSON");
        }
        if (widget == null || !widget.isObject()) throw invalid("MetricWidget must be an object");
        if (!"timeSeries".equals(widget.path("view").asText("timeSeries"))) {
            throw invalid("Only timeSeries metric widgets are supported");
        }
        int width = widget.path("width").asInt(600), height = widget.path("height").asInt(395);
        if (width < 100 || width > 2000 || height < 100 || height > 2000) throw invalid("Invalid widget dimensions");
        Instant now = Instant.now();
        Instant start = time(widget.path("start").asText("-PT3H"), now);
        Instant end = time(widget.path("end").asText("PT0H"), now);
        if (!end.isAfter(start)) throw invalid("Widget end must be after start");
        String requestedRegion = widget.path("region").asText(region);
        List<List<CloudWatchMetricsService.Datapoint>> series = new ArrayList<>();
        List<String> statistics = new ArrayList<>();
        List<String> previous = List.of();
        double min = 0, max = 0;
        for (JsonNode row : widget.path("metrics")) {
            if (!row.isArray() || row.size() < 2 || !row.get(0).isTextual()) {
                throw invalid("Widget metric expressions are not supported");
            }
            List<String> fields = new ArrayList<>();
            JsonNode options = mapper.createObjectNode();
            for (int i = 0; i < row.size(); i++) {
                JsonNode field = row.get(i);
                if (field.isObject()) { options = field; break; }
                String value = field.asText();
                if (value.equals(".")) {
                    if (i >= previous.size()) throw invalid("Metric shorthand has no previous value");
                    value = previous.get(i);
                }
                fields.add(value);
            }
            previous = fields;
            if ((fields.size() - 2) % 2 != 0) throw invalid("Metric dimensions must be name/value pairs");
            List<Dimension> dimensions = new ArrayList<>();
            for (int i = 2; i < fields.size(); i += 2) dimensions.add(new Dimension(fields.get(i), fields.get(i + 1)));
            String stat = options.path("stat").asText(widget.path("stat").asText("Average"));
            if (!List.of("Average", "Sum", "Minimum", "Maximum", "SampleCount").contains(stat)) {
                throw invalid("Unsupported widget statistic: " + stat);
            }
            int period = options.path("period").asInt(widget.path("period").asInt(300));
            if (period < 1) throw invalid("Widget period must be positive");
            var data = metrics.getMetricStatistics(fields.get(0), fields.get(1), dimensions, start, end, period,
                    List.of(stat), options.path("unit").asText(null), requestedRegion);
            if (!options.path("visible").asBoolean(true)) continue;
            series.add(data);
            statistics.add(stat);
            for (var point : data) {
                double value = CloudWatchMetricsService.resolveStatValue(point, stat);
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        if (max == min) max = min + 1;
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, 0xffffff);
        int left = 35, top = 15, right = width - 15, bottom = height - 25;
        for (int i = 0; i <= 4; i++) {
            int y = top + (bottom - top) * i / 4;
            line(pixels, width, height, left, y, right, y, 0xe8e8e8);
        }
        line(pixels, width, height, left, top, left, bottom, 0x505050);
        line(pixels, width, height, left, bottom, right, bottom, 0x505050);
        int[] colors = {0x2277bb, 0xe08022, 0x229955, 0xbb3377, 0x7755cc};
        for (int i = 0; i < series.size(); i++) {
            int lastX = -1, lastY = -1;
            for (var point : series.get(i)) {
                double value = CloudWatchMetricsService.resolveStatValue(point, statistics.get(i));
                double fraction = (point.timestamp().toEpochMilli() - start.toEpochMilli())
                        / (double) (end.toEpochMilli() - start.toEpochMilli());
                int x = Math.clamp(left + (int) (fraction * (right - left)), left, right);
                int y = Math.clamp(bottom - (int) ((value - min) / (max - min) * (bottom - top)), top, bottom);
                if (lastX >= 0) line(pixels, width, height, lastX, lastY, x, y, colors[i % colors.length]);
                line(pixels, width, height, x - 2, y, x + 2, y, colors[i % colors.length]);
                line(pixels, width, height, x, y - 2, x, y + 2, colors[i % colors.length]);
                lastX = x;
                lastY = y;
            }
        }
        return png(pixels, width, height, "MetricWidget\0" + definition);
    }

    private static Instant time(String text, Instant now) {
        try {
            if (text.startsWith("-P")) return now.minus(Duration.parse(text.substring(1)));
            if (text.startsWith("P")) return now.plus(Duration.parse(text));
            return Instant.parse(text);
        } catch (java.time.DateTimeException e) {
            throw invalid("Invalid widget time: " + text);
        }
    }

    private static void line(int[] pixels, int width, int height, int x, int y, int endX, int endY, int color) {
        int dx = Math.abs(endX - x), dy = -Math.abs(endY - y);
        int sx = x < endX ? 1 : -1, sy = y < endY ? 1 : -1;
        int error = dx + dy;
        while (true) {
            if (x >= 0 && x < width && y >= 0 && y < height) pixels[y * width + x] = color;
            if (x == endX && y == endY) break;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x += sx; }
            if (twice <= dx) { error += dx; y += sy; }
        }
    }

    private static byte[] png(int[] pixels, int width, int height, String description) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeLong(0x89504e470d0a1a0aL);
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            DataOutputStream dimensions = new DataOutputStream(header);
            dimensions.writeInt(width);
            dimensions.writeInt(height);
            dimensions.write(new byte[]{8, 2, 0, 0, 0});
            chunk(output, "IHDR", header.toByteArray());
            chunk(output, "tEXt", description.getBytes(StandardCharsets.ISO_8859_1));
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
                for (int y = 0; y < height; y++) {
                    deflater.write(0);
                    for (int x = 0; x < width; x++) {
                        int pixel = pixels[y * width + x];
                        deflater.write(pixel >> 16 & 255);
                        deflater.write(pixel >> 8 & 255);
                        deflater.write(pixel & 255);
                    }
                }
            }
            chunk(output, "IDAT", compressed.toByteArray());
            chunk(output, "IEND", new byte[0]);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode metric widget", e);
        }
    }

    private static void chunk(DataOutputStream output, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(data.length);
        output.write(name);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }
}
