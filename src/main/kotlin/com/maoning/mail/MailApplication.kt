package com.maoning.mail

import com.maoning.mail.attachment.AttachmentStorage
import com.maoning.mail.audit.AuditService
import com.maoning.mail.auth.AuthService
import com.maoning.mail.config.AppConfig
import com.maoning.mail.jpa.JpaMailStore
import com.maoning.mail.jpa.MailProperties
import com.maoning.mail.mail.MailService
import com.maoning.mail.jpa.LoginAttemptRepository
import com.maoning.mail.mime.MimeParser
import com.maoning.mail.pop3.Pop3Server
import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.smtp.SmtpServer
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import javax.annotation.PreDestroy

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MailProperties::class)
class MailApplication {
    @Bean
    fun appConfig(properties: MailProperties): AppConfig = properties.toAppConfig()

    @Bean
    fun attachmentStorage(config: AppConfig) = AttachmentStorage(config.attachmentDir)

    @Bean
    fun mimeParser(config: AppConfig, attachmentStorage: AttachmentStorage) =
        MimeParser(attachmentStorage, config.maxAttachmentBytes, config.maxTotalAttachmentBytes)

    @Bean
    fun loginRateLimiter(config: AppConfig, loginAttemptRepository: LoginAttemptRepository) = LoginRateLimiter(config, loginAttemptRepository)

    @Bean
    fun authService(store: JpaMailStore, loginRateLimiter: LoginRateLimiter) = AuthService(store, loginRateLimiter)

    @Bean
    fun mailService(config: AppConfig, store: JpaMailStore, mimeParser: MimeParser, auditService: AuditService) = MailService(store, mimeParser, auditService, config)

    @Bean
    fun socketServers(config: AppConfig, props: MailProperties, mailService: MailService, authService: AuthService, store: JpaMailStore) =
        SocketServerLifecycle(config, props, mailService, authService, store)
}

class SocketServerLifecycle(
    private val config: AppConfig,
    private val props: MailProperties,
    mailService: MailService,
    authService: AuthService,
    store: JpaMailStore
) : ApplicationRunner {
    private val smtp = SmtpServer(config, mailService, authService, "mail.${config.domain}", store)
    private val pop3 = Pop3Server(config, store, authService)

    override fun run(args: ApplicationArguments) {
        config.validateForRuntime()
        if (!props.socketServersEnabled) return
        smtp.start()
        pop3.start()
    }

    @PreDestroy
    fun stop() {
        smtp.stop()
        pop3.stop()
    }
}

fun main(args: Array<String>) {
    runApplication<MailApplication>(*args)
}

data class AdminSession(val token: String)
data class WebmailSession(val token: String)
