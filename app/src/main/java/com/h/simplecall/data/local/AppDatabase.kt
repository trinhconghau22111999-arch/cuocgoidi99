package com.h.simplecall.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CallHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun callHistoryDao(): CallHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_history.db"
                )
                    // An toàn khi schema lệch giữa các lần build/test (identity hash mismatch) -
                    // Room sẽ CRASH NGAY LÚC MỞ DB nếu không có dòng này. Chấp nhận mất lịch sử
                    // cũ trong tình huống hiếm đó còn hơn làm sập cả ứng dụng.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
