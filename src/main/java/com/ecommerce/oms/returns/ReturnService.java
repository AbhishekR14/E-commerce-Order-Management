package com.ecommerce.oms.returns;

import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.InvalidRequestException;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.common.money.Money;
import com.ecommerce.oms.events.ReturnStatusChangedEvent;
import com.ecommerce.oms.inventory.InventoryService;
import com.ecommerce.oms.order.OrderService;
import com.ecommerce.oms.order.OrderStatus;
import com.ecommerce.oms.order.entity.Order;
import com.ecommerce.oms.order.entity.OrderItem;
import com.ecommerce.oms.order.entity.OrderItemAllocation;
import com.ecommerce.oms.payment.RefundReason;
import com.ecommerce.oms.payment.RefundService;
import com.ecommerce.oms.returns.dto.CreateReturnRequest;
import com.ecommerce.oms.returns.dto.ReceiveReturnRequest;
import com.ecommerce.oms.returns.dto.ReturnResponse;
import com.ecommerce.oms.returns.dto.ReturnResponse.ItemResponse;
import com.ecommerce.oms.returns.entity.ReturnItem;
import com.ecommerce.oms.returns.entity.ReturnRequest;
import com.ecommerce.oms.security.AuthUser;
import com.ecommerce.oms.warehouse.WarehouseService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Item-level returns (doc 09): the customer requests, staff of the return's warehouse approve or reject,
 * and receiving restocks (optionally) and refunds pro rata in one transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReturnService {

    private final ReturnRequestRepository returnRepository;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final RefundService refundService;
    private final WarehouseService warehouseService;
    private final ReturnProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ---- customer ---------------------------------------------------------------------------

    @Transactional
    public ReturnResponse create(Long customerId, Long orderId, CreateReturnRequest request) {
        Order order = orderService.requireForCustomer(customerId, orderId);            // 1. ownership -> 404
        if (order.getStatus() != OrderStatus.DELIVERED && order.getStatus() != OrderStatus.PARTIALLY_RETURNED) {
            throw notAllowed("Order " + order.getOrderNumber() + " is " + order.getStatus() + "; returns need a delivered order");
        }
        Instant deadline = order.getDeliveredAt().plus(Duration.ofDays(properties.windowDays()));
        if (clock.instant().isAfter(deadline)) {                                       // 3. window
            throw notAllowed("The " + properties.windowDays() + "-day return window for order "
                    + order.getOrderNumber() + " closed on " + deadline);
        }
        Set<Long> seen = new HashSet<>();                                               // 4. duplicates -> 400
        for (CreateReturnRequest.Item item : request.items()) {
            if (!seen.add(item.orderItemId())) {
                throw new InvalidRequestException("Order item " + item.orderItemId() + " is listed more than once");
            }
        }
        Map<Long, OrderItem> orderItems = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));

        ReturnRequest ret = new ReturnRequest();
        ret.setOrderId(orderId);
        ret.setCustomerId(customerId);
        ret.setReason(request.reason().trim());
        ret.setStatus(ReturnStatus.REQUESTED);
        for (CreateReturnRequest.Item item : request.items()) {                        // 5. ownership + returnable
            OrderItem oi = orderItems.get(item.orderItemId());
            if (oi == null) {
                throw notAllowed("Order item " + item.orderItemId() + " does not belong to order " + order.getOrderNumber());
            }
            int returnable = oi.getQuantity() - oi.getReturnedQuantity() - returnRepository.openQuantityFor(oi.getId());
            if (item.quantity() > returnable) {
                throw notAllowed("Only " + returnable + " unit(s) of " + oi.getSku() + " can still be returned");
            }
            ReturnItem ri = new ReturnItem();
            ri.setOrderItemId(oi.getId());
            ri.setQuantity(item.quantity());
            ret.addItem(ri);
        }
        // assumption 13: the warehouse of the lowest-id allocation of the first requested item
        OrderItem first = orderItems.get(request.items().get(0).orderItemId());
        Long warehouseId = first.getAllocations().stream()
                .min(Comparator.comparing(OrderItemAllocation::getId))
                .map(OrderItemAllocation::getWarehouseId)
                .orElseThrow(() -> new IllegalStateException("Order item " + first.getId() + " has no allocation"));
        ret.setWarehouse(warehouseService.require(warehouseId));

        ret = returnRepository.save(ret);
        log.info("Return {} requested for order {} by customer {} ({} line(s), warehouse {})", ret.getId(),
                order.getOrderNumber(), customerId, ret.getItems().size(), warehouseId);
        publish(ret, null, ReturnStatus.REQUESTED, customerId);
        return toResponse(ret, order);
    }

    @Transactional(readOnly = true)
    public Page<ReturnResponse> listForCustomer(Long customerId, Pageable pageable) {
        return returnRepository.findAllByCustomerId(customerId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ReturnResponse getForCustomer(Long customerId, Long returnId) {
        ReturnRequest ret = returnRepository.findByIdWithWarehouse(returnId)
                .filter(r -> r.getCustomerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Return", returnId));
        return toResponse(ret);
    }

    // ---- warehouse -----------------------------------------------------------------------------

    /** Staff see their own warehouse; admins may filter or see all. */
    @Transactional(readOnly = true)
    public Page<ReturnResponse> listForWarehouse(AuthUser actor, ReturnStatus status, Long warehouseId, Pageable pageable) {
        Long scope = actor.isAdmin() ? warehouseId : actor.warehouseId();
        return returnRepository.search(scope, status, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ReturnResponse getForWarehouse(AuthUser actor, Long returnId) {
        return toResponse(requireVisible(actor, returnId));
    }

    @Transactional
    public ReturnResponse approve(AuthUser actor, Long returnId, String note) {
        ReturnRequest ret = requireVisible(actor, returnId);
        assertStatus(ret, ReturnStatus.REQUESTED);
        decide(ret, ReturnStatus.APPROVED, note, actor.id());
        return toResponse(ret);
    }

    /** Rejection frees the quantities for a new request (open-quantity query ignores REJECTED). */
    @Transactional
    public ReturnResponse reject(AuthUser actor, Long returnId, String note) {
        ReturnRequest ret = requireVisible(actor, returnId);
        assertStatus(ret, ReturnStatus.REQUESTED);
        decide(ret, ReturnStatus.REJECTED, note, actor.id());
        return toResponse(ret);
    }

    /** Values needed for the restock loop, captured before any guarded update detaches the entities. */
    private record Receipt(Long returnItemId, Long orderItemId, Long productId, int quantity, boolean restock) {
    }

    /** Receive + refund, one transaction (doc 09). */
    @Transactional
    public ReturnResponse receive(AuthUser actor, Long returnId, ReceiveReturnRequest request) {
        ReturnRequest ret = requireVisible(actor, returnId);
        assertStatus(ret, ReturnStatus.APPROVED);
        Map<Long, Boolean> restockById = new HashMap<>();
        for (ReceiveReturnRequest.Item item : request.items()) {
            if (restockById.put(item.returnItemId(), item.restock()) != null) {
                throw new InvalidRequestException("Return item " + item.returnItemId() + " is listed more than once");
            }
        }
        Set<Long> expected = ret.getItems().stream().map(ReturnItem::getId).collect(Collectors.toSet());
        if (!restockById.keySet().equals(expected)) {
            throw new InvalidRequestException("Every return item must be listed exactly once: expected " + expected);
        }
        Order order = orderService.requireAny(ret.getOrderId());
        Map<Long, OrderItem> orderItems = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Long warehouseId = ret.getWarehouse().getId();
        Long customerId = order.getCustomerId();

        // 1. restock (guarded updates): plain values first, then the loop
        List<Receipt> receipts = new ArrayList<>();
        for (ReturnItem ri : ret.getItems()) {
            receipts.add(new Receipt(ri.getId(), ri.getOrderItemId(), orderItems.get(ri.getOrderItemId()).getProductId(),
                    ri.getQuantity(), restockById.get(ri.getId())));
        }
        for (Receipt r : receipts) {
            if (r.restock()) {
                inventoryService.restockOrCreate(r.productId(), warehouseId, r.quantity(), returnId, actor.id());
            }
        }
        // reload what the guarded updates detached
        ret = returnRepository.findByIdWithWarehouse(returnId).orElseThrow();
        order = orderService.requireAny(ret.getOrderId());
        orderItems = order.getItems().stream().collect(Collectors.toMap(OrderItem::getId, Function.identity()));

        // 2-3. refund per item, cumulative proration; update the order lines
        BigDecimal total = Money.ZERO;
        for (ReturnItem ri : ret.getItems()) {
            ri.setRestock(restockById.get(ri.getId()));
            OrderItem oi = orderItems.get(ri.getOrderItemId());
            BigDecimal refund = RefundCalculator.refundFor(oi.getLineTotal(), oi.getQuantity(), oi.getReturnedQuantity(),
                    ri.getQuantity());
            oi.setReturnedQuantity(oi.getReturnedQuantity() + ri.getQuantity());
            oi.setRefundedAmount(Money.add(oi.getRefundedAmount(), refund));
            total = Money.add(total, refund);
        }

        // 4. one refund for the return (RefundService enforces sum(refunds) <= payment)
        ret.setRefundAmount(total);
        if (Money.isPositive(total)) {
            refundService.issue(order.getId(), customerId, total, RefundReason.RETURN, returnId, actor.id());
        }

        // 5. status
        ReturnStatus from = ret.getStatus();
        ret.setStatus(ReturnStatus.REFUNDED);
        ret.setReceivedBy(actor.id());
        ret.setReceivedAt(clock.instant());

        // 6. order: RETURNED once every unit is back, otherwise PARTIALLY_RETURNED
        boolean allReturned = order.getItems().stream().allMatch(oi -> oi.getReturnedQuantity() == oi.getQuantity());
        orderService.changeStatus(order, allReturned ? OrderStatus.RETURNED : OrderStatus.PARTIALLY_RETURNED, actor.id(),
                "Return " + returnId + " received");

        // 7. events (RefundIssuedEvent was published by RefundService)
        publish(ret, from, ReturnStatus.REFUNDED, actor.id());
        log.info("Return {} received by user {}: refund {}, order {} now {}", returnId, actor.id(), total,
                order.getOrderNumber(), order.getStatus());
        return toResponse(ret, order);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ReturnRequest requireVisible(AuthUser actor, Long returnId) {
        ReturnRequest ret = returnRepository.findByIdWithWarehouse(returnId)
                .orElseThrow(() -> new NotFoundException("Return", returnId));
        if (!actor.isAdmin() && !ret.getWarehouse().getId().equals(actor.warehouseId())) {
            throw new NotFoundException("Return", returnId);
        }
        return ret;
    }

    private static void assertStatus(ReturnRequest ret, ReturnStatus expected) {
        if (ret.getStatus() != expected) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Return " + ret.getId() + " is " + ret.getStatus() + ", expected " + expected);
        }
    }

    private void decide(ReturnRequest ret, ReturnStatus to, String note, Long actorId) {
        ReturnStatus from = ret.getStatus();
        ret.setStatus(to);
        ret.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        ret.setDecidedBy(actorId);
        ret.setDecidedAt(clock.instant());
        log.info("Return {} {} -> {} by user {}", ret.getId(), from, to, actorId);
        publish(ret, from, to, actorId);
    }

    private void publish(ReturnRequest ret, ReturnStatus from, ReturnStatus to, Long actorId) {
        events.publishEvent(new ReturnStatusChangedEvent(ret.getId(), ret.getOrderId(), ret.getCustomerId(),
                ret.getWarehouse().getId(), from, to, actorId));
    }

    private static BusinessRuleException notAllowed(String detail) {
        return new BusinessRuleException(ErrorCode.RETURN_NOT_ALLOWED, detail);
    }

    private ReturnResponse toResponse(ReturnRequest ret) {
        return toResponse(ret, orderService.requireAny(ret.getOrderId()));
    }

    private static ReturnResponse toResponse(ReturnRequest ret, Order order) {
        Map<Long, OrderItem> orderItems = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        return new ReturnResponse(ret.getId(), order.getId(), order.getOrderNumber(), ret.getStatus(), ret.getReason(),
                ret.getDecisionNote(), ret.getWarehouse().getId(), ret.getWarehouse().getCode(),
                ret.getItems().stream().map(ri -> new ItemResponse(ri.getId(), ri.getOrderItemId(),
                        orderItems.get(ri.getOrderItemId()).getSku(), ri.getQuantity(), ri.getRestock())).toList(),
                ret.getRefundAmount(), ret.getCreatedAt(), ret.getDecidedAt(), ret.getReceivedAt());
    }
}
