package dev.koenv.chaptervault.infrastructure.database.repositories

import dev.koenv.chaptervault.infrastructure.config.DatabaseConfig
import dev.koenv.chaptervault.infrastructure.database.DatabaseFactory
import dev.koenv.chaptervault.infrastructure.database.entities.UserSeriesStatusTable
import dev.koenv.chaptervault.infrastructure.storage.ArchiveWriterSelector
import dev.koenv.chaptervault.infrastructure.storage.FileStorage
import dev.koenv.chaptervault.kernel.api.BackupProgress
import dev.koenv.chaptervault.kernel.api.BackupReadingStatus
import dev.koenv.chaptervault.kernel.api.Credentials
import dev.koenv.chaptervault.kernel.api.LibraryBackup
import dev.koenv.chaptervault.kernel.library.ReadingStatus
import dev.koenv.chaptervault.shared.result.Result
import dev.koenv.chaptervault.shared.utils.Id
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupRepositoryTest {
    private val backups = BackupRepository()
    private val users = UserRepository()
    private val statuses = UserSeriesStatusRepository()
    private val chapters = ChapterRepository()
    private lateinit var series: SeriesRepository
    private var alice: Id = Id.generate()
    private var bob: Id = Id.generate()
    private var seriesId: Id = Id.generate()
    private var chapterId: Id = Id.generate()

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        DatabaseFactory.init(DatabaseConfig(url = "jdbc:sqlite:${dir.resolve("backup.db")}"))
        series =
            SeriesRepository(
                FileStorage(
                    Files.createDirectories(dir.resolve("lib")),
                    Files.createDirectories(dir.resolve("thumbs")),
                    ArchiveWriterSelector(emptyList()),
                ),
            )
        alice = (users.register(Credentials("alice", "password-alice")) as Result.Success).value.id
        bob = (users.register(Credentials("bob", "password-bob")) as Result.Success).value.id
        seriesId = (series.addToLibrary("mangadex", "ext-1", "en", false) as Result.Success).value.id
        chapterId = (chapters.insertChapter(seriesId, "Chapter 1", 1.0, "ch-1") as Result.Success).value.id
    }

    private fun backup(
        progress: List<BackupProgress> = emptyList(),
        statuses: List<BackupReadingStatus> = emptyList(),
    ) = LibraryBackup(exportedAt = "2026-10-14T00:00:00Z", progress = progress, readingStatuses = statuses)

    private fun importFor(
        user: Id,
        backup: LibraryBackup,
    ) = (runBlocking { backups.importBackup(user, backup) } as Result.Success).value

    @Test
    fun `an export imports into another user and reproduces progress and status`() =
        runBlocking<Unit> {
            statuses.setStatus(alice, seriesId, ReadingStatus.READING)
            importFor(
                alice,
                backup(progress = listOf(BackupProgress("mangadex", "ext-1", "ch-1", 7, "2026-01-02T03:04:05Z"))),
            )
            val exported = (backups.exportBackup(alice, "now") as Result.Success).value

            val summary = importFor(bob, exported)

            assertEquals(1, summary.progressRestored)
            assertEquals(1, summary.statusesRestored)
            assertEquals(ReadingStatus.READING, statuses.getStatus(bob, seriesId))
            val again = (backups.exportBackup(bob, "now") as Result.Success).value
            assertEquals(7, again.progress.single().latestPage)
            assertEquals("ch-1", again.progress.single().latestChapterExternalId)
        }

    @Test
    fun `the same external id in two languages keeps separate progress`() =
        runBlocking<Unit> {
            series.addToLibrary("mangadex", "ext-1", "nl", false)
            importFor(
                alice,
                backup(
                    progress =
                        listOf(
                            BackupProgress("mangadex", "ext-1", null, 3, "2026-01-02T03:04:05Z", "en"),
                            BackupProgress("mangadex", "ext-1", null, 9, "2026-01-02T03:04:05Z", "nl"),
                        ),
                ),
            )
            val exported = (backups.exportBackup(alice, "now") as Result.Success).value

            val summary = importFor(bob, exported)

            assertEquals(2, summary.progressRestored)
            assertEquals(setOf("en" to 3, "nl" to 9), exported.progress.map { it.language to it.latestPage }.toSet())
        }

    @Test
    fun `an unknown status is skipped and never stored`() =
        runBlocking<Unit> {
            val summary = importFor(alice, backup(statuses = listOf(BackupReadingStatus("mangadex", "ext-1", "NOT_A_STATUS"))))

            assertEquals(0, summary.statusesRestored)
            assertEquals(1, summary.statusesSkipped)
            assertEquals(null, statuses.getStatus(alice, seriesId))
        }

    @Test
    fun `a negative page is skipped`() =
        runBlocking<Unit> {
            val summary = importFor(alice, backup(progress = listOf(BackupProgress("mangadex", "ext-1", null, -5, "2026-01-02T03:04:05Z"))))

            assertEquals(0, summary.progressRestored)
            assertEquals(1, summary.progressSkipped)
        }

    @Test
    fun `entries for a series that is not in the library are skipped`() =
        runBlocking<Unit> {
            val summary =
                importFor(
                    alice,
                    backup(
                        progress = listOf(BackupProgress("mangadex", "missing", null, 1, "2026-01-02T03:04:05Z")),
                        statuses = listOf(BackupReadingStatus("mangadex", "missing", "READING")),
                    ),
                )

            assertEquals(1, summary.progressSkipped)
            assertEquals(1, summary.statusesSkipped)
        }

    @Test
    fun `a bad status already in the database does not break reading the library`() =
        runBlocking<Unit> {
            val userKey = alice.toString()
            val seriesKey = seriesId.toString()
            transaction {
                UserSeriesStatusTable.insert {
                    it[UserSeriesStatusTable.userId] = userKey
                    it[UserSeriesStatusTable.seriesId] = seriesKey
                    it[UserSeriesStatusTable.status] = "NOT_A_STATUS"
                    it[UserSeriesStatusTable.updatedAt] = Instant.now().toKotlinInstant()
                }
            }

            val all = statuses.getAllStatuses(alice, listOf(seriesId))

            assertTrue(all.isEmpty())
        }
}
