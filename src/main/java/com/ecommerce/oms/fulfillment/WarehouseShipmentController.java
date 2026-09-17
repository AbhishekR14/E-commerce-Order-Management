package com.ecommerce.oms.fulfillment;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.fulfillment.dto.ShipmentResponse;
import com.ecommerce.oms.fulfillment.dto.ShipmentStatusUpdateRequest;
import com.ecommerce.oms.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouse/shipments")
@PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Warehouse - Shipments", description = "Pack, ship and deliver shipments of your warehouse")
public class WarehouseShipmentController {

    private final ShipmentService shipmentService;

    @GetMapping
    @Operation(summary = "Shipments of your warehouse (admins may filter by warehouseId)")
    public PageResponse<ShipmentResponse> list(
            @AuthenticationPrincipal AuthUser principal,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) Long warehouseId,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(shipmentService.list(principal, status, warehouseId, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One shipment with its items and the shipping address")
    public ShipmentResponse get(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return shipmentService.get(principal, id);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Move a shipment to the next status (PACKED deducts stock, SHIPPED needs a tracking number)")
    public ShipmentResponse updateStatus(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                         @Valid @RequestBody ShipmentStatusUpdateRequest request) {
        return shipmentService.updateStatus(principal, id, request.status(), request.trackingNumber());
    }
}
