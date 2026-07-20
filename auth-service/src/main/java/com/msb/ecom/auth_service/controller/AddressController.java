package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.AddressCreateRequest;
import com.msb.ecom.auth_service.dto.AddressPatchRequest;
import com.msb.ecom.auth_service.dto.AddressResponse;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.service.AddressBookService;
import com.msb.ecom.common.web.http.IfMatchVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private static final String ADDRESS_VERSION_REQUIRED =
            "If-Match must contain the current address version";

    private final AddressBookService addressBookService;

    @GetMapping
    public ApiDataResponse<List<AddressResponse>> list() {
        return new ApiDataResponse<>(addressBookService.listCurrent());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDataResponse<AddressResponse> create(@RequestBody AddressCreateRequest request) {
        return new ApiDataResponse<>(addressBookService.create(request));
    }

    @PatchMapping("/{addressId}")
    public ApiDataResponse<AddressResponse> patch(
            @PathVariable String addressId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody AddressPatchRequest request) {
        return new ApiDataResponse<>(addressBookService.patch(
                addressId,
                IfMatchVersion.parseRequired(ifMatch, ADDRESS_VERSION_REQUIRED),
                request));
    }

    @DeleteMapping("/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String addressId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        addressBookService.delete(
                addressId,
                IfMatchVersion.parseRequired(ifMatch, ADDRESS_VERSION_REQUIRED));
    }

    @PostMapping("/{addressId}/default")
    public ApiDataResponse<AddressResponse> setDefault(
            @PathVariable String addressId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return new ApiDataResponse<>(addressBookService.setDefault(
                addressId,
                IfMatchVersion.parseRequired(ifMatch, ADDRESS_VERSION_REQUIRED)));
    }
}
