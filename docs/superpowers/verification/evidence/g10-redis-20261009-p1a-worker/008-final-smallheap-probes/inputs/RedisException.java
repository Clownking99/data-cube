package com.datacube.redis;

/** Redis 返回的协议级错误（例如 ERR、WRONGTYPE 或 NOAUTH）。 */
public final class RedisException extends RuntimeException {

    public enum Kind { LEGACY, SERVER, RESOURCE, PROTOCOL, DEADLINE, NESTED_SERVER_ERROR, TRANSPORT, CLOSED, CONTEXT }
    public enum Delivery { NOT_SENT, MAY_HAVE_SENT, REPLIED }
    private final Kind kind;
    private final Delivery delivery;

    public RedisException(String message) {
        this(message, null);
    }

    public RedisException(String message, Throwable cause) {
        this(message, cause, Kind.LEGACY, Delivery.NOT_SENT);
    }

    RedisException(String message, Throwable cause, Kind kind, Delivery delivery) {
        super(message, cause);
        this.kind = kind;
        this.delivery = delivery;
    }

    public Kind kind() { return kind; }
    public Delivery delivery() { return delivery; }

    static RedisException rejected(Kind kind, Delivery delivery) {
        String message = switch (kind) {
            case RESOURCE -> "Redis resource budget exceeded";
            case PROTOCOL -> "Invalid Redis protocol or argument";
            case DEADLINE -> "Redis response read deadline exceeded";
            case NESTED_SERVER_ERROR -> "Redis nested error left an incomplete response";
            case TRANSPORT -> "Redis connection lost";
            case CLOSED -> "Redis client or session is closed";
            case CONTEXT -> "Redis session context cannot be restored; reopen the session";
            default -> "Redis operation failed";
        };
        if (delivery == Delivery.MAY_HAVE_SENT) message += "; command result is uncertain and was not replayed";
        return new RedisException(message, null, kind, delivery);
    }
}
