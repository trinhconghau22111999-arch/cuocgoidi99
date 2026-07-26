package com.h.simplecall.data

data class Contact(
    val name: String,
    val number: String,
    val photoUri: String? = null,
    val starred: Boolean = false // đã được đánh dấu sao (yêu thích) trong danh bạ hệ thống
)

data class CallLogEntry(
    val name: String,
    val number: String,
    val type: Int,
    val date: Long,
    val simSlot: Int? = null,      // 0 = SIM 1, 1 = SIM 2
    val numberType: String = "",   // "Di động", "Việt Nam", v.v.
    val duration: Long = 0,        // giây - dùng để hiển thị "Chưa được kết nối" khi = 0
    val count: Int = 1             // số cuộc gọi LIÊN TIẾP cùng số đã được gộp vào dòng này
)

/** Gộp các cuộc gọi LIÊN TIẾP tới/từ cùng 1 số thành 1 dòng duy nhất - đúng hành vi chuẩn của
 *  danh bạ Android gốc (vd: gọi nhỡ 3 lần liên tiếp cùng 1 số chỉ hiện 1 dòng kèm "(3)").
 *  Giữ lại tên/loại cuộc gọi/thời gian của lần MỚI NHẤT trong nhóm. Danh sách đầu vào phải đã
 *  sắp xếp theo date DESC (mới nhất trước) - đúng như cách Room trả về hiện tại.
 *  LƯU Ý: chỉ gộp các dòng LIÊN TIẾP - nếu giữa 2 cuộc gọi cùng số có 1 cuộc gọi số khác xen
 *  vào thì KHÔNG gộp, để không làm mất thứ tự thời gian thực tế của nhật ký. */
fun List<CallLogEntry>.mergeConsecutiveDuplicates(): List<CallLogEntry> {
    if (isEmpty()) return this
    val result = mutableListOf<CallLogEntry>()
    var head = this[0]
    var count = 1
    for (i in 1 until size) {
        val next = this[i]
        if (next.number.isNotEmpty() && next.number == head.number) {
            count++
        } else {
            result.add(head.copy(count = count))
            head = next
            count = 1
        }
    }
    result.add(head.copy(count = count))
    return result
}
