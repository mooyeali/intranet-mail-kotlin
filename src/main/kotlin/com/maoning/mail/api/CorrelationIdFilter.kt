package com.maoning.mail.api

import java.util.UUID
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val correlationId = request.getHeader(CORRELATION_ID_HEADER)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        MDC.put(MDC_KEY, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"
    }
}
