package com.msb.ecom.inventory_service.dto;
public record InventoryReturnRestockRequest(String orderId,String returnId,String businessId){}
