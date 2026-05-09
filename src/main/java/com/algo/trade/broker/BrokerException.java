package com.algo.trade.broker;

/**
 * Runtime exception used at the broker boundary so callers do not depend on SDK-specific exceptions.
 */
public class BrokerException extends RuntimeException {

    public BrokerException(String message) {
        super(message);
    }

    public BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
