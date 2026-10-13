package app.opentv.sync.cloud

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CloudReplicaTest {
    private val key = CloudCrypto.randomKey()
    private val account = "https://example.supabase.co|account-one"
    private val first = "00000000-0000-4000-8000-000000000001"
    private val second = "00000000-0000-4000-8000-000000000002"
    private val a = CloudReplica(key, account, first)
    private val b = CloudReplica(key, account, second)
    private val provider = CloudItem("source", mapOf("name" to "Authorized provider", "url" to "https://example.test/get.php?token=private", "password" to "secret"))
    private val table = CloudCategory.PROVIDERS.table
    private val id = CloudCrypto.stableId(key, table, "provider-one")

    @Test fun `two devices create edit and delete the same provider without duplicates`() {
        val original = a.edit(table, id, provider, null, false, 100)
        var receiver = b.merge(null, original.record)
        assertEquals(provider, b.content(receiver.record))
        val update = b.edit(table, id, provider.copy(values = provider.values + ("password" to "new-password")), receiver, false, 200)
        receiver = a.merge(original, update.record)
        assertEquals("new-password", a.content(receiver.record).values["password"])
        val deleted = a.edit(table, id, a.content(receiver.record), receiver, true, 300)
        assertTrue(b.merge(update, deleted.record).record.deleted)
        assertTrue(b.merge(b.merge(update, deleted.record), original.record).record.deleted)
        assertEquals(deleted.record.id, original.record.id)
    }
    @Test fun `offline edits converge by clock and device tie break`() {
        val original = a.edit(table, id, provider, null, false, 100)
        val left = a.edit(table, id, provider.copy(values = mapOf("name" to "left")), original, false, 200)
        val right = b.edit(table, id, provider.copy(values = mapOf("name" to "right")), original, false, 200)
        assertEquals(right.record, a.merge(left, right.record).record)
        assertEquals(right.record, b.merge(right, left.record).record)
        assertTrue(right.pending)
        assertFalse(b.merge(right, right.record).pending)
    }
    @Test fun `clock remains monotonic if wall clock goes backwards`() {
        val before = a.edit(table, id, provider, null, false, 1000)
        assertEquals(1001, a.edit(table, id, provider, before, true, 10).record.clock)
    }
    @Test fun `delayed playback capture cannot overwrite a newer viewing event`() {
        val table = CloudCategory.WATCH.table
        val newer = CloudItem("position", mapOf("updated" to "300", "position" to "9000"))
        val old = CloudItem("position", mapOf("updated" to "200", "position" to "1000"))
        val current = b.edit(table, id, newer, null, false, 500)
        val delayed = a.edit(table, id, old, current, false, 1000)
        assertEquals(300L, current.record.clock)
        assertEquals(200L, delayed.record.clock)
        assertEquals(current.record, b.merge(current, delayed.record).record)
        assertEquals(501L, a.edit(table, id, old, current, true, 501).record.clock)
    }
    @Test fun `recapturing a playback event after profile rename keeps its encrypted revision`() {
        val table = CloudCategory.WATCH.table
        val item = CloudItem("position", mapOf("updated" to "200", "profile_name" to "Before"))
        val original = a.edit(table, id, item, null, false, 500)
        val renamed = item.copy(values = item.values + ("profile_name" to "After"))
        val recaptured = a.edit(table, id, renamed, original, false, 1000)
        assertEquals(original.record, recaptured.record)
        assertEquals(a.digest(renamed), recaptured.baseline)
        assertFalse(a.merge(recaptured, original.record).pending)
    }
    @Test fun `replayed events and acknowledgements are idempotent`() {
        val entry = a.edit(table, id, provider, null, false, 100)
        val acknowledged = a.merge(entry, entry.record)
        assertFalse(acknowledged.pending)
        assertEquals(acknowledged, a.merge(acknowledged, entry.record))
        assertEquals(acknowledged.record, b.merge(b.merge(null, entry.record), entry.record).record)
    }
    @Test fun `ciphertext hides URLs passwords and names and uses fresh nonces`() {
        val one = a.edit(table, id, provider, null, false, 100).record
        val two = a.edit(table, id, provider, null, false, 100).record
        assertNotEquals(one.ciphertext, two.ciphertext)
        assertFalse(one.ciphertext.contains("secret"))
        assertFalse(one.ciphertext.contains("example.test"))
        assertEquals(provider, a.content(one))
    }
    @Test fun `tampered metadata ciphertext and cross account replay fail authentication`() {
        val record = a.edit(table, id, provider, null, false, 100).record
        for (tampered in listOf(record.copy(deleted = true), record.copy(id = UUID.randomUUID().toString()),
            record.copy(clock = 200), record.copy(writer = second), record.copy(table = CloudCategory.FAVORITES.table))) {
            assertThrows(Exception::class.java) { a.content(tampered) }
        }
        assertThrows(Exception::class.java) { CloudReplica(key, "other-account", second).content(record) }
        assertThrows(Exception::class.java) { CloudReplica(CloudCrypto.randomKey(), account, second).content(record) }
        val parts = record.ciphertext.split('.').toMutableList()
        val cipher = CloudCrypto.decode(parts[2]); cipher[0] = (cipher[0].toInt() xor 1).toByte(); parts[2] = CloudCrypto.encode(cipher)
        assertThrows(Exception::class.java) { a.content(record.copy(ciphertext = parts.joinToString("."))) }
    }
    @Test fun `favorites and watch history use the same deletion safe merge`() {
        for (category in listOf(CloudCategory.FAVORITES, CloudCategory.WATCH, CloudCategory.PROFILES, CloudCategory.SETTINGS, CloudCategory.PLAYLISTS)) {
            val payload = CloudItem("test", mapOf("position" to "4321"))
            val original = a.edit(category.table, id, payload, null, false, 100)
            assertEquals(payload, b.content(b.merge(null, original.record).record))
            val deletion = b.edit(category.table, id, payload, b.merge(null, original.record), true, 101)
            assertTrue(a.merge(original, deletion.record).record.deleted)
        }
    }
    @Test fun `canonical maps and keyed identities prevent duplicate creation or plaintext hashes`() {
        assertEquals(a.digest(CloudItem("test", linkedMapOf("a" to "1", "b" to "2"))), a.digest(CloudItem("test", linkedMapOf("b" to "2", "a" to "1"))))
        assertEquals(id, CloudCrypto.stableId(key, table, "provider-one"))
        assertNotEquals(id, CloudCrypto.stableId(CloudCrypto.randomKey(), table, "provider-one"))
    }
    @Test fun `pairing derives matching keys and verification codes and binds transcript`() {
        val left = CloudCrypto.pairingKeys(); val right = CloudCrypto.pairingKeys()
        val context = "$account|request-id|${CloudCrypto.encode(left.public.encoded)}|${CloudCrypto.encode(right.public.encoded)}"
        val one = CloudCrypto.pairingSecret(left, CloudCrypto.encode(right.public.encoded), context)
        val two = CloudCrypto.pairingSecret(right, CloudCrypto.encode(left.public.encoded), context)
        assertArrayEquals(one, two)
        assertEquals(CloudCrypto.verification(one), CloudCrypto.verification(two))
        val wrapped = CloudCrypto.seal(one, CloudCrypto.encode(key), context)
        assertArrayEquals(key, CloudCrypto.decode(CloudCrypto.open(two, wrapped, context)))
        assertThrows(Exception::class.java) { CloudCrypto.open(two, wrapped, "$context-other-request") }
    }
}
