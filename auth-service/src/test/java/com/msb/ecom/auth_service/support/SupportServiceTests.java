package com.msb.ecom.auth_service.support;

import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.msb.ecom.auth_service.support.SupportContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportServiceTests {
    private static final String USER = id(1);
    private static final String ADMIN = id(2);
    private static final String OTHER_ADMIN = id(3);
    private static final String TICKET = id(4);
    private static final Instant NOW = Instant.parse("2026-08-21T02:00:00Z");
    private final AuthService auth = mock(AuthService.class);
    private final AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
    private final SupportRepository repository = mock(SupportRepository.class);
    private final SupportExternalContextClient external = mock(SupportExternalContextClient.class);
    private final UlidGenerator ids = mock(UlidGenerator.class);
    private final SupportService service = new SupportService(auth, authorization, repository, external, ids,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void defaults() {
        when(repository.links(any())).thenReturn(List.of());
        when(repository.messages(any())).thenReturn(List.of());
        when(repository.notes(any())).thenReturn(List.of());
        when(repository.events(any())).thenReturn(List.of());
        when(repository.escalations(any())).thenReturn(List.of());
        when(repository.adminName(any())).thenReturn("Support administrator");
    }

    @Test
    void createsRequesterOwnedTicketWithoutStartingSpecializedWorkflow() {
        User requester = user(USER, "Marketplace buyer");
        when(auth.ensureUserEntity()).thenReturn(requester);
        when(repository.command(USER, "CREATE_TICKET", "support-create-001")).thenReturn(Optional.empty());
        when(repository.local(TargetType.USER, USER)).thenReturn(Optional.of(
                new SupportRepository.LocalContext("Marketplace buyer", "/admin/users/" + USER,
                        Map.of("userId", USER, "accountState", "ACTIVE"))));
        when(repository.recentDuplicate(eq(USER), any(), any())).thenReturn(Optional.empty());
        when(repository.insertCommand(any())).thenReturn(true);
        when(ids.next()).thenReturn(TICKET, id(5), id(6), id(7));
        when(repository.owned(TICKET, USER)).thenReturn(Optional.of(ticket(TICKET, USER, Status.OPEN, null, 0)));

        var result = service.create(new CreateTicketRequest(Category.ACCOUNT_HELP, "Cannot update profile",
                "My profile changes are not being saved after submission.", null, null, null),
                "support-create-001", "correlation-1");

        assertThat(result.ticketId()).isEqualTo(TICKET);
        assertThat(result.status()).isEqualTo(Status.OPEN);
        verify(repository).insertTicket(argThat(row -> row.requester().equals(USER)
                && row.category() == Category.ACCOUNT_HELP));
        verify(repository).completeCommand(id(5), TICKET);
        verifyNoInteractions(external);
    }

    @Test
    void requesterDetailNeverReadsInternalNotesOrAssignmentIdentity() {
        when(auth.ensureUserEntity()).thenReturn(user(USER, "Marketplace buyer"));
        when(repository.owned(TICKET, USER)).thenReturn(Optional.of(ticket(TICKET, USER, Status.UNDER_REVIEW, ADMIN, 3)));

        var result = service.userDetail(TICKET);

        assertThat(result.ticketId()).isEqualTo(TICKET);
        assertThat(result).hasNoNullFieldsOrPropertiesExcept("resolutionCode", "resolutionReason", "resolvedAt");
        verify(repository, never()).notes(any());
        verify(repository, never()).adminName(any());
    }

    @Test
    void identicalRequesterMessageRetryDoesNotAppendAgain() {
        String body = "Here is the information you requested.";
        when(auth.ensureUserEntity()).thenReturn(user(USER, "Marketplace buyer"));
        when(repository.owned(TICKET, USER)).thenReturn(Optional.of(ticket(TICKET, USER, Status.WAITING_FOR_USER, ADMIN, 4)));
        when(repository.messageRetry(TICKET, USER, "support-reply-001")).thenReturn(Optional.of(
                new SupportRepository.MessageRow(id(8), TICKET, "REQUESTER", USER, body,
                        "support-reply-001", sha(body), NOW, null)));

        var result = service.userMessage(TICKET, new MessageRequest(body, 4L, null),
                "support-reply-001", "correlation-2");

        assertThat(result.status()).isEqualTo(Status.WAITING_FOR_USER);
        verify(repository, never()).requesterReply(any(), anyLong(), any(), any());
        verify(repository, never()).insertMessage(any());
    }

    @Test
    void anotherAdminCannotRespondToClaimedTicket() {
        configureAdmin(ADMIN);
        when(repository.ticket(TICKET, false)).thenReturn(Optional.of(
                ticket(TICKET, USER, Status.UNDER_REVIEW, OTHER_ADMIN, 2)));

        assertThatThrownBy(() -> service.respond(TICKET,
                new MessageRequest("I can help with that.", 2L, null), "support-admin-001", "correlation-3"))
                .isInstanceOfSatisfying(SupportException.class, error -> {
                    assertThat(error.code()).isEqualTo("SUPPORT_TICKET_NOT_ASSIGNED_TO_CURRENT_ADMIN");
                    assertThat(error.status().value()).isEqualTo(403);
                });
        verify(repository, never()).insertMessage(any());
    }

    @Test
    void resolvingTicketOnlyChangesSupportPersistence() {
        configureAdmin(ADMIN);
        SupportRepository.TicketRow before = ticket(TICKET, USER, Status.UNDER_REVIEW, ADMIN, 7);
        SupportRepository.TicketRow resolved = new SupportRepository.TicketRow(TICKET, USER, Category.REFUND_HELP,
                "Refund status", "I need help understanding the refund processing status.", Status.RESOLVED,
                Priority.MEDIUM, ADMIN, NOW, ResolutionCode.INFORMATION_PROVIDED.name(), "Status explained",
                "fingerprint", "correlation", 8, NOW.minusSeconds(60), NOW, "Marketplace buyer", "ACTIVE", "buyer@example.test");
        when(repository.ticket(TICKET, false)).thenReturn(Optional.of(before), Optional.of(resolved));
        when(repository.command(ADMIN, "RESOLVE_TICKET", "support-resolve-001")).thenReturn(Optional.empty());
        when(repository.insertCommand(any())).thenReturn(true);
        when(repository.resolve(TICKET, 7, ADMIN, ResolutionCode.INFORMATION_PROVIDED, "Status explained", NOW)).thenReturn(true);
        when(ids.next()).thenReturn(id(9), id(10), id(11), id(12));

        var result = service.resolve(TICKET,
                new ResolutionRequest(ResolutionCode.INFORMATION_PROVIDED, "Status explained", 7L, null),
                "support-resolve-001", "correlation-4");

        assertThat(result.summary().status()).isEqualTo(Status.RESOLVED);
        verify(repository).resolve(TICKET, 7, ADMIN, ResolutionCode.INFORMATION_PROVIDED, "Status explained", NOW);
        verifyNoInteractions(external);
    }

    private void configureAdmin(String id) {
        User admin = user(id, "Support admin");
        when(auth.ensureUserEntity()).thenReturn(admin);
        when(authorization.accessFor(admin)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("SUPPORT_ADMIN"), List.of(AdminPermission.SUPPORT_READ.id(), AdminPermission.SUPPORT_ASSIGN.id(),
                AdminPermission.SUPPORT_RESPOND.id(), AdminPermission.SUPPORT_RESOLVE.id(),
                AdminPermission.SUPPORT_ESCALATE.id())));
    }

    private static SupportRepository.TicketRow ticket(String id, String requester, Status status,
            String assigned, long version) {
        return new SupportRepository.TicketRow(id, requester, Category.ACCOUNT_HELP, "Account help",
                "I need assistance with a marketplace account setting.", status, Priority.MEDIUM, assigned,
                null, null, null, "fingerprint", "correlation", version, NOW.minusSeconds(60), NOW,
                "Marketplace buyer", "ACTIVE", "buyer@example.test");
    }

    private static User user(String id, String name) {
        return User.create(id, "keycloak-" + id, id + "@example.test", true, name, "handle-" + id.substring(24));
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String id(int value) { return "01" + String.format("%024d", value); }
}
