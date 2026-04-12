package com.kiteapioptions.util;

import java.math.BigDecimal;

public final class Validation {

    private Validation() {
    }

    public static void notBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    public static void notNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }

    public static void nonNegative(BigDecimal value, String fieldName) {
        notNull(value, fieldName);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
    }
}
