package com.ecommerce.oms.support;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import com.ecommerce.oms.catalog.CategoryRepository;
import com.ecommerce.oms.catalog.ProductRepository;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.cart.CartRepository;
import com.ecommerce.oms.cart.entity.Cart;
import com.ecommerce.oms.cart.entity.CartItem;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.pricing.CouponRepository;
import com.ecommerce.oms.pricing.DiscountType;
import com.ecommerce.oms.pricing.entity.Coupon;
import com.ecommerce.oms.fulfillment.ShipmentRepository;
import com.ecommerce.oms.order.CheckoutFacade;
import com.ecommerce.oms.order.dto.CheckoutRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.order.dto.ShippingAddressRequest;
import com.ecommerce.oms.user.Role;
import com.ecommerce.oms.user.UserRepository;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.WarehouseRepository;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds test data straight through the repositories (no HTTP), so tests can focus on the behaviour
 * under test. Grows with each phase (warehouses, products, stock, coupons, orders...).
 */
@Component
@RequiredArgsConstructor
public class TestDataFactory {

    /** Every factory-made user has this password, so tests can log in through the API when they want to. */
    public static final String PASSWORD = "Passw0rd!";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryRepository inventoryRepository;
    private final CouponRepository couponRepository;
    private final CartRepository cartRepository;
    private final CheckoutFacade checkoutFacade;
    private final ShipmentRepository shipmentRepository;
    private final Clock clock;

    private String hash;

    public User admin() {
        return user("admin@oms.test", "Admin", Role.ADMIN, null);
    }

    public User customer(int n) {
        return user("customer" + n + "@oms.test", "Customer " + n, Role.CUSTOMER, null);
    }

    public User staff(Warehouse warehouse) {
        return user("staff." + warehouse.getCode().toLowerCase() + "@oms.test", "Staff " + warehouse.getCode(),
                Role.WAREHOUSE_STAFF, warehouse.getId());
    }

    public User user(String email, String fullName, Role role, Long warehouseId) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordHash());
        user.setFullName(fullName);
        user.setRole(role);
        user.setWarehouseId(warehouseId);
        user.setActive(true);
        return userRepository.save(user);
    }

    public User deactivate(User user) {
        user.setActive(false);
        return userRepository.save(user);
    }

    // ---- catalog ----------------------------------------------------------------------------

    public Category category(String name, String taxRate) {
        return category(name, taxRate, null);
    }

    public Category category(String name, String taxRate, Category parent) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(name.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        category.setTaxRate(new BigDecimal(taxRate));
        category.setParent(parent);
        category.setActive(true);
        return categoryRepository.save(category);
    }

    public Product product(String sku, String price, Category category) {
        return product(sku, "Product " + sku, price, category);
    }

    public Product product(String sku, String name, String price, Category category) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setDescription(null);
        product.setCategory(category);
        product.setPrice(new BigDecimal(price));
        product.setActive(true);
        return productRepository.save(product);
    }

    public Product deactivate(Product product) {
        product.setActive(false);
        return productRepository.save(product);
    }

    // ---- warehouses and stock ------------------------------------------------------------------

    public Warehouse warehouse(String code, int priority) {
        Warehouse w = new Warehouse();
        w.setCode(code);
        w.setName("Warehouse " + code);
        w.setCity(code.substring(0, Math.min(3, code.length())));
        w.setPriority(priority);
        w.setActive(true);
        return warehouseRepository.save(w);
    }

    public Warehouse deactivate(Warehouse warehouse) {
        warehouse.setActive(false);
        return warehouseRepository.save(warehouse);
    }

    /** on_hand = qty, reserved = 0. */
    public Inventory stock(Product product, Warehouse warehouse, int qty) {
        return stock(product, warehouse, qty, 0);
    }

    public Inventory stock(Product product, Warehouse warehouse, int onHand, int reserved) {
        Inventory i = new Inventory();
        i.setProduct(product);
        i.setWarehouse(warehouse);
        i.setOnHand(onHand);
        i.setReserved(reserved);
        return inventoryRepository.save(i);
    }

    // ---- coupons -------------------------------------------------------------------------------

    /** Active for +/- 1 day around now, unlimited global use, once per customer, no category scope. */
    public Coupon coupon(String code, DiscountType type, String value, String maxDiscount, String minOrder) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDescription(code);
        c.setDiscountType(type);
        c.setDiscountValue(new BigDecimal(value));
        c.setMaxDiscount(maxDiscount == null ? null : new BigDecimal(maxDiscount));
        c.setMinOrderAmount(new BigDecimal(minOrder));
        c.setValidFrom(clock.instant().minus(Duration.ofDays(1)));
        c.setValidTo(clock.instant().plus(Duration.ofDays(1)));
        c.setPerCustomerLimit(1);
        c.setActive(true);
        return couponRepository.save(c);
    }

    // ---- carts and checkout ------------------------------------------------------------------

    /** Puts qty of the product in the customer cart (creating the cart if needed), bypassing the soft checks. */
    @Transactional
    public Cart cartWith(User customer, Product product, int qty) {
        Cart cart = cartRepository.findByCustomerId(customer.getId()).orElseGet(() -> {
            Cart c = new Cart();
            c.setCustomerId(customer.getId());
            return c;
        });
        CartItem item = new CartItem();
        item.setProduct(product);
        item.setQuantity(qty);
        cart.addItem(item);
        return cartRepository.save(cart);
    }

    public static ShippingAddressRequest address() {
        return new ShippingAddressRequest("Alice Test", "1 Test Street", null, "Bengaluru", "KA", "560001", "9876543210");
    }

    public static CheckoutRequest checkoutRequest(String couponCode) {
        return new CheckoutRequest(couponCode, address());
    }

    private int orderSeq;

    /** Places an order from the customer cart through the real checkout path (no HTTP). */
    public OrderResponse placeOrder(User customer, String couponCode) {
        return checkoutFacade.checkout(customer.getId(), "factory-key-" + (++orderSeq) + "-" + customer.getId(),
                checkoutRequest(couponCode)).order();
    }

    /** Places the order and waits until the async routing has created its shipments (order CONFIRMED). */
    public OrderResponse placedAndRoutedOrder(User customer, String couponCode) {
        OrderResponse order = placeOrder(customer, couponCode);
        await().atMost(5, SECONDS).until(() -> shipmentRepository.existsByOrderId(order.id()));
        return order;
    }

    /** BCrypt is slow by design; hash the shared password once per JVM. */
    private String passwordHash() {
        if (hash == null) {
            hash = passwordEncoder.encode(PASSWORD);
        }
        return hash;
    }
}
