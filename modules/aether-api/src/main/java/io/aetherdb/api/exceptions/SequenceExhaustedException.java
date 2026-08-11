package io.aetherdb.api.exceptions;

/** No further positive sequence number can be assigned safely. */
public final class SequenceExhaustedException extends AetherException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a sequence-exhaustion failure.
     *
     * @param message failure description
     */
    public SequenceExhaustedException(String message) {
        super(message);
    }
}
