package com.ecommerce.oms.returns;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.fulfillment.ShipmentRepository;
import com.ecommerce.oms.fulfillment.ShipmentStatus;
import com.ecommerce.oms.fulfillment.dto.ShipmentStatusUpdateRequest;
import com.ecommerce.oms.fulfillment.entity.Shipment;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.pricing.DiscountType;
import com.ecommerce.oms.returns.dto.CreateReturnRequest;
import com.ecommerce.oms.returns.dto.ReceiveReturnRequest;
import com.ecommerce.oms.returns.dto.RejectReturnRequest;
import com.ecommerce.oms.returns.dto.ReturnDecisionRequest;
import com.ecommerce.oms.returns.dto.ReturnResponse;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Doc 09 end to end: request -> approve -> receive with restock and pro-rata refund. */
class ReturnFlowIT extends AbstractIntegrationTest {

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
    Product phone;   // 1000.00 @ 18%
    Product book;    // 500.00 @ 5%
    Warehouse blr;
    Warehouse mum;

    @BeforeEach
    void setUp() {
        alice = data.customer(1);
        admin = data.admin();
        Category electronics = data.category("Electronics", "18");
        Category books = data.category("Books", "5");
        phone = data.product("PH-001", "Phone", "1000.00", electronics);
        book = data.product("BK-001", "Book", "500.00", books);
        blr = data.warehouse("BLR-1", 1);
        mum = data.warehouse("MUM-1", 2);
        blrStaff = data.staff(blr);
        mumStaff = data.staff(mum);
    }

    private Inventory stock(Product p, Warehouse w) {
        return inventoryRepository.findByProduct_IdAndWarehouse_Id(p.getId(), w.getId()).orElseThrow();
    }

    /** Places the doc 08 worked-example order (2 phones + 1 book, WELCOME10) and drives it to DELIVERED. */
    private OrderResponse deliveredWorkedExample() throws Exception {
        data.stock(phone, blr, 10);
        data.stock(book, blr, 10);
        data.coupon("WELCOME10", DiscountType.PERCENTAGE, "10", "200", "0");
        data.cartWith(alice, phone, 2);
        data.cartWith(alice, book, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, "WELCOME10");
        for (Shipment s : shipmentRepository.findAllByOrderId(order.id())) {
            for (ShipmentStatus st : List.of(ShipmentStatus.PACKED, ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED)) {
                mvc.perform(patchJson("/api/v1/warehouse/shipments/" + s.getId() + "/status",
                                new ShipmentStatusUpdateRequest(st, st == ShipmentStatus.SHIPPED ? "TRK" : null), blrStaff))
                        .andExpect(status().isOk());
            }
        }
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice)).andExpect(jsonPath("$.status").value("DELIVERED"));
        return order;
    }

    private static CreateReturnRequest returnOf(String reason, Long orderItemId, int qty) {
        return new CreateReturnRequest(reason, List.of(new CreateReturnRequest.Item(orderItemId, qty)));
    }

    @Test
    @DisplayName("partial return -> PARTIALLY_RETURNED with a pro-rata refund; the rest -> RETURNED; refunds sum to the payment")
    void happyPath() throws Exception {
        OrderResponse order = deliveredWorkedExample();
        long phoneItem = order.items().get(0).id();   // 2 x 1000, lineTotal 2171.20
        long bookItem = order.items().get(1).id();    // 1 x 500, lineTotal 483.00
        assertThat(stock(phone, blr).getOnHand()).isEqualTo(8);

        // request 1 phone
        long ret1 = readBody(mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns",
                        returnOf("Screen scratched", phoneItem, 1), alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.orderNumber").value(order.orderNumber()))
                .andExpect(jsonPath("$.warehouseCode").value("BLR-1"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("PH-001"))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.items[0].restock").doesNotExist())
                .andExpect(jsonPath("$.refundAmount").doesNotExist())
                .andReturn(), ReturnResponse.class).id();

        // staff of BLR see it, approve it
        mvc.perform(getJson("/api/v1/warehouse/returns?status=REQUESTED", blrStaff))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret1 + "/approve", new ReturnDecisionRequest("ok"), blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionNote").value("ok"))
                .andExpect(jsonPath("$.decidedAt").isString());
        // receive before approval of anything else: restock = true
        long returnItem1 = readBody(mvc.perform(getJson("/api/v1/returns/" + ret1, alice)).andReturn(), ReturnResponse.class)
                .items().get(0).id();
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret1 + "/receive",
                        new ReceiveReturnRequest(List.of(new ReceiveReturnRequest.Item(returnItem1, true))), blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundAmount").value(1085.60))         // round(2171.20 * 1/2)
                .andExpect(jsonPath("$.items[0].restock").value(true))
                .andExpect(jsonPath("$.receivedAt").isString());

        assertThat(stock(phone, blr).getOnHand()).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where type = 'RETURN_RESTOCK' and reference_id = ?",
                Integer.class, ret1)).isEqualTo(1);
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.items[0].returnedQuantity").value(1))
                .andExpect(jsonPath("$.items[0].refundedAmount").value(1085.60))
                .andExpect(jsonPath("$.refunds", hasSize(1)))
                .andExpect(jsonPath("$.refunds[0].reason").value("RETURN"))
                .andExpect(jsonPath("$.refunds[0].amount").value(1085.60));

        // return the other phone (restock=false) and the book: order becomes RETURNED
        long ret2 = readBody(mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns",
                        new CreateReturnRequest("Not needed", List.of(
                                new CreateReturnRequest.Item(phoneItem, 1), new CreateReturnRequest.Item(bookItem, 1))), alice))
                .andExpect(status().isCreated()).andReturn(), ReturnResponse.class).id();
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret2 + "/approve", "{}", admin))
                .andExpect(status().isOk());
        ReturnResponse r2 = readBody(mvc.perform(getJson("/api/v1/returns/" + ret2, alice)).andReturn(), ReturnResponse.class);
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret2 + "/receive", new ReceiveReturnRequest(List.of(
                        new ReceiveReturnRequest.Item(r2.items().get(0).id(), false),
                        new ReceiveReturnRequest.Item(r2.items().get(1).id(), true))), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(1568.60));        // 1085.60 (2171.20 - 1085.60) + 483.00

        assertThat(stock(phone, blr).getOnHand()).isEqualTo(9);              // restock=false: unchanged
        assertThat(stock(book, blr).getOnHand()).isEqualTo(10);              // 9 + 1 restocked
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.items[0].returnedQuantity").value(2))
                .andExpect(jsonPath("$.items[0].refundedAmount").value(2171.20))
                .andExpect(jsonPath("$.items[1].refundedAmount").value(483.00))
                .andExpect(jsonPath("$.refunds", hasSize(2)));
        BigDecimal refunded = jdbc.queryForObject("select sum(amount) from refunds where order_id = ?", BigDecimal.class, order.id());
        assertThat(refunded).isEqualByComparingTo(order.grandTotal());           // 2654.20: never more than paid
        assertThat(jdbc.queryForObject("select count(*) from refunds where return_request_id is null", Integer.class)).isZero();

        // nothing left to return
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("again", bookItem, 1), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_ALLOWED"));

        // customer views and notifications
        mvc.perform(getJson("/api/v1/returns", alice))
                .andExpect(jsonPath("$.totalElements").value(2));
        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'RETURN_STATUS'",
                    Integer.class, alice.getId())).isEqualTo(6);   // REQUESTED, APPROVED, REFUNDED x 2
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'RETURN_REQUESTED'",
                    Integer.class, blrStaff.getId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'REFUND_ISSUED'",
                    Integer.class, alice.getId())).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("window expired (mutable clock) -> RETURN_NOT_ALLOWED; not delivered -> RETURN_NOT_ALLOWED")
    void windowAndStatus() throws Exception {
        OrderResponse order = deliveredWorkedExample();
        long phoneItem = order.items().get(0).id();

        clock.advance(Duration.ofDays(7).plusMinutes(1));
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("late", phoneItem, 1), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("7-day return window")));
        clock.reset();
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("in time", phoneItem, 1), alice))
                .andExpect(status().isCreated());

        // a merely CONFIRMED order cannot be returned
        data.cartWith(alice, book, 1);
        OrderResponse confirmed = data.placedAndRoutedOrder(alice, null);
        mvc.perform(postJson("/api/v1/orders/" + confirmed.id() + "/returns",
                        returnOf("too early", confirmed.items().get(0).id(), 1), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("quantity above returnable (including open requests); rejection frees it; duplicates and foreign items")
    void quantityRules() throws Exception {
        OrderResponse order = deliveredWorkedExample();
        long phoneItem = order.items().get(0).id();   // quantity 2

        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("x", phoneItem, 3), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.detail").value("Only 2 unit(s) of PH-001 can still be returned"));

        long open = readBody(mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("x", phoneItem, 2), alice))
                .andExpect(status().isCreated()).andReturn(), ReturnResponse.class).id();
        // both units are tied up in the open request
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("y", phoneItem, 1), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Only 0 unit(s) of PH-001 can still be returned"));

        // reject note is required; rejection frees the quantity
        mvc.perform(postJson("/api/v1/warehouse/returns/" + open + "/reject", Map.of("note", " "), blrStaff))
                .andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/warehouse/returns/" + open + "/reject", new RejectReturnRequest("damaged by customer"), blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("damaged by customer"));
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("y", phoneItem, 1), alice))
                .andExpect(status().isCreated());
        // a rejected return cannot be approved or received any more
        mvc.perform(postJson("/api/v1/warehouse/returns/" + open + "/approve", "{}", blrStaff))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // duplicates -> 400; an item of another order -> 422; empty items -> 400
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", new CreateReturnRequest("dup", List.of(
                        new CreateReturnRequest.Item(phoneItem, 1), new CreateReturnRequest.Item(phoneItem, 1))), alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("foreign", 999L, 1), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_ALLOWED"));
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", new CreateReturnRequest("empty", List.of()), alice))
                .andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("nobody", phoneItem, 1), data.customer(2)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("staff of another warehouse get 404; receive needs APPROVED and every item listed once")
    void staffScopeAndReceiveRules() throws Exception {
        OrderResponse order = deliveredWorkedExample();
        long phoneItem = order.items().get(0).id();
        long ret = readBody(mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns", returnOf("x", phoneItem, 1), alice))
                .andExpect(status().isCreated()).andReturn(), ReturnResponse.class).id();
        long returnItem = readBody(mvc.perform(getJson("/api/v1/returns/" + ret, alice)).andReturn(), ReturnResponse.class)
                .items().get(0).id();

        mvc.perform(getJson("/api/v1/warehouse/returns/" + ret, mumStaff))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/approve", "{}", mumStaff))
                .andExpect(status().isNotFound());
        mvc.perform(getJson("/api/v1/warehouse/returns", mumStaff))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(getJson("/api/v1/warehouse/returns", alice))
                .andExpect(status().isForbidden());
        mvc.perform(getJson("/api/v1/returns/" + ret, data.customer(2)))
                .andExpect(status().isNotFound());

        // receive before approval -> 409
        ReceiveReturnRequest receive = new ReceiveReturnRequest(List.of(new ReceiveReturnRequest.Item(returnItem, true)));
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/receive", receive, blrStaff))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/approve", "{}", blrStaff))
                .andExpect(status().isOk());
        // wrong / missing items -> 400
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/receive",
                        new ReceiveReturnRequest(List.of(new ReceiveReturnRequest.Item(999L, true))), blrStaff))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/receive", new ReceiveReturnRequest(List.of(
                        new ReceiveReturnRequest.Item(returnItem, true), new ReceiveReturnRequest.Item(returnItem, false))), blrStaff))
                .andExpect(status().isBadRequest());
        assertThat(stock(phone, blr).getOnHand()).isEqualTo(8);

        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/receive", receive, blrStaff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        // receiving twice -> 409, one refund only
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/receive", receive, blrStaff))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from refunds where return_request_id = ?", Integer.class, ret)).isEqualTo(1);
    }

    @Test
    @DisplayName("restock into a warehouse that never stocked the product creates the inventory row")
    void restockCreatesRow() throws Exception {
        // MUM never stocked the phone; make the return land there by allocating from MUM only
        data.stock(phone, mum, 5);
        data.cartWith(alice, phone, 1);
        OrderResponse order = data.placedAndRoutedOrder(alice, null);
        Shipment s = shipmentRepository.findAllByOrderId(order.id()).get(0);
        for (ShipmentStatus st : List.of(ShipmentStatus.PACKED, ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED)) {
            mvc.perform(patchJson("/api/v1/warehouse/shipments/" + s.getId() + "/status",
                    new ShipmentStatusUpdateRequest(st, "TRK"), mumStaff)).andExpect(status().isOk());
        }
        // simulate the row disappearing (e.g. product delisted and stock zeroed) by deleting it
        jdbc.update("delete from inventory_movements");
        jdbc.update("delete from inventory where warehouse_id = ?", mum.getId());
        assertThat(inventoryRepository.findByProduct_IdAndWarehouse_Id(phone.getId(), mum.getId())).isEmpty();

        long ret = readBody(mvc.perform(postJson("/api/v1/orders/" + order.id() + "/returns",
                        returnOf("x", order.items().get(0).id(), 1), alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warehouseCode").value("MUM-1"))
                .andReturn(), ReturnResponse.class).id();
        long item = readBody(mvc.perform(getJson("/api/v1/returns/" + ret, alice)).andReturn(), ReturnResponse.class).items().get(0).id();
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/approve", "{}", mumStaff)).andExpect(status().isOk());
        mvc.perform(postJson("/api/v1/warehouse/returns/" + ret + "/receive",
                        new ReceiveReturnRequest(List.of(new ReceiveReturnRequest.Item(item, true))), mumStaff))
                .andExpect(status().isOk());

        Inventory created = stock(phone, mum);
        assertThat(created.getOnHand()).isEqualTo(1);
        assertThat(created.getReserved()).isZero();
        mvc.perform(getJson("/api/v1/orders/" + order.id(), alice))
                .andExpect(jsonPath("$.status").value("RETURNED"));
    }
}
