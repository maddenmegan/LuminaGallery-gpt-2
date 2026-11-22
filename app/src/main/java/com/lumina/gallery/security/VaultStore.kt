package com.lumina.gallery.security

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class VaultStore(private val context: Context) {
    private val directory = File(context.filesDir, "vault").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("lumina_vault", Context.MODE_PRIVATE)
    data class Entry(val file: File, val originalName: String)

    fun hasPin(): Boolean = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        require(pin.length in 4..12 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val digest = pinDigest(pin, salt)
        prefs.edit().putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(digest, Base64.NO_WRAP)).apply()
    }

    fun verifyPin(pin: String): Boolean = runCatching {
        val salt = Base64.decode(prefs.getString("pin_salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("pin_hash", ""), Base64.NO_WRAP)
        MessageDigest.isEqual(expected, pinDigest(pin, salt))
    }.getOrDefault(false)

    private fun pinDigest(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("lumina_vault_key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                "lumina_vault_key",
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun add(uri: Uri, name: String): Boolean = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val output = File(directory, "${System.currentTimeMillis()}-${name.hashCode()}.vault")
        input.use { source ->
            output.outputStream().use { raw ->
                raw.write(cipher.iv)
                CipherOutputStream(raw, cipher).use { encrypted -> source.copyTo(encrypted) }
            }
        }
        prefs.edit().putString(output.name, name).apply()
        true
    }.getOrDefault(false)

    fun entries(): List<Entry> = directory.listFiles()?.filter { it.isFile && it.extension == "vault" }
        ?.map { Entry(it, prefs.getString(it.name, it.name) ?: it.name) }?.sortedByDescending { it.file.lastModified() }.orEmpty()

    fun export(entry: Entry): File? = runCatching {
        val safeName = entry.originalName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "vault-export.bin" }
        val output = File(context.cacheDir, safeName)
        entry.file.inputStream().use { raw ->
            val iv = ByteArray(12)
            require(raw.read(iv) == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, encryptionKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            }
            CipherInputStream(raw, cipher).use { decrypted -> output.outputStream().use { decrypted.copyTo(it) } }
        }
        output
    }.getOrNull()

    fun delete(entry: Entry) {
        entry.file.delete()
        prefs.edit().remove(entry.file.name).apply()
    }
}
