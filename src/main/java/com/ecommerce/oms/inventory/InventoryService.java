package com.ecommerce.oms.inventory;

import com.ecommerce.oms.catalog.ProductRepository;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.events.InventoryAdjustedEvent;
import com.ecommerce.oms.inventory.InventoryRepository.WarehouseStock;
import com.ecommerce.oms.inventory.dto.AdjustmentRequest;
import com.ecommerce.oms.inventory.dto.InventoryResponse;
import com.ecommerce.oms.inventory.dto.MovementResponse;
import com.ecommerce.oms.inventory.dto.ThresholdRequest;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.inventory.entity.InventoryMovement;
import com.ecommerce.oms.warehouse.WarehouseService;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * All stock changes go through here. Each change is one guarded UPDATE (doc 05) plus one ledger row, in
 * the caller's transaction. The order-flow methods (reserve, release, packDeduct, restock) return whether
 * the guard passed; the calling service decides what a {@code false} means (retry, conflict, ...).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final WarehouseService warehouseService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ---- admin ------------------------------------------------------------------------------

    /**
     * Signed stock change. Creates the row when missing (delta must then be positive). A negative delta may
     * not take on_hand below reserved: INVENTORY_ADJUSTMENT_INVALID.
     */
    @Transactional
    public InventoryResponse adjust(AdjustmentRequest request, Long actorId) {
        int delta = request.delta();
        if (delta == 0) {
            throw new BusinessRuleException(ErrorCode.INVENTORY_ADJUSTMENT_INVALID, "delta must not be zero");
        }
        Product product = requireProduct(request.productId());
        Warehouse warehouse = warehouseService.require(request.warehouseId());

        Inventory row = inventoryRepository.findByProduct_IdAndWarehouse_Id(product.getId(), warehouse.getId())
                .orElse(null);
        if (row == null) {
            if (delta < 0) {
                throw new BusinessRuleException(ErrorCode.INVENTORY_ADJUSTMENT_INVALID,
                        "No stock of product " + product.getId() + " in warehouse " + warehouse.getId());
            }
            row = new Inventory();
            row.setProduct(product);
            row.setWarehouse(warehouse);
            row.setOnHand(delta);
            row.setReserved(0);
            row = inventoryRepository.save(row);
        } else {
            int updated = inventoryRepository.adjust(product.getId(), warehouse.getId(), delta, clock.instant());
            if (updated == 0) {
                throw new BusinessRuleException(ErrorCode.INVENTORY_ADJUSTMENT_INVALID,
                        "Adjustment of " + delta + " would take on_hand below the reserved quantity ("
                                + row.getReserved() + ")");
            }
            row = reload(product.getId(), warehouse.getId());
        }
        recordMovement(product, warehouse, delta > 0 ? MovementType.STOCK_IN : MovementType.ADJUSTMENT, delta,
                ReferenceType.MANUAL, null, request.reason(), actorId);
        events.publishEvent(new InventoryAdjustedEvent(product.getId(), warehouse.getId(), delta, actorId));
        log.info("Inventory adjusted: product={} warehouse={} delta={} by user {}", product.getId(),
                warehouse.getId(), delta, actorId);
        return InventoryMapper.toResponse(row);
    }

    @Transactional
    public InventoryResponse setThreshold(ThresholdRequest request) {
        Inventory row = inventoryRepository
                .findByProduct_IdAndWarehouse_Id(request.productId(), request.warehouseId())
                .orElseThrow(() -> new NotFoundException("No stock of product " + request.productId()
                        + " in warehouse " + request.warehouseId()));
        row.setLowStockThreshold(request.lowStockThreshold());
        return InventoryMapper.toResponse(row);
    }

    @Transactional(readOnly = true)
    public Page<InventoryResponse> search(Long productId, Long warehouseId, boolean lowStock, Pageable pageable) {
        return inventoryRepository.search(productId, warehouseId, lowStock, pageable).map(InventoryMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<MovementResponse> movements(Long productId, Long warehouseId, MovementType type, Pageable pageable) {
        return movementRepository.search(productId, warehouseId, type, pageable).map(InventoryMapper::toResponse);
    }

    // ---- queries for other modules ---------------------------------------------------------

    /** Per product: availability in each active warehouse, ordered by warehouse priority then id. */
    @Transactional(readOnly = true)
    public Map<Long, List<WarehouseStock>> availabilitySnapshot(Collection<Long> productIds) {
        Map<Long, List<WarehouseStock>> result = new LinkedHashMap<>();
        for (Long id : productIds) {
            result.put(id, new ArrayList<>());
        }
        if (!productIds.isEmpty()) {
            for (WarehouseStock stock : inventoryRepository.availabilitySnapshot(productIds)) {
                result.get(stock.productId()).add(stock);
            }
        }
        return result;
    }

    // ---- order flow (used by checkout, fulfillment, cancellation, returns) ----------------------

    /** Checkout reservation; false = not enough available at this instant (guard failed). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(Long productId, Long warehouseId, int qty, Long orderId) {
        boolean ok = inventoryRepository.tryReserve(productId, warehouseId, qty, clock.instant()) == 1;
        if (ok) {
            recordMovement(productId, warehouseId, MovementType.RESERVE, qty, ReferenceType.ORDER, orderId, null, null);
        }
        return ok;
    }

    /** Cancel before pack: false = the reservation was not there to release. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(Long productId, Long warehouseId, int qty, Long orderId) {
        boolean ok = inventoryRepository.release(productId, warehouseId, qty, clock.instant()) == 1;
        if (ok) {
            recordMovement(productId, warehouseId, MovementType.RELEASE, -qty, ReferenceType.ORDER, orderId, null, null);
        }
        return ok;
    }

    /** Shipment PACKED: false = reserved/on_hand did not cover the quantity. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean packDeduct(Long productId, Long warehouseId, int qty, Long shipmentId) {
        boolean ok = inventoryRepository.packDeduct(productId, warehouseId, qty, clock.instant()) == 1;
        if (ok) {
            recordMovement(productId, warehouseId, MovementType.PACK_DEDUCT, -qty, ReferenceType.SHIPMENT, shipmentId,
                    null, null);
        }
        return ok;
    }

    /** Units back on the shelf after a cancel-after-pack or a received return. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean restock(Long productId, Long warehouseId, int qty, MovementType type, ReferenceType referenceType,
                           Long referenceId, Long actorId) {
        boolean ok = inventoryRepository.restock(productId, warehouseId, qty, clock.instant()) == 1;
        if (ok) {
            recordMovement(productId, warehouseId, type, qty, referenceType, referenceId, null, actorId);
        }
        return ok;
    }

    /**
     * Return received with restock: units go back on the shelf at the return warehouse. Creates the stock row
     * when the product was never stocked there (doc 09 step 1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restockOrCreate(Long productId, Long warehouseId, int qty, Long returnId, Long actorId) {
        if (inventoryRepository.restock(productId, warehouseId, qty, clock.instant()) == 0) {
            Inventory row = new Inventory();
            row.setProduct(productRepository.getReferenceById(productId));
            row.setWarehouse(warehouseService.require(warehouseId));
            row.setOnHand(qty);
            row.setReserved(0);
            inventoryRepository.save(row);
        }
        recordMovement(productId, warehouseId, MovementType.RETURN_RESTOCK, qty, ReferenceType.RETURN, returnId, null,
                actorId);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Product requireProduct(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
    }

    private Inventory reload(Long productId, Long warehouseId) {
        return inventoryRepository.findByProduct_IdAndWarehouse_Id(productId, warehouseId).orElseThrow();
    }

    private void recordMovement(Long productId, Long warehouseId, MovementType type, int quantity,
                                ReferenceType referenceType, Long referenceId, String reason, Long actorId) {
        recordMovement(productRepository.getReferenceById(productId), warehouseService.require(warehouseId), type,
                quantity, referenceType, referenceId, reason, actorId);
    }

    private void recordMovement(Product product, Warehouse warehouse, MovementType type, int quantity,
                                ReferenceType referenceType, Long referenceId, String reason, Long actorId) {
        InventoryMovement m = new InventoryMovement();
        m.setProduct(product);
        m.setWarehouse(warehouse);
        m.setType(type);
        m.setQuantity(quantity);
        m.setReferenceType(referenceType);
        m.setReferenceId(referenceId);
        m.setReason(reason);
        m.setActorId(actorId);
        movementRepository.save(m);
    }
}
