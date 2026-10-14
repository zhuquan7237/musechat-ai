package io.github.zhuquan7237.musechat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "messages", indices = [Index("sessionId")], foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class MessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String, val kind: String = "wire", val wire: String = "", val text: String = "", val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "images", indices = [Index("sessionId")], foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class ImageEntity(@PrimaryKey val id: String, val sessionId: String, val toolCallId: String, val path: String, val prompt: String, val model: String, val parameters: String, val width: Int, val height: Int, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "model_configs")
data class ConfigEntity(@PrimaryKey val id: String, val json: String)
@Dao
interface MuseDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC") fun sessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions") suspend fun allSessions(): List<SessionEntity>
    @Insert suspend fun insertSession(session: SessionEntity)
    @Query("UPDATE sessions SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)
    @Query("SELECT * FROM messages WHERE sessionId = :id ORDER BY id") fun messages(id: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :id ORDER BY id") suspend fun history(id: String): List<MessageEntity>
    @Insert suspend fun insertMessage(message: MessageEntity)
    @Query("SELECT * FROM images ORDER BY createdAt DESC") fun images(): Flow<List<ImageEntity>>
    @Query("SELECT * FROM images WHERE sessionId = :id") suspend fun sessionImages(id: String): List<ImageEntity>
    @Insert suspend fun insertImages(images: List<ImageEntity>)
    @Query("DELETE FROM images WHERE id = :id") suspend fun deleteImage(id: String)
    @Query("SELECT * FROM model_configs") fun configs(): Flow<List<ConfigEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveConfig(config: ConfigEntity)
    @Query("DELETE FROM model_configs WHERE id = :id") suspend fun deleteConfig(id: String)
}
@Database(entities = [SessionEntity::class, MessageEntity::class, ImageEntity::class, ConfigEntity::class], version = 1, exportSchema = true)
abstract class MuseDatabase: RoomDatabase() { abstract fun dao(): MuseDao }
