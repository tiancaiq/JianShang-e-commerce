package com.msb.ecom.product_service.listing;

import com.msb.ecom.product_service.listing.dto.CategoryResponse;
import com.msb.ecom.product_service.listing.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.listing.dto.ListingDraftResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return listingService.getActiveCategories();
    }

    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingDraftResponse createDraft(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateListingDraftRequest request) {
        if (jwt == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        return listingService.createDraft(jwt, request);
    }
}
