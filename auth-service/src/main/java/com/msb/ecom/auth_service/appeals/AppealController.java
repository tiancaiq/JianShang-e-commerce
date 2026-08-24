package com.msb.ecom.auth_service.appeals;

import com.msb.ecom.auth_service.appeals.AppealContracts.*;
import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AppealController {
    private final AppealService service;

    @GetMapping("/api/v1/enforcements/mine")
    public ApiDataResponse<List<EnforcementNotice>> notices() {
        return new ApiDataResponse<>(service.myEnforcementNotices());
    }

    @PostMapping("/api/v1/enforcements/{enforcementActionId}/appeals")
    public ApiDataResponse<SubmissionResult> submit(@PathVariable String enforcementActionId,
            @RequestBody CreateAppealRequest request, HttpServletRequest http) {
        return new ApiDataResponse<>(service.submit(enforcementActionId, request,
                CorrelationIdFilter.current(http)));
    }

    @GetMapping("/api/v1/appeals/mine")
    public ApiDataResponse<List<MyAppeal>> mine() {
        return new ApiDataResponse<>(service.mine());
    }
}
