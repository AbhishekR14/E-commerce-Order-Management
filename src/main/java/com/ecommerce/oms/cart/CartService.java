package com.ecommerce.oms.cart;

import com.ecommerce.oms.cart.dto.CartResponse;
import com.ecommerce.oms.cart.entity.Cart;
import com.ecommerce.oms.cart.entity.CartItem;
import com.ecommerce.oms.catalog.InventoryQueryPort;
import com.ecommerce.oms.catalog.ProductService;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.InvalidRequestException;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.pricing.PriceQuote;
import com.ecommerce.oms.pricing.PricingLine;
import com.ecommerce.oms.pricing.PricingService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One cart per customer, created on first touch. Availability here is a <b>soft</b> check for a good UX;
 * stock is reserved only at checkout (doc 00 #10).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    public static final int MAX_LINE_QUANTITY = 100;

    private final CartRepository cartRepository;
    private final ProductService productService;
    private final InventoryQueryPort inventoryQueryPort;
    private final PricingService pricingService;

    @Transactional
    public CartResponse getCart(Long customerId) {
        return toResponse(cartOf(customerId));
    }

    /** Adds to any existing quantity of the same product. */
    @Transactional
    public CartResponse addItem(Long customerId, Long productId, int quantity) {
        Cart cart = cartOf(customerId);
        Product product = requireSellable(productId);
        CartItem item = cart.itemFor(productId).orElse(null);
        int total = quantity + (item == null ? 0 : item.getQuantity());
        checkQuantity(product, total);
        if (item == null) {
            item = new CartItem();
            item.setProduct(product);
            cart.addItem(item);
        }
        item.setQuantity(total);
        log.debug("Cart {}: product {} -> {}", cart.getId(), productId, total);
        return toResponse(cart);
    }

    /** Sets the quantity of a line that must already exist. */
    @Transactional
    public CartResponse updateItem(Long customerId, Long productId, int quantity) {
        Cart cart = cartOf(customerId);
        CartItem item = cart.itemFor(productId)
                .orElseThrow(() -> new NotFoundException("Cart item for product " + productId + " not found"));
        checkQuantity(requireSellable(productId), quantity);
        item.setQuantity(quantity);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse removeItem(Long customerId, Long productId) {
        Cart cart = cartOf(customerId);
        CartItem item = cart.itemFor(productId)
                .orElseThrow(() -> new NotFoundException("Cart item for product " + productId + " not found"));
        cart.getItems().remove(item);   // orphanRemoval deletes the row
        return toResponse(cart);
    }

    @Transactional
    public void clear(Long customerId) {
        cartOf(customerId).getItems().clear();
    }

    /** Prices the cart with an optional coupon without placing an order. Same rules as checkout (doc 08). */
    @Transactional
    public PriceQuote quote(Long customerId, String couponCode) {
        Cart cart = cartOf(customerId);
        return pricingService.quote(customerId, pricingLines(cart), couponCode);
    }

    /**
     * Snapshot of the cart as pricing input. Fails with CART_EMPTY or PRODUCT_UNAVAILABLE, the same checks
     * checkout applies, so a quote that succeeds will price identically at checkout.
     */
    @Transactional(readOnly = true)
    public List<PricingLine> pricingLines(Cart cart) {
        if (cart.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.CART_EMPTY, "The cart is empty");
        }
        return cart.getItems().stream().map(item -> {
            Product product = item.getProduct();
            if (!product.isActive()) {
                throw new BusinessRuleException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "Product " + product.getSku() + " is no longer available");
            }
            return PricingLine.of(product, item.getQuantity());
        }).toList();
    }

    /** The cart entity for checkout/pricing (same transaction as the caller). */
    @Transactional
    public Cart cartOf(Long customerId) {
        return cartRepository.findByCustomerId(customerId).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setCustomerId(customerId);
            return cartRepository.save(cart);
        });
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Product requireSellable(Long productId) {
        Product product = productService.findExisting(productId);
        if (!product.isActive()) {
            throw new BusinessRuleException(ErrorCode.PRODUCT_UNAVAILABLE,
                    "Product " + product.getSku() + " is no longer available");
        }
        return product;
    }

    private void checkQuantity(Product product, int total) {
        if (total > MAX_LINE_QUANTITY) {
            throw new InvalidRequestException("Quantity per product cannot exceed " + MAX_LINE_QUANTITY);
        }
        int available = inventoryQueryPort.availableQuantities(List.of(product.getId()))
                .getOrDefault(product.getId(), 0);
        if (total > available) {
            throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK,
                    "Only " + available + " units of " + product.getSku() + " available");
        }
    }

    private CartResponse toResponse(Cart cart) {
        List<Long> productIds = cart.getItems().stream().map(i -> i.getProduct().getId()).toList();
        Map<Long, Integer> available = inventoryQueryPort.availableQuantities(productIds);
        return CartMapper.toResponse(cart, available);
    }
}
