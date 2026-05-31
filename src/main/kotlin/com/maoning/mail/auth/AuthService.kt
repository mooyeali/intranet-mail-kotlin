package com.maoning.mail.auth

import com.maoning.mail.security.LoginRateLimiter
import com.maoning.mail.store.MailStore
import com.maoning.mail.store.Session
import com.maoning.mail.store.User
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.Base64

class AuthService(
    private val store: MailStore,
    private val loginRateLimiter: LoginRateLimiter? = null
) {
    private val random = SecureRandom()

    fun register(username: String, password: String): User {
        require(username.matches(Regex("^[a-zA-Z0-9._-]{3,32}$"))) {
            "Username must be 3-32 chars: letters, digits, dot, underscore or hyphen"
        }
        require(password.length >= 8) { "Password must be at least 8 chars" }
        val mailbox = store.normalizeMailbox(username)
        val hash = BCrypt.hashpw(password, BCrypt.gensalt(12))
        return store.createUser(User(username = username, mailbox = mailbox, passwordHash = hash))
    }

    fun login(mailboxOrUsername: String, password: String, ip: String? = null): Session {
        if (ip != null) loginRateLimiter?.assertAllowed(mailboxOrUsername, ip)
        return runCatching {
            val user = store.findUser(mailboxOrUsername) ?: error("Invalid credentials")
            if (!BCrypt.checkpw(password, user.passwordHash)) error("Invalid credentials")
            val token = newToken()
            store.saveSession(Session(token = token, mailbox = user.mailbox))
        }.onSuccess {
            if (ip != null) loginRateLimiter?.record(mailboxOrUsername, ip, true)
        }.onFailure {
            if (ip != null) loginRateLimiter?.record(mailboxOrUsername, ip, false)
        }.getOrThrow()
    }

    fun authenticate(bearer: String?): Session? {
        val token = bearer?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return store.findSession(token)
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
