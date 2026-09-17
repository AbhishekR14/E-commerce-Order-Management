package com.ecommerce.oms.notification;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.fulfillment.ShipmentRepository;
import com.ecommerce.oms.notification.entity.Notification;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.support.TestDataFactory;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NotificationIT extends AbstractIntegrationTest {

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Test
    @DisplayName("checkout -> routing -> customer notified of CONFIRMED, staff of both warehouses notified of their shipment")
    void checkoutNotifiesCustomerAndStaff() throws Exception {
        User alice = data.customer(1);
        Warehouse blr = data.warehouse("BLR-1", 1);
        Warehouse mum = data.warehouse("MUM-1", 2);
        User blrStaff = data.staff(blr);
        User mumStaff = data.staff(mum);
        User inactiveStaff = data.deactivate(data.user("old.blr@oms.test", "Old", com.ecommerce.oms.user.Role.WAREHOUSE_STAFF, blr.getId()));
        Category c = data.category("Electronics", "18");
        Product phone = data.product("PH-001", "100.00", c);
        data.stock(phone, blr, 3);
        data.stock(phone, mum, 3);
        data.cartWith(alice, phone, 5);   // forces a split: 3 from BLR + 2 from MUM

        long orderId = readBody(mvc.perform(postJson("/api/v1/checkout", TestDataFactory.checkoutRequest(null), alice)
                        .header("Idempotency-Key", "notif-0001-split"))
                .andExpect(status().isCreated()).andReturn(), OrderResponse.class).id();

        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(shipmentRepository.findAllByOrderId(orderId)).hasSize(2);
            List<Notification> aliceN = notificationRepository.findAllByUserIdOrderByIdAsc(alice.getId());
            assertThat(aliceN).hasSize(1);
            assertThat(aliceN.get(0).getType()).isEqualTo(NotificationType.ORDER_STATUS);
            assertThat(aliceN.get(0).getTitle()).contains("CONFIRMED");
            assertThat(aliceN.get(0).getReferenceType()).isEqualTo("ORDER");
            assertThat(aliceN.get(0).getReferenceId()).isEqualTo(orderId);
            assertThat(aliceN.get(0).isRead()).isFalse();
            assertThat(notificationRepository.findAllByUserIdOrderByIdAsc(blrStaff.getId()))
                    .singleElement().satisfies(n -> assertThat(n.getType()).isEqualTo(NotificationType.SHIPMENT_ASSIGNED));
            assertThat(notificationRepository.findAllByUserIdOrderByIdAsc(mumStaff.getId())).hasSize(1);
        });
        assertThat(notificationRepository.findAllByUserIdOrderByIdAsc(inactiveStaff.getId())).isEmpty();

        // the endpoints: own notifications only, unreadOnly filter, mark read, 404 for someone else's
        mvc.perform(getJson("/api/v1/notifications", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ORDER_STATUS"))
                .andExpect(jsonPath("$.content[0].read").value(false));
        long id = notificationRepository.findAllByUserIdOrderByIdAsc(alice.getId()).get(0).getId();
        mvc.perform(patchJson("/api/v1/notifications/" + id + "/read", "{}", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        mvc.perform(getJson("/api/v1/notifications?unreadOnly=true", alice))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(getJson("/api/v1/notifications", alice))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(patchJson("/api/v1/notifications/" + id + "/read", "{}", blrStaff))
                .andExpect(status().isNotFound());
        mvc.perform(getJson("/api/v1/notifications", blrStaff))
                .andExpect(jsonPath("$.content[0].type").value("SHIPMENT_ASSIGNED"))
                .andExpect(jsonPath("$.content[0].referenceType").value("SHIPMENT"));
        mvc.perform(getJson("/api/v1/notifications", null))
                .andExpect(status().isUnauthorized());
    }
}
