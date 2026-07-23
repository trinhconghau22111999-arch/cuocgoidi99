package com.h.simplecall.data

data class Contact(
    val name: String,
    val number: String,
    val photoUri: String? = null
)

data class CallLogEntry(
    val name: String,
    val number: String,
    val type: Int,
    val date: Long,
    val simSlot: Int? = null,      // 0 = SIM 1, 1 = SIM 2
    val numberType: String = ""    // "Di động", "Việt Nam", v.v.
)
