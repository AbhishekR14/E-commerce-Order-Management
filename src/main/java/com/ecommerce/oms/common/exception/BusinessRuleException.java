package com.ecommerce.oms.common.exception;

/** 422: the request is well-formed but violates a business rule (empty cart, coupon invalid, ...). */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(ErrorCode code, String detail) {
        super(requireStatus(code, 422), detail);
    }
}
