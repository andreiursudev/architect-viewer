package io.stackvibed.agent.contract;

/**
 * Span attribute names the stack agent extension writes and the reporter reads.
 *
 * <p>Header maps are JSON objects of lower-cased header name to the list of values; parameter lists
 * are JSON arrays of strings. Bodies are JSON (or the raw text for character bodies), cut to {@link
 * #MAX_BODY_CHARS} characters with the matching {@code *_truncated} flag set.
 */
public final class StackAttributes {

    public static final String SCENARIO = "stack.scenario";
    public static final String RUN = "stack.run";

    public static final String HTTP_REQUEST_HEADERS = "stack.http.request.headers";
    public static final String HTTP_RESPONSE_HEADERS = "stack.http.response.headers";
    public static final String HTTP_REQUEST_BODY = "stack.http.request.body";
    public static final String HTTP_RESPONSE_BODY = "stack.http.response.body";
    public static final String HTTP_REQUEST_BODY_TRUNCATED = "stack.http.request.body_truncated";
    public static final String HTTP_RESPONSE_BODY_TRUNCATED = "stack.http.response.body_truncated";

    public static final String DB_PARAMETERS = "stack.db.parameters";
    public static final String DB_ROW_COUNT = "stack.db.row_count";

    public static final int MAX_BODY_CHARS = 262_144;

    private StackAttributes() {}
}
