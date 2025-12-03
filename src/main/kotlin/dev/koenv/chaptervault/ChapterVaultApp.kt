package dev.koenv.chaptervault

import dev.koenv.chaptervault.core.Logger
import kotlinx.coroutines.runBlocking

class ChapterVaultApp {

    fun run() = runBlocking {
        Logger.logToFile(false)

        Logger.info("App started", tag = "Main")

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ChapterVaultApp().run()
        }
    }
}
