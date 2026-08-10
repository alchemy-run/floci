package io.github.hectorvent.floci.services.lambda.model;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Chunk pipe between the runtime API server (Vert.x event loop, producer) and a
 * response writer (JAX-RS worker thread, consumer) for streaming invocation
 * responses ({@code Lambda-Runtime-Function-Response-Mode: streaming}).
 *
 * <p>The producer {@link #offer(byte[])}s chunks as they arrive on the wire and
 * {@link #close()}s at end of stream; the consumer pulls with {@link #next} until
 * it returns {@code null}.
 */
public class StreamingPayload {

    /** Max Lambda execution time; a mid-stream stall beyond this fails the read. */
    public static final long DRAIN_TIMEOUT_MS = 900_000;

    /** End-of-stream sentinel, matched by reference ({@link #offer} skips empty chunks). */
    private static final byte[] EOF = new byte[0];

    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private final String contentType;

    public StreamingPayload(String contentType) {
        this.contentType = contentType;
    }

    /** Content-Type the runtime sent on its response POST, or null. */
    public String getContentType() {
        return contentType;
    }

    public void offer(byte[] chunk) {
        if (chunk != null && chunk.length > 0) {
            chunks.add(chunk);
        }
    }

    public void close() {
        chunks.add(EOF);
    }

    /**
     * Next chunk, or {@code null} at end of stream. Throws {@link TimeoutException}
     * if no chunk arrives within {@link #DRAIN_TIMEOUT_MS}.
     */
    public byte[] next() throws InterruptedException, TimeoutException {
        byte[] chunk = chunks.poll(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (chunk == null) {
            throw new TimeoutException("Timed out waiting for streaming response chunk");
        }
        return chunk == EOF ? null : chunk;
    }

    /** Reads the remainder of the stream into one buffer. */
    public byte[] drain() throws InterruptedException, TimeoutException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk;
        while ((chunk = next()) != null) {
            out.write(chunk, 0, chunk.length);
        }
        return out.toByteArray();
    }
}
