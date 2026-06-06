package com.maoning.mail.api

import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertNotNull

class MailControllerAnnotationTest {
    @Test
    fun mailControllerIsDeclaredAsRestController() {
        assertNotNull(MailController::class.java.getAnnotation(RestController::class.java))
    }
}
