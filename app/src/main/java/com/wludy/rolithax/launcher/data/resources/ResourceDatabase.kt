package com.wludy.rolithax.launcher.data.resources

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "resource_items", primaryKeys = ["queryKey", "position"])
data class ResourceItemEntity(
    val queryKey: String,
    val position: Int,
    val id: String,
    val name: String,
    val summary: String,
    val author: String,
    val iconUrl: String?,
    val downloads: Long,
    val updatedAt: String,
    val type: String,
    val loaders: String,
    val gameVersions: String,
    val serverCompatible: Boolean,
    val clientCompatible: Boolean,
)

@Entity(tableName = "resource_sync")
data class ResourceSyncEntity(
    @PrimaryKey val queryKey: String,
    val nextOffset: Int,
    val totalHits: Int,
    val endReached: Boolean,
    val syncedAt: Long,
    val etag: String?,
)

@Entity(tableName = "resource_versions", primaryKeys = ["projectId", "id"])
data class ResourceVersionEntity(
    val projectId: String,
    val id: String,
    val name: String,
    val number: String,
    val gameVersions: String,
    val loaders: String,
    val filesJson: String,
    val dependenciesJson: String,
    val serverCompatible: Boolean,
)

@Entity(tableName = "resource_cart", primaryKeys = ["projectId", "instanceKey"])
data class ResourceCartEntity(
    val projectId: String,
    val instanceKey: String,
    val provider: String,
    val type: String,
    val versionId: String,
    val projectJson: String,
    val versionJson: String,
    val addedAt: Long,
)

@Dao
interface ResourceDao {
    @Query("SELECT * FROM resource_items WHERE queryKey = :key ORDER BY position")
    fun pagingSource(key: String): PagingSource<Int, ResourceItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItems(items: List<ResourceItemEntity>)

    @Query("DELETE FROM resource_items WHERE queryKey = :key")
    suspend fun clearItems(key: String)

    @Query("SELECT COUNT(*) FROM resource_items WHERE queryKey = :key")
    suspend fun itemCount(key: String): Int

    @Query("SELECT * FROM resource_sync WHERE queryKey = :key")
    suspend fun syncInfo(key: String): ResourceSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncInfo(info: ResourceSyncEntity)

    @Query("SELECT * FROM resource_versions WHERE projectId = :projectId")
    suspend fun versions(projectId: String): List<ResourceVersionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVersions(items: List<ResourceVersionEntity>)

    @Query("SELECT * FROM resource_cart WHERE instanceKey = :instanceKey ORDER BY addedAt")
    suspend fun cart(instanceKey: String): List<ResourceCartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCartItem(item: ResourceCartEntity)

    @Query("DELETE FROM resource_cart WHERE instanceKey = :instanceKey AND projectId = :projectId")
    suspend fun removeCartItem(instanceKey: String, projectId: String)

    @Query("DELETE FROM resource_cart WHERE instanceKey = :instanceKey")
    suspend fun clearCart(instanceKey: String)
}

@Database(
    entities = [ResourceItemEntity::class, ResourceSyncEntity::class, ResourceVersionEntity::class, ResourceCartEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ResourceDatabase : RoomDatabase() {
    abstract fun resourceDao(): ResourceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE resource_items ADD COLUMN clientCompatible INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
