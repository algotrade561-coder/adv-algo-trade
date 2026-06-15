package com.algo.trade.domain;

import java.time.LocalDate;

/**
 * A single option leg within a multi-leg spread strategy.
 */
public record SpreadLeg(
        String instrumentKey,
        int strike,
        OptionType optionType,
        OrderSide side,
        int quantity,
        LocalDate expiry
) {}
