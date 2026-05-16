package dev.koenv.chaptervault.server

import org.koin.core.context.startKoin

object DependencyInjection {
    fun start() {
        startKoin {
            modules(allModules)
        }
    }
}
