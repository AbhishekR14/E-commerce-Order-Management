package com.ecommerce.oms.returns.dto;

import jakarta.validation.constraints.Size;

/** Approve: note optional. (Reject uses RejectReturnRequest, where the note is required.) */
public record ReturnDecisionRequest(@Size(max = 500) String note) {
}
