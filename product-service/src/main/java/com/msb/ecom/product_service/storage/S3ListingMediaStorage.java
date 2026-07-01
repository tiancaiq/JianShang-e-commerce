package com.msb.ecom.product_service.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

public class S3ListingMediaStorage implements ListingMediaStorage, AutoCloseable {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final HttpClient httpClient;
    private final String bucket;
    private final Duration signedUrlTtl;

    public S3ListingMediaStorage(ListingMediaStorageProperties properties) {
        ListingMediaStorageProperties.S3Properties s3 = properties.s3();
        this.bucket = required("S3 bucket", s3.bucket());
        this.signedUrlTtl = properties.signedUrlTtl();

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        required("S3 access key", s3.accessKeyId()),
                        required("S3 secret key", s3.secretAccessKey())));

        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(s3.pathStyleAccess())
                .checksumValidationEnabled(false)
                .chunkedEncodingEnabled(false)
                .build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(required("S3 region", s3.region())))
                .serviceConfiguration(s3Configuration);
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.of(required("S3 region", s3.region())))
                .serviceConfiguration(s3Configuration);

        if (s3.endpointUrl() != null && !s3.endpointUrl().isBlank()) {
            URI endpoint = URI.create(s3.endpointUrl());
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }

        this.s3Client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public StorageUploadTarget createUploadTarget(String objectKey, String contentType, long sizeBytes) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(signedUrlTtl)
                .putObjectRequest(putObjectRequest)
                .build();
        PresignedPutObjectRequest signedRequest = presigner.presignPutObject(presignRequest);
        return new StorageUploadTarget(bucket, objectKey, "PUT", signedRequest.url().toString());
    }

    @Override
    public void uploadObject(String objectKey, String contentType, byte[] bytes) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(bytes));
    }

    @Override
    // Confirm verifies the object exists in storage before metadata can become UPLOADED.
    public void verifyUploaded(String objectKey, String expectedContentType, long expectedSizeBytes) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
            if (head.contentLength() != expectedSizeBytes) {
                throw new IllegalArgumentException("Uploaded object size does not match upload request.");
            }
            if (head.contentType() != null
                    && !head.contentType().isBlank()
                    && !head.contentType().equalsIgnoreCase(expectedContentType)) {
                throw new IllegalArgumentException("Uploaded object content type does not match upload request.");
            }
        } catch (NoSuchKeyException exception) {
            throw new IllegalArgumentException("Uploaded object was not found in storage.");
        } catch (S3Exception exception) {
            throw new IllegalArgumentException("Uploaded object could not be verified.");
        }
    }

    @Override
    public byte[] readObject(String objectKey) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(signedUrlTtl)
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
                throw new StorageObjectAccessDeniedException(
                        "Stored media object could not be read. storageStatus=403 storageBody="
                                + safeStorageBody(response.body()));
            }
            if (response.statusCode() == 404) {
                throw new StorageObjectNotFoundException("Stored media object could not be read.");
            }
            throw S3Exception.builder()
                    .message("Stored media object read failed with HTTP " + response.statusCode())
                    .statusCode(response.statusCode())
                    .build();
        } catch (IOException exception) {
            throw S3Exception.builder()
                    .message("Stored media object could not be read.")
                    .cause(exception)
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw S3Exception.builder()
                    .message("Stored media object read was interrupted.")
                    .cause(exception)
                    .build();
        }
    }

    private String safeStorageBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "<empty>";
        }
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 240 ? text.substring(0, 240) : text;
    }

    private String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required when listing media storage is s3.");
        }
        return value.trim();
    }

    @Override
    public void close() {
        presigner.close();
        s3Client.close();
    }
}
