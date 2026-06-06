package com.maoning.mail.api

import com.maoning.mail.config.AppConfig
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
class RequestSizeLimitFilter(
    private val config: AppConfig
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (shouldLimit(request) && request.contentLengthLong > config.maxMessageBytes) {
            response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
            response.contentType = "application/json"
            response.writer.write("{\"error\":\"request exceeds MAX_MESSAGE_BYTES\"}")
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun shouldLimit(request: HttpServletRequest): Boolean =
        request.method.equals("POST", ignoreCase = true) && request.requestURI == "/api/mail/send"
}
