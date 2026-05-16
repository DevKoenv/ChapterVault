package dev.koenv.chaptervault.kernel.extension

sealed class Capability {
    data object CanFetchSeries : Capability()
    data object CanDownloadChapters : Capability()
    data object CanServeOpds : Capability()
    data object CanEnrichMetadata : Capability()
    data object CanServeAdmin : Capability()
}
