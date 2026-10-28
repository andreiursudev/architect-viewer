package io.stackvibed.model

val sampleHttpEvent = HttpServerEvent(
    eventId = "ev-1",
    runId = "run-1",
    traceId = "0af7651916cd43dd8448eb211c80319c",
    spanId = "b7ad6b7169203331",
    parentSpanId = null,
    component = "orders-service",
    startEpochNanos = 1_700_000_000_000_000_000L,
    durationNanos = 12_500_000L,
    method = "POST",
    route = "/orders",
    path = "/orders",
    status = 201,
    requestHeaders = mapOf("content-type" to listOf("application/json")),
    responseHeaders = mapOf("location" to listOf("/orders/1")),
    requestBody = """{"sku":"A-1"}""",
    responseBody = """{"id":1}""",
)

val sampleDbEvent = DbClientEvent(
    eventId = "ev-2",
    runId = "run-1",
    traceId = "0af7651916cd43dd8448eb211c80319c",
    spanId = "00f067aa0ba902b7",
    parentSpanId = "b7ad6b7169203331",
    component = "orders-service",
    startEpochNanos = 1_700_000_000_001_000_000L,
    durationNanos = 2_000_000L,
    dbSystem = "postgresql",
    dbName = "orders",
    statement = "insert into orders(sku, note) values (?, ?)",
    parameters = listOf("A-1", null),
    rowCount = 1L,
)

val sampleClientEvent = HttpClientEvent(
    eventId = "ev-3",
    runId = "run-1",
    traceId = "0af7651916cd43dd8448eb211c80319c",
    spanId = "d4cda95b652f4a15",
    parentSpanId = "b7ad6b7169203331",
    component = "orders-service",
    startEpochNanos = 1_700_000_000_002_000_000L,
    durationNanos = 5_000_000L,
    method = "POST",
    url = "http://localhost:8081/stock/reservations",
    serverAddress = "localhost",
    serverPort = 8081,
    status = 201,
    errorType = null,
)
