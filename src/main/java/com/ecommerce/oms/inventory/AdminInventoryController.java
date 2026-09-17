package com.ecommerce.oms.inventory;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.inventory.dto.AdjustmentRequest;
import com.ecommerce.oms.inventory.dto.InventoryResponse;
import com.ecommerce.oms.inventory.dto.MovementResponse;
import com.ecommerce.oms.inventory.dto.ThresholdRequest;
import com.ecommerce.oms.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/inventory")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin - Inventory", description = "Stock levels, adjustments and the movement ledger")
public class AdminInventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    @Operation(summary = "Stock rows, optionally by product/warehouse or low stock only")
    public PageResponse<InventoryResponse> search(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false, defaultValue = "false") boolean lowStock,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(inventoryService.search(productId, warehouseId, lowStock, pageable));
    }

    @PostMapping("/adjustments")
    @Operation(summary = "Stock in (delta > 0) or adjust down (delta < 0, never below reserved)")
    public ResponseEntity<InventoryResponse> adjust(@Valid @RequestBody AdjustmentRequest request,
                                                    @AuthenticationPrincipal AuthUser principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.adjust(request, principal.id()));
    }

    @PatchMapping("/threshold")
    @Operation(summary = "Set the low-stock threshold of one stock row")
    public InventoryResponse setThreshold(@Valid @RequestBody ThresholdRequest request) {
        return inventoryService.setThreshold(request);
    }

    @GetMapping("/movements")
    @Operation(summary = "The append-only movement ledger")
    public PageResponse<MovementResponse> movements(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) MovementType type,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(inventoryService.movements(productId, warehouseId, type, pageable));
    }
}
