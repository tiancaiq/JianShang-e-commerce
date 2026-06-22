package com.msb.ecom.auth_service.repository;

import com.msb.ecom.auth_service.model.BusinessApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessApplicationRepository extends JpaRepository<BusinessApplication, String> {

    boolean existsByApplicantUserIdAndStatus(String applicantUserId, String status);

    Optional<BusinessApplication> findByIdAndApplicantUserId(String id, String applicantUserId);
}
