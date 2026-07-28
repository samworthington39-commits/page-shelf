package com.example.bookshelf.narration

enum class NarrationVoice(val displayName: String) {
    FEMALE("Matcha · 温柔女声"),
}

enum class NarrationStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR,
}

data class NarrationRequest(
    val bookId: String,
    val bookTitle: String,
    val bookFormat: String,
    val contentVersion: String?,
    val chapterId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val charOffset: Int,
)

data class NarrationState(
    val status: NarrationStatus = NarrationStatus.IDLE,
    val bookId: String? = null,
    val bookTitle: String = "",
    internal val bookFormat: String = "",
    internal val contentVersion: String? = null,
    val chapterId: String? = null,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val charOffset: Int = 0,
    val currentTextEndOffset: Int = 0,
    val currentText: String = "",
    val voice: NarrationVoice = NarrationVoice.FEMALE,
    val playbackSpeed: Float = 1f,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = status in setOf(
            NarrationStatus.PREPARING,
            NarrationStatus.PLAYING,
            NarrationStatus.PAUSED,
        )
}

internal data class NarrationSegment(val start: Int, val end: Int, val text: String)
