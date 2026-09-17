package com.ecommerce.oms.common.exception;

/** 403: wrong role, inactive user, or acting on another warehouse. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String detail) {
        super(ErrorCode.FORBIDDEN, detail);
    }
}
