package de.hhu.bsinfo.dxnet.subscribe;

public class IllegalSubscriberException extends RuntimeException {

    public IllegalSubscriberException() {
        super();
    }

    public IllegalSubscriberException(String message) {
        super(message);
    }

    public IllegalSubscriberException(String message, Throwable cause) {
        super(message, cause);
    }

    public IllegalSubscriberException(Throwable cause) {
        super(cause);
    }

    protected IllegalSubscriberException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
