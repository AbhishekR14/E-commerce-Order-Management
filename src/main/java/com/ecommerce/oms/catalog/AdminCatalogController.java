package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.dto.CategoryRequest;
import com.ecommerce.oms.catalog.dto.CategoryResponse;
import com.ecommerce.oms.catalog.dto.ProductRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin - Catalog", description = "Manage categories and products")
public class AdminCatalogController {

    private final CategoryService categoryService;
    private final ProductService productService;

    // ---- categories ------------------------------------------------------------------------

    @PostMapping("/categories")
    @Operation(summary = "Create a category")
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/categories/{id}")
    @Operation(summary = "Update a category (a category cannot become its own ancestor)")
    public CategoryResponse updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/categories/{id}")
    @Operation(summary = "Soft-delete a category (rejected while it has active products or children)")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- products --------------------------------------------------------------------------

    @PostMapping("/products")
    @Operation(summary = "Create a product")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/products/{id}")
    @Operation(summary = "Update a product (the SKU is immutable)")
    public ProductResponse updateProduct(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/products/{id}")
    @Operation(summary = "Soft-delete a product")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/products")
    @Operation(summary = "Search all products, including inactive ones")
    public PageResponse<ProductResponse> searchProducts(
            @ParameterObject @Valid ProductSearchCriteria criteria,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(productService.searchIncludingInactive(criteria, pageable));
    }
}
