/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Keystore wrapping key never leaves this install; ciphertext is deliberately excluded from backup. */
interface CloudSecrets {
    fun put(name: String, value: String?)
    fun get(name: String): String?
}

class CloudVault(context: Context) : CloudSecrets {
    private val prefs = context.getSharedPreferences("opentv_cloud_vault", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("opentv.cloud.wrap.v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("opentv.cloud.wrap.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    @Synchronized override fun put(name: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(name) else editor.putString(name, CloudCrypto.seal(key, value, name))
        check(editor.commit()) { "Could not save the cloud session securely" }
    }
    @Synchronized override fun get(name: String): String? = prefs.getString(name, null)?.let { CloudCrypto.open(key, it, name) }
}

/** Durable encrypted outbox and remote inbox. Independent of catalogue rebuilds and LAN/NAS bundles. */
class CloudStore(context: Context, name: String = "opentv-cloud.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (account TEXT NOT NULL, category TEXT NOT NULL, id TEXT NOT NULL, entry TEXT NOT NULL, PRIMARY KEY(account,category,id))")
        db.execSQL("CREATE TABLE bindings (account TEXT NOT NULL, category TEXT NOT NULL, local_id TEXT NOT NULL, cloud_id TEXT NOT NULL, PRIMARY KEY(account,category,local_id), UNIQUE(account,category,cloud_id))")
        db.execSQL("CREATE TABLE cursors (account TEXT PRIMARY KEY NOT NULL, sequence INTEGER NOT NULL, initialized INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unknown cloud journal version")
    fun entries(account: String): List<CloudEntry> = readableDatabase.rawQuery("SELECT entry FROM records WHERE account=?", arrayOf(account)).use { c ->
        buildList { while (c.moveToNext()) add(CloudReplica.json.decodeFromString<CloudEntry>(c.getString(0))) }
    }
    fun save(account: String, entry: CloudEntry) {
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply {
            put("account", account); put("category", entry.record.table); put("id", entry.record.id)
            put("entry", CloudReplica.json.encodeToString(CloudEntry.serializer(), entry))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun initialized(account: String): Boolean = readableDatabase.rawQuery("SELECT initialized FROM cursors WHERE account=?", arrayOf(account)).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun markInitialized(account: String) { writableDatabase.execSQL("UPDATE cursors SET initialized=1 WHERE account=?", arrayOf(account)) }
    fun cursor(account: String): Long = readableDatabase.rawQuery("SELECT sequence FROM cursors WHERE account=?", arrayOf(account)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    fun cursor(account: String, sequence: Long) {
        writableDatabase.insertWithOnConflict("cursors", null, ContentValues().apply { put("account", account); put("sequence", sequence); put("initialized", if (initialized(account)) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun cloudId(account: String, category: String, localId: String): String? = readableDatabase.rawQuery(
        "SELECT cloud_id FROM bindings WHERE account=? AND category=? AND local_id=?", arrayOf(account, category, localId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }
    fun localId(account: String, category: String, cloudId: String): String? = readableDatabase.rawQuery(
        "SELECT local_id FROM bindings WHERE account=? AND category=? AND cloud_id=?", arrayOf(account, category, cloudId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }
    fun bind(account: String, category: String, localId: String, cloudId: String) {
        writableDatabase.insertWithOnConflict("bindings", null, ContentValues().apply {
            put("account", account); put("category", category); put("local_id", localId); put("cloud_id", cloudId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun transaction(block: () -> Unit) {
        val db = writableDatabase; db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
