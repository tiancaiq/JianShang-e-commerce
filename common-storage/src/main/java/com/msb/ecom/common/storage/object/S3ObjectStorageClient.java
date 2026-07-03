package com.msb.ecom.common.storage.object;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class S3ObjectStorageClient implements AutoCloseable {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final HttpClient httpClient;
    private final S3ObjectStorageSettings settings;

    public S3ObjectStorageClient(S3ObjectStorageSettings settings) {
        this.settings = settings;

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        settings.requiredAccessKeyId(),
                        settings.requiredSecretAccessKey()));

        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(settings.pathStyleAccess())
                .checksumValidationEnabled(false)
                .chunkedEncodingEnabled(false)
                .build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(settings.requiredRegion()))
                .serviceConfiguration(s3Configuration);
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(settings.requiredRegion()))
                .serviceConfiguration(s3Configuration);

        if (settings.endpointUrl() != null && !settings.endpointUrl().isBlank()) {
            URI endpoint = URI.create(settings.endpointUrl());
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }

        this.s3Client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
        this.httpClient = HttpClient.newHttpClient();
    }

    public String bucket() {
        return settings.requiredBucket();
    }

    // Creates the presigned PUT target shared by listing media and avatar uploads.
    public ObjectStorageUploadTarget createUploadTarget(String objectKey, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(settings.signedUrlTtl())
                .putObjectRequest(putObjectRequest)
                .build();
        PresignedPutObjectRequest signedRequest = presigner.presignPutObject(presignRequest);
        return new ObjectStorageUploadTarget(bucket(), objectKey, "PUT", signedRequest.url().toString());
    }

    public void putObject(String objectKey, String contentType, byte[] bytes) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(bytes));
    }

    // Verifies object metadata after a browser/client upload without applying service-specific ownership rules.
    public void verifyUploaded(
            String objectKey,
            String expectedContentType,
            long expectedSizeBytes,
            ObjectStorageVerificationMessages messages) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey)
                    .build());
            if (head.contentLength() != expectedSizeBytes) {
                throw new IllegalArgumentException(messages.sizeMismatch());
            }
            if (head.contentType() != null
                    && !head.contentType().isBlank()
                    && !head.contentType().equalsIgnoreCase(expectedContentType)) {
                throw new IllegalArgumentException(messages.contentTypeMismatch());
            }
        } catch (NoSuchKeyException exception) {
            throw new IllegalArgumentException(messages.notFound());
        } catch (S3Exception exception) {
            throw new ObjectStorageException(messages.unavailable(), exception);
        }
    }

    public byte[] readObject(String objectKey) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket())
                .key(objectKey)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(settings.signedUrlTtl())
                .getObjectRequest(getObjectRequest)
                .build();
        try {
            PresignedGetObjectRequest signedRequest = presigner.presignGetObject(presignRequest);
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(signedRequest.url().toString())).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
            if (response.statusCode() == 403) {
                throw new ObjectStorageAccessDeniedException(
                        "Stored object could not be read. storageStatus=403 storageBody="
                                + safeStorageBody(response.body()));
            }
            if (response.statusCode() == 404) {
                throw new ObjectStorageNotFoundException("Stored object could not be read.");
            }
            throw new ObjectStorageException(
                    "Stored object read failed with HTTP " + response.statusCode(),
                    S3Exception.builder()
                            .message("Stored object read failed with HTTP " + response.statusCode())
                            .statusCode(response.statusCode())
                            .build());
        } catch (IOException exception) {
            throw new ObjectStorageException("Stored object could not be read.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ObjectStorageException("Stored object read was interrupted.", exception);
        }
    }

    public void deleteObjectIfExists(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey)
                    .build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw new ObjectStorageException("Stored object could not be deleted.", exception);
            }
        }
    }

    private String safeStorageBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "<empty>";
        }
        String text = new String(body, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 240 ? text.substring(0, 240) : text;
    }

    @Override
    public void close() {
        presigner.close();
        s3Client.close();
    }
}
