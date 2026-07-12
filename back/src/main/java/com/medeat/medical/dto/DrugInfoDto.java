package com.medeat.medical.dto;

import java.time.LocalDateTime;

public class DrugInfoDto {

    private Long itemSeq;

    private String itemName;
    private String entpName;
    private String ingrName;
    private String itemImage;

    private String efcyQesitm;
    private String useMethodQesitm;
    private String atpnWarnQesitm;
    private String atpnQesitm;
    private String intrcQesitm;
    private String seQesitm;
    private String depositMethodQesitm;

    // 식별 메타
    private String drugShape;
    private String colorClass1;
    private String colorClass2;

    private String printFront;
    private String printBack;

    private String lineFront;
    private String lineBack;

    private String lengLong;
    private String lengShort;
    private String thick;
    private LocalDateTime updatedAt;

    // ===== Getter / Setter =====
    public Long getItemSeq() { return itemSeq; }
    public void setItemSeq(Long itemSeq) { this.itemSeq = itemSeq; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public String getEntpName() { return entpName; }
    public void setEntpName(String entpName) { this.entpName = entpName; }

    public String getIngrName() { return ingrName; }
    public void setIngrName(String ingrName) { this.ingrName = ingrName; }

    public String getItemImage() { return itemImage; }
    public void setItemImage(String itemImage) { this.itemImage = itemImage; }

    public String getEfcyQesitm() { return efcyQesitm; }
    public void setEfcyQesitm(String efcyQesitm) { this.efcyQesitm = efcyQesitm; }

    public String getUseMethodQesitm() { return useMethodQesitm; }
    public void setUseMethodQesitm(String useMethodQesitm) { this.useMethodQesitm = useMethodQesitm; }

    public String getAtpnWarnQesitm() { return atpnWarnQesitm; }
    public void setAtpnWarnQesitm(String atpnWarnQesitm) { this.atpnWarnQesitm = atpnWarnQesitm; }

    public String getAtpnQesitm() { return atpnQesitm; }
    public void setAtpnQesitm(String atpnQesitm) { this.atpnQesitm = atpnQesitm; }

    public String getIntrcQesitm() { return intrcQesitm; }
    public void setIntrcQesitm(String intrcQesitm) { this.intrcQesitm = intrcQesitm; }

    public String getSeQesitm() { return seQesitm; }
    public void setSeQesitm(String seQesitm) { this.seQesitm = seQesitm; }

    public String getDepositMethodQesitm() { return depositMethodQesitm; }
    public void setDepositMethodQesitm(String depositMethodQesitm) { this.depositMethodQesitm = depositMethodQesitm; }

    public String getDrugShape() { return drugShape; }
    public void setDrugShape(String drugShape) { this.drugShape = drugShape; }

    public String getColorClass1() { return colorClass1; }
    public void setColorClass1(String colorClass1) { this.colorClass1 = colorClass1; }

    public String getColorClass2() { return colorClass2; }
    public void setColorClass2(String colorClass2) { this.colorClass2 = colorClass2; }

    public String getPrintFront() { return printFront; }
    public void setPrintFront(String printFront) { this.printFront = printFront; }

    public String getPrintBack() { return printBack; }
    public void setPrintBack(String printBack) { this.printBack = printBack; }

    public String getLineFront() { return lineFront; }
    public void setLineFront(String lineFront) { this.lineFront = lineFront; }

    public String getLineBack() { return lineBack; }
    public void setLineBack(String lineBack) { this.lineBack = lineBack; }

    public String getLengLong() { return lengLong; }
    public void setLengLong(String lengLong) { this.lengLong = lengLong; }

    public String getLengShort() { return lengShort; }
    public void setLengShort(String lengShort) { this.lengShort = lengShort; }

    public String getThick() { return thick; }
    public void setThick(String thick) { this.thick = thick; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
