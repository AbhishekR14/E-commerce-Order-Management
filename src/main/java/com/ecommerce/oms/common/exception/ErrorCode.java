package com.ecommerce.oms.common.exception;

import org.springframework.http.HttpStatus;

/** Every error code the API can return, with its HTTP status (docs/design/03-api-spec.md §Errors). */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Duplicate resource"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    CART_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY, "Cart is empty"),
    PRODUCT_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Product unavailable"),
    COUPON_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Coupon invalid"),
    ORDER_NOT_CANCELLABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Order not cancellable"),
    RETURN_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "Return not allowed"),
    INVENTORY_ADJUSTMENT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Inventory adjustment invalid"),
    CATEGORY_IN_USE(HttpStatus.UNPROCESSABLE_ENTITY, "Category in use"),
    CATEGORY_CYCLE(HttpStatus.UNPROCESSABLE_ENTITY, "Category cycle"),
    USER_ROLE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "User role invalid"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
