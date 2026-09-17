package com.ecommerce.oms.returns;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.returns.dto.CreateReturnRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Returns", description = "Request returns of delivered items")
public class CustomerReturnController {

    private final ReturnService returnService;

    @PostMapping("/orders/{orderId}/returns")
    @Operation(summary = "Request a return of some units of a delivered order (within the return window)")
    public ResponseEntity<ReturnResponse> create(@AuthenticationPrincipal AuthUser principal, @PathVariable Long orderId,
                                                 @Valid @RequestBody CreateReturnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(returnService.create(principal.id(), orderId, request));
    }

    @GetMapping("/returns")
    @Operation(summary = "Own return requests")
    public PageResponse<ReturnResponse> list(
            @AuthenticationPrincipal AuthUser principal,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(returnService.listForCustomer(principal.id(), pageable));
    }

    @GetMapping("/returns/{id}")
    @Operation(summary = "One of your return requests")
    public ReturnResponse get(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return returnService.getForCustomer(principal.id(), id);
    }
}
