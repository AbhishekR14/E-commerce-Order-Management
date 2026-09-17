package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByIdAndActiveTrue(Long id);

    boolean existsBySku(String sku);

    boolean existsByCategory_IdAndActiveTrue(Long categoryId);
}
