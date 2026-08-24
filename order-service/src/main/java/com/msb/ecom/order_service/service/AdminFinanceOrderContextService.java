package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.dto.AdminFinanceOrderContext;
import com.msb.ecom.order_service.repository.AdminFinanceOrderContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AdminFinanceOrderContextService {
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private final InternalOrderEventAuthenticator authenticator;
    private final AdminFinanceOrderContextRepository repository;

    public AdminFinanceOrderContextService(InternalOrderEventAuthenticator authenticator,
            AdminFinanceOrderContextRepository repository) {
        this.authenticator = authenticator; this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AdminFinanceOrderContext> contexts(String token, List<String> ids) {
        authenticator.requireAuthenticated(token);
        if (ids == null || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || !ULID.matcher(id).matches())) {
            throw new IllegalArgumentException("One to one hundred payment intent IDs are required.");
        }
        return repository.byPaymentIds(new LinkedHashSet<>(ids));
    }

    @Transactional(readOnly = true)
    public Optional<AdminFinanceOrderContext> order(String token, String orderId) {
        authenticator.requireAuthenticated(token);
        if (orderId == null || !ULID.matcher(orderId).matches()) throw new IllegalArgumentException("Order ID is invalid.");
        return repository.byOrderId(orderId);
    }
}
