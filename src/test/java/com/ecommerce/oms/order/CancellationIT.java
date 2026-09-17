package com.ecommerce.oms.order;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.fulfillment.FulfillmentRoutingService;
import com.ecommerce.oms.fulfillment.ShipmentRepository;
import com.ecommerce.oms.fulfillment.ShipmentStatus;
import com.ecommerce.oms.fulfillment.dto.ShipmentStatusUpdateRequest;
import com.ecommerce.oms.fulfillment.entity.Shipment;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.order.dto.CancelOrderRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.pricing.CouponRepository;
import com.ecommerce.oms.pricing.DiscountType;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CancellationIT extends AbstractIntegrationTest {

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    FulfillmentRoutingService routingService;

    @Autowired
    JdbcTemplate jdbc;

    User alice;
    User admin;
    Product phone;
    Warehouse blr;
    Warehouse mum;
    User blrStaff;

    @BeforeEach
    void setUp() {
        alice = data.customer(1);
        admin = data.admin();
        Category c = data.category("Electronics", "18");
        phone = data.product("PH-001", "Phone", "100.00", c);
        blr = data.warehouse("BLR-1", 1);
        mum = data.warehouse("MUM-1", 2);
        blrStaff = data.staff(blr);
    }

    private Inventory stock(Warehouse w) {
        return inventoryRepository.findByProduct_IdAndWarehouse_Id(phone.getId(), w.getId()).orElseThrow();
    }

    private Shipment shipmentAt(long orderId, Warehouse w) {
        return shipmentRepository.findAllByOrderId(orderId).stream()
                .filter(s -> s.getWarehouse().getId().equals(w.getId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("cancel a CONFIRMED order: reservation released, full refund, coupon released, shipments cancelled")
    void cancelConfirmed() throws Exception {
        data.stock(phone, blr, 10);
        data.coupon("TEN", DiscountType.FLAT, "10", null, "0");
        data.cartWith(alice, phone, 2);
        OrderResponse order = data.placedAndRoutedOrder(alice, "TEN");
        assertThat(stock(blr).getReserved()).isEqualTo(2);
        assertThat(couponRepository.findByCode("TEN").orElseThrow().getUsedCount()).isEqualTo(1);

        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/cancel", new CancelOrderRequest("changed my mind"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString())
                .andExpect(jsonPath("$.refunds", hasSize(1)))
                .andExpect(jsonPath("$.refunds[0].amount").value(order.grandTotal().doubleValue()))
                .andExpect(jsonPath("$.refunds[0].reason").value("CANCELLATION"))
                .andExpect(jsonPath("$.refunds[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.shipments[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.statusHistory[-1].to").value("CANCELLED"))
                .andExpect(jsonPath("$.statusHistory[-1].note").value("changed my mind"))
                .andExpect(jsonPath("$.statusHistory[-1].actorId").value(alice.getId()));

        // stock: reservation released, on_hand untouched, RELEASE ledger row
        assertThat(stock(blr).getReserved()).isZero();
        assertThat(stock(blr).getOnHand()).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where type = 'RELEASE' and reference_id = ?",
                Integer.class, order.id())).isEqualTo(1);
        // coupon: use returned, redemption marked released
        assertThat(couponRepository.findByCode("TEN").orElseThrow().getUsedCount()).isZero();
        assertThat(jdbc.queryForObject("select released from coupon_redemptions where order_id = ?", Boolean.class,
                order.id())).isTrue();
        // the customer can use the coupon again
        data.cartWith(alice, phone, 1);
        mvc.perform(postJson("/api/v1/cart/quote", new com.ecommerce.oms.pricing.dto.QuoteRequest("TEN"), alice))
                .andExpect(status().isOk());

        // after commit: cancellation notification and refund notification, audit rows
        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'ORDER_CANCELLED'",
                    Integer.class, alice.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'REFUND_ISSUED'",
                    Integer.class, alice.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'OrderCancelled'", Integer.class))
                    .isEqualTo(1);
        });
    }

    @Test
    @DisplayName("cancel after one of two shipments is PACKED: packed units restocked, the other reservation released")
    void cancelAfterPartialPack() throws Exception {
        data.stock(phone, blr, 3);
        data.stock(phone, mum, 4);
        data.cartWith(alice, phone, 5);                      // 3 BLR + 2 MUM
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        long blrShipment = shipmentAt(order.id(), blr).getId();
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + blrShipment + "/status",
                        new ShipmentStatusUpdateRequest(ShipmentStatus.PACKED, null), blrStaff))
                .andExpect(status().isOk());
        assertThat(stock(blr).getOnHand()).isZero();
        assertThat(stock(mum).getReserved()).isEqualTo(2);

        mvc.perform(postJson("/api/v1/admin/orders/" + order.id() + "/cancel", new CancelOrderRequest(null), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.shipments[*].status").value(org.hamcrest.Matchers.contains("CANCELLED", "CANCELLED")))
                .andExpect(jsonPath("$.statusHistory[-1].actorId").value(admin.getId()));

        // BLR: the packed 3 are back on the shelf; MUM: the reservation of 2 is released
        assertThat(stock(blr).getOnHand()).isEqualTo(3);
        assertThat(stock(blr).getReserved()).isZero();
        assertThat(stock(mum).getOnHand()).isEqualTo(4);
        assertThat(stock(mum).getReserved()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where type = 'CANCEL_RESTOCK' and reference_id = ?",
                Integer.class, order.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where type = 'RELEASE' and reference_id = ?",
                Integer.class, order.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(amount) from refunds where order_id = ?", java.math.BigDecimal.class,
                order.id())).isEqualByComparingTo(order.grandTotal());
    }

    @Test
    @DisplayName("cancel after a shipment is SHIPPED -> 422 ORDER_NOT_CANCELLABLE, nothing changes")
    void cancelAfterShipped_422() throws Exception {
        data.stock(phone, blr, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        long shipment = shipmentAt(order.id(), blr).getId();
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status",
                new ShipmentStatusUpdateRequest(ShipmentStatus.PACKED, null), blrStaff)).andExpect(status().isOk());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status",
                new ShipmentStatusUpdateRequest(ShipmentStatus.SHIPPED, "TRK"), blrStaff)).andExpect(status().isOk());

        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/cancel", new CancelOrderRequest("late"), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
        mvc.perform(postJson("/api/v1/admin/orders/" + order.id() + "/cancel", new CancelOrderRequest("late"), admin))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.refunds", hasSize(0)));
        assertThat(stock(blr).getOnHand()).isEqualTo(4);
    }

    @Test
    void cancelTwice_422_andAnotherCustomer_404() throws Exception {
        data.stock(phone, blr, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        User bob = data.customer(2);

        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/cancel", new CancelOrderRequest(null), bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/cancel", new CancelOrderRequest(null), alice))
                .andExpect(status().isOk());
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/cancel", new CancelOrderRequest(null), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
        // one refund only
        assertThat(jdbc.queryForObject("select count(*) from refunds where order_id = ?", Integer.class, order.id()))
                .isEqualTo(1);
        // staff cannot cancel orders
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/cancel", new CancelOrderRequest(null), blrStaff))
                .andExpect(status().isForbidden());
        mvc.perform(postJson("/api/v1/admin/orders/" + order.id() + "/cancel", new CancelOrderRequest(null), alice))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cancel-then-routing race: routing a cancelled order creates no shipments")
    void routingAfterCancellationIsANoOp() throws Exception {
        data.stock(phone, blr, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placeOrder(alice, null);
        // cancel; whether routing already ran or not, the end state must be the same
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/cancel", new CancelOrderRequest("race"), alice))
                .andExpect(status().isOk());
        awaitListeners();
        // simulate the listener arriving late, on an order that has no (or only cancelled) shipments
        jdbc.update("delete from shipment_items");
        jdbc.update("delete from shipments");

        routingService.route(order.id());

        assertThat(shipmentRepository.findAllByOrderId(order.id())).isEmpty();
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stock(blr).getReserved()).isZero();
    }
}
