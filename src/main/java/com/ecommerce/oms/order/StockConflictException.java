package com.ecommerce.oms.order;

/**
 * A reservation guard returned 0 rows: the availability snapshot was stale because another checkout won the
 * race. Internal and <b>retryable</b>: the whole checkout transaction rolls back and CheckoutFacade retries
 * with a fresh snapshot. Never reaches the client.
 */
public class StockConflictException extends RuntimeException {

    private final String sku;
    private final int requested;

    public StockConflictException(String sku, Long warehouseId, int requested) {
        super("Reservation of " + requested + " x " + sku + " in warehouse " + warehouseId + " lost a race");
        this.sku = sku;
        this.requested = requested;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }
}
