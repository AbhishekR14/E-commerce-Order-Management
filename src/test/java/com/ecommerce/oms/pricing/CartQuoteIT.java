package com.ecommerce.oms.pricing;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.cart.dto.AddCartItemRequest;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.pricing.dto.QuoteRequest;
import com.ecommerce.oms.pricing.entity.Coupon;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code POST /cart/quote} end to end, using the doc 08 worked example through real tables. */
class CartQuoteIT extends AbstractIntegrationTest {

    @Autowired
    CouponRepository couponRepository;

    User alice;
    Product phone;
    Product book;
    Category electronics;
    Category books;

    @BeforeEach
    void setUp() throws Exception {
        alice = data.customer(1);
        electronics = data.category("Electronics", "18");
        Category phones = data.category("Phones", "18", electronics);
        books = data.category("Books", "5");
        phone = data.product("PH-001", "Phone", "1000.00", phones);
        book = data.product("BK-001", "Book", "500.00", books);
        Warehouse blr = data.warehouse("BLR-1", 1);
        data.stock(phone, blr, 10);
        data.stock(book, blr, 10);
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(phone.getId(), 2), alice))
                .andExpect(status().isCreated());
        mvc.perform(postJson("/api/v1/cart/items", new AddCartItemRequest(book.getId(), 1), alice))
                .andExpect(status().isCreated());
    }

    @Test
    void quoteWithoutCoupon() throws Exception {
        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest(null), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].sku").value("PH-001"))
                .andExpect(jsonPath("$.lines[0].taxRate").value(18.00))
                .andExpect(jsonPath("$.lines[0].lineTax").value(360.00))
                .andExpect(jsonPath("$.subtotal").value(2500.00))
                .andExpect(jsonPath("$.discountTotal").value(0.00))
                .andExpect(jsonPath("$.taxTotal").value(385.00))
                .andExpect(jsonPath("$.grandTotal").value(2885.00))
                .andExpect(jsonPath("$.couponCode").doesNotExist());
    }

    @Test
    @DisplayName("doc 08 worked example over HTTP: WELCOME10 -> 2654.20")
    void quoteWithCoupon_workedExample() throws Exception {
        data.coupon("WELCOME10", DiscountType.PERCENTAGE, "10", "200", "0");

        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest("welcome10"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lineDiscount").value(160.00))
                .andExpect(jsonPath("$.lines[0].lineTax").value(331.20))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(2171.20))
                .andExpect(jsonPath("$.lines[0].couponApplied").value(true))
                .andExpect(jsonPath("$.lines[1].lineDiscount").value(40.00))
                .andExpect(jsonPath("$.lines[1].lineTax").value(23.00))
                .andExpect(jsonPath("$.lines[1].lineTotal").value(483.00))
                .andExpect(jsonPath("$.discountTotal").value(200.00))
                .andExpect(jsonPath("$.taxTotal").value(354.20))
                .andExpect(jsonPath("$.grandTotal").value(2654.20))
                .andExpect(jsonPath("$.couponCode").value("WELCOME10"))
                .andExpect(jsonPath("$.couponMessage").isString());
    }

    @Test
    @DisplayName("category-scoped coupon through the real category tree (Electronics > Phones)")
    void categoryScopedCoupon() throws Exception {
        Coupon elec = data.coupon("ELEC20", DiscountType.PERCENTAGE, "20", null, "0");
        elec.setCategory(electronics);
        couponRepository.save(elec);

        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest("ELEC20"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].couponApplied").value(true))
                .andExpect(jsonPath("$.lines[0].lineDiscount").value(400.00))
                .andExpect(jsonPath("$.lines[1].couponApplied").value(false))
                .andExpect(jsonPath("$.lines[1].lineDiscount").value(0.00))
                .andExpect(jsonPath("$.grandTotal").value(2413.00));
    }

    @Test
    void invalidCoupon_422_withReason() throws Exception {
        Coupon min = data.coupon("MIN5000", DiscountType.FLAT, "100", null, "5000");

        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest("NOPE"), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"))
                .andExpect(jsonPath("$.detail").value("Coupon not found"));
        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest("MIN5000"), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("minimum")));

        min.setActive(false);
        couponRepository.save(min);
        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest("MIN5000"), alice))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("inactive")));
    }

    @Test
    void emptyCart_422_andInactiveProduct_422() throws Exception {
        User bob = data.customer(2);
        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest(null), bob))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));

        data.deactivate(book);
        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest(null), alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
    }

    @Test
    void quote_customerOnly() throws Exception {
        mvc.perform(postJson("/api/v1/cart/quote", new QuoteRequest(null), data.admin()))
                .andExpect(status().isForbidden());
    }
}
