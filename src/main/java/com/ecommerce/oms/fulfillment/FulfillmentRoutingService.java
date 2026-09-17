package com.ecommerce.oms.fulfillment;

import com.ecommerce.oms.fulfillment.entity.Shipment;
import com.ecommerce.oms.fulfillment.entity.ShipmentItem;
import com.ecommerce.oms.order.OrderService;
import com.ecommerce.oms.order.OrderStatus;
import com.ecommerce.oms.order.entity.Order;
import com.ecommerce.oms.order.entity.OrderItem;
import com.ecommerce.oms.order.entity.OrderItemAllocation;
import com.ecommerce.oms.warehouse.WarehouseService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One PENDING shipment per warehouse from the order's allocations, then PLACED -> CONFIRMED (doc 04
 * "Routing"). Idempotent: skips orders that are no longer PLACED (e.g. cancelled meanwhile) and orders that
 * already have shipments; UNIQUE(order_id, warehouse_id) backs that up at the DB level.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FulfillmentRoutingService {

    private final OrderService orderService;
    private final ShipmentRepository shipmentRepository;
    private final WarehouseService warehouseService;

    /** Own transaction: the checkout transaction that raised the event has already committed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void route(Long orderId) {
        Order order = orderService.requireAny(orderId);
        if (order.getStatus() != OrderStatus.PLACED) {
            log.info("Routing skipped for order {}: status is {}", order.getOrderNumber(), order.getStatus());
            return;
        }
        if (shipmentRepository.existsByOrderId(orderId)) {
            log.info("Routing skipped for order {}: shipments already exist", order.getOrderNumber());
            return;
        }

        Map<Long, Shipment> byWarehouse = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            for (OrderItemAllocation allocation : item.getAllocations()) {
                Shipment shipment = byWarehouse.computeIfAbsent(allocation.getWarehouseId(), warehouseId -> {
                    Shipment s = new Shipment();
                    s.setOrderId(orderId);
                    s.setWarehouse(warehouseService.require(warehouseId));
                    s.setStatus(ShipmentStatus.PENDING);
                    return s;
                });
                ShipmentItem si = new ShipmentItem();
                si.setOrderItemId(item.getId());
                si.setQuantity(allocation.getQuantity());
                shipment.addItem(si);
            }
        }
        shipmentRepository.saveAll(byWarehouse.values());
        orderService.changeStatus(order, OrderStatus.CONFIRMED, null,
                "Routed to " + byWarehouse.size() + " warehouse(s)");
        log.info("Order {} routed: {} shipment(s)", order.getOrderNumber(), byWarehouse.size());
    }
}
