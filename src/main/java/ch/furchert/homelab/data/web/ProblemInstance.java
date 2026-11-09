package ch.furchert.homelab.data.web;

import java.util.regex.Pattern;

/**
 * The {@code instance} member of problem documents. A path that carries a visitor id is replaced by its template, so
 * no problem body ever contains the id (docs/100 §6.5; WA-5 plan ruling R7). The template is a valid URI reference,
 * so its braces are percent-encoded: {@code /api/webanalytics/visitors/%7BvisitorId%7D}.
 */
public final class ProblemInstance {

    public static final String VISITORS_TEMPLATE = "/api/webanalytics/visitors/%7BvisitorId%7D";
    private static final Pattern VISITORS = Pattern.compile("^/api/webanalytics/visitors/[^/]*$");

    private ProblemInstance() {
    }

    public static String of(String requestUri) {
        return requestUri != null && VISITORS.matcher(requestUri).matches() ? VISITORS_TEMPLATE : requestUri;
    }
}
