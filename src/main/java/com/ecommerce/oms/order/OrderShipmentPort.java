package com.ecommerce.oms.order;

import com.ecommerce.oms.order.dto.OrderResponse.ShipmentSummary;
import java.util.List;

/** What the order view needs from fulfillment. Phase 7 provides the real implementation. */
public interface OrderShipmentPort {

    List<ShipmentSummary> shipmentsOf(Long orderId);
}
