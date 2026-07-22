package com.h.simplecall.data

data class Contact(
    val id: String,
    val name: String,
    val number: String
)

data class CallLogEntry(
    val name: String,
    val number: String,
    val type: Int,
    val date: Long
)
