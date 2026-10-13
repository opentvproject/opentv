/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.sync.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CloudCategory(val table: String) {
    PROVIDERS("sync_providers"), PLAYLISTS("sync_playlists"), FAVORITES("sync_favorites"),
    WATCH("sync_watch_progress"), PROFILES("sync_profiles"), SETTINGS("sync_settings");
    companion object { fun fromTable(table: String) = entries.first { it.table == table } }
}

@Serializable
data class CloudItem(val type: String, val values: Map<String, String>)

@Serializable
data class CloudRecord(
    @SerialName("table_name") val table: String,
    val id: String,
    val clock: Long,
    @SerialName("writer_device_id") val writer: String,
    val deleted: Boolean,
    val ciphertext: String,
    @SerialName("schema_version") val version: Int = 1,
    @SerialName("change_seq") val sequence: Long = 0,
) {
    fun aad(account: String) = "$account|$table|$id|$clock|$writer|$deleted|$version"
    fun newerThan(other: CloudRecord) = clock > other.clock || (clock == other.clock && writer > other.writer)
}

@Serializable
data class CloudEntry(
    val record: CloudRecord,
    /** Keyed digest of the last content successfully materialized locally; no plaintext URLs. */
    val baseline: String? = null,
    val pending: Boolean = false,
    val applied: Boolean = false,
)

/** Pure deterministic reconciliation, shared by real devices and the two-device tests. */
class CloudReplica(private val key: ByteArray, private val account: String, private val device: String) {
    companion object { val json = Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    fun content(record: CloudRecord): CloudItem {
        require(record.version == 1) { "Update OpenTV to read this cloud schema" }
        return json.decodeFromString(CloudCrypto.open(key, record.ciphertext, record.aad(account)))
    }
    fun digest(item: CloudItem): String = CloudCrypto.encode(CloudCrypto.hmac(key, encode(item)))
    fun encode(item: CloudItem): String = json.encodeToString(CloudItem.serializer(), item.copy(values = item.values.toSortedMap()))
    fun edit(table: String, id: String, item: CloudItem, old: CloudEntry?, deleted: Boolean, now: Long): CloudEntry {
        // Playback updates retain their event time even when captured long after watching.
        // Deliberate removals and other edits still advance the existing logical clock.
        val clock = if (table == CloudCategory.WATCH.table && item.type == "position" && !deleted) {
            item.values.getValue("updated").toLong()
        } else maxOf(now, (old?.record?.clock ?: 0) + 1)
        if (old != null && old.record.clock == clock && old.record.writer == device && !old.record.deleted && !deleted) {
            // Re-capturing the same viewing event (e.g. after a profile rename) must retain
            // its encrypted revision so the server acknowledgement remains idempotent.
            return old.copy(baseline = digest(item), applied = true)
        }
        val record = CloudRecord(table, id, clock, device, deleted, "")
        return CloudEntry(
            record.copy(ciphertext = CloudCrypto.seal(key, encode(item), record.aad(account))),
            if (deleted) null else digest(item), pending = true, applied = true,
        )
    }
    fun merge(local: CloudEntry?, remote: CloudRecord): CloudEntry {
        content(remote) // Authenticate before accepting a record, even a tombstone.
        return when {
            local == null || remote.newerThan(local.record) -> CloudEntry(remote, baseline = local?.baseline)
            remote.clock == local.record.clock && remote.writer == local.record.writer -> {
                require(remote.ciphertext == local.record.ciphertext && remote.deleted == local.record.deleted)
                local.copy(pending = false)
            }
            else -> local
        }
    }
}
