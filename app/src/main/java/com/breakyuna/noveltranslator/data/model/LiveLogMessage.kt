package com.breakyuna.noveltranslator.data.model

import java.text.SimpleDateFormat
import java.util.*

enum class LiveLogType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    STEP
}

data class LiveLogMessage(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: Long = System.currentTimeMillis(),
    val chapterIndex: Int? = null,
    val chunkInfo: String? = null,
    val type: LiveLogType = LiveLogType.INFO,
    val message: String,
    val detail: String? = null,
    val tokensInfo: String? = null,
    val costInfo: String? = null
) {
    val timeFormatted: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
