package com.ecommerce.oms.common.exception;

/** 400: a well-typed request that is still unacceptable for a reason Bean Validation cannot express. */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String detail) {
        super(ErrorCode.VALIDATION_FAILED, detail);
    }
}
