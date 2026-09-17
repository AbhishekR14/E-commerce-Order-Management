package com.ecommerce.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.pricing.CouponRepository;
import com.ecommerce.oms.pricing.DiscountType;
import com.ecommerce.oms.pricing.entity.Coupon;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.support.TestDataFactory;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The no-oversell proof (doc 11): many customers race for the last units through the real checkout path
 * ({@link CheckoutFacade}, so retries are included). Asserts the invariants, not the mechanism.
 */
class InventoryConcurrencyIT extends AbstractIntegrationTest {

    static final int CUSTOMERS = 20;

    @Autowired
    CheckoutFacade checkoutFacade;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    JdbcTemplate jdbc;

    Product phone;
    Warehouse blr;
    Warehouse mum;

    @BeforeEach
    void setUp() {
        Category electronics = data.category("Electronics", "18");
        phone = data.product("PH-001", "Phone", "1000.00", electronics);
        blr = data.warehouse("BLR-1", 1);
        mum = data.warehouse("MUM-1", 2);
    }

    /** One checkout per customer, all released at the same instant. Returns the exceptions (null = success). */
    private List<Throwable> raceCheckouts(List<User> customers, String couponCode) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(customers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        for (User customer : customers) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    checkoutFacade.checkout(customer.getId(), "race-" + n.incrementAndGet() + "-" + customer.getId(),
                            TestDataFactory.checkoutRequest(couponCode));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        start.countDown();
        List<Throwable> outcomes = new ArrayList<>();
        for (Future<Throwable> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return outcomes;
    }

    private List<User> customersWithCarts(int count, int qtyEach) {
        List<User> customers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            User c = data.customer(i);
            data.cartWith(c, phone, qtyEach);
            customers.add(c);
        }
        return customers;
    }

    private void assertNoOversell() {
        Integer bad = jdbc.queryForObject("select count(*) from inventory where reserved > on_hand", Integer.class);
        assertThat(bad).isZero();
    }

    @Test
    @DisplayName("20 customers buy the last 5 units: exactly 5 orders, 15 INSUFFICIENT_STOCK, reserved == 5")
    void twentyCustomersFiveUnits() throws Exception {
        data.stock(phone, blr, 5);
        List<User> customers = customersWithCarts(CUSTOMERS, 1);

        List<Throwable> outcomes = raceCheckouts(customers, null);

        long successes = outcomes.stream().filter(t -> t == null).count();
        long insufficient = outcomes.stream().filter(t -> t instanceof InsufficientStockException).count();
        List<Throwable> unexpected = outcomes.stream()
                .filter(t -> t != null && !(t instanceof InsufficientStockException)).toList();
        assertThat(unexpected).as("unexpected failures: %s", unexpected).isEmpty();
        assertThat(successes).isEqualTo(5);
        assertThat(insufficient).isEqualTo(15);

        Inventory row = inventoryRepository.findByProduct_IdAndWarehouse_Id(phone.getId(), blr.getId()).orElseThrow();
        assertThat(row.getReserved()).isEqualTo(5);
        assertThat(row.getOnHand()).isEqualTo(5);
        assertThat(orderRepository.count()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from payments", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select coalesce(sum(quantity), 0) from inventory_movements where type = 'RESERVE'",
                Integer.class)).isEqualTo(5);
        assertNoOversell();
    }

    @Test
    @DisplayName("stock split across two warehouses (3 + 2): still exactly 5 orders, nothing oversold")
    void twoWarehouses() throws Exception {
        data.stock(phone, blr, 3);
        data.stock(phone, mum, 2);
        List<User> customers = customersWithCarts(CUSTOMERS, 1);

        List<Throwable> outcomes = raceCheckouts(customers, null);

        assertThat(outcomes.stream().filter(t -> t == null).count()).isEqualTo(5);
        assertThat(outcomes.stream().filter(t -> t instanceof InsufficientStockException).count()).isEqualTo(15);
        Inventory b = inventoryRepository.findByProduct_IdAndWarehouse_Id(phone.getId(), blr.getId()).orElseThrow();
        Inventory m = inventoryRepository.findByProduct_IdAndWarehouse_Id(phone.getId(), mum.getId()).orElseThrow();
        assertThat(b.getReserved()).isEqualTo(3);
        assertThat(m.getReserved()).isEqualTo(2);
        assertThat(orderRepository.count()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from order_item_allocations", Integer.class)).isEqualTo(5);
        assertNoOversell();
    }

    @Test
    @DisplayName("a coupon with usage_limit 3 under 10 concurrent checkouts is redeemed exactly 3 times")
    void couponUsageLimit() throws Exception {
        data.stock(phone, blr, 100);
        Coupon coupon = data.coupon("LIMIT3", DiscountType.FLAT, "100", null, "0");
        coupon.setUsageLimit(3);
        couponRepository.save(coupon);
        List<User> customers = customersWithCarts(10, 1);

        List<Throwable> outcomes = raceCheckouts(customers, "LIMIT3");

        long successes = outcomes.stream().filter(t -> t == null).count();
        long couponInvalid = outcomes.stream()
                .filter(t -> t instanceof BusinessRuleException e && e.getCode() == ErrorCode.COUPON_INVALID).count();
        assertThat(successes).isEqualTo(3);
        assertThat(couponInvalid).isEqualTo(7);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from coupon_redemptions", Integer.class)).isEqualTo(3);
        assertThat(orderRepository.count()).isEqualTo(3);
        // the 7 rolled-back checkouts left no reservations behind
        Inventory row = inventoryRepository.findByProduct_IdAndWarehouse_Id(phone.getId(), blr.getId()).orElseThrow();
        assertThat(row.getReserved()).isEqualTo(3);
        assertNoOversell();
    }
}
