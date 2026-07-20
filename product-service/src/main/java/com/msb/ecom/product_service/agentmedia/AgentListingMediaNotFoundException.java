package com.msb.ecom.product_service.agentmedia;

public class AgentListingMediaNotFoundException extends RuntimeException {

    public AgentListingMediaNotFoundException() {
        super("Owned listing draft media was not found.");
    }
}
