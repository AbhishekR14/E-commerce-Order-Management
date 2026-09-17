package com.ecommerce.oms.common.exception;

/** 401: missing/invalid token or bad login credentials. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(ErrorCode code, String detail) {
        super(requireStatus(code, 401), detail);
    }
}
