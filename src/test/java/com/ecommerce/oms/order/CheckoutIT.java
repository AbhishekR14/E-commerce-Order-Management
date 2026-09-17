package com.ecommerce.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.MovementType;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.order.dto.CheckoutRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.order.dto.ShippingAddressRequest;
import com.ecommerce.oms.pricing.CouponRepository;
import com.ecommerce.oms.pricing.DiscountType;
import com.ecommerce.oms.pricing.entity.Coupon;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.support.TestDataFactory;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The synchronous half of checkout (doc 06). Async side effects are asserted from phase 7 on. */
class CheckoutIT extends AbstractIntegrationTest {

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    JdbcTemplate jdbc;

    User alice;
    Product phone;
    Product book;
    Warehouse blr;
    Warehouse mum;

    @BeforeEach
    void setUp() {
        alice = data.customer(1);
        Category electronics = data.category("Electronics", "18");
        Category books = data.category("Books", "5");
        phone = data.product("PH-001", "Phone", "1000.00", electronics);
        book = data.product("BK-001", "Book", "500.00", books);
        blr = data.warehouse("BLR-1", 1);
        mum = data.warehouse("MUM-1", 2);
    }

    private MockHttpServletRequestBuilder checkout(User as, String key, CheckoutRequest body) throws Exception {
        return postJson("/api/v1/checkout", body, as).header("Idempotency-Key", key);
    }

    private Inventory stock(Product p, Warehouse w) {
        return inventoryRepository.findByProduct_IdAndWarehouse_Id(p.getId(), w.getId()).orElseThrow();
    }

    @Test
    @DisplayName("happy path: doc 08 totals, stock reserved, ledger written, payment recorded, cart cleared, history")
    void happyPath() throws Exception {
        data.stock(phone, blr, 10);
        data.stock(book, blr, 10);
        data.coupon("WELCOME10", DiscountType.PERCENTAGE, "10", "200", "0");
        data.cartWith(alice, phone, 2);
        data.cartWith(alice, book, 1);

        MvcResult result = mvc.perform(checkout(alice, "key-0001-happy", TestDataFactory.checkoutRequest("welcome10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.matchesRegex("ORD-\\d{8}-[A-Z0-9]{6}")))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.subtotal").value(2500.00))
                .andExpect(jsonPath("$.discountTotal").value(200.00))
                .andExpect(jsonPath("$.taxTotal").value(354.20))
                .andExpect(jsonPath("$.grandTotal").value(2654.20))
                .andExpect(jsonPath("$.couponCode").value("WELCOME10"))
                .andExpect(jsonPath("$.placedAt").isString())
                .andExpect(jsonPath("$.shippingAddress.city").value("Bengaluru"))
                .andExpect(jsonPath("$.shippingAddress.line2").doesNotExist())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].sku").value("PH-001"))
                .andExpect(jsonPath("$.items[0].lineDiscount").value(160.00))
                .andExpect(jsonPath("$.items[0].lineTax").value(331.20))
                .andExpect(jsonPath("$.items[0].lineTotal").value(2171.20))
                .andExpect(jsonPath("$.items[0].returnedQuantity").value(0))
                .andExpect(jsonPath("$.items[0].allocations[0].warehouseId").value(blr.getId()))
                .andExpect(jsonPath("$.items[0].allocations[0].quantity").value(2))
                .andExpect(jsonPath("$.items[1].lineTotal").value(483.00))
                .andExpect(jsonPath("$.shipments", hasSize(0)))
                .andExpect(jsonPath("$.payment.amount").value(2654.20))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.transactionRef").value(org.hamcrest.Matchers.startsWith("MOCK-")))
                .andExpect(jsonPath("$.refunds", hasSize(0)))
                .andExpect(jsonPath("$.statusHistory", hasSize(1)))
                .andExpect(jsonPath("$.statusHistory[0].from").doesNotExist())
                .andExpect(jsonPath("$.statusHistory[0].to").value("PLACED"))
                .andExpect(jsonPath("$.statusHistory[0].actorId").value(alice.getId()))
                .andReturn();
        OrderResponse order = readBody(result, OrderResponse.class);

        // stock: reserved, not deducted
        assertThat(stock(phone, blr).getReserved()).isEqualTo(2);
        assertThat(stock(phone, blr).getOnHand()).isEqualTo(10);
        assertThat(stock(book, blr).getReserved()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where type = ? and reference_id = ?",
                Integer.class, MovementType.RESERVE.name(), order.id())).isEqualTo(2);

        // coupon used once, redemption recorded
        assertThat(couponRepository.findByCode("WELCOME10").orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from coupon_redemptions where order_id = ? and released = false",
                Integer.class, order.id())).isEqualTo(1);

        // cart cleared
        mvc.perform(getJson("/api/v1/cart", alice))
                .andExpect(jsonPath("$.items", hasSize(0)));

        // visible in the customer views
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(2654.20));
        mvc.perform(getJson("/api/v1/orders", alice))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(order.id()));
    }

    @Test
    @DisplayName("same Idempotency-Key -> 200 with the original order, stock reserved once; a new key -> a new order")
    void idempotentReplay() throws Exception {
        data.stock(phone, blr, 10);
        data.cartWith(alice, phone, 1);

        long first = readBody(mvc.perform(checkout(alice, "key-0002-replay", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isCreated()).andReturn(), OrderResponse.class).id();

        // the cart is now empty, yet the replay still returns the original order
        mvc.perform(checkout(alice, "key-0002-replay", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));
        assertThat(stock(phone, blr).getReserved()).isEqualTo(1);
        assertThat(orderRepository.count()).isEqualTo(1);

        // a different key with a refilled cart places a second order
        data.cartWith(alice, phone, 1);
        mvc.perform(checkout(alice, "key-0003-another", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(first)));
        assertThat(stock(phone, blr).getReserved()).isEqualTo(2);
    }

    @Test
    void idempotencyKey_missingOrTooShort_400() throws Exception {
        mvc.perform(postJson("/api/v1/checkout", TestDataFactory.checkoutRequest(null), alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
        mvc.perform(checkout(alice, "short", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addressValidation_400() throws Exception {
        data.stock(phone, blr, 1);
        data.cartWith(alice, phone, 1);
        CheckoutRequest bad = new CheckoutRequest(null,
                new ShippingAddressRequest("A", "L1", null, "City", "ST", "12345", "12345"));

        mvc.perform(checkout(alice, "key-0004-address", bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "shippingAddress.pincode", "shippingAddress.phone")));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void emptyCart_422() throws Exception {
        mvc.perform(checkout(alice, "key-0005-empty", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
    }

    @Test
    @DisplayName("invalid coupon -> 422, nothing reserved, no order, cart intact")
    void invalidCoupon_rollsBackEverything() throws Exception {
        data.stock(phone, blr, 10);
        data.cartWith(alice, phone, 1);

        mvc.perform(checkout(alice, "key-0006-coupon", TestDataFactory.checkoutRequest("NOPE")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"));

        assertThat(stock(phone, blr).getReserved()).isZero();
        assertThat(orderRepository.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cart_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements", Integer.class)).isZero();
    }

    @Test
    void inactiveProductInCart_422() throws Exception {
        data.stock(phone, blr, 10);
        data.cartWith(alice, phone, 1);
        data.deactivate(phone);

        mvc.perform(checkout(alice, "key-0007-inactive", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("not enough stock anywhere -> 409 INSUFFICIENT_STOCK, no reservations")
    void insufficientStock_409() throws Exception {
        data.stock(phone, blr, 2);
        data.stock(phone, mum, 2);
        data.cartWith(alice, phone, 5);

        mvc.perform(checkout(alice, "key-0008-stock", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail").value("Only 4 units of PH-001 available (requested 5)"));

        assertThat(stock(phone, blr).getReserved()).isZero();
        assertThat(stock(phone, mum).getReserved()).isZero();
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("a split order reserves from two warehouses and records two allocations")
    void splitWarehouseOrder() throws Exception {
        data.stock(phone, blr, 3);
        data.stock(phone, mum, 4);
        data.cartWith(alice, phone, 5);

        mvc.perform(checkout(alice, "key-0009-split", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].allocations", hasSize(2)))
                .andExpect(jsonPath("$.items[0].allocations[0].warehouseId").value(blr.getId()))
                .andExpect(jsonPath("$.items[0].allocations[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].allocations[1].warehouseId").value(mum.getId()))
                .andExpect(jsonPath("$.items[0].allocations[1].quantity").value(2));

        assertThat(stock(phone, blr).getReserved()).isEqualTo(3);
        assertThat(stock(phone, mum).getReserved()).isEqualTo(2);
    }

    @Test
    @DisplayName("per-customer coupon limit: the second order by the same customer is refused")
    void perCustomerLimit() throws Exception {
        data.stock(phone, blr, 10);
        Coupon once = data.coupon("ONCE", DiscountType.FLAT, "100", null, "0");   // perCustomerLimit 1
        data.cartWith(alice, phone, 1);
        mvc.perform(checkout(alice, "key-0010-once-a", TestDataFactory.checkoutRequest("ONCE")))
                .andExpect(status().isCreated());

        data.cartWith(alice, phone, 1);
        mvc.perform(checkout(alice, "key-0011-once-b", TestDataFactory.checkoutRequest("ONCE")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"));

        // but another customer may still use it
        User bob = data.customer(2);
        data.cartWith(bob, phone, 1);
        mvc.perform(checkout(bob, "key-0012-once-c", TestDataFactory.checkoutRequest("ONCE")))
                .andExpect(status().isCreated());
        assertThat(couponRepository.findById(once.getId()).orElseThrow().getUsedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("customers only see their own orders; admins see everything and can filter")
    void ownershipAndAdminSearch() throws Exception {
        data.stock(phone, blr, 10);
        User bob = data.customer(2);
        data.cartWith(alice, phone, 1);
        data.cartWith(bob, phone, 1);
        long aliceOrder = readBody(mvc.perform(checkout(alice, "key-0013-alice", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isCreated()).andReturn(), OrderResponse.class).id();
        mvc.perform(checkout(bob, "key-0014-bob", TestDataFactory.checkoutRequest(null)))
                .andExpect(status().isCreated());

        mvc.perform(getJson("/api/v1/orders/" + aliceOrder, bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(getJson("/api/v1/orders", bob))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(getJson("/api/v1/orders?status=CANCELLED", alice))
                .andExpect(jsonPath("$.totalElements").value(0));

        User admin = data.admin();
        mvc.perform(getJson("/api/v1/admin/orders", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(getJson("/api/v1/admin/orders?customerId=" + bob.getId(), admin))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(getJson("/api/v1/admin/orders?status=PLACED&from=2020-01-01T00:00:00Z&to=2099-01-01T00:00:00Z", admin))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(getJson("/api/v1/admin/orders?to=2020-01-01T00:00:00Z", admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(getJson("/api/v1/admin/orders/" + aliceOrder, admin))
                .andExpect(status().isOk());

        // role boundaries
        mvc.perform(getJson("/api/v1/admin/orders", alice))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/checkout").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Idempotency-Key", "key-0015-staff").header("Authorization", bearer(data.staff(blr))))
                .andExpect(status().isForbidden());
    }
}
