package io.stackvibed.harness

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/** A reporter stand-in that answers every screenshot upload with [status] and keeps what it received. */
class FakeReporter(private val status: Int = 201) : AutoCloseable {
    data class Upload(val path: String, val query: String, val contentType: String?, val body: ByteArray)

    val uploads = mutableListOf<Upload>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            synchronized(uploads) {
                uploads += Upload(
                    exchange.requestURI.path,
                    exchange.requestURI.rawQuery,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes(),
                )
            }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
