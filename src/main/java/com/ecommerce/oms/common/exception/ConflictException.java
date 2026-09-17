package com.ecommerce.oms.common.exception;

/** 409: duplicates, insufficient stock, invalid state transitions, concurrent modification. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String detail) {
        super(requireStatus(code, 409), detail);
    }
}
