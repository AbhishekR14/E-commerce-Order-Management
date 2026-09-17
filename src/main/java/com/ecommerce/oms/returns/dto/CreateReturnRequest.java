package com.ecommerce.oms.returns.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateReturnRequest(
        @NotBlank @Size(max = 500) String reason,
        @NotEmpty @Valid List<Item> items) {

    public record Item(@NotNull Long orderItemId, @NotNull @Min(1) Integer quantity) {
    }
}
