package dev.koenv.chaptervault.kernel.extension

sealed class Capability {
    data object CanFetchSeries : Capability()

    data object CanDownloadChapters : Capability()

    data object CanEnrichMetadata : Capability()
}
