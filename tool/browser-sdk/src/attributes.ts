// Span names and attribute names the reporter reads; mirrors io.stackvibed.agent.contract.StackAttributes.

export const SCENARIO = 'stack.scenario';
export const RUN = 'stack.run';

export const NAVIGATION_SPAN = 'stack.navigation';
export const ACTION_SPAN = 'stack.action';
export const UI_NAVIGATION_KIND = 'stack.ui.navigation.kind';
export const UI_URL = 'stack.ui.url';
export const UI_ROUTE = 'stack.ui.route';
export const UI_ACTION_EVENT = 'stack.ui.action.event';
export const UI_ACTION_TARGET = 'stack.ui.action.target';

// Stable OpenTelemetry HTTP semantic conventions on the fetch CLIENT spans (written by the fetch instrumentation).
export const HTTP_REQUEST_METHOD = 'http.request.method';
export const URL_FULL = 'url.full';
export const SERVER_ADDRESS = 'server.address';
export const SERVER_PORT = 'server.port';
export const HTTP_RESPONSE_STATUS_CODE = 'http.response.status_code';
export const ERROR_TYPE = 'error.type';

export const SERVICE_NAME = 'service.name';
