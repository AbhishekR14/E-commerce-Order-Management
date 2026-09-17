package com.ecommerce.oms.returns.entity;

import com.ecommerce.oms.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "return_items")
@Getter
@Setter
@NoArgsConstructor
public class ReturnItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_request_id", nullable = false)
    private ReturnRequest returnRequest;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Null until staff receive the item and decide whether it goes back on the shelf. */
    @Column(name = "restock")
    private Boolean restock;
}
