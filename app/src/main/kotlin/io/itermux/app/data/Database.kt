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
    val keyLabel: String? = null
) {
    fun target(modeOverride: SessionMode? = null) = Target(address, port, username, AuthMethod.valueOf(auth), modeOverride ?: SessionMode.valueOf(mode))
}

@Entity(tableName = "host_pins")
data class StoredPin(@PrimaryKey val endpoint: String, val algorithm: String, val key: String) {
    fun toPin() = HostPin(endpoint, algorithm, key)
}

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<Host>>
    @Query("SELECT * FROM hosts WHERE id = :id") suspend fun find(id: String): Host?
    @Upsert suspend fun save(host: Host)
    @Query("DELETE FROM hosts WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM host_pins WHERE endpoint = :endpoint") suspend fun pin(endpoint: String): StoredPin?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPin(pin: StoredPin): Long
    @Query("DELETE FROM host_pins WHERE endpoint = :endpoint") suspend fun forgetPin(endpoint: String)
}

@Database(entities = [Host::class, StoredPin::class], version = 1, exportSchema = true)
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
