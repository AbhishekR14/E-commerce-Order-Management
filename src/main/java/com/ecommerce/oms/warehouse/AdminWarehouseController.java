package com.ecommerce.oms.warehouse;

import com.ecommerce.oms.warehouse.dto.WarehouseRequest;
import com.ecommerce.oms.warehouse.dto.WarehouseResponse;
import com.ecommerce.oms.warehouse.dto.WarehouseStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/warehouses")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin - Warehouses", description = "Manage warehouses")
public class AdminWarehouseController {

    private final WarehouseService warehouseService;

    @PostMapping
    @Operation(summary = "Create a warehouse")
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(warehouseService.create(request));
    }

    @GetMapping
    @Operation(summary = "List warehouses in allocation order (priority, id)")
    public List<WarehouseResponse> list() {
        return warehouseService.list();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a warehouse (the code is immutable)")
    public WarehouseResponse update(@PathVariable Long id, @Valid @RequestBody WarehouseRequest request) {
        return warehouseService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a warehouse")
    public WarehouseResponse setStatus(@PathVariable Long id, @Valid @RequestBody WarehouseStatusRequest request) {
        return warehouseService.setActive(id, request.active());
    }
}
