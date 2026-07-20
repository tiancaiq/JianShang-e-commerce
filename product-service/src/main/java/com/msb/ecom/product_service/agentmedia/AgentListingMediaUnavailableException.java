package com.msb.ecom.product_service.agentmedia;

public class AgentListingMediaUnavailableException extends RuntimeException {

    public AgentListingMediaUnavailableException() {
        super("Listing media is temporarily unavailable.");
    }
}
