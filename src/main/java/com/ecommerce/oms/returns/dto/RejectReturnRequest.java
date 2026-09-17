package com.ecommerce.oms.returns.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectReturnRequest(@NotBlank @Size(max = 500) String note) {
}
