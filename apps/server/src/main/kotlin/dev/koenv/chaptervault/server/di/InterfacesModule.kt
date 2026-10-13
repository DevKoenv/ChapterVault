package dev.koenv.chaptervault.server.di

import dev.koenv.chaptervault.interfaces.api.websocket.EventProjectionService
import org.koin.dsl.module

val interfacesModule =
    module {
        single { EventProjectionService(get()) }
    }
