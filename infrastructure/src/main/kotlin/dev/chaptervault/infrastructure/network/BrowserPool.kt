package dev.chaptervault.infrastructure.network

class BrowserPool {
    fun acquire(): BrowserHandle = TODO("BrowserPool not yet implemented")
    fun release(handle: BrowserHandle) = Unit
}

class BrowserHandle
