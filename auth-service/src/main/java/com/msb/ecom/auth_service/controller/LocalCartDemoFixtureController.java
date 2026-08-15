package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.LocalCartDemoFixtureResponse;
import com.msb.ecom.auth_service.service.LocalCartDemoFixtureService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/demo-fixtures/cart")
@ConditionalOnProperty(name = "demo.cart-second-business-fixture.enabled", havingValue = "true")
public class LocalCartDemoFixtureController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final LocalCartDemoFixtureService fixtureService;

    public LocalCartDemoFixtureController(LocalCartDemoFixtureService fixtureService) {
        this.fixtureService = fixtureService;
    }

    @PostMapping("/second-business")
    // Exposes the deterministic fixture only behind the local-demo property and service credential.
    public LocalCartDemoFixtureResponse ensureSecondBusiness(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestHeader(name = "X-Local-Demo-Owner-Subject", required = false)
            String ownerSubject,
            @RequestHeader(name = "X-Local-Demo-Shen-Owner-Subject", required = false)
            String shenOwnerSubject) {
        return fixtureService.ensure(internalToken, ownerSubject, shenOwnerSubject);
    }
}
