package com.ecommerce.oms.pricing.dto;

import jakarta.validation.constraints.Size;

public record QuoteRequest(@Size(max = 40) String couponCode) {
}
