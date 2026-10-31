package io.stackvibed.agent.contract;

/**
 * Span attribute names the stack agent extension writes and the reporter reads.
 *
 * <p>Header maps are JSON objects of lower-cased header name to the list of values; parameter lists
 * are JSON arrays of strings. Bodies are JSON (or the raw text for character bodies), cut to {@link
 * #MAX_BODY_CHARS} characters with the matching {@code *_truncated} flag set. Bodies that are never
 * read for capture are recorded as placeholders: {@code <binary N bytes>}, {@code <stream>}, {@code
 * <reader>}, {@code <resource>}.
 *
 * <p>Method spans (kind INTERNAL, named {@code SimpleClass.method}) carry the fully qualified class in
 * {@link #METHOD_CLASS}. {@link #METHOD_PARAM_NAMES} and {@link #METHOD_ARGS} are JSON arrays of
 * strings: each element of {@code args} is one argument rendered as JSON (e.g. {@code ["\"a\"","3"]}),
 * and {@link #METHOD_RETURN} is the rendered return value, absent for {@code void} methods and when
 * the method threw.
 *
 * <p>The browser package ({@code @stackvibed/browser}) writes the UI spans: {@link #NAVIGATION_SPAN}
 * (page load or route change; {@link #UI_NAVIGATION_KIND} is {@code load} or {@code route}, with
 * {@link #UI_URL} and {@link #UI_ROUTE}) and {@link #ACTION_SPAN} ({@link #UI_ACTION_EVENT} is {@code
 * click} or {@code submit}, with {@link #UI_ACTION_TARGET} and {@link #UI_ROUTE}). Both are INTERNAL spans.
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

    public static final String METHOD_CLASS = "stack.method.class";
    public static final String METHOD_NAME = "stack.method.name";
    public static final String METHOD_PARAM_NAMES = "stack.method.param_names";
    public static final String METHOD_ARGS = "stack.method.args";
    public static final String METHOD_RETURN = "stack.method.return";
    public static final String METHOD_EXCEPTION_TYPE = "stack.method.exception.type";
    public static final String METHOD_EXCEPTION_MESSAGE = "stack.method.exception.message";
    public static final String METHOD_THREAD = "stack.method.thread";

    public static final String NAVIGATION_SPAN = "stack.navigation";
    public static final String ACTION_SPAN = "stack.action";
    public static final String UI_NAVIGATION_KIND = "stack.ui.navigation.kind";
    public static final String UI_URL = "stack.ui.url";
    public static final String UI_ROUTE = "stack.ui.route";
    public static final String UI_ACTION_EVENT = "stack.ui.action.event";
    public static final String UI_ACTION_TARGET = "stack.ui.action.target";

    public static final int MAX_BODY_CHARS = 262_144;

    private StackAttributes() {}
}
