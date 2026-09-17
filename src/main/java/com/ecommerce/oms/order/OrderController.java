package com.ecommerce.oms.order;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.order.dto.CancelOrderRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
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
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "The customer's own orders")
public class OrderController {

    private final OrderService orderService;
    private final CancellationService cancellationService;

    @GetMapping
    @Operation(summary = "Own orders, newest first, optionally by status")
    public PageResponse<OrderResponse> list(
            @AuthenticationPrincipal AuthUser principal,
            @RequestParam(required = false) OrderStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "placedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(orderService.listForCustomer(principal.id(), status, pageable));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an own order while nothing has shipped (full refund)")
    public OrderResponse cancel(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return cancellationService.cancel(principal, id, request == null ? null : request.reason());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One of the customer's own orders (another customer's order is a 404)")
    public OrderResponse get(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return orderService.getForCustomer(principal.id(), id);
    }
}
