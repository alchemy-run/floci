package io.floci.aws.core.common;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A server-side WebSocket (RFC 6455) of text messages over the socket a handshake answered by hand upgraded, for
 * protocols Vert.x's WebSocket server can't serve as-is (IVS Chat echoes a token as the subprotocol, which Vert.x
 * echoes only when named in the server's options): text messages (fragmented or not), pings answered with pongs,
 * and the close handshake.
 */
public final class TextWebSocket {

    /** The largest message a connection takes, reassembled. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int TEXT = 0x1;
    private static final int BINARY = 0x2;
    private static final int CLOSE = 0x8;
    private static final int PING = 0x9;
    private static final int PONG = 0xA;

    private final NetSocket socket;
    private final Consumer<String> onText;
    private final Runnable onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Buffer pending = Buffer.buffer();
    private ByteArrayOutputStream message;
    private int messageOpcode;

    public TextWebSocket(NetSocket socket, Consumer<String> onText, Runnable onClosed) {
        this.socket = socket;
        this.onText = onText;
        this.onClosed = onClosed;
        socket.handler(this::read);
        socket.closeHandler(ignored -> closed());
        socket.exceptionHandler(ignored -> socket.close());
    }

    /** The {@code Sec-WebSocket-Accept} answer to a handshake's {@code Sec-WebSocket-Key}. */
    public static String accept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key.trim() + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }

    /** Sends a text message, unless the connection is closing. */
    public void send(String text) {
        if (!closed.get()) {
            socket.write(frame(TEXT, text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Starts the close handshake with a status and reason, and ends the connection. */
    public void close(int code, String reason) {
        if (closed.compareAndSet(false, true)) {
            byte[] text = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            int length = Math.min(text.length, 123);
            Buffer body = Buffer.buffer().appendUnsignedShort(code).appendBytes(text, 0, length);
            socket.write(frame(CLOSE, body.getBytes())).onComplete(ignored -> socket.close());
            onClosed.run();
        }
    }

    public boolean isOpen() {
        return !closed.get();
    }

    private void closed() {
        if (closed.compareAndSet(false, true)) {
            onClosed.run();
        }
    }

    private static Buffer frame(int opcode, byte[] payload) {
        Buffer frame = Buffer.buffer(payload.length + 10).appendUnsignedByte((short) (0x80 | opcode));
        if (payload.length < 126) {
            frame.appendUnsignedByte((short) payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.appendUnsignedByte((short) 126).appendUnsignedShort(payload.length);
        } else {
            frame.appendUnsignedByte((short) 127).appendLong(payload.length);
        }
        return frame.appendBytes(payload);
    }

    private void read(Buffer data) {
        pending.appendBuffer(data);
        while (!closed.get()) {
            int consumed = readFrame();
            if (consumed <= 0) {
                return;
            }
            pending = pending.getBuffer(consumed, pending.length());
        }
    }

    /** Handles the next whole frame in {@link #pending}: the bytes it took, or 0 while it is incomplete. */
    private int readFrame() {
        if (pending.length() < 2) {
            return 0;
        }
        int first = pending.getUnsignedByte(0);
        int second = pending.getUnsignedByte(1);
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        int offset = 2;
        if (length == 126) {
            if (pending.length() < 4) {
                return 0;
            }
            length = pending.getUnsignedShort(2);
            offset = 4;
        } else if (length == 127) {
            if (pending.length() < 10) {
                return 0;
            }
            length = pending.getLong(2);
            offset = 10;
        }
        if (!masked) {
            close(1002, "Client frames must be masked.");
            return 0;
        }
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            close(1009, "Message too big.");
            return 0;
        }
        if (pending.length() < offset + 4 + length) {
            return 0;
        }
        byte[] mask = pending.getBytes(offset, offset + 4);
        byte[] payload = pending.getBytes(offset + 4, offset + 4 + (int) length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        handle(fin, opcode, payload);
        return offset + 4 + (int) length;
    }

    private void handle(boolean fin, int opcode, byte[] payload) {
        switch (opcode) {
            case PING -> socket.write(frame(PONG, payload));
            case PONG -> { }
            case CLOSE -> {
                int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1000;
                close(code == 1005 || code == 1006 ? 1000 : code, "");
            }
            case TEXT, BINARY -> {
                message = new ByteArrayOutputStream();
                messageOpcode = opcode;
                append(fin, payload);
            }
            case 0 -> {
                if (message == null) {
                    close(1002, "A continuation frame has no message to continue.");
                } else {
                    append(fin, payload);
                }
            }
            default -> close(1002, "Unknown opcode " + opcode + ".");
        }
    }

    private void append(boolean fin, byte[] payload) {
        message.writeBytes(payload);
        if (message.size() > MAX_MESSAGE_BYTES) {
            close(1009, "Message too big.");
            return;
        }
        if (fin) {
            byte[] whole = message.toByteArray();
            message = null;
            if (messageOpcode == BINARY) {
                close(1003, "This endpoint takes text messages.");
                return;
            }
            onText.accept(new String(whole, StandardCharsets.UTF_8));
        }
    }
}
