package com.ecommerce.oms.order;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.order.dto.CancelOrderRequest;
import com.ecommerce.oms.security.AuthUser;
import com.ecommerce.oms.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin - Orders", description = "Search and inspect any order")
public class AdminOrderController {

    private final OrderService orderService;
    private final CancellationService cancellationService;

    @GetMapping
    @Operation(summary = "Search orders by status, customer and placed-at range (ISO-8601 instants)")
    public PageResponse<OrderResponse> search(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20, sort = "placedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(orderService.search(status, customerId, from, to, pageable));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel any order while nothing has shipped (full refund)")
    public OrderResponse cancel(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                                @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return cancellationService.cancel(principal, id, request == null ? null : request.reason());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Any order by id")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.getAny(id);
    }
}
