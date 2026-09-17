package com.ecommerce.oms.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.ecommerce.oms.catalog.dto.CategoryRequest;
import com.ecommerce.oms.catalog.dto.CategoryTreeNode;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    ProductRepository productRepository;

    @InjectMocks
    CategoryService service;

    static Category category(long id, String name, Category parent) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setSlug(name.toLowerCase());
        c.setTaxRate(new BigDecimal("18.00"));
        c.setParent(parent);
        c.setActive(true);
        return c;
    }

    // ---- cycle detection (pure) ---------------------------------------------------------------

    @Test
    @DisplayName("parent = self is a cycle")
    void selfParent_isCycle() {
        Category a = category(1, "A", null);

        assertThatThrownBy(() -> CategoryService.assertNoCycle(a, a))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getCode())
                .isEqualTo(ErrorCode.CATEGORY_CYCLE);
    }

    @Test
    @DisplayName("moving A under its own grandchild C (A > B > C) is a cycle")
    void descendantAsParent_isCycle() {
        Category a = category(1, "A", null);
        Category b = category(2, "B", a);
        Category c = category(3, "C", b);

        assertThatThrownBy(() -> CategoryService.assertNoCycle(a, c))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> CategoryService.assertNoCycle(a, b))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void unrelatedOrAncestorParent_isFine() {
        Category root = category(1, "Root", null);
        Category a = category(2, "A", root);
        Category b = category(3, "B", root);
        Category c = category(4, "C", a);

        assertThatCode(() -> CategoryService.assertNoCycle(c, b)).doesNotThrowAnyException();   // sibling subtree
        assertThatCode(() -> CategoryService.assertNoCycle(c, root)).doesNotThrowAnyException(); // ancestor
        assertThatCode(() -> CategoryService.assertNoCycle(a, b)).doesNotThrowAnyException();
    }

    @Test
    void newCategory_cannotFormCycle() {
        Category unsaved = category(0, "New", null);
        unsaved.setId(null);
        Category parent = category(1, "P", null);

        assertThatCode(() -> CategoryService.assertNoCycle(unsaved, parent)).doesNotThrowAnyException();
    }

    // ---- update wiring ----------------------------------------------------------------------

    @Test
    @DisplayName("update() rejects re-parenting under a descendant with CATEGORY_CYCLE")
    void update_reparentUnderDescendant_throws() {
        Category a = category(1, "A", null);
        Category b = category(2, "B", a);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(a));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(b));
        when(categoryRepository.existsBySlugAndIdNot("a", 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.update(1L, new CategoryRequest("A", "a", 2L, new BigDecimal("18"))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getCode())
                .isEqualTo(ErrorCode.CATEGORY_CYCLE);
        assertThat(a.getParent()).isNull();
    }

    // ---- tree and descendants -----------------------------------------------------------------

    @Test
    void tree_nestsChildrenAndKeepsNameOrder() {
        Category electronics = category(1, "Electronics", null);
        Category phones = category(2, "Phones", electronics);
        Category laptops = category(3, "Laptops", electronics);
        Category books = category(4, "Books", null);
        when(categoryRepository.findAllByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(books, electronics, laptops, phones)); // name order, as the repo returns it

        List<CategoryTreeNode> tree = service.tree();

        assertThat(tree).extracting(CategoryTreeNode::name).containsExactly("Books", "Electronics");
        assertThat(tree.get(1).children()).extracting(CategoryTreeNode::name).containsExactly("Laptops", "Phones");
        assertThat(tree.get(0).children()).isEmpty();
        assertThat(tree.get(1).taxRate()).isEqualByComparingTo("18.00");
    }

    @Test
    void idWithDescendants_walksTheWholeSubtree() {
        Category a = category(1, "A", null);
        Category b = category(2, "B", a);
        Category c = category(3, "C", b);
        Category d = category(4, "D", a);
        Category other = category(5, "Other", null);
        when(categoryRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(a, b, c, d, other));

        assertThat(service.idWithDescendants(1L)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(service.idWithDescendants(2L)).containsExactlyInAnyOrder(2L, 3L);
        assertThat(service.idWithDescendants(5L)).containsExactly(5L);
    }

    @Test
    void delete_withActiveProducts_isInUse() {
        Category a = category(1, "A", null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(a));
        when(productRepository.existsByCategory_IdAndActiveTrue(anyLong())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).getCode())
                .isEqualTo(ErrorCode.CATEGORY_IN_USE);
        assertThat(a.isActive()).isTrue();
    }
}
