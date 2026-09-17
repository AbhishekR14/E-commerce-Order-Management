package com.ecommerce.oms.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.cart.dto.AddCartItemRequest;
import com.ecommerce.oms.cart.dto.UpdateCartItemRequest;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.inventory.InventoryRepository;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CartIT extends AbstractIntegrationTest {

    @Autowired
    CartRepository cartRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    JdbcTemplate jdbc;

    User alice;
    Product phone;
    Product book;
    Warehouse blr;

    @BeforeEach
    void setUp() {
        alice = data.customer(1);
        Category electronics = data.category("Electronics", "18");
        Category books = data.category("Books", "5");
        phone = data.product("PH-001", "Pixel", "59999.00", electronics);
        book = data.product("BK-001", "Clean Code", "499.50", books);
        blr = data.warehouse("BLR-1", 1);
        data.stock(phone, blr, 5);
        data.stock(book, blr, 20);
    }

    @Test
    @DisplayName("empty cart is created on first GET")
    void emptyCart() throws Exception {
        assertThat(cartRepository.findByCustomerId(alice.getId())).isEmpty();

        mvc.perform(getJson("/api/v1/cart", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.subtotal").value(0.00));

        assertThat(cartRepository.findByCustomerId(alice.getId())).isPresent();
    }

    @Test
    @DisplayName("add, merge, update, remove, clear")
    void lifecycle() throws Exception {
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 2), alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(phone.getId()))
                .andExpect(jsonPath("$.items[0].sku").value("PH-001"))
                .andExpect(jsonPath("$.items[0].name").value("Pixel"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(59999.00))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineSubtotal").value(119998.00))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.subtotal").value(119998.00));

        // same product again merges: 2 + 3 = 5
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 3), alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(5));

        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(book.getId(), 3), alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[1].lineSubtotal").value(1498.50))
                .andExpect(jsonPath("$.subtotal").value(301493.50));

        mvc.perform(putJson("/api/v1/cart/items/" + phone.getId(), new UpdateCartItemRequest(1), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.subtotal").value(61497.50));

        mvc.perform(deleteJson("/api/v1/cart/items/" + phone.getId(), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("BK-001"));

        mvc.perform(deleteJson("/api/v1/cart", alice))
                .andExpect(status().isNoContent());
        mvc.perform(getJson("/api/v1/cart", alice))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.subtotal").value(0.00));

        // the cart row itself survives; only its lines are gone
        assertThat(cartRepository.findByCustomerId(alice.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from cart_items", Integer.class)).isZero();
    }

    @Test
    @DisplayName("quantity above what is available -> 409 INSUFFICIENT_STOCK (add, merge and update)")
    void insufficientStock_409() throws Exception {
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 6), alice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail").value("Only 5 units of PH-001 available"));

        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 4), alice))
                .andExpect(status().isCreated());
        // merge would make 6
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 2), alice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mvc.perform(putJson("/api/v1/cart/items/" + phone.getId(), new UpdateCartItemRequest(6), alice))
                .andExpect(status().isConflict());
        // the line is unchanged
        mvc.perform(getJson("/api/v1/cart", alice))
                .andExpect(jsonPath("$.items[0].quantity").value(4));
    }

    @Test
    @DisplayName("the cart view flags lines that are no longer available, but does not remove them")
    void availableFlag() throws Exception {
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 5), alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].available").value(true));
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(book.getId(), 1), alice))
                .andExpect(status().isCreated());

        // stock disappears and the book is delisted after the fact
        inventoryReserve(phone, 3);   // 5 on hand, 3 reserved -> 2 available < 5 in cart
        data.deactivate(book);

        mvc.perform(getJson("/api/v1/cart", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.items[1].available").value(false))
                .andExpect(jsonPath("$.subtotal").value(300494.50));
    }

    @Test
    void inactiveProduct_422_unknownProduct_404() throws Exception {
        data.deactivate(book);

        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(book.getId(), 1), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(999L, 1), alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(putJson("/api/v1/cart/items/" + phone.getId(), new UpdateCartItemRequest(1), alice))
                .andExpect(status().isNotFound());
        mvc.perform(deleteJson("/api/v1/cart/items/" + phone.getId(), alice))
                .andExpect(status().isNotFound());
    }

    @Test
    void validation_400() throws Exception {
        data.stock(book, data.warehouse("MUM-1", 2), 200);

        mvc.perform(postJson("/api/v1/cart/items", Map.of("productId", book.getId(), "quantity", 0), alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        mvc.perform(postJson("/api/v1/cart/items", Map.of("productId", book.getId(), "quantity", 101), alice))
                .andExpect(status().isBadRequest());
        // 60 + 60 = 120 > 100 even though 220 units exist
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(book.getId(), 60), alice))
                .andExpect(status().isCreated());
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(book.getId(), 60), alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void cartsAreIsolatedPerCustomerAndCustomerOnly() throws Exception {
        User bob = data.customer(2);
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 1), alice))
                .andExpect(status().isCreated());

        mvc.perform(getJson("/api/v1/cart", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(getJson("/api/v1/cart", data.admin()))
                .andExpect(status().isForbidden());
        mvc.perform(getJson("/api/v1/cart", data.staff(blr)))
                .andExpect(status().isForbidden());
        mvc.perform(getJson("/api/v1/cart", null))
                .andExpect(status().isUnauthorized());
    }

    /** Simulates a reservation by another order: available drops while on_hand stays. */
    private void inventoryReserve(Product product, int qty) {
        Inventory row = inventoryRepository.findByProduct_IdAndWarehouse_Id(product.getId(), blr.getId()).orElseThrow();
        row.setReserved(qty);
        inventoryRepository.save(row);
    }
}
