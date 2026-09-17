package com.ecommerce.oms.returns;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.returns.dto.ReceiveReturnRequest;
import com.ecommerce.oms.returns.dto.RejectReturnRequest;
import com.ecommerce.oms.returns.dto.ReturnDecisionRequest;
import com.ecommerce.oms.returns.dto.ReturnResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouse/returns")
@PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Warehouse - Returns", description = "Decide on and receive returns assigned to your warehouse")
public class WarehouseReturnController {

    private final ReturnService returnService;

    @GetMapping
    @Operation(summary = "Returns assigned to your warehouse (admins may filter by warehouseId)")
    public PageResponse<ReturnResponse> list(
            @AuthenticationPrincipal AuthUser principal,
            @RequestParam(required = false) ReturnStatus status,
            @RequestParam(required = false) Long warehouseId,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(returnService.listForWarehouse(principal, status, warehouseId, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One return of your warehouse")
    public ReturnResponse get(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return returnService.getForWarehouse(principal, id);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a requested return")
    public ReturnResponse approve(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                  @Valid @RequestBody(required = false) ReturnDecisionRequest request) {
        return returnService.approve(principal, id, request == null ? null : request.note());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a requested return (note required)")
    public ReturnResponse reject(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                 @Valid @RequestBody RejectReturnRequest request) {
        return returnService.reject(principal, id, request.note());
    }

    @PostMapping("/{id}/receive")
    @Operation(summary = "Receive an approved return: restock per item and refund pro rata")
    public ReturnResponse receive(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                  @Valid @RequestBody ReceiveReturnRequest request) {
        return returnService.receive(principal, id, request);
    }
}
