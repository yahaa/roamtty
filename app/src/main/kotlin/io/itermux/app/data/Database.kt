package io.itermux.app.data

import android.content.Context
import androidx.room.*
import io.itermux.ssh.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "hosts")
data class Host(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val auth: String = AuthMethod.PASSWORD.name,
    val mode: String = SessionMode.TMUX.name,
    val encryptedPassword: ByteArray? = null,
    val encryptedKey: ByteArray? = null,
    val keyLabel: String? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val os: String = "UNKNOWN",
    /** Installed plugins as [HostPlugin.encode]; null until the host has been probed. */
    val plugins: String? = null,
    /** The desktop probe: yes, no or nologin ([io.itermux.desktop.DesktopProbe]); null until probed. */
    val desktop: String? = null,
    /** When [desktop] was probed, epoch milliseconds. */
    val desktopProbedAt: Long? = null,
) {
    fun target(modeOverride: SessionMode? = null) = Target(address, port, username, AuthMethod.valueOf(auth), modeOverride ?: SessionMode.valueOf(mode))
}

val Host.installedPlugins: Set<HostPlugin>? get() = HostPlugin.decode(plugins)
/** New sessions use tmux when the host has it; before the first probe the saved mode applies. */
/** What keeps this host's resumable sessions: psmux on a native Windows shell, else tmux. */
val Host.multiplexer: HostPlugin get() =
    if (installedPlugins?.let { HostPlugin.PSMUX in it && HostPlugin.TMUX !in it } == true) HostPlugin.PSMUX else HostPlugin.TMUX
val Host.newSessionMode: SessionMode get() = installedPlugins?.let { if (it.any(HostPlugin::keepsSessions)) SessionMode.TMUX else SessionMode.SHELL }
    ?: SessionMode.valueOf(mode)
/** Terminals prefer mosh whenever the host has mosh-server. */
val Host.prefersMosh: Boolean get() = installedPlugins?.contains(HostPlugin.MOSH) == true

@Entity(tableName = "host_pins")
data class StoredPin(@PrimaryKey val endpoint: String, val algorithm: String, val key: String) {
    fun toPin() = HostPin(endpoint, algorithm, key)
}

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<Host>>
    @Query("SELECT * FROM hosts WHERE id = :id") suspend fun find(id: String): Host?
    @Upsert suspend fun save(host: Host)
    // A probe for an old address must neither overwrite an edited host nor resurrect a deleted one.
    @Query("UPDATE hosts SET os = :os WHERE id = :id AND address = :address AND port = :port AND username = :username")
    suspend fun updateSystem(id: String, address: String, port: Int, username: String, os: String)
    @Query("UPDATE hosts SET plugins = :plugins WHERE id = :id AND address = :address AND port = :port AND username = :username")
    suspend fun updatePlugins(id: String, address: String, port: Int, username: String, plugins: String)
    @Query("UPDATE hosts SET desktop = :desktop, desktopProbedAt = :at WHERE id = :id AND address = :address AND port = :port AND username = :username")
    suspend fun updateDesktop(id: String, address: String, port: Int, username: String, desktop: String, at: Long)
    @Query("DELETE FROM hosts WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM host_pins WHERE endpoint = :endpoint") suspend fun pin(endpoint: String): StoredPin?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPin(pin: StoredPin): Long
    @Query("DELETE FROM host_pins WHERE endpoint = :endpoint") suspend fun forgetPin(endpoint: String)
}

@Database(entities = [Host::class, StoredPin::class], version = 4, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)], exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "itermux.db").build()
    }
}

class RoomPinStore(private val database: AppDatabase) : PinStore {
    override suspend fun get(endpoint: String) = database.hosts().pin(endpoint)?.toPin()
    override suspend fun putIfAbsent(pin: HostPin): HostPin = database.withTransaction {
        database.hosts().insertPin(StoredPin(pin.endpoint, pin.algorithm, pin.key))
        requireNotNull(database.hosts().pin(pin.endpoint)).toPin()
    }
}
