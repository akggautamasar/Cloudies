package app.cloudies.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials are encrypted with a non-exportable Android Keystore key. */
class ConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("connections", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("cloudies.credentials.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("cloudies.credentials.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun all(): List<Connection> {
        val value = preferences.getString("sealed", null) ?: return emptyList()
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Connection(item.getString("id"), item.getString("name"), item.getString("kind"), item.optString("endpoint"), item.optString("username"), item.optString("password"), item.optString("root"))
        }
    }
    @Synchronized fun put(connection: Connection) { write(all().filter { it.id != connection.id } + connection) }
    @Synchronized fun remove(id: String) { write(all().filter { it.id != id }) }
    private fun write(connections: List<Connection>) {
        val array = JSONArray()
        connections.forEach { item -> array.put(JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind)
            .put("endpoint", item.endpoint).put("username", item.username).put("password", item.password).put("root", item.rootId)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("sealed", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Could not save the account." }
    }
}
