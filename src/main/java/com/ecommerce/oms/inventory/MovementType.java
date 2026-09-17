package com.ecommerce.oms.inventory;

/** Which column a movement touched and why (doc 05 table). */
public enum MovementType {
    STOCK_IN,
    ADJUSTMENT,
    RESERVE,
    RELEASE,
    PACK_DEDUCT,
    CANCEL_RESTOCK,
    RETURN_RESTOCK
}
