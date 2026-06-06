package com.maoning.mail.api

import com.maoning.mail.config.AppConfig
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIpResolverTest {
    @Test
    fun usesLeftmostForwardedForAddressWhenRemoteProxyIsTrusted() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "10.0.0.5"
            addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.5")
        }

        assertEquals(
            "203.0.113.7",
            request.clientIp(AppConfig(trustedProxyCidrs = listOf("10.0.0.0/8")))
        )
    }

    @Test
    fun ignoresForwardedHeadersWhenRemoteProxyIsNotTrusted() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "198.51.100.10"
            addHeader("X-Forwarded-For", "203.0.113.7")
        }

        assertEquals("198.51.100.10", request.clientIp(AppConfig(trustedProxyCidrs = listOf("10.0.0.0/8"))))
    }

    @Test
    fun supportsRfcForwardedForHeaderFromTrustedProxy() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("Forwarded", "for=\"2001:db8::1\";proto=https;host=mail.test")
        }

        assertEquals("2001:db8::1", request.clientIp(AppConfig(trustedProxyCidrs = listOf("127.0.0.1/32"))))
    }
}
