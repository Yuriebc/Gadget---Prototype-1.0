package com.example.gadgetapp

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "user_table")
data class User(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password_hash") val passwordHash: String
)

@Entity(tableName = "command_table")
data class Command(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo(name = "command") val command: String,
    @ColumnInfo(name = "status") val status: String
)

@Dao
interface UserDao {
    @Query("SELECT * FROM user_table WHERE username = :username LIMIT 1")
    fun findByUsername(username: String): User?

    @Insert
    fun insert(user: User)
}

@Dao
interface CommandDao {
    @Insert
    fun insert(command: Command)

    @Query("SELECT * FROM command_table WHERE status = 'pending' ORDER BY id ASC LIMIT 1")
    fun getNextPendingCommand(): Command?

    @Update
    fun updateCommandStatus(command: Command)

    @Query("SELECT * FROM command_table ORDER BY id DESC")
    fun getAllCommands(): Flow<List<Command>>
}

@Database(entities = [User::class, Command::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun commandDao(): CommandDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gadget-database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}