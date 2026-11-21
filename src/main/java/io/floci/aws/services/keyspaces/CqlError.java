package io.floci.aws.services.keyspaces;

/** A CQL error response: its protocol error code and message, and for some codes extra fields. */
final class CqlError extends RuntimeException {

    static final int SERVER = 0x0000;
    static final int PROTOCOL = 0x000A;
    static final int BAD_CREDENTIALS = 0x0100;
    static final int UNAVAILABLE = 0x1000;
    static final int SYNTAX = 0x2000;
    static final int UNAUTHORIZED = 0x2100;
    static final int INVALID = 0x2200;
    static final int CONFIG = 0x2300;
    static final int ALREADY_EXISTS = 0x2400;
    static final int UNPREPARED = 0x2500;

    final int code;
    final String keyspace;
    final String table;
    final byte[] preparedId;

    CqlError(int code, String message) {
        this(code, message, null, null, null);
    }

    CqlError(int code, String message, String keyspace, String table, byte[] preparedId) {
        super(message);
        this.code = code;
        this.keyspace = keyspace;
        this.table = table;
        this.preparedId = preparedId;
    }

    static CqlError invalid(String message) {
        return new CqlError(INVALID, message);
    }

    static CqlError alreadyExists(String message, String keyspace, String table) {
        return new CqlError(ALREADY_EXISTS, message, keyspace, table == null ? "" : table, null);
    }
}
