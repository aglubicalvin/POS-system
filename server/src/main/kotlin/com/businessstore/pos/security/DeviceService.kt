package com.businessstore.pos.security

import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.RegisterDeviceResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * Device registration and short-lived bearer tokens.
 *
 * A till is enrolled once and receives an opaque token with a short expiry. Only
 * the SHA-256 hash of the token is stored, so a database dump does not yield live
 * credentials. Tokens are looked up by hash, which is why the column is the
 * primary key of `device_tokens`.
 */
class DeviceService(
    private val db: Database,
    private val tokenTtl: Duration = Duration.ofMinutes(15),
) {
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    data class AuthResult(val deviceId: String, val storeId: String)

    fun register(
        storeId: String,
        name: String,
        model: String?,
        requestedDeviceId: String?,
    ): RegisterDeviceResponse {
        val deviceId = requestedDeviceId ?: UUID.randomUUID().toString()
        val token = newToken()
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(tokenTtl)

        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO devices (device_id, store_id, name, model, registered_at, last_seen_at,
                                     token_expires_at, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (device_id) DO UPDATE SET
                  name = EXCLUDED.name, model = EXCLUDED.model, is_active = TRUE
                """.trimIndent(),
            ).use { st ->
                st.setString(1, deviceId)
                st.setString(2, storeId)
                st.setString(3, name)
                st.setString(4, model)
                st.setObject(5, OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC))
                st.setObject(6, OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC))
                st.setObject(7, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                st.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO device_tokens (token_hash, device_id, issued_at, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, NULL)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, hash(token))
                st.setString(2, deviceId)
                st.setObject(3, OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC))
                st.setObject(4, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                st.executeUpdate()
            }
        }

        return RegisterDeviceResponse(
            deviceId = deviceId,
            token = token,
            expiresAt = expiresAt.toString(),
        )
    }

    /** Returns the device when the token is present, unexpired, unrevoked and active. */
    fun authenticate(token: String): AuthResult? = db.read { conn ->
        val sql = """
            SELECT d.device_id, d.store_id
              FROM device_tokens t
              JOIN devices d ON d.device_id = t.device_id
             WHERE t.token_hash = ?
               AND t.revoked_at IS NULL
               AND t.expires_at > now()
               AND d.is_active = TRUE
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, hash(token))
            st.executeQuery().use { rs ->
                if (rs.next()) AuthResult(rs.getString("device_id"), rs.getString("store_id")) else null
            }
        }
    }

    fun touch(deviceId: String) {
        db.transaction { conn ->
            conn.prepareStatement("UPDATE devices SET last_seen_at = now() WHERE device_id = ?").use { st ->
                st.setString(1, deviceId)
                st.executeUpdate()
            }
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
