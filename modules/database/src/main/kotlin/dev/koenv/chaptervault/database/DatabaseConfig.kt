package dev.koenv.chaptervault.database

import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

/**
 * Database configuration and initialization
 */
object DatabaseConfig {
    
    /**
     * Initialize database connection
     * Uses H2 file-based database by default
     */
    fun initialize(dataDir: File): Database {
        dataDir.mkdirs()
        val dbFile = File(dataDir, "chaptervault")
        
        return Database.connect(
            url = "jdbc:h2:file:${dbFile.absolutePath};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
    }
    
    /**
     * Initialize in-memory database for testing
     */
    fun initializeInMemory(): Database {
        return Database.connect(
            url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
    }
}
