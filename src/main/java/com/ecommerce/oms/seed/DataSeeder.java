package com.ecommerce.oms.seed;

import com.ecommerce.oms.catalog.CategoryRepository;
import com.ecommerce.oms.catalog.ProductRepository;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.inventory.InventoryMovementRepository;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.MovementType;
import com.ecommerce.oms.inventory.ReferenceType;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.inventory.entity.InventoryMovement;
import com.ecommerce.oms.pricing.CouponRepository;
import com.ecommerce.oms.pricing.DiscountType;
import com.ecommerce.oms.pricing.entity.Coupon;
import com.ecommerce.oms.user.Role;
import com.ecommerce.oms.user.UserRepository;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.WarehouseRepository;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo data for reviewers (docs/design/12-implementation-plan.md, phase 11). Runs at startup when
 * {@code app.seed.enabled=true} and only if the users table is empty, so a Postgres database is seeded once.
 * The credentials below are documented in the README and are for demo use only.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    public static final String ADMIN_EMAIL = "admin@oms.test";
    public static final String ADMIN_PASSWORD = "Admin@123";
    public static final String STAFF_PASSWORD = "Staff@123";
    public static final String CUSTOMER_PASSWORD = "Customer@123";

    private final UserRepository userRepository;
    private final WarehouseRepository warehouseRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryMovementRepository movementRepository;
    private final CouponRepository couponRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("Seed skipped: users already exist");
            return;
        }
        log.info("Seeding demo data");

        // ---- users and warehouses
        User admin = user(ADMIN_EMAIL, "OMS Admin", Role.ADMIN, ADMIN_PASSWORD, null);
        Warehouse blr = warehouse("BLR-1", "Bengaluru Hub", "Bengaluru", 1);
        Warehouse mum = warehouse("MUM-1", "Mumbai Hub", "Mumbai", 2);
        Warehouse del = warehouse("DEL-1", "Delhi Hub", "Delhi", 3);
        user("staff.blr@oms.test", "BLR Staff", Role.WAREHOUSE_STAFF, STAFF_PASSWORD, blr.getId());
        user("staff.mum@oms.test", "MUM Staff", Role.WAREHOUSE_STAFF, STAFF_PASSWORD, mum.getId());
        user("staff.del@oms.test", "DEL Staff", Role.WAREHOUSE_STAFF, STAFF_PASSWORD, del.getId());
        user("alice@oms.test", "Alice Customer", Role.CUSTOMER, CUSTOMER_PASSWORD, null);
        user("bob@oms.test", "Bob Customer", Role.CUSTOMER, CUSTOMER_PASSWORD, null);

        // ---- categories (tax rate per category, no inheritance)
        Category electronics = category("Electronics", "18.00", null);
        Category phones = category("Phones", "18.00", electronics);
        Category laptops = category("Laptops", "18.00", electronics);
        Category books = category("Books", "5.00", null);
        Category grocery = category("Grocery", "5.00", null);
        Category apparel = category("Apparel", "12.00", null);

        // ---- products with stock: (blr, mum, del). Some exist only in MUM-1 to demonstrate split shipments.
        stock(product("PH-001", "Pixel 9", "Google Pixel 9, 128 GB", "59999.00", phones), blr, mum, del, 10, 5, 0, admin);
        stock(product("PH-002", "Galaxy S24", "Samsung Galaxy S24, 256 GB", "69999.00", phones), blr, mum, del, 8, 0, 4, admin);
        stock(product("PH-003", "iPhone 15", "Apple iPhone 15, 128 GB", "79999.00", phones), blr, mum, del, 3, 3, 0, admin);
        stock(product("LP-001", "ThinkPad X1 Carbon", "14-inch, 16 GB RAM, 512 GB SSD", "149999.00", laptops), blr, mum, del, 4, 0, 2, admin);
        stock(product("LP-002", "MacBook Air M3", "13-inch, 8 GB RAM, 256 GB SSD", "114999.00", laptops), blr, mum, del, 0, 6, 0, admin);
        stock(product("BK-001", "Clean Code", "Robert C. Martin", "499.00", books), blr, mum, del, 50, 20, 20, admin);
        stock(product("BK-002", "Domain-Driven Design", "Eric Evans", "899.00", books), blr, mum, del, 0, 15, 0, admin);
        stock(product("BK-003", "Designing Data-Intensive Applications", "Martin Kleppmann", "1299.00", books), blr, mum, del, 25, 0, 10, admin);
        stock(product("GR-001", "Basmati Rice 5 kg", "Aged long-grain rice", "699.00", grocery), blr, mum, del, 100, 100, 100, admin);
        stock(product("GR-002", "Olive Oil 1 L", "Extra virgin", "899.00", grocery), blr, mum, del, 40, 0, 30, admin);
        stock(product("AP-001", "Cotton T-shirt", "Unisex, navy, size M", "799.00", apparel), blr, mum, del, 60, 60, 0, admin);
        stock(product("AP-002", "Slim Fit Jeans", "Dark wash, 32/32", "1999.00", apparel), blr, mum, del, 0, 25, 25, admin);

        // ---- coupons
        var now = clock.instant();
        coupon("WELCOME10", "10% off, max 200", DiscountType.PERCENTAGE, "10", "200", "0", null,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)), null, 1);
        coupon("FLAT100", "100 off orders of 999 or more", DiscountType.FLAT, "100", null, "999", null,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)), 1000, 5);
        coupon("BOOKS20", "20% off books", DiscountType.PERCENTAGE, "20", null, "0", books,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)), null, 3);
        coupon("EXPIRED5", "5% off (expired)", DiscountType.PERCENTAGE, "5", null, "0", null,
                now.minus(Duration.ofDays(365)), now.minus(Duration.ofDays(1)), null, 1);

        log.info("Seed complete: {} users, {} warehouses, {} products, {} coupons", userRepository.count(),
                warehouseRepository.count(), productRepository.count(), couponRepository.count());
    }

    private User user(String email, String name, Role role, String password, Long warehouseId) {
        User u = new User();
        u.setEmail(email.toLowerCase(Locale.ROOT));
        u.setFullName(name);
        u.setRole(role);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setWarehouseId(warehouseId);
        u.setActive(true);
        return userRepository.save(u);
    }

    private Warehouse warehouse(String code, String name, String city, int priority) {
        Warehouse w = new Warehouse();
        w.setCode(code);
        w.setName(name);
        w.setCity(city);
        w.setPriority(priority);
        w.setActive(true);
        return warehouseRepository.save(w);
    }

    private Category category(String name, String taxRate, Category parent) {
        Category c = new Category();
        c.setName(name);
        c.setSlug(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        c.setTaxRate(new BigDecimal(taxRate));
        c.setParent(parent);
        c.setActive(true);
        return categoryRepository.save(c);
    }

    private Product product(String sku, String name, String description, String price, Category category) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setDescription(description);
        p.setPrice(new BigDecimal(price));
        p.setCategory(category);
        p.setActive(true);
        return productRepository.save(p);
    }

    private void stock(Product p, Warehouse blr, Warehouse mum, Warehouse del, int qBlr, int qMum, int qDel, User admin) {
        stockAt(p, blr, qBlr, admin);
        stockAt(p, mum, qMum, admin);
        stockAt(p, del, qDel, admin);
    }

    private void stockAt(Product p, Warehouse w, int qty, User admin) {
        if (qty <= 0) {
            return;
        }
        Inventory i = new Inventory();
        i.setProduct(p);
        i.setWarehouse(w);
        i.setOnHand(qty);
        i.setReserved(0);
        inventoryRepository.save(i);
        InventoryMovement m = new InventoryMovement();
        m.setProduct(p);
        m.setWarehouse(w);
        m.setType(MovementType.STOCK_IN);
        m.setQuantity(qty);
        m.setReferenceType(ReferenceType.MANUAL);
        m.setReason("seed");
        m.setActorId(admin.getId());
        movementRepository.save(m);
    }

    private void coupon(String code, String description, DiscountType type, String value, String cap, String minOrder,
                        Category category, Instant from, Instant to, Integer usageLimit,
                        int perCustomer) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDescription(description);
        c.setDiscountType(type);
        c.setDiscountValue(new BigDecimal(value));
        c.setMaxDiscount(cap == null ? null : new BigDecimal(cap));
        c.setMinOrderAmount(new BigDecimal(minOrder));
        c.setCategory(category);
        c.setValidFrom(from);
        c.setValidTo(to);
        c.setUsageLimit(usageLimit);
        c.setPerCustomerLimit(perCustomer);
        c.setActive(true);
        couponRepository.save(c);
    }
}
