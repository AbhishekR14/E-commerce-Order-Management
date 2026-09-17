package com.ecommerce.oms.cart;

import com.ecommerce.oms.cart.dto.CartResponse;
import com.ecommerce.oms.cart.dto.CartResponse.CartItemResponse;
import com.ecommerce.oms.cart.entity.Cart;
import com.ecommerce.oms.cart.entity.CartItem;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.common.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class CartMapper {

    private CartMapper() {
    }

    /** @param availableByProduct available quantity per product id (missing = 0) */
    public static CartResponse toResponse(Cart cart, Map<Long, Integer> availableByProduct) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(item -> toResponse(item, availableByProduct.getOrDefault(item.getProduct().getId(), 0)))
                .toList();
        BigDecimal subtotal = Money.sum(items.stream().map(CartItemResponse::lineSubtotal).toList());
        return new CartResponse(items, subtotal);
    }

    private static CartItemResponse toResponse(CartItem item, int available) {
        Product p = item.getProduct();
        return new CartItemResponse(
                p.getId(),
                p.getSku(),
                p.getName(),
                p.getPrice(),
                item.getQuantity(),
                Money.multiply(p.getPrice(), item.getQuantity()),
                p.isActive() && available >= item.getQuantity());
    }
}
