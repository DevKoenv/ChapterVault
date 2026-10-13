package dev.koenv.chaptervault.server.di

import dev.koenv.chaptervault.infrastructure.config.AppConfig
import dev.koenv.chaptervault.infrastructure.config.ConfigLoader
import org.koin.dsl.module

val configModule =
    module {
        single<AppConfig> { ConfigLoader.load() }
    }
