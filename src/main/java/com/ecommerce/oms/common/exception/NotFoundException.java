package com.ecommerce.oms.common.exception;

/** 404. Also used when a resource exists but is not owned by the caller (doc 03). */
public class NotFoundException extends ApiException {

    public NotFoundException(String detail) {
        super(ErrorCode.NOT_FOUND, detail);
    }

    public NotFoundException(String resource, Object id) {
        this(resource + " " + id + " not found");
    }
}
