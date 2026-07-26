package com.h.simplecall.data

import android.content.Context
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.h.simplecall.ui.firstLetterKey

/**
 * Bộ nhớ đệm danh bạ dùng chung cho cả app (singleton object - process này chỉ có 1 bản).
 *
 * KHÔNG còn tự động truy xuất danh bạ trước khi người dùng mở tab Danh bạ (đã bỏ theo yêu cầu -
 * trước đây MainActivity.onCreate() có gọi nạp trước ngầm ngay khi mở app, giờ không còn nữa).
 * getContacts() chỉ thực sự truy vấn ContactsContract khi được gọi lần đầu (lúc người dùng bấm
 * vào tab Danh bạ), và cache lại kết quả cho các lần gọi sau trong cùng phiên chạy app.
 */
object ContactsRepository {

    @Volatile private var cache: List<Contact>? = null
    private val mutex = Mutex()

    /** Có cache sẵn hay chưa - dùng để quyết định có cần hiện loading hay không. */
    fun peek(): List<Contact>? = cache

    /** Trả cache ngay nếu có; nếu chưa có lần nào thì mới thật sự truy vấn (và lưu cache).
     *  Nếu lúc đọc CHƯA có quyền đọc danh bạ (vd. app vừa mở, người dùng chưa bấm "Cho phép"),
     *  trả về rỗng nhưng KHÔNG lưu vào cache - để lần gọi kế tiếp (sau khi có quyền) thử
     *  truy vấn lại thật, thay vì bị kẹt mãi ở kết quả rỗng. */
    suspend fun getContacts(context: Context): List<Contact> {
        cache?.let { return it }
        if (!hasPermission(context)) return emptyList()
        return mutex.withLock {
            cache ?: loadFromSystem(context).also { cache = it }
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun loadFromSystem(context: Context): List<Contact> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()

        val list = mutableListOf<Contact>()
        val cur = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED
            ), null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return list

        cur.use {
            val iName    = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNum     = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val iPhoto   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
            val iStarred = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
            while (it.moveToNext()) {
                list.add(Contact(
                    name     = it.getString(iName) ?: "",
                    number   = it.getString(iNum) ?: "",
                    photoUri = it.getString(iPhoto),
                    starred  = iStarred >= 0 && it.getInt(iStarred) != 0
                ))
            }
        }
        // DISPLAY_NAME ASC (SQL) xếp ký tự/số TRƯỚC chữ cái theo bảng mã Unicode, nên các liên
        // hệ thuộc nhóm "#" (tên bắt đầu bằng số/ký hiệu) bị đẩy lên ĐẦU danh sách - không khớp
        // cột chỉ mục A-Z bên phải (đã xếp "#" ở CUỐI). Đẩy nhóm "#" xuống cuối, dùng sortedBy
        // (stable) nên thứ tự A-Z trong mỗi nhóm vẫn giữ nguyên.
        return list.sortedBy { if (firstLetterKey(it.name) == "#") 1 else 0 }
    }

    /** Gọi khi biết chắc danh bạ hệ thống vừa đổi (thêm/sửa/xoá số) để lần đọc kế tiếp
     *  buộc phải truy vấn lại thay vì trả cache cũ. */
    fun invalidate() { cache = null }
}
