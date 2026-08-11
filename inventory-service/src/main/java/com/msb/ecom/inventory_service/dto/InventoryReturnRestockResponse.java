package com.msb.ecom.inventory_service.dto;
import java.time.Instant;
public record InventoryReturnRestockResponse(String restockId,String returnId,String status,int restoredQuantity,Instant completedAt){}
