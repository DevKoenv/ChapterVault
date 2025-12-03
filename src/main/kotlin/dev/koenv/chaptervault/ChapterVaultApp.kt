package dev.koenv.chaptervault

import kotlinx.coroutines.runBlocking

class ChapterVaultApp {

    fun run() = runBlocking {
        println("Hello World")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ChapterVaultApp().run()
        }
    }
}
