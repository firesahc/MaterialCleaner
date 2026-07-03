package me.gm.cleaner.core.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.CharConversionException
import java.io.File
import java.security.Provider
import java.security.Security

@SuppressLint("StaticFieldLeak")
object SecurityHelper {
    private const val TAG = "SecurityHelper"
    private lateinit var context: Context
    private lateinit var masterKey: MasterKey

    fun init(context: Context) {
        this.context = context.applicationContext
        try {
            masterKey = MasterKey(context)
        } catch (e: Exception) {
            Log.w(TAG, "MasterKey creation failed", e)
        }
    }

    fun isInitSuccess(): Boolean = ::masterKey.isInitialized

    @SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi")
    fun warmUpJcaProviders() {
        val androidKeyStoreProviderClass = try {
            Class.forName("android.security.keystore2.AndroidKeyStoreProvider")
        } catch (e: ClassNotFoundException) {
            Class.forName("android.security.keystore.AndroidKeyStoreProvider")
        }
        androidKeyStoreProviderClass.getDeclaredMethod("install")
            .invoke(null)

        val warmUpServiceProvisionMethod = Provider::class.java
            .getDeclaredMethod("warmUpServiceProvision")
        Security.getProviders().forEach { provider ->
            warmUpServiceProvisionMethod.invoke(provider)
        }
    }

    fun encryptedFile(file: File, keysetPrefName: String? = null): EncryptedFile =
        EncryptedFile(context, file, masterKey, keysetPrefName = keysetPrefName)

    fun EncryptedFile.write(b: ByteArray) = openFileOutput().use { outputStream ->
        outputStream.write(b)
        outputStream.flush()
    }

    fun EncryptedFile.read(): ByteArrayOutputStream = openFileInput().use { inputStream ->
        val byteArrayOutputStream = ByteArrayOutputStream()
        var nextByte = inputStream.read()
        while (nextByte != -1) {
            byteArrayOutputStream.write(nextByte)
            nextByte = inputStream.read()
        }
        byteArrayOutputStream
    }

    fun encryptedSharedPreferences(fileName: String, retryCount: Int = 0): SharedPreferences =
        if (isInitSuccess()) {
            try {
                EncryptedSharedPreferences(context, fileName, masterKey)
            } catch (e: CharConversionException) {
                if (retryCount < 1 && context.deleteSharedPreferences(fileName)) {
                    encryptedSharedPreferences(fileName, retryCount + 1)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            }
        } else {
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
}
