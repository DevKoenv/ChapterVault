package dev.koenv.chaptervault.server.di

import dev.koenv.chaptervault.infrastructure.PersistingTaskQueue
import dev.koenv.chaptervault.kernel.api.SystemApi
import dev.koenv.chaptervault.kernel.api.impl.SystemApiImpl
import dev.koenv.chaptervault.kernel.event.EventBus
import dev.koenv.chaptervault.kernel.event.InMemoryEventBus
import dev.koenv.chaptervault.kernel.extension.DefaultExtensionRegistry
import dev.koenv.chaptervault.kernel.extension.ExtensionRegistry
import dev.koenv.chaptervault.kernel.runtime.EventPublishingTaskQueue
import dev.koenv.chaptervault.kernel.runtime.InMemoryTaskQueue
import dev.koenv.chaptervault.kernel.runtime.TaskQueue
import org.koin.dsl.module

val kernelModule =
    module {
        single<EventBus> { InMemoryEventBus() }
        single<ExtensionRegistry> { DefaultExtensionRegistry() }
        single<TaskQueue> { PersistingTaskQueue(EventPublishingTaskQueue(InMemoryTaskQueue(), get()), get()) }
        single<SystemApi> { SystemApiImpl(get(), get(), get(), get()) }
    }
