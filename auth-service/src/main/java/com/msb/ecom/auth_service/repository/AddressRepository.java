package com.msb.ecom.auth_service.repository;

import com.msb.ecom.auth_service.model.Address;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, String> {

    List<Address> findAllByUserIdOrderByDefaultAddressDescUpdatedAtDescIdAsc(String userId);

    Optional<Address> findByIdAndUserId(String id, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select address
            from Address address
            where address.userId = :userId
            order by address.createdAt asc, address.id asc
            """)
    List<Address> lockAllByUserId(@Param("userId") String userId);
}
