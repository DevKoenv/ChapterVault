package dev.koenv.chaptervault.infrastructure.storage

import java.nio.file.Files
import java.nio.file.Path

/** Free and total bytes of the disk a path lives on. An interface so tests can pretend the disk is full. */
interface DiskSpace {
    fun usableBytes(path: Path): Long

    fun totalBytes(path: Path): Long
}

object SystemDiskSpace : DiskSpace {
    override fun usableBytes(path: Path): Long = Files.getFileStore(existing(path)).usableSpace

    override fun totalBytes(path: Path): Long = Files.getFileStore(existing(path)).totalSpace

    // getFileStore needs a path that exists; the library folder may not have been created yet
    private fun existing(path: Path): Path = generateSequence(path.toAbsolutePath()) { it.parent }.first { Files.exists(it) }
}
