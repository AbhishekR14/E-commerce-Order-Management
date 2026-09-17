package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.dto.CategoryRequest;
import com.ecommerce.oms.catalog.dto.CategoryResponse;
import com.ecommerce.oms.catalog.dto.CategoryTreeNode;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.NotFoundException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    // ---- public ------------------------------------------------------------------------------

    /** The active category tree, roots and children ordered by name. */
    @Transactional(readOnly = true)
    public List<CategoryTreeNode> tree() {
        List<Category> all = categoryRepository.findAllByActiveTrueOrderByNameAsc();
        Map<Long, List<CategoryTreeNode>> childrenByParent = new LinkedHashMap<>();
        Map<Long, CategoryTreeNode> nodes = new LinkedHashMap<>();
        for (Category c : all) {
            List<CategoryTreeNode> children = new ArrayList<>();
            childrenByParent.put(c.getId(), children);
            nodes.put(c.getId(), new CategoryTreeNode(c.getId(), c.getName(), c.getSlug(), c.getTaxRate(), children));
        }
        List<CategoryTreeNode> roots = new ArrayList<>();
        for (Category c : all) {
            Long parentId = c.getParentId();
            // A child whose parent is inactive is treated as a root rather than dropped.
            if (parentId != null && childrenByParent.containsKey(parentId)) {
                childrenByParent.get(parentId).add(nodes.get(c.getId()));
            } else {
                roots.add(nodes.get(c.getId()));
            }
        }
        return roots;
    }

    /** The id plus every active descendant id (used by the product search's categoryId filter). */
    @Transactional(readOnly = true)
    public Set<Long> idWithDescendants(Long categoryId) {
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (Category c : categoryRepository.findAllByActiveTrueOrderByNameAsc()) {
            if (c.getParentId() != null) {
                childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        Set<Long> result = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (result.add(id)) {
                queue.addAll(childrenByParent.getOrDefault(id, List.of()));
            }
        }
        return result;
    }

    /** An existing, active category for other services (products, coupons). */
    @Transactional(readOnly = true)
    public Category requireActive(Long id) {
        return categoryRepository.findById(id)
                .filter(Category::isActive)
                .orElseThrow(() -> new NotFoundException("Category", id));
    }

    // ---- admin ------------------------------------------------------------------------------

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Category slug already exists");
        }
        Category category = new Category();
        apply(category, request);
        category = categoryRepository.save(category);
        log.info("Created category {} ({})", category.getId(), category.getSlug());
        return CategoryMapper.toResponse(category);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = requireActive(id);
        if (categoryRepository.existsBySlugAndIdNot(request.slug(), id)) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Category slug already exists");
        }
        apply(category, request);
        return CategoryMapper.toResponse(category);
    }

    /** Soft delete. Rejected while the category still has active products or active children. */
    @Transactional
    public void delete(Long id) {
        Category category = requireActive(id);
        if (productRepository.existsByCategory_IdAndActiveTrue(id)) {
            throw new BusinessRuleException(ErrorCode.CATEGORY_IN_USE, "Category has active products");
        }
        if (categoryRepository.existsByParent_IdAndActiveTrue(id)) {
            throw new BusinessRuleException(ErrorCode.CATEGORY_IN_USE, "Category has active child categories");
        }
        category.setActive(false);
        log.info("Deactivated category {}", id);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void apply(Category category, CategoryRequest request) {
        category.setName(request.name().trim());
        category.setSlug(request.slug());
        category.setTaxRate(request.taxRate());
        category.setParent(resolveParent(category, request.parentId()));
    }

    private Category resolveParent(Category category, Long parentId) {
        if (parentId == null) {
            return null;
        }
        Category parent = requireActive(parentId);
        assertNoCycle(category, parent);
        return parent;
    }

    /**
     * A category may not become its own ancestor. Walks up from the proposed parent; if the walk reaches
     * the category being edited, the move would close a loop. New (unsaved) categories cannot form cycles.
     */
    static void assertNoCycle(Category category, Category proposedParent) {
        if (category.getId() == null) {
            return;
        }
        Category current = proposedParent;
        while (current != null) {
            if (category.getId().equals(current.getId())) {
                throw new BusinessRuleException(ErrorCode.CATEGORY_CYCLE,
                        "Category " + category.getId() + " cannot be its own ancestor");
            }
            current = current.getParent();
        }
    }
}
