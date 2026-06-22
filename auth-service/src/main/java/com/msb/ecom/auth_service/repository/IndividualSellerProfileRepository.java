package com.msb.ecom.auth_service.repository;

import com.msb.ecom.auth_service.model.IndividualSellerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IndividualSellerProfileRepository extends JpaRepository<IndividualSellerProfile, String> {

    Optional<IndividualSellerProfile> findByUserId(String userId);

    boolean existsByUserId(String userId);
}
