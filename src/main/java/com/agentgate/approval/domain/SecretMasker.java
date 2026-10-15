package com.agentgate.approval.domain;

import java.util.regex.Pattern;

/**
 * Masks the values of secret-looking keys ({@code "api_key": "..."}) in JSON-ish text, so a reason or
 * argument preview an agent sent never reaches approvers, webhooks or the API with the real secret.
 * Mirrors the runtime's masking, as a safety net for callers that do not mask themselves.
 */
final class SecretMasker {

    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(\"[^\"\\\\]*(?:pass(?:word|wd)|secret|token|api[-_]?key|authorization|credential)[^\"\\\\]*\"\\s*:\\s*)"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s,}\\]]+)",
            Pattern.CASE_INSENSITIVE);

    private SecretMasker() {
    }

    static String mask(String text) {
        if (text == null) {
            return null;
        }
        return SECRET_PAIR.matcher(text).replaceAll("$1\"***\"");
    }
}
