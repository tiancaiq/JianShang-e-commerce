package com.msb.ecom.product_service.agentmedia;

public class AgentListingMediaRejectedException extends RuntimeException {

    public AgentListingMediaRejectedException() {
        super("Selected listing media was rejected.");
    }
}
