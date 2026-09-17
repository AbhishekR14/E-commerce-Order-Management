package com.ecommerce.oms.fulfillment;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.fulfillment.dto.ShipmentStatusUpdateRequest;
import com.ecommerce.oms.fulfillment.entity.Shipment;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FulfillmentIT extends AbstractIntegrationTest {

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    JdbcTemplate jdbc;

    User alice;
    User blrStaff;
    User mumStaff;
    User admin;
    Product phone;
    Warehouse blr;
    Warehouse mum;

    @BeforeEach
    void setUp() {
        alice = data.customer(1);
        admin = data.admin();
        Category c = data.category("Electronics", "18");
        phone = data.product("PH-001", "Phone", "100.00", c);
        blr = data.warehouse("BLR-1", 1);
        mum = data.warehouse("MUM-1", 2);
        blrStaff = data.staff(blr);
        mumStaff = data.staff(mum);
    }

    private Inventory stock(Warehouse w) {
        return inventoryRepository.findByProduct_IdAndWarehouse_Id(phone.getId(), w.getId()).orElseThrow();
    }

    private Shipment shipmentAt(long orderId, Warehouse w) {
        return shipmentRepository.findAllByOrderId(orderId).stream()
                .filter(s -> s.getWarehouse().getId().equals(w.getId())).findFirst().orElseThrow();
    }

    private static ShipmentStatusUpdateRequest to(ShipmentStatus status) {
        return new ShipmentStatusUpdateRequest(status, null);
    }

    @Test
    @DisplayName("pack -> ship -> deliver across two shipments: stock deducted on pack, order status derived")
    void packShipDeliver_twoWarehouses() throws Exception {
        data.stock(phone, blr, 3);
        data.stock(phone, mum, 4);
        data.cartWith(alice, phone, 5);                       // 3 from BLR + 2 from MUM
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        long blrShipment = shipmentAt(order.id(), blr).getId();
        long mumShipment = shipmentAt(order.id(), mum).getId();

        // staff see their own warehouse only; the detail carries items and the address
        mvc.perform(getJson("/api/v1/warehouse/shipments", blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(blrShipment))
                .andExpect(jsonPath("$.content[0].orderNumber").value(order.orderNumber()))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
        mvc.perform(getJson("/api/v1/warehouse/shipments/" + blrShipment, blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouseCode").value("BLR-1"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("PH-001"))
                .andExpect(jsonPath("$.items[0].name").value("Phone"))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.shippingAddress.pincode").value("560001"));
        // admins see everything and may filter
        mvc.perform(getJson("/api/v1/warehouse/shipments", admin))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(getJson("/api/v1/warehouse/shipments?warehouseId=" + mum.getId(), admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].warehouseCode").value("MUM-1"));

        // BLR packs: on_hand 3 -> 0, reserved 3 -> 0; order stays CONFIRMED (MUM still PENDING)
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + blrShipment + "/status", to(ShipmentStatus.PACKED), blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PACKED"))
                .andExpect(jsonPath("$.packedAt").isString());
        assertThat(stock(blr).getOnHand()).isZero();
        assertThat(stock(blr).getReserved()).isZero();
        assertThat(stock(mum).getOnHand()).isEqualTo(4);
        assertThat(stock(mum).getReserved()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where type = 'PACK_DEDUCT' and reference_id = ?",
                Integer.class, blrShipment)).isEqualTo(1);
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // MUM packs: both PACKED -> order PACKED
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + mumShipment + "/status", to(ShipmentStatus.PACKED), mumStaff))
                .andExpect(status().isOk());
        assertThat(stock(mum).getOnHand()).isEqualTo(2);
        assertThat(stock(mum).getReserved()).isZero();
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("PACKED"));

        // BLR ships (tracking required) -> order still PACKED; MUM ships -> order SHIPPED
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + blrShipment + "/status",
                        new ShipmentStatusUpdateRequest(ShipmentStatus.SHIPPED, "TRK-BLR-1"), blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value("TRK-BLR-1"))
                .andExpect(jsonPath("$.shippedAt").isString());
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("PACKED"));
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + mumShipment + "/status",
                        new ShipmentStatusUpdateRequest(ShipmentStatus.SHIPPED, "TRK-MUM-1"), mumStaff))
                .andExpect(status().isOk());
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipments[*].trackingNumber").value(
                        org.hamcrest.Matchers.containsInAnyOrder("TRK-BLR-1", "TRK-MUM-1")));

        // deliver both -> DELIVERED with deliveredAt, history complete
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + blrShipment + "/status", to(ShipmentStatus.DELIVERED), blrStaff))
                .andExpect(status().isOk());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + mumShipment + "/status", to(ShipmentStatus.DELIVERED), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredAt").isString());
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveredAt").isString())
                .andExpect(jsonPath("$.statusHistory[*].to").value(org.hamcrest.Matchers.contains(
                        "PLACED", "CONFIRMED", "PACKED", "SHIPPED", "DELIVERED")));

        // the customer was told about shipping and delivery (SHIPPED/DELIVERED only, per warehouse)
        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'SHIPMENT_STATUS'",
                        Integer.class, alice.getId())).isEqualTo(4));
    }

    @Test
    @DisplayName("skipping a step, going backwards or touching a DELIVERED shipment -> 409 INVALID_STATE_TRANSITION")
    void invalidTransitions_409() throws Exception {
        data.stock(phone, blr, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        long shipment = shipmentAt(order.id(), blr).getId();

        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.SHIPPED), blrStaff))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PACKED")));
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.CANCELLED), blrStaff))
                .andExpect(status().isConflict());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.PENDING), blrStaff))
                .andExpect(status().isConflict());
        // nothing was deducted by the failed attempts
        assertThat(stock(blr).getOnHand()).isEqualTo(5);
        assertThat(stock(blr).getReserved()).isEqualTo(1);

        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.PACKED), blrStaff))
                .andExpect(status().isOk());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.PENDING), blrStaff))
                .andExpect(status().isConflict());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status",
                        new ShipmentStatusUpdateRequest(ShipmentStatus.SHIPPED, "T"), blrStaff))
                .andExpect(status().isOk());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.DELIVERED), blrStaff))
                .andExpect(status().isOk());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.DELIVERED), blrStaff))
                .andExpect(status().isConflict());
    }

    @Test
    void shippedWithoutTrackingNumber_400() throws Exception {
        data.stock(phone, blr, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        long shipment = shipmentAt(order.id(), blr).getId();
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.PACKED), blrStaff))
                .andExpect(status().isOk());

        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.SHIPPED), blrStaff))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("trackingNumber")));
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status",
                        new ShipmentStatusUpdateRequest(ShipmentStatus.SHIPPED, "  "), blrStaff))
                .andExpect(status().isBadRequest());
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", Map.of("status", "NOPE"), blrStaff))
                .andExpect(status().isBadRequest());
        assertThat(shipmentRepository.findById(shipment).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.PACKED);
    }

    @Test
    @DisplayName("a shipment of another warehouse is a 404 for staff (never revealed); customers get 403")
    void wrongWarehouse_404() throws Exception {
        data.stock(phone, blr, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        long shipment = shipmentAt(order.id(), blr).getId();

        mvc.perform(getJson("/api/v1/warehouse/shipments/" + shipment, mumStaff))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(patchJson("/api/v1/warehouse/shipments/" + shipment + "/status", to(ShipmentStatus.PACKED), mumStaff))
                .andExpect(status().isNotFound());
        // the warehouseId filter is ignored for staff: MUM staff still see nothing
        mvc.perform(getJson("/api/v1/warehouse/shipments?warehouseId=" + blr.getId(), mumStaff))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(getJson("/api/v1/warehouse/shipments/999", admin))
                .andExpect(status().isNotFound());
        mvc.perform(getJson("/api/v1/warehouse/shipments", alice))
                .andExpect(status().isForbidden());
        assertThat(shipmentRepository.findById(shipment).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.PENDING);
    }

    @Test
    @DisplayName("routing is idempotent: running it again for a CONFIRMED order creates nothing")
    void routingIdempotent(@Autowired FulfillmentRoutingService routing) throws Exception {
        data.stock(phone, blr, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        awaitListeners();
        List<Shipment> before = shipmentRepository.findAllByOrderId(order.id());

        routing.route(order.id());

        assertThat(shipmentRepository.findAllByOrderId(order.id())).hasSameSizeAs(before);
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.statusHistory", hasSize(2)));
    }
}
