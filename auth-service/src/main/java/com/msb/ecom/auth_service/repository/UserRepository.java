package com.msb.ecom.auth_service.repository;

import com.msb.ecom.auth_service.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByKeycloakSub(String keycloakSub);

    boolean existsByKeycloakSub(String keycloakSub);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> lockById(@Param("id") String id);
}
