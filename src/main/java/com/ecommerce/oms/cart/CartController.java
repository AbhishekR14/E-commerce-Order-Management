package com.ecommerce.oms.cart;

import com.ecommerce.oms.cart.dto.AddCartItemRequest;
import com.ecommerce.oms.cart.dto.CartResponse;
import com.ecommerce.oms.cart.dto.UpdateCartItemRequest;
import com.ecommerce.oms.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
@Tag(name = "Cart", description = "The customer's cart")
public class CartController {

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "The current cart (created on first use)")
    public CartResponse get(@AuthenticationPrincipal AuthUser principal) {
        return cartService.getCart(principal.id());
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product; merges with an existing line")
    public ResponseEntity<CartResponse> add(@AuthenticationPrincipal AuthUser principal,
                                            @Valid @RequestBody AddCartItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cartService.addItem(principal.id(), request.productId(), request.quantity()));
    }

    @PutMapping("/items/{productId}")
    @Operation(summary = "Set the quantity of a line")
    public CartResponse update(@AuthenticationPrincipal AuthUser principal, @PathVariable Long productId,
                               @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItem(principal.id(), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove a line")
    public CartResponse remove(@AuthenticationPrincipal AuthUser principal, @PathVariable Long productId) {
        return cartService.removeItem(principal.id(), productId);
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart")
    public ResponseEntity<Void> clear(@AuthenticationPrincipal AuthUser principal) {
        cartService.clear(principal.id());
        return ResponseEntity.noContent().build();
    }
}
