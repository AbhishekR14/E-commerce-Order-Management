package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.dto.CategoryTreeNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Tag(name = "Catalog", description = "Public browsing")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "The active category tree")
    public List<CategoryTreeNode> tree() {
        return categoryService.tree();
    }
}
