package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ActivateIndividualSellerRequest;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.IndividualSellerProfileResponse;
import com.msb.ecom.auth_service.service.IndividualSellerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/individual-seller")
@RequiredArgsConstructor
public class IndividualSellerController {

    private final IndividualSellerService individualSellerService;

    @GetMapping("/me")
    public ApiDataResponse<IndividualSellerProfileResponse> me() {
        return new ApiDataResponse<>(individualSellerService.getCurrentProfile());
    }

    @PostMapping("/activation")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDataResponse<IndividualSellerProfileResponse> activate(
            @Valid @RequestBody ActivateIndividualSellerRequest request) {
        return new ApiDataResponse<>(individualSellerService.activate(request));
    }
}
