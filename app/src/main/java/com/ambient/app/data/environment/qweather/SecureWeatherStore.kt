package com.ambient.app.data.environment.qweather

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES key stays in Android Keystore; the imported signing key is encrypted at rest.
 * Ed25519 signing itself runs in app memory to support Android 8+ devices.
 * Call on IO, never from a composition or the main thread.
 */
interface WeatherStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
}

class SecureWeatherStore(context: Context) : WeatherStore {
    private val directory = File(context.noBackupFilesDir, "weather").apply { mkdirs() }
    private val alias = "ambient.weather.storage.v1"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Storage key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    override fun read(name: String): String? {
        val file = AtomicFile(File(directory, name))
        // AtomicFile must get the chance to recover an interrupted write's backup on older Android.
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return null }
        check(bytes.size in 29..1048576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }

    override fun write(name: String, text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    override fun delete(name: String) = AtomicFile(File(directory, name)).delete()
}
