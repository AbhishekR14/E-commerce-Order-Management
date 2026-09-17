package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.dto.ProductRequest;
import com.ecommerce.oms.catalog.dto.ProductResponse;
import com.ecommerce.oms.catalog.dto.ProductSearchCriteria;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.InvalidRequestException;
import com.ecommerce.oms.common.exception.NotFoundException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final InventoryQueryPort inventoryQueryPort;

    // ---- public ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(ProductSearchCriteria criteria, Pageable pageable) {
        return toResponses(productRepository.findAll(specification(criteria, true), pageable));
    }

    @Transactional(readOnly = true)
    public ProductResponse getActive(Long id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new NotFoundException("Product", id));
        return toResponse(product);
    }

    /** An existing product regardless of active flag (callers decide how to report inactive ones). */
    @Transactional(readOnly = true)
    public Product findExisting(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
    }

    // ---- admin ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ProductResponse> searchIncludingInactive(ProductSearchCriteria criteria, Pageable pageable) {
        return toResponses(productRepository.findAll(specification(criteria, false), pageable));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String sku = request.sku().trim();
        if (productRepository.existsBySku(sku)) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Product SKU already exists");
        }
        Product product = new Product();
        product.setSku(sku);
        apply(product, request);
        product = productRepository.save(product);
        log.info("Created product {} ({})", product.getId(), product.getSku());
        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        if (!product.getSku().equals(request.sku().trim())) {
            throw new InvalidRequestException("SKU is immutable; expected " + product.getSku());
        }
        apply(product, request);
        return toResponse(product);
    }

    /** Soft delete: the product disappears from public browsing and can no longer be carted or checked out. */
    @Transactional
    public void delete(Long id) {
        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        product.setActive(false);
        log.info("Deactivated product {}", id);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void apply(Product product, ProductRequest request) {
        Category category = categoryService.requireActive(request.categoryId());
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setCategory(category);
        product.setPrice(request.price());
    }

    private Specification<Product> specification(ProductSearchCriteria c, boolean activeOnly) {
        Specification<Product> spec = Specification.unrestricted();
        if (activeOnly) {
            spec = spec.and(ProductSpecifications.activeOnly());
        }
        if (c.q() != null && !c.q().isBlank()) {
            spec = spec.and(ProductSpecifications.nameOrSkuContains(c.q()));
        }
        if (c.categoryId() != null) {
            spec = spec.and(ProductSpecifications.categoryIn(categoryService.idWithDescendants(c.categoryId())));
        }
        if (c.minPrice() != null) {
            spec = spec.and(ProductSpecifications.priceAtLeast(c.minPrice()));
        }
        if (c.maxPrice() != null) {
            spec = spec.and(ProductSpecifications.priceAtMost(c.maxPrice()));
        }
        if (Boolean.TRUE.equals(c.inStock())) {
            spec = spec.and(inventoryQueryPort.inStock());
        }
        return spec;
    }

    private Page<ProductResponse> toResponses(Page<Product> page) {
        List<Long> ids = page.getContent().stream().map(Product::getId).toList();
        Map<Long, Integer> available = inventoryQueryPort.availableQuantities(ids);
        return page.map(p -> ProductMapper.toResponse(p, available.getOrDefault(p.getId(), 0)));
    }

    private ProductResponse toResponse(Product product) {
        int available = inventoryQueryPort.availableQuantities(List.of(product.getId()))
                .getOrDefault(product.getId(), 0);
        return ProductMapper.toResponse(product, available);
    }
}
