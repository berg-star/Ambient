package com.ambient.app.data.environment.qweather

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64

/** Never implement toString() with fields: neither keys nor IDs belong in logs. */
class QWeatherCredentials(
    val developerId: String,
    val projectId: String,
    val credentialId: String,
    val apiHost: String,
    val privateKeyPem: String,
) {
    fun validate() {
        require(apiHost.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.qweatherapi\\.com")))
        require(developerId.matches(Regex("Q[A-Za-z0-9]{9}")))
        require(listOf(projectId, credentialId).all { it.matches(Regex("[A-Za-z0-9]{5,64}")) })
        decodeKey() // Validate before replacing the previous working credentials.
    }

    fun decodeKey(): EdDSAPrivateKey {
        require(privateKeyPem.trim().startsWith("-----BEGIN PRIVATE KEY-----"))
        require(privateKeyPem.trim().endsWith("-----END PRIVATE KEY-----"))
        val encoded = privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
        return EdDSAPrivateKey(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }

    fun encode(): String = JsonObject().apply {
        addProperty("developerId", developerId)
        addProperty("projectId", projectId)
        addProperty("credentialId", credentialId)
        addProperty("apiHost", apiHost)
        addProperty("privateKeyPem", privateKeyPem)
    }.toString()

    companion object {
        fun parse(json: String, pem: String? = null): QWeatherCredentials {
            require(json.length <= 65536 && (pem?.length ?: 0) <= 16384)
            val o = JsonParser.parseString(json.trimStart('\uFEFF')).asJsonObject
            return QWeatherCredentials(
                o.get("developerId").asString.trim(), o.get("projectId").asString.trim(),
                o.get("credentialId").asString.trim(), o.get("apiHost").asString.trim().lowercase(),
                pem ?: o.get("privateKeyPem").asString,
            ).also { it.validate() }
        }
    }
}

class QWeatherJwt(private val credentials: QWeatherCredentials, private val clock: Clock = Clock.systemUTC()) {
    fun create(): String {
        val now = clock.instant().epochSecond
        val header = JsonObject().apply {
            addProperty("alg", "EdDSA"); addProperty("kid", credentials.credentialId)
        }
        val payload = JsonObject().apply {
            addProperty("iss", credentials.developerId); addProperty("sub", credentials.projectId)
            addProperty("iat", now - 30); addProperty("exp", now + 600)
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val input = encoder.encodeToString(header.toString().toByteArray(Charsets.UTF_8)) + "." +
            encoder.encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        val signer = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        signer.initSign(credentials.decodeKey())
        signer.update(input.toByteArray(Charsets.UTF_8))
        return input + "." + encoder.encodeToString(signer.sign())
    }
}
