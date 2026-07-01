package com.msb.ecom.api_gateway.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public final class LoginReturnUrl {

    public static final String SESSION_ATTRIBUTE = LoginReturnUrl.class.getName() + ".RETURN_URL";
    public static final String POPUP_SESSION_ATTRIBUTE = LoginReturnUrl.class.getName() + ".POPUP";

    private LoginReturnUrl() {
    }

    /**
     * Allows only same-site relative paths to prevent login from becoming an
     * open redirect through the OIDC success handler.
     */
    public static Optional<String> sanitize(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return Optional.empty();
        }
        if (!returnUrl.startsWith("/") || returnUrl.startsWith("//") || containsUnsafeCharacter(returnUrl)) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(returnUrl);
            if (uri.isAbsolute() || uri.getHost() != null || uri.getRawAuthority() != null) {
                return Optional.empty();
            }
            return Optional.of(returnUrl);
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    public static String joinWithBaseUri(String baseUri, String relativePath) {
        String normalizedBase = baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
        return normalizedBase + relativePath;
    }

    private static boolean containsUnsafeCharacter(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\\') >= 0;
    }
}
