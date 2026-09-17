package com.ecommerce.oms.returns.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Every return item must be listed exactly once with its restock decision. */
public record ReceiveReturnRequest(@NotEmpty @Valid List<Item> items) {

    public record Item(@NotNull Long returnItemId, @NotNull Boolean restock) {
    }
}
