package com.ecommerce.oms;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OmsApplication {

    public static void main(String[] args) {
        // Everything in this system is UTC (see CLAUDE.md "Time"). Set it before the context starts
        // so that the JDBC driver, Hibernate and Jackson all agree on the zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(OmsApplication.class, args);
    }
}
