package org.example.musicplugin.api

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object NeteaseCrypto {
    private const val KEY1 = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val PUB_E = "010001"
    private const val PUB_N =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725" +
        "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312" +
        "ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424" +
        "d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    data class WeApiPayload(val params: String, val encSecKey: String)

    fun encrypt(jsonText: String): WeApiPayload {
        val secretKey = randomKey()
        val params1 = aesEncrypt(jsonText, KEY1)
        val params2 = aesEncrypt(params1, secretKey)
        val encSecKey = rsaEncrypt(secretKey)
        return WeApiPayload(params2, encSecKey)
    }

    private fun randomKey(): String {
        val sr = SecureRandom()
        return buildString(16) { repeat(16) { append(ALPHABET[sr.nextInt(ALPHABET.length)]) } }
    }

    private fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun rsaEncrypt(secretKey: String): String {
        val reversed = secretKey.reversed().toByteArray(StandardCharsets.UTF_8)
        val keyHex = reversed.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val m = BigInteger(keyHex, 16)
        val e = BigInteger(PUB_E, 16)
        val n = BigInteger(PUB_N, 16)
        val result = m.modPow(e, n)
        return result.toString(16).padStart(256, '0')
    }
}
