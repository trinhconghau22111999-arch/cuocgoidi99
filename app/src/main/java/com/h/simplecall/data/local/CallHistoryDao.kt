package com.h.simplecall.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Tất cả hàm ở đây chạy ĐỒNG BỘ (blocking) - giống các hàm truy vấn cũ trong CallLogFragment/
 * DialerFragment. Nơi gọi PHẢI tự chạy trong bgExecutor, không được gọi trực tiếp trên main
 * thread (Room sẽ ném IllegalStateException nếu vi phạm).
 */
@Dao
interface CallHistoryDao {

    @Insert
    fun insert(entry: CallHistoryEntity): Long

    @Update
    fun update(entry: CallHistoryEntity)

    @Query("SELECT * FROM call_history WHERE id = :id LIMIT 1")
    fun getById(id: Long): CallHistoryEntity?

    @Query("SELECT * FROM call_history ORDER BY date DESC")
    fun getAll(): List<CallHistoryEntity>

    @Query("SELECT * FROM call_history ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int): List<CallHistoryEntity>

    @Query("SELECT * FROM call_history WHERE number LIKE :numberPattern ORDER BY date DESC")
    fun getByNumber(numberPattern: String): List<CallHistoryEntity>

    @Query("UPDATE call_history SET isNew = 0 WHERE type = :missedType AND isNew = 1")
    fun markMissedAsRead(missedType: Int)

    @Query("DELETE FROM call_history WHERE number LIKE :numberPattern")
    fun deleteByNumber(numberPattern: String)

    @Query("DELETE FROM call_history")
    fun clearAll()
}
