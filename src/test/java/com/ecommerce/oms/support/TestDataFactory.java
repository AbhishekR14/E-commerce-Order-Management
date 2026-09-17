package com.ecommerce.oms.support;

import com.ecommerce.oms.catalog.CategoryRepository;
import com.ecommerce.oms.catalog.ProductRepository;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.user.Role;
import com.ecommerce.oms.user.UserRepository;
import com.ecommerce.oms.user.entity.User;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

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

    private String hash;

    public User admin() {
        return user("admin@oms.test", "Admin", Role.ADMIN, null);
    }

    public User customer(int n) {
        return user("customer" + n + "@oms.test", "Customer " + n, Role.CUSTOMER, null);
    }

    public User staff(Long warehouseId) {
        return user("staff" + warehouseId + "@oms.test", "Staff " + warehouseId, Role.WAREHOUSE_STAFF, warehouseId);
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

    /** BCrypt is slow by design; hash the shared password once per JVM. */
    private String passwordHash() {
        if (hash == null) {
            hash = passwordEncoder.encode(PASSWORD);
        }
        return hash;
    }
}
