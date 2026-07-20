import com.msb.ecom.common.storage.object.S3ObjectStorageClient;
import com.msb.ecom.common.storage.object.S3ObjectStorageSettings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class SeedTaihouUpload {
    private SeedTaihouUpload() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: SeedTaihouUpload <path> <objectKey> <contentType>");
        }
        Map<String, String> env = loadEnv();
        S3ObjectStorageSettings settings = new S3ObjectStorageSettings(
                env.getOrDefault("S3_ENDPOINT_URL", "https://storage.googleapis.com"),
                env.getOrDefault("S3_REGION", "auto"),
                env.getOrDefault("S3_BUCKET", "jianshang"),
                env.get("S3_ACCESS_KEY_ID"),
                env.get("S3_SECRET_ACCESS_KEY"),
                Boolean.parseBoolean(env.getOrDefault("S3_PATH_STYLE_ACCESS", "true")),
                Duration.parse(env.getOrDefault("LISTING_MEDIA_SIGNED_URL_TTL", "PT15M")),
                "listing media");
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        try (S3ObjectStorageClient client = new S3ObjectStorageClient(settings)) {
            String uploadUrl = client.createUploadTarget(args[1], args[2]).uploadUrl();
            HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .header("Content-Type", args[2])
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Presigned upload failed with HTTP "
                        + response.statusCode() + ": " + safeBody(response.body()));
            }
            client.verifyUploaded(args[1], args[2], bytes.length, new com.msb.ecom.common.storage.object.ObjectStorageVerificationMessages(
                    "Uploaded object size does not match upload request.",
                    "Uploaded object content type does not match upload request.",
                    "Uploaded object was not found in storage.",
                    "Uploaded object could not be verified."));
        }
    }

    private static String safeBody(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }

    private static Map<String, String> loadEnv() throws IOException {
        Map<String, String> values = new HashMap<>();
        readEnvFile(values, Path.of(".env.demo"));
        readEnvFile(values, Path.of(".env"));
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("S3_") || key.equals("LISTING_MEDIA_SIGNED_URL_TTL")) {
                values.put(key, value);
            }
        });
        return values;
    }

    private static void readEnvFile(Map<String, String> values, Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            String key = parts[0].trim();
            String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
            if (isPlaceholder(value) && values.containsKey(key)) {
                continue;
            }
            values.put(key, value);
        }
    }

    private static boolean isPlaceholder(String value) {
        return value != null && value.startsWith("demo-change-me");
    }
}
