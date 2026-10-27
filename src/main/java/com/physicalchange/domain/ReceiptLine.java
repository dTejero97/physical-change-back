package com.physicalchange.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Línea de un comprobante. Nombre y precio son copias: el comprobante no cambia si cambia el catálogo. */
@Entity
@Table(name = "RECEIPT_LINE")
public class ReceiptLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "RECEIPT_ID", nullable = false) private Long receiptId;
    @Column(nullable = false) private Short position;
    @Column(nullable = false, length = 8) private String kind;
    @Column(name = "PRODUCT_ID") private Long productId;
    @Column(name = "PLAN_ID") private Long planId;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "UNIT_PRICE", nullable = false, precision = 10, scale = 2) private BigDecimal unitPrice;
    @Column(nullable = false) private Short quantity;
    @Column(name = "TAX_RATE", nullable = false) private Short taxRate;
    @Column(name = "LINE_TOTAL", nullable = false, precision = 12, scale = 2) private BigDecimal lineTotal;

    public Long getId() { return id; }
    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long r) { this.receiptId = r; }
    public Short getPosition() { return position; }
    public void setPosition(Short p) { this.position = p; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public Long getProductId() { return productId; }
    public void setProductId(Long p) { this.productId = p; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long p) { this.planId = p; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal u) { this.unitPrice = u; }
    public Short getQuantity() { return quantity; }
    public void setQuantity(Short q) { this.quantity = q; }
    public Short getTaxRate() { return taxRate; }
    public void setTaxRate(Short t) { this.taxRate = t; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal t) { this.lineTotal = t; }
}
