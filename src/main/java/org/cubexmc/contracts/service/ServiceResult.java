package org.cubexmc.contracts.service;

import org.cubexmc.contracts.model.Contract;

import java.math.BigDecimal;

public record ServiceResult(boolean success, String reason, Contract contract, BigDecimal amount) {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public static ServiceResult ok(Contract contract) {
        return new ServiceResult(true, "", contract, ZERO);
    }

    public static ServiceResult ok(Contract contract, BigDecimal amount) {
        return new ServiceResult(true, "", contract, amount == null ? ZERO : amount);
    }

    public static ServiceResult fail(String reason) {
        return new ServiceResult(false, reason, null, ZERO);
    }
}
