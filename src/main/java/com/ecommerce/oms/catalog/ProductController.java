package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.dto.ProductResponse;
import com.ecommerce.oms.catalog.dto.ProductSearchCriteria;
import com.ecommerce.oms.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Catalog", description = "Public browsing")
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @Operation(summary = "Search active products (q, categoryId incl. descendants, minPrice, maxPrice, inStock)")
    public PageResponse<ProductResponse> search(
            @ParameterObject @Valid ProductSearchCriteria criteria,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(productService.search(criteria, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Product detail with total available stock")
    public ProductResponse get(@PathVariable Long id) {
        return productService.getActive(id);
    }
}
