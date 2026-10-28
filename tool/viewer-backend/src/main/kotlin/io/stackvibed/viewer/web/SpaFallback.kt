package io.stackvibed.viewer.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * The UI routes on the client (`/sequence/<runId>`), so a reload asks the server for a path it has no file for:
 * serve the app and let the router take over. The API and requests for a file (`/assets/missing.js`) stay 404.
 */
@ControllerAdvice
class SpaFallback {
    @ExceptionHandler(NoResourceFoundException::class)
    fun fallback(e: NoResourceFoundException, request: HttpServletRequest): String {
        val path = "/" + e.resourcePath.trimStart('/')
        val isApi = path == "/api" || path.startsWith("/api/")
        val isFile = path.substringAfterLast('/').contains('.')
        if (request.method != "GET" || isApi || isFile) throw e
        return "forward:/index.html"
    }
}
