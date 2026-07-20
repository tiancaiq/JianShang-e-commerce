package com.msb.ecom.product_service.agentmedia;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import com.msb.ecom.product_service.storage.StorageObjectAccessDeniedException;
import com.msb.ecom.product_service.storage.StorageObjectNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class AgentListingMediaService {

    private static final String RESPONSE_SCHEMA = "ai-list-owned-draft-media-v1";

    private final AgentListingMediaAuthorization authorization;
    private final ListingMediaStorage storage;
    private final AgentListingMediaMetrics metrics;
    private final String internalServiceToken;
    private final boolean enabled;

    public AgentListingMediaService(
            AgentListingMediaAuthorization authorization,
            ListingMediaStorage storage,
            AgentListingMediaMetrics metrics,
            @Value("${agent.internal-service-token}") String internalServiceToken,
            @Value("${agent.listing-media-tool-enabled:false}") boolean enabled) {
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new IllegalArgumentException("Agent internal service token must be configured.");
        }
        this.authorization = authorization;
        this.storage = storage;
        this.metrics = metrics;
        this.internalServiceToken = internalServiceToken;
        this.enabled = enabled;
    }

    // Resolves only Product-authorized, seller-owned draft images for the Agent allowlist.
    public AgentListingMediaResponse readOwnedDraftMedia(
            String suppliedToken,
            String listingId,
            AgentListingMediaRequest request,
            String correlationId) {
        Instant startedAt = Instant.now();
        String result = "unavailable";
        String actorForAudit = "";
        String listingForAudit = listingId == null ? "" : listingId;
        int mediaCount = request == null || request.mediaIds() == null ? 0 : request.mediaIds().size();
        try {
            requireInternalToken(suppliedToken);
            if (!enabled) {
                result = "disabled";
                throw new AgentListingMediaUnavailableException();
            }
            if (request == null) {
                result = "rejected";
                throw new AgentListingMediaRejectedException();
            }
            if (!RESPONSE_SCHEMA.equals(request.schemaVersion())) {
                result = "rejected";
                throw new AgentListingMediaRejectedException();
            }
            actorForAudit = FixedLengthIds.requireTrimmed("Actor user ID", request.actorUserId(), 26);
            listingForAudit = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
            AgentListingMediaResponse response =
                    readAuthorized(actorForAudit, listingForAudit, request.mediaIds());
            result = "success";
            return response;
        } catch (ListingAuthorizationException exception) {
            result = "unauthorized";
            throw exception;
        } catch (AgentListingMediaNotFoundException exception) {
            result = "not_found";
            throw exception;
        } catch (AgentListingMediaRejectedException exception) {
            result = "rejected";
            throw exception;
        } catch (AgentListingMediaUnavailableException exception) {
            if (!"disabled".equals(result)) {
                result = "unavailable";
            }
            throw exception;
        } catch (IllegalArgumentException exception) {
            result = "rejected";
            throw exception;
        } catch (RuntimeException exception) {
            result = "unavailable";
            throw new AgentListingMediaUnavailableException();
        } finally {
            Duration duration = Duration.between(startedAt, Instant.now());
            metrics.record(result, duration);
            log.info(
                    "Agent listing-media read result={} actorHash={} listingHash={} correlationHash={} mediaCount={} latencyMs={}",
                    result,
                    hash(actorForAudit),
                    hash(listingForAudit),
                    hash(correlationId == null ? "" : correlationId),
                    Math.min(
                            Math.max(mediaCount, 0),
                            AgentListingMediaAuthorization.MAX_MEDIA_COUNT),
                    Math.max(0, duration.toMillis()));
        }
    }

    private AgentListingMediaResponse readAuthorized(
            String actorUserId,
            String listingId,
            List<String> requestedMediaIds) {
        AgentListingMediaAuthorization.AuthorizedSelection selection =
                authorization.authorize(actorUserId, listingId, requestedMediaIds);

        List<AgentListingMediaResponse.Media> responseMedia = new ArrayList<>();
        long actualTotal = 0;
        for (AgentListingMediaRepository.MediaSnapshot media : selection.media()) {
            byte[] bytes = readBytes(media);
            actualTotal += bytes.length;
            if (actualTotal > AgentListingMediaAuthorization.MAX_TOTAL_BYTES) {
                throw new AgentListingMediaRejectedException();
            }
            String actualContentType = detectedContentType(bytes);
            String declaredContentType = normalizedContentType(media.contentType());
            if (!declaredContentType.equals(actualContentType)) {
                throw new AgentListingMediaRejectedException();
            }
            String digest = sha256(bytes);
            if (media.checksumSha256() != null
                    && !digest.equals(media.checksumSha256().toLowerCase(Locale.ROOT))) {
                throw new AgentListingMediaRejectedException();
            }
            responseMedia.add(new AgentListingMediaResponse.Media(
                    media.id(),
                    actualContentType,
                    bytes.length,
                    digest,
                    Long.toString(media.version()),
                    Base64.getEncoder().encodeToString(bytes)));
        }
        return new AgentListingMediaResponse(
                RESPONSE_SCHEMA,
                selection.listing().id(),
                Long.toString(selection.listing().version()),
                "OWNED_DRAFT",
                List.copyOf(responseMedia));
    }

    private byte[] readBytes(AgentListingMediaRepository.MediaSnapshot media) {
        try {
            byte[] bytes = storage.readObject(media.objectKey());
            if (bytes == null
                    || bytes.length < 1
                    || bytes.length > AgentListingMediaAuthorization.MAX_MEDIA_BYTES
                    || bytes.length != media.sizeBytes()) {
                throw new AgentListingMediaRejectedException();
            }
            return bytes;
        } catch (StorageObjectNotFoundException exception) {
            throw new AgentListingMediaNotFoundException();
        } catch (StorageObjectAccessDeniedException exception) {
            throw new AgentListingMediaUnavailableException();
        } catch (AgentListingMediaRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentListingMediaUnavailableException();
        }
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ListingAuthorizationException("Agent source authentication is required.");
        }
    }

    private String normalizedContentType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String detectedContentType(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 16
                && bytes[0] == 0x52
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x46
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50
                && bytes[12] == 0x56
                && bytes[13] == 0x50
                && bytes[14] == 0x38
                && (bytes[15] == 0x20 || bytes[15] == 0x4c || bytes[15] == 0x58)) {
            return "image/webp";
        }
        throw new AgentListingMediaRejectedException();
    }

    private String hash(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required.", exception);
        }
    }
}
