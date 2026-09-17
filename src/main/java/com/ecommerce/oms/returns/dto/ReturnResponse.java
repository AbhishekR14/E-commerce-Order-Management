package com.ecommerce.oms.returns.dto;

import com.ecommerce.oms.returns.ReturnStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReturnResponse(
        Long id,
        Long orderId,
        String orderNumber,
        ReturnStatus status,
        String reason,
        String decisionNote,
        Long warehouseId,
        String warehouseCode,
        List<ItemResponse> items,
        BigDecimal refundAmount,
        Instant createdAt,
        Instant decidedAt,
        Instant receivedAt) {

    public record ItemResponse(Long id, Long orderItemId, String sku, int quantity, Boolean restock) {
    }
}
