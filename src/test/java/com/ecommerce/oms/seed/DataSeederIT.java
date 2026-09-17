package com.ecommerce.oms.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots a separate context with seeding on (its own H2 database so the other ITs stay empty) and checks
 * the demo data is complete, usable and only seeded once.
 */
@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:oms-seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSeederIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    DataSeeder seeder;

    @Test
    @DisplayName("the demo data matches the plan and the demo users can log in")
    void seedIsCompleteAndIdempotent() throws Exception {
        assertThat(userRepository.count()).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from warehouses", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from categories", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select count(*) from coupons", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from inventory", Integer.class))
                .isEqualTo(jdbc.queryForObject("select count(*) from inventory_movements", Integer.class));
        // a MUM-only product exists (split-shipment demo)
        assertThat(jdbc.queryForObject("""
                select count(*) from products p where p.sku = 'LP-002'
                  and not exists (select 1 from inventory i join warehouses w on w.id = i.warehouse_id
                                  where i.product_id = p.id and w.code <> 'MUM-1')
                """, Integer.class)).isEqualTo(1);

        // running the seeder again is a no-op
        seeder.run(new DefaultApplicationArguments());
        assertThat(userRepository.count()).isEqualTo(6);

        // demo logins work with the documented passwords
        for (String[] login : new String[][] {
                {DataSeeder.ADMIN_EMAIL, DataSeeder.ADMIN_PASSWORD, "ADMIN"},
                {"staff.blr@oms.test", DataSeeder.STAFF_PASSWORD, "WAREHOUSE_STAFF"},
                {"alice@oms.test", DataSeeder.CUSTOMER_PASSWORD, "CUSTOMER"}}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + login[0] + "\",\"password\":\"" + login[1] + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.role").value(login[2]));
        }
        // the public catalog shows stock across active warehouses
        mvc.perform(get("/api/v1/products").param("q", "pixel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].availableQuantity").value(15));
        mvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "Apparel", "Books", "Electronics", "Grocery")));
    }
}
