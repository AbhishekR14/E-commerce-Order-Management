package com.ecommerce.oms.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.dto.CategoryRequest;
import com.ecommerce.oms.catalog.dto.CategoryResponse;
import com.ecommerce.oms.catalog.dto.ProductRequest;
import com.ecommerce.oms.catalog.dto.ProductResponse;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

class CatalogIT extends AbstractIntegrationTest {

    @Autowired
    ProductRepository productRepository;

    @Autowired
    CategoryRepository categoryRepository;

    // ---- categories ------------------------------------------------------------------------

    @Test
    @DisplayName("admin creates a tree; the public endpoint returns only active nodes, nested and name-ordered")
    void categoryCrudAndTree() throws Exception {
        User admin = data.admin();

        MvcResult created = mvc.perform(postJson("/api/v1/admin/categories",
                        new CategoryRequest("Electronics", "electronics", null, new BigDecimal("18.00")), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.slug").value("electronics"))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.taxRate").value(18.00))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long electronics = readBody(created, CategoryResponse.class).id();

        long phones = readBody(mvc.perform(postJson("/api/v1/admin/categories",
                        new CategoryRequest("Phones", "phones", electronics, new BigDecimal("18")), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(electronics))
                .andReturn(), CategoryResponse.class).id();
        mvc.perform(postJson("/api/v1/admin/categories",
                        new CategoryRequest("Books", "books", null, new BigDecimal("5")), admin))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Books"))
                .andExpect(jsonPath("$[0].children", hasSize(0)))
                .andExpect(jsonPath("$[1].name").value("Electronics"))
                .andExpect(jsonPath("$[1].taxRate").value(18.00))
                .andExpect(jsonPath("$[1].children[0].name").value("Phones"))
                .andExpect(jsonPath("$[1].children[0].id").value(phones));

        // rename + change tax rate via PUT
        mvc.perform(putJson("/api/v1/admin/categories/" + phones,
                        new CategoryRequest("Smartphones", "phones", electronics, new BigDecimal("12.50")), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Smartphones"))
                .andExpect(jsonPath("$.taxRate").value(12.50));

        // delete the leaf, then it disappears from the tree
        mvc.perform(deleteJson("/api/v1/admin/categories/" + phones, admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[1].children", hasSize(0)));
        assertThat(categoryRepository.findById(phones).orElseThrow().isActive()).isFalse();

        // deleted categories are gone for admin operations too
        mvc.perform(deleteJson("/api/v1/admin/categories/" + phones, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void category_validationAndDuplicates() throws Exception {
        User admin = data.admin();
        data.category("Books", "5");

        mvc.perform(postJson("/api/v1/admin/categories",
                        new CategoryRequest("Dup", "books", null, new BigDecimal("5")), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));

        mvc.perform(postJson("/api/v1/admin/categories",
                        Map.of("name", "", "slug", "Bad Slug!", "taxRate", 150), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[1].field").value("slug"))
                .andExpect(jsonPath("$.errors[2].field").value("taxRate"));

        // unknown parent
        mvc.perform(postJson("/api/v1/admin/categories",
                        new CategoryRequest("Orphan", "orphan", 999L, new BigDecimal("5")), admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("re-parenting a category under its own descendant -> 422 CATEGORY_CYCLE")
    void category_cycle_422() throws Exception {
        User admin = data.admin();
        Category a = data.category("A", "5");
        Category b = data.category("B", "5", a);
        Category c = data.category("C", "5", b);

        mvc.perform(putJson("/api/v1/admin/categories/" + a.getId(),
                        new CategoryRequest("A", "a", c.getId(), new BigDecimal("5")), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
        mvc.perform(putJson("/api/v1/admin/categories/" + a.getId(),
                        new CategoryRequest("A", "a", a.getId(), new BigDecimal("5")), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));

        // moving C directly under A (skipping B) is fine
        mvc.perform(putJson("/api/v1/admin/categories/" + c.getId(),
                        new CategoryRequest("C", "c", a.getId(), new BigDecimal("5")), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(a.getId()));
    }

    @Test
    @DisplayName("delete is rejected with CATEGORY_IN_USE while active products or children exist")
    void category_inUse_422() throws Exception {
        User admin = data.admin();
        Category parent = data.category("Parent", "5");
        Category child = data.category("Child", "5", parent);
        Product product = data.product("SKU-1", "10.00", child);

        mvc.perform(deleteJson("/api/v1/admin/categories/" + parent.getId(), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
        mvc.perform(deleteJson("/api/v1/admin/categories/" + child.getId(), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        // once the product is soft-deleted the chain can be removed bottom-up
        mvc.perform(deleteJson("/api/v1/admin/products/" + product.getId(), admin))
                .andExpect(status().isNoContent());
        mvc.perform(deleteJson("/api/v1/admin/categories/" + child.getId(), admin))
                .andExpect(status().isNoContent());
        mvc.perform(deleteJson("/api/v1/admin/categories/" + parent.getId(), admin))
                .andExpect(status().isNoContent());
    }

    // ---- products --------------------------------------------------------------------------

    @Test
    void productCrud() throws Exception {
        User admin = data.admin();
        Category phones = data.category("Phones", "18");
        Category books = data.category("Books", "5");

        MvcResult created = mvc.perform(postJson("/api/v1/admin/products",
                        new ProductRequest("PH-001", "Pixel", "A phone", phones.getId(), new BigDecimal("49999.00")),
                        admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("PH-001"))
                .andExpect(jsonPath("$.price").value(49999.00))
                .andExpect(jsonPath("$.category.id").value(phones.getId()))
                .andExpect(jsonPath("$.category.name").value("Phones"))
                .andExpect(jsonPath("$.taxRate").value(18.00))
                .andExpect(jsonPath("$.availableQuantity").value(0))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long id = readBody(created, ProductResponse.class).id();

        // public detail
        mvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pixel"))
                .andExpect(jsonPath("$.description").value("A phone"));

        // update: move to Books, change price; tax rate follows the category
        mvc.perform(putJson("/api/v1/admin/products/" + id,
                        new ProductRequest("PH-001", "Pixel 2", null, books.getId(), new BigDecimal("39999.5")), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pixel 2"))
                .andExpect(jsonPath("$.price").value(39999.50))
                .andExpect(jsonPath("$.category.name").value("Books"))
                .andExpect(jsonPath("$.taxRate").value(5.00));

        // SKU is immutable
        mvc.perform(putJson("/api/v1/admin/products/" + id,
                        new ProductRequest("PH-002", "Pixel 2", null, books.getId(), new BigDecimal("1")), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // duplicate SKU, unknown category, invalid price
        mvc.perform(postJson("/api/v1/admin/products",
                        new ProductRequest("PH-001", "Dup", null, phones.getId(), new BigDecimal("1")), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        mvc.perform(postJson("/api/v1/admin/products",
                        new ProductRequest("PH-009", "Nope", null, 999L, new BigDecimal("1")), admin))
                .andExpect(status().isNotFound());
        mvc.perform(postJson("/api/v1/admin/products",
                        new ProductRequest("PH-010", "Free", null, phones.getId(), new BigDecimal("0")), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));

        // soft delete hides it from the public endpoints but not from the admin list
        mvc.perform(deleteJson("/api/v1/admin/products/" + id, admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/products/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(getJson("/api/v1/admin/products", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].active").value(false));
        assertThat(productRepository.findById(id).orElseThrow().isActive()).isFalse();
    }

    @Test
    void product_inInactiveCategory_404() throws Exception {
        User admin = data.admin();
        Category dead = data.category("Dead", "5");
        dead.setActive(false);
        categoryRepository.save(dead);

        mvc.perform(postJson("/api/v1/admin/products",
                        new ProductRequest("X-1", "X", null, dead.getId(), new BigDecimal("1")), admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("public search: q, categoryId with descendants, price range, sort, paging, inStock")
    void productSearch() throws Exception {
        Category electronics = data.category("Electronics", "18");
        Category phones = data.category("Phones", "18", electronics);
        Category laptops = data.category("Laptops", "18", electronics);
        Category books = data.category("Books", "5");
        data.product("PH-001", "Pixel 9", "59999.00", phones);
        data.product("PH-002", "Galaxy S", "69999.00", phones);
        data.product("LP-001", "ThinkPad", "89999.00", laptops);
        data.product("BK-001", "Clean Code", "499.00", books);
        data.deactivate(data.product("BK-002", "Hidden Book", "299.00", books));

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].sku").value(
                        org.hamcrest.Matchers.contains("PH-001", "PH-002", "LP-001", "BK-001")));

        // q matches name or sku, case-insensitively
        mvc.perform(get("/api/v1/products").param("q", "pixel"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("PH-001"));
        mvc.perform(get("/api/v1/products").param("q", "ph-00"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/products").param("q", "hidden"))
                .andExpect(jsonPath("$.totalElements").value(0));

        // categoryId includes descendants
        mvc.perform(get("/api/v1/products").param("categoryId", String.valueOf(electronics.getId())))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/products").param("categoryId", String.valueOf(laptops.getId())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("LP-001"));

        // price range and sort
        mvc.perform(get("/api/v1/products").param("minPrice", "60000").param("maxPrice", "90000")
                        .param("sort", "price,desc"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].sku").value("LP-001"))
                .andExpect(jsonPath("$.content[1].sku").value("PH-002"));

        // paging
        mvc.perform(get("/api/v1/products").param("size", "3").param("page", "1").param("sort", "name,asc"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("ThinkPad"));

        // inStock: no inventory module yet, so nothing is in stock (phase 3 wires the real port)
        mvc.perform(get("/api/v1/products").param("inStock", "true"))
                .andExpect(jsonPath("$.totalElements").value(0));

        // bad params -> 400
        mvc.perform(get("/api/v1/products").param("minPrice", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/products").param("minPrice", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void adminCatalog_403_forCustomer() throws Exception {
        User customer = data.customer(1);
        Category c = data.category("C", "5");

        mvc.perform(postJson("/api/v1/admin/categories",
                        new CategoryRequest("X", "x", null, new BigDecimal("5")), customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(postJson("/api/v1/admin/products",
                        new ProductRequest("X-1", "X", null, c.getId(), new BigDecimal("1")), customer))
                .andExpect(status().isForbidden());
        mvc.perform(getJson("/api/v1/admin/products", customer))
                .andExpect(status().isForbidden());
        mvc.perform(getJson("/api/v1/admin/products", null))
                .andExpect(status().isUnauthorized());
    }
}
