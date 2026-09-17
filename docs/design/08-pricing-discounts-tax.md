# 08 — Pricing, Discounts & Tax

`PricingService.quote(customerId, lines, couponCode, now)` returns a `PriceQuote`. It is used by both `/cart/quote` and checkout, so the two always agree.

```
PriceQuote { lines:[LineQuote{productId, sku, name, unitPrice, quantity, taxRate,
                               lineSubtotal, lineDiscount, lineTax, lineTotal, couponApplied}],
             subtotal, discountTotal, taxTotal, grandTotal, couponCode, couponMessage }
```

## Rules (scale 2, HALF_UP everywhere)

1. `lineSubtotal = unitPrice × quantity`.
2. **Eligible lines:** all lines if `coupon.category_id` is null; otherwise lines whose product category is that category or a descendant. Resolve the descendant ids once, via the category tree.
3. `eligibleSubtotal = Σ lineSubtotal(eligible)`.
4. **Coupon validation** (`CouponValidator`, pure, unit-tested) checks, in order:
   1. the coupon exists
   2. it is active
   3. `now ≥ valid_from`
   4. `now ≤ valid_to`
   5. there is at least one eligible line
   6. `eligibleSubtotal ≥ min_order_amount`
   7. `usage_limit` has not been reached (`used_count < usage_limit`)
   8. the customer's non-released redemptions are below `per_customer_limit`

   The first failing check gives `COUPON_INVALID` with a specific message.
5. **Discount amount:**
   - `PERCENTAGE`: `eligibleSubtotal × value / 100`, capped at `max_discount` if set.
   - `FLAT`: `min(value, eligibleSubtotal)`.
6. **Allocate the discount to eligible lines** proportionally to `lineSubtotal`:
   - `share_i = round(discount × lineSubtotal_i / eligibleSubtotal)`
   - Rounding drift (`discount − Σshare`) is added to the eligible line with the largest subtotal (ties go to the first line), so `Σ lineDiscount == discount` exactly.
7. `lineTax = round((lineSubtotal − lineDiscount) × taxRate / 100)`. The tax rate comes from the product's category.
8. `lineTotal = lineSubtotal − lineDiscount + lineTax`.
9. Order totals are the sums of the line values. `grandTotal = Σ lineTotal`.

## Worked example (for tests)

The cart:
- Phone: 1000.00 × 2, Electronics at 18%
- Book: 500.00 × 1, Books at 5%

Coupon `WELCOME10` is 10% of all products, capped at 200.

| | Phone | Book | Total |
|---|---|---|---|
| subtotal | 2000.00 | 500.00 | 2500.00 |
| discount (10% = 250, capped at 200; split 2000:500) | 160.00 | 40.00 | 200.00 |
| taxable | 1840.00 | 460.00 | |
| tax | 331.20 | 23.00 | 354.20 |
| line total | 2171.20 | 483.00 | **2654.20** |

## Rounding-drift example

Three eligible lines of 1.00 each, with a flat discount of 2.00:
- The raw share for each line is 2.00 × 1/3 = 0.666…, which rounds to 0.67. The shares sum to 2.01.
- The drift is 2.00 − 2.01 = −0.01. It is applied to the largest line (the first, on a tie), which becomes 0.66.
- Result: 0.66 + 0.67 + 0.67 = **2.00** exactly.

## Unit test list (`PricingServiceTest`, `CouponValidatorTest`)

- No coupon
- Percentage with and without cap
- Flat greater than the subtotal
- Category-scoped coupon, including a descendant category
- Minimum order not met
- Expired, not started, inactive
- Usage limit reached, per-customer limit reached
- Drift allocation
- Zero tax rate
- The worked example above
