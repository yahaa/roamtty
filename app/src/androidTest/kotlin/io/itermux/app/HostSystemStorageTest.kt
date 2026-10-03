package io.itermux.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.AppDatabase
import io.itermux.app.data.Host
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HostSystemStorageTest {
    @Test fun metadataSurvivesReopenAndNeverOverwritesEditedOrDeletedHosts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "host-system-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val original = Host(name = "Server", address = "old.example", username = "dev", os = "LINUX")
        try {
            database.hosts().save(original)
            database.hosts().updateSystem(original.id, original.address, original.port, original.username, "DEBIAN")
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals("DEBIAN", database.hosts().find(original.id)?.os)
            database.hosts().save(original.copy(name = "Edited", address = "new.example", os = "WINDOWS"))
            database.hosts().updateSystem(original.id, original.address, original.port, original.username, "UBUNTU")
            assertEquals("WINDOWS", database.hosts().find(original.id)?.os)
            assertEquals("Edited", database.hosts().find(original.id)?.name)
            database.hosts().delete(original.id)
            database.hosts().updateSystem(original.id, original.address, original.port, original.username, "UBUNTU")
            assertNull(database.hosts().find(original.id))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
