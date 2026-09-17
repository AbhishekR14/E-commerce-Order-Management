package com.ecommerce.oms.order;

import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;

/** 409 INSUFFICIENT_STOCK: the total stock cannot cover the request. Not retryable. */
public class InsufficientStockException extends ConflictException {

    public InsufficientStockException(String sku, int requested, int available) {
        super(ErrorCode.INSUFFICIENT_STOCK,
                "Only " + available + " units of " + sku + " available (requested " + requested + ")");
    }

    public InsufficientStockException(String detail) {
        super(ErrorCode.INSUFFICIENT_STOCK, detail);
    }
}
