package com.ecommerce.oms.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Money helpers. All monetary values in the system are {@link BigDecimal} with scale 2 and
 * {@link RoundingMode#HALF_UP}; every arithmetic result passes through {@link #scale(BigDecimal)}.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    /** Normalises any BigDecimal to money scale (2, HALF_UP). Null becomes zero. */
    public static BigDecimal scale(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(String value) {
        return scale(new BigDecimal(value));
    }

    public static BigDecimal of(long units) {
        return scale(BigDecimal.valueOf(units));
    }

    /** unitPrice × quantity, rounded. */
    public static BigDecimal multiply(BigDecimal unitPrice, int quantity) {
        return scale(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /** amount × (percent / 100), rounded, e.g. {@code percentOf(200, 12.5) = 25.00}. */
    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return scale(amount.multiply(percent).divide(HUNDRED, SCALE, ROUNDING));
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return scale(a.add(b));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return scale(a.subtract(b));
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        return scale(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return scale(a.min(b));
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return scale(a.max(b));
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static boolean isZero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }
}
