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
    val date: Long
)
