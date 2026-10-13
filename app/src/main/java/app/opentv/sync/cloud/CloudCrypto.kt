/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** All portable content, including stream URLs and names, is encrypted before leaving the device. */
object CloudCrypto {
    private val random = SecureRandom()
    fun randomKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun encode(bytes: ByteArray): String = bytes.toByteString().base64Url().trimEnd('=')
    fun decode(value: String): ByteArray = requireNotNull(value.decodeBase64()).toByteArray()
    fun hash(value: ByteArray): String = encode(MessageDigest.getInstance("SHA-256").digest(value))
    fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    fun stableId(key: ByteArray, category: String, identity: String): String {
        val bytes = hmac(key, "$category\u0000$identity").copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 15) or 80).toByte()
        bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
        val b = java.nio.ByteBuffer.wrap(bytes)
        return UUID(b.long, b.long).toString()
    }
    fun seal(key: ByteArray, plaintext: String, aad: String): String =
        seal(SecretKeySpec(key, "AES"), plaintext, aad)

    fun seal(key: java.security.Key, plaintext: String, aad: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return "1.${encode(cipher.iv)}.${encode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))}"
    }
    fun open(key: ByteArray, envelope: String, aad: String): String =
        open(SecretKeySpec(key, "AES"), envelope, aad)

    fun open(key: java.security.Key, envelope: String, aad: String): String {
        val parts = envelope.split('.')
        require(parts.size == 3 && parts[0] == "1") { "Unsupported encryption format" }
        val nonce = decode(parts[1])
        require(nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(decode(parts[2])), Charsets.UTF_8)
    }
    fun pairingKeys(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    /** HKDF-SHA256; context binds both ephemeral public keys, account, and one-time request. */
    fun pairingSecret(keys: KeyPair, peer: String, context: String): ByteArray {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(peer)))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keys.private); doPhase(publicKey, true); generateSecret()
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(context.toByteArray(Charsets.UTF_8))
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(shared) }
        shared.fill(0)
        return hmac(prk, "OpenTV key pairing v1\u0001").also { prk.fill(0) }
    }
    fun verification(secret: ByteArray): String = hmac(secret, "compare on BOTH screens")
        .take(5).joinToString("") { "%02X".format(it.toInt() and 255) }
}
