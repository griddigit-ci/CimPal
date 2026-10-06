/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.util.UUID;

/**
 * Error bodies of the {@code /v1} API: RFC 9457 {@code application/problem+json} with
 * {@code type}, {@code title}, {@code status}, {@code detail} and, where there is one, the
 * {@code jobId}. The older endpoints keep {@code {"error": ...}}.
 */
final class Problem {

    static final String CONTENT_TYPE = "application/problem+json";

    private Problem() {
    }

    static String json(int status, String detail, UUID jobId) {
        StringBuilder sb = new StringBuilder("{\"type\":\"about:blank\",\"title\":")
                .append(ServeServer.jsonStr(title(status)))
                .append(",\"status\":").append(status)
                .append(",\"detail\":").append(ServeServer.jsonStr(detail));
        if (jobId != null) {
            sb.append(",\"jobId\":").append(ServeServer.jsonStr(jobId.toString()));
        }
        return sb.append('}').toString();
    }

    static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 413 -> "Content Too Large";
            case 415 -> "Unsupported Media Type";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> status >= 500 ? "Internal Server Error" : "Error";
        };
    }
}
