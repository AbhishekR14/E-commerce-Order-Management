package com.ecommerce.oms.warehouse;

import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.InvalidRequestException;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.warehouse.dto.WarehouseRequest;
import com.ecommerce.oms.warehouse.dto.WarehouseResponse;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list() {
        return warehouseRepository.findAllByOrderByPriorityAscIdAsc().stream().map(WarehouseMapper::toResponse).toList();
    }

    /** An existing warehouse, active or not (inventory may still be counted in a closed warehouse). */
    @Transactional(readOnly = true)
    public Warehouse require(Long id) {
        return warehouseRepository.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
    }

    /** An existing, active warehouse (staff assignment, allocation). */
    @Transactional(readOnly = true)
    public Warehouse requireActive(Long id) {
        return warehouseRepository.findById(id)
                .filter(Warehouse::isActive)
                .orElseThrow(() -> new NotFoundException("Warehouse", id));
    }

    @Transactional
    public WarehouseResponse create(WarehouseRequest request) {
        if (warehouseRepository.existsByCode(request.code())) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Warehouse code already exists");
        }
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(request.code());
        apply(warehouse, request);
        warehouse = warehouseRepository.save(warehouse);
        log.info("Created warehouse {} ({})", warehouse.getId(), warehouse.getCode());
        return WarehouseMapper.toResponse(warehouse);
    }

    @Transactional
    public WarehouseResponse update(Long id, WarehouseRequest request) {
        Warehouse warehouse = require(id);
        if (!warehouse.getCode().equals(request.code())) {
            throw new InvalidRequestException("Warehouse code is immutable; expected " + warehouse.getCode());
        }
        apply(warehouse, request);
        return WarehouseMapper.toResponse(warehouse);
    }

    @Transactional
    public WarehouseResponse setActive(Long id, boolean active) {
        Warehouse warehouse = require(id);
        warehouse.setActive(active);
        log.info("Warehouse {} active={}", id, active);
        return WarehouseMapper.toResponse(warehouse);
    }

    private static void apply(Warehouse warehouse, WarehouseRequest request) {
        warehouse.setName(request.name().trim());
        warehouse.setCity(request.city().trim());
        warehouse.setPriority(request.priority());
    }
}
