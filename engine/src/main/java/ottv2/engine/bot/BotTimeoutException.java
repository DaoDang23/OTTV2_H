package ottv2.engine.bot;

/** Indicates that a bot failed to return a move within its allotted turn time. */
public final class BotTimeoutException extends RuntimeException {
    public BotTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
