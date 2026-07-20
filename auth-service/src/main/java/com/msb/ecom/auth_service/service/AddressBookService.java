package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AddressCreateRequest;
import com.msb.ecom.auth_service.dto.AddressPatchRequest;
import com.msb.ecom.auth_service.dto.AddressResponse;
import com.msb.ecom.auth_service.dto.InternalBuyerAddressResponse;
import com.msb.ecom.auth_service.model.Address;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.AddressRepository;
import com.msb.ecom.auth_service.repository.UserRepository;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AddressBookService {

    private static final int MAX_ADDRESSES = 20;
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final UlidGenerator ulidGenerator;
    private final InternalCommerceAuthenticator internalCommerceAuthenticator;
    private final AddressBookMetrics metrics;

    @Transactional(readOnly = true)
    // Lists only the current user's bounded address book with the default first.
    public List<AddressResponse> listCurrent() {
        User user = authService.ensureUserEntity();
        return addressRepository.findAllByUserIdOrderByDefaultAddressDescUpdatedAtDescIdAsc(user.getId())
                .stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Transactional
    // Creates one buyer-owned address while serializing the first-default and collection-limit rules.
    public AddressResponse create(AddressCreateRequest request) {
        User user = lockCurrentUser();
        List<Address> current = addressRepository.lockAllByUserId(user.getId());
        if (current.size() >= MAX_ADDRESSES) {
            log.info("Address book limit reached userId={} count={}", user.getId(), current.size());
            metrics.limitConflict();
            metrics.command("create", "limit_reached");
            throw new AddressBookLimitReachedException();
        }

        AddressValues values = values(request);
        Address address = Address.create(
                ulidGenerator.next(),
                user.getId(),
                values.label(),
                values.recipientName(),
                values.phone(),
                values.line1(),
                values.line2(),
                values.city(),
                values.region(),
                values.postalCode(),
                values.countryCode(),
                current.isEmpty());
        Address saved = addressRepository.saveAndFlush(address);
        log.info("Created buyer address addressId={} userId={} default={}",
                saved.getId(), user.getId(), saved.isDefaultAddress());
        metrics.command("create", "success");
        return AddressResponse.from(saved);
    }

    @Transactional
    // Applies a presence-aware patch after checking ownership and the current optimistic version.
    public AddressResponse patch(String addressId, long expectedVersion, AddressPatchRequest request) {
        if (!request.hasChanges()) {
            throw invalid("request", "EMPTY");
        }
        User user = authService.ensureUserEntity();
        Address address = requireOwned(user.getId(), normalizedAddressId(addressId));
        requireVersion(address, expectedVersion, "patch");

        AddressValues values = values(address, request);
        address.update(
                values.label(),
                values.recipientName(),
                values.phone(),
                values.line1(),
                values.line2(),
                values.city(),
                values.region(),
                values.postalCode(),
                values.countryCode());
        Address saved = addressRepository.saveAndFlush(address);
        log.info("Updated buyer address addressId={} userId={}", saved.getId(), user.getId());
        metrics.command("patch", "success");
        return AddressResponse.from(saved);
    }

    @Transactional
    // Hard-deletes one owned address and promotes a deterministic replacement when needed.
    public void delete(String addressId, long expectedVersion) {
        User user = lockCurrentUser();
        String normalizedAddressId = normalizedAddressId(addressId);
        List<Address> addresses = addressRepository.lockAllByUserId(user.getId());
        Address target = addresses.stream()
                .filter(address -> address.getId().equals(normalizedAddressId))
                .findFirst()
                .orElseThrow(AddressNotFoundException::new);
        requireVersion(target, expectedVersion, "delete");

        boolean removedDefault = target.isDefaultAddress();
        addressRepository.delete(target);
        addressRepository.flush();

        if (removedDefault) {
            addresses.stream()
                    .filter(address -> !address.getId().equals(target.getId()))
                    .min(Comparator.comparing(Address::getCreatedAt).thenComparing(Address::getId))
                    .ifPresent(replacement -> {
                        replacement.setDefaultAddress(true);
                        addressRepository.saveAndFlush(replacement);
                    });
        }
        log.info("Deleted buyer address addressId={} userId={} promotedReplacement={}",
                target.getId(), user.getId(), removedDefault && addresses.size() > 1);
        metrics.command("delete", "success");
    }

    @Transactional
    // Switches the address-book default under one owner lock and database uniqueness guard.
    public AddressResponse setDefault(String addressId, long expectedVersion) {
        User user = lockCurrentUser();
        String normalizedAddressId = normalizedAddressId(addressId);
        List<Address> addresses = addressRepository.lockAllByUserId(user.getId());
        Address target = addresses.stream()
                .filter(address -> address.getId().equals(normalizedAddressId))
                .findFirst()
                .orElseThrow(AddressNotFoundException::new);
        requireVersion(target, expectedVersion, "set_default");
        if (target.isDefaultAddress()) {
            metrics.command("set_default", "unchanged");
            return AddressResponse.from(target);
        }

        addresses.stream()
                .filter(Address::isDefaultAddress)
                .forEach(currentDefault -> currentDefault.setDefaultAddress(false));
        addressRepository.flush();

        target.setDefaultAddress(true);
        Address saved = addressRepository.saveAndFlush(target);
        log.info("Changed default buyer address addressId={} userId={}", saved.getId(), user.getId());
        metrics.command("set_default", "success");
        return AddressResponse.from(saved);
    }

    @Transactional(readOnly = true)
    // Resolves one exact buyer/address pair for trusted checkout snapshot creation.
    public InternalBuyerAddressResponse resolveInternal(
            String suppliedToken,
            String buyerId,
            String addressId) {
        long startedAt = System.nanoTime();
        try {
            internalCommerceAuthenticator.require(suppliedToken);
            String normalizedBuyerId = normalizedBuyerId(buyerId);
            String normalizedAddressId = normalizedInternalAddressId(addressId);
            User buyer = userRepository.findById(normalizedBuyerId)
                    .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                    .orElseThrow(BuyerAddressNotFoundException::new);
            Address address = addressRepository.findByIdAndUserId(normalizedAddressId, buyer.getId())
                    .orElseThrow(BuyerAddressNotFoundException::new);
            log.info("Resolved buyer address for internal checkout addressId={} buyerId={}",
                    address.getId(), buyer.getId());
            metrics.internalResolver("success", System.nanoTime() - startedAt);
            return new InternalBuyerAddressResponse(
                    address.getId(),
                    buyer.getId(),
                    address.getLabel(),
                    address.getRecipientName(),
                    address.getPhone(),
                    address.getLine1(),
                    address.getLine2(),
                    address.getCity(),
                    address.getRegion(),
                    address.getPostalCode(),
                    address.getCountryCode(),
                    address.getVersion());
        } catch (InternalCommerceAuthorizationException exception) {
            metrics.internalResolver("denied", System.nanoTime() - startedAt);
            throw exception;
        } catch (BuyerAddressNotFoundException exception) {
            metrics.internalResolver("not_found", System.nanoTime() - startedAt);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    // Maps one authenticated subject to its active buyer and exact owned address for checkout.
    public InternalBuyerAddressResponse resolveCheckoutAddress(
            String suppliedToken,
            String subject,
            String addressId) {
        long startedAt = System.nanoTime();
        try {
            internalCommerceAuthenticator.require(suppliedToken);
            if (subject == null || subject.isBlank() || subject.trim().length() > 64) {
                throw new BuyerAddressNotFoundException();
            }
            String normalizedAddressId = normalizedInternalAddressId(addressId);
            User buyer = userRepository.findByKeycloakSub(subject.trim())
                    .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                    .orElseThrow(BuyerAddressNotFoundException::new);
            Address address = addressRepository.findByIdAndUserId(normalizedAddressId, buyer.getId())
                    .orElseThrow(BuyerAddressNotFoundException::new);
            metrics.internalResolver("checkout_success", System.nanoTime() - startedAt);
            return internalResponse(buyer, address);
        } catch (InternalCommerceAuthorizationException exception) {
            metrics.internalResolver("checkout_denied", System.nanoTime() - startedAt);
            throw exception;
        } catch (BuyerAddressNotFoundException exception) {
            metrics.internalResolver("checkout_not_found", System.nanoTime() - startedAt);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    // Resolves the active opaque buyer ID used for checkout ownership reads and cancellation.
    public String resolveCheckoutBuyer(String suppliedToken, String subject) {
        internalCommerceAuthenticator.require(suppliedToken);
        if (subject == null || subject.isBlank() || subject.trim().length() > 64) {
            throw new BuyerAddressNotFoundException();
        }
        return userRepository.findByKeycloakSub(subject.trim())
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .map(User::getId)
                .orElseThrow(BuyerAddressNotFoundException::new);
    }

    private InternalBuyerAddressResponse internalResponse(User buyer, Address address) {
        return new InternalBuyerAddressResponse(
                address.getId(),
                buyer.getId(),
                address.getLabel(),
                address.getRecipientName(),
                address.getPhone(),
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getRegion(),
                address.getPostalCode(),
                address.getCountryCode(),
                address.getVersion());
    }

    private User lockCurrentUser() {
        User current = authService.ensureUserEntity();
        return userRepository.lockById(current.getId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user mapping is missing"));
    }

    private Address requireOwned(String userId, String addressId) {
        return addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(AddressNotFoundException::new);
    }

    private void requireVersion(Address address, long expectedVersion, String operation) {
        if (address.getVersion() != expectedVersion) {
            log.info("Buyer address version conflict addressId={} expected={} actual={}",
                    address.getId(), expectedVersion, address.getVersion());
            metrics.versionConflict();
            metrics.command(operation, "version_conflict");
            throw new AddressVersionConflictException();
        }
    }

    private String normalizedAddressId(String addressId) {
        try {
            return FixedLengthIds.requireTrimmed("Address ID", addressId, 26);
        } catch (IllegalArgumentException exception) {
            throw new AddressNotFoundException();
        }
    }

    private String normalizedBuyerId(String buyerId) {
        try {
            return FixedLengthIds.requireTrimmed("Buyer ID", buyerId, 26);
        } catch (IllegalArgumentException exception) {
            throw new BuyerAddressNotFoundException();
        }
    }

    private String normalizedInternalAddressId(String addressId) {
        try {
            return FixedLengthIds.requireTrimmed("Address ID", addressId, 26);
        } catch (IllegalArgumentException exception) {
            throw new BuyerAddressNotFoundException();
        }
    }

    private AddressValues values(AddressCreateRequest request) {
        return validated(
                request.label(),
                request.recipientName(),
                request.phone(),
                request.line1(),
                request.line2(),
                request.city(),
                request.region(),
                request.postalCode(),
                request.countryCode());
    }

    private AddressValues values(Address current, AddressPatchRequest request) {
        return validated(
                request.labelPresent() ? request.label() : current.getLabel(),
                request.recipientNamePresent() ? request.recipientName() : current.getRecipientName(),
                request.phonePresent() ? request.phone() : current.getPhone(),
                request.line1Present() ? request.line1() : current.getLine1(),
                request.line2Present() ? request.line2() : current.getLine2(),
                request.cityPresent() ? request.city() : current.getCity(),
                request.regionPresent() ? request.region() : current.getRegion(),
                request.postalCodePresent() ? request.postalCode() : current.getPostalCode(),
                request.countryCodePresent() ? request.countryCode() : current.getCountryCode());
    }

    private AddressValues validated(
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode) {
        String normalizedPhone = required("phone", phone, 32);
        if (!PHONE.matcher(normalizedPhone).matches()) {
            throw invalid("phone", "PATTERN");
        }
        String normalizedCountryCode = required("countryCode", countryCode, 2).toUpperCase(Locale.ROOT);
        if (!COUNTRY_CODE.matcher(normalizedCountryCode).matches()) {
            throw invalid("countryCode", "PATTERN");
        }
        return new AddressValues(
                optional("label", label, 40),
                required("recipientName", recipientName, 120),
                normalizedPhone,
                required("line1", line1, 200),
                optional("line2", line2, 200),
                required("city", city, 100),
                required("region", region, 100),
                required("postalCode", postalCode, 32),
                normalizedCountryCode);
    }

    private String required(String field, String value, int maxLength) {
        String normalized = optional(field, value, maxLength);
        if (normalized == null) {
            throw invalid(field, "REQUIRED");
        }
        return normalized;
    }

    private String optional(String field, String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw invalid(field, "TOO_LONG");
        }
        if (CONTROL_CHARACTER.matcher(trimmed).find()) {
            throw invalid(field, "CONTROL_CHARACTER");
        }
        return trimmed;
    }

    private AddressValidationException invalid(String field, String code) {
        return new AddressValidationException(field, code);
    }

    private record AddressValues(
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode
    ) {
    }
}
