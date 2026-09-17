package com.ecommerce.oms.order;

import com.ecommerce.oms.order.CheckoutFacade.CheckoutResult;
import com.ecommerce.oms.order.dto.CheckoutRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkout")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "Place an order from the cart")
public class CheckoutController {

    private final CheckoutFacade checkoutFacade;

    /** 201 with the new order, or 200 with the original order when the Idempotency-Key was already used. */
    @PostMapping
    @Operation(summary = "Place an order (idempotent via the Idempotency-Key header)")
    public ResponseEntity<OrderResponse> checkout(
            @AuthenticationPrincipal AuthUser principal,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutResult result = checkoutFacade.checkout(principal.id(), idempotencyKey, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.order());
    }
}
