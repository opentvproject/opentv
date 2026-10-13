package app.opentv.sync.cloud

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import app.opentv.sync.SyncEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CloudLocalDataTest {
    private val key = CloudCrypto.randomKey()
    private val account = "https://example.supabase.co|test-account"
    private lateinit var a: ServiceLocator.Graph
    private lateinit var b: ServiceLocator.Graph
    private lateinit var localA: CloudLocalData
    private lateinit var localB: CloudLocalData
    private lateinit var storeA: CloudStore
    private lateinit var storeB: CloudStore
    private val replica = CloudReplica(key, account, "00000000-0000-4000-8000-000000000001")
    private val selected = CloudCategory.entries.toSet()
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication() as Context
        a = ServiceLocator.Graph(context, Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build())
        b = ServiceLocator.Graph(context, Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build())
        storeA = CloudStore(context, "a-${UUID.randomUUID()}.db")
        storeB = CloudStore(context, "b-${UUID.randomUUID()}.db")
        localA = CloudLocalData(a, storeA, account, key); localB = CloudLocalData(b, storeB, account, key)
    }
    @After fun close() { a.database.close(); b.database.close(); storeA.close(); storeB.close() }
    private suspend fun transfer(type: String, deleted: Boolean = false): CloudRecord {
        val item = localA.gather(selected).first { it.item.type == type }
        val record = replica.edit(item.table, item.id, item.item, null, deleted, 100).record
        assertTrue(localB.apply(record, replica.content(record), selected))
        return record
    }
    @Test fun `real Room adapters create update deduplicate and delete Xtream providers`() = runTest {
        val source = Source(name = "First", kind = SourceKind.XTREAM, url = "https://provider.test", username = "authorized", password = "private", epgUrl = "https://guide.test?token=private", sortIndex = 4)
        val localId = a.sourceRepository.save(source)
        val original = transfer("source")
        assertEquals("private", b.database.sources().all().single().password)
        assertEquals(4, b.database.sources().all().single().sortIndex)
        localB.apply(original, replica.content(original), selected)
        assertEquals(1, b.database.sources().all().size)
        a.sourceRepository.save(source.copy(id = localId, name = "Renamed", password = "updated", url = "https://new-provider.test"))
        val edited = transfer("source")
        assertEquals(original.id, edited.id)
        assertEquals("updated", b.database.sources().all().single().password)
        transfer("source", deleted = true)
        assertTrue(b.database.sources().all().isEmpty())
    }
    @Test fun `M3U8 playlists preserve signed URL and playlist-specific configuration`() = runTest {
        a.sourceRepository.save(Source(name = "Playlist", kind = SourceKind.M3U, url = "https://playlist.test/live.m3u8?token=private", epgUrl = "https://epg.test/feed.xml", userAgent = "My TV"))
        val record = transfer("source")
        assertEquals(CloudCategory.PLAYLISTS.table, record.table)
        assertEquals("https://playlist.test/live.m3u8?token=private", b.database.sources().all().single().url)
        assertEquals("My TV", b.database.sources().all().single().userAgent)
    }
    @Test fun `changing provider kind is safe when successor arrives before old-kind tombstone`() = runTest {
        val id = a.sourceRepository.save(Source(name = "Provider", kind = SourceKind.XTREAM, url = "https://provider.test", username = "user", password = "private"))
        val original = transfer("source")
        a.sourceRepository.save(a.sourceRepository.byId(id)!!.copy(kind = SourceKind.M3U, url = "https://playlist.test/live.m3u8"))
        val replacement = transfer("source")
        assertEquals(original.id, replacement.id)
        assertTrue(localB.apply(original.copy(deleted = true), replica.content(original), selected))
        assertEquals(SourceKind.M3U, b.database.sources().all().single().kind)
    }
    @Test fun `favorites wait for catalog and propagate intentional removals`() = runTest {
        val source = a.sourceRepository.save(Source(name = "Playlist", kind = SourceKind.M3U, url = "https://playlist.test/live.m3u"))
        val channel = Channel(sourceId = source, streamId = "one", name = "One", categoryId = null, logoUrl = null, epgChannelId = null, number = null,
            streamUrl = "https://stream.test?token=private", favourite = true)
        a.database.channels().upsertAll(listOf(channel))
        transfer("source")
        val item = localA.gather(selected).first { it.item.type == "channel" }
        val record = replica.edit(item.table, item.id, item.item, null, false, 100).record
        assertFalse(localB.apply(record, item.item, selected))
        assertFalse(localB.canDelete(item.item))
        b.database.channels().upsertAll(listOf(channel.copy(sourceId = b.database.sources().all().single().id, favourite = false)))
        assertTrue(localB.apply(record, item.item, selected))
        assertTrue(b.database.channels().byStreamUrl(channel.streamUrl)!!.favourite)
        assertTrue(localB.apply(record.copy(deleted = true), item.item, selected))
        assertFalse(b.database.channels().byStreamUrl(channel.streamUrl)!!.favourite)
    }
    @Test fun `profiles rename by identity and local sync still gathers its original bundle`() = runTest {
        val profile = a.profiles.insert(Profile(name = "Person"))
        val original = transfer("profile")
        a.profiles.rename(profile, "Person renamed")
        val renamed = transfer("profile")
        assertEquals(original.id, renamed.id)
        assertEquals("Person renamed", b.profiles.all().single().name)
        assertFalse(localB.apply(renamed.copy(deleted = true), replica.content(renamed), selected))
        val oldEngine = SyncEngine(b)
        val bundle = oldEngine.gather()
        assertEquals(listOf("Person renamed"), bundle.profiles)
        assertEquals(bundle, oldEngine.decode(oldEngine.encode(bundle)))
    }
    @Test fun `settings allowlist excludes local secrets paths decoder and authentication material`() = runTest {
        val settings = localA.gather(setOf(CloudCategory.SETTINGS)).filter { it.item.type == "preference" }.map { it.item.values.getValue("key") }.toSet()
        assertEquals(setOf("theme","layout","subtitles","preview_video","preview_sound","resume_last","resize","live","movies","series"), settings)
    }
    @Test fun `movie and episode history resolve different local IDs and support deliberate deletion`() = runTest {
        val profile = a.profiles.insert(Profile(name = "Viewer")); transfer("profile")
        val source = a.sourceRepository.save(Source(name = "Provider", kind = SourceKind.XTREAM, url = "https://provider.test")); transfer("source")
        val movie = Movie(id = 10, sourceId = source, streamId = "film", name = "Film", categoryId = null, posterUrl = null, rating = null,
            year = null, plot = null, durationSeconds = 3600, containerExtension = "mp4", streamUrl = "https://stream.test/movie?token=private")
        val episode = Episode(id = 20, sourceId = source, seriesId = "show", episodeId = "one", season = 1, episodeNumber = 1,
            title = "Episode", plot = null, durationSeconds = 1800, stillUrl = null, streamUrl = "https://stream.test/episode?token=private")
        a.database.movies().upsertAll(listOf(movie)); a.database.episodes().upsertAll(listOf(episode))
        a.playbackPositions.upsert(PlaybackPosition(profile,"movie:10",12_000,3_600_000,123))
        a.playbackPositions.upsert(PlaybackPosition(profile,"ep:20",34_000,1_800_000,456))
        val items = localA.gather(selected).filter { it.item.type == "position" }
        assertEquals(2, items.size)
        for (item in items) {
            val record = replica.edit(item.table, item.id, item.item, null, false, 100).record
            assertFalse(localB.apply(record, item.item, selected))
        }
        b.database.movies().upsertAll(listOf(movie.copy(id = 80)))
        b.database.episodes().upsertAll(listOf(episode.copy(id = 90)))
        for (item in items) {
            val record = replica.edit(item.table, item.id, item.item, null, false, 100).record
            assertTrue(localB.apply(record, item.item, selected))
        }
        val history = b.playbackPositions.all().associateBy { it.mediaKey }
        assertEquals(12_000L, history.getValue("movie:80").positionMillis)
        assertEquals(456L, history.getValue("ep:90").updatedAtMillis)
        val item = items.first { it.item.values["kind"] == "movie" }
        val deletion = replica.edit(item.table, item.id, item.item, null, true, 101).record
        assertTrue(localB.apply(deletion, item.item, selected))
        assertEquals(listOf("ep:90"), b.playbackPositions.all().map { it.mediaKey })
    }
    @Test fun `encrypted journal and cursor survive reopening and cursor does not bootstrap early`() {
        val record = replica.edit(CloudCategory.PROVIDERS.table, UUID.randomUUID().toString(), CloudItem("source", mapOf("password" to "private")), null, false, 100)
        storeA.save(account, record); storeA.cursor(account, 42)
        assertFalse(storeA.initialized(account))
        storeA.markInitialized(account)
        val context = RuntimeEnvironment.getApplication() as Context
        val name = storeA.databaseName; storeA.close(); storeA = CloudStore(context, name)
        assertEquals(record, storeA.entries(account).single()); assertEquals(42, storeA.cursor(account)); assertTrue(storeA.initialized(account))
        storeA.cursor(account, 43); assertTrue(storeA.initialized(account))
        assertTrue(storeA.entries("other-account").isEmpty())
    }
}
