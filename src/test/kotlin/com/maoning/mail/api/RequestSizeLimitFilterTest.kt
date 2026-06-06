package com.maoning.mail.api

import com.maoning.mail.config.AppConfig
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestSizeLimitFilterTest {
    @Test
    fun rejectsOversizedMailSendRequestBeforeMvcDeserialization() {
        val filter = RequestSizeLimitFilter(AppConfig(maxMessageBytes = 16))
        val request = MockHttpServletRequest("POST", "/api/mail/send")
        request.setContent(ByteArray(17) { 'x'.code.toByte() })
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), response.status)
        assertEquals(false, chain.called)
    }

    @Test
    fun allowsNonMailSendRequestsThrough() {
        val filter = RequestSizeLimitFilter(AppConfig(maxMessageBytes = 16))
        val request = MockHttpServletRequest("POST", "/api/login")
        request.setContent(ByteArray(17) { 'x'.code.toByte() })
        val response = MockHttpServletResponse()
        val chain = RecordingFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(true, chain.called)
    }
}

private class RecordingFilterChain : MockFilterChain() {
    var called = false
    override fun doFilter(request: javax.servlet.ServletRequest, response: javax.servlet.ServletResponse) {
        called = true
    }
}
