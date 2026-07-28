package com.example.bookshelf.narration

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal data class TtsCacheKey(
    val bookId: String,
    val chapterId: String,
    val text: String,
    val voice: NarrationVoice,
)

internal data class CachedTtsAudio(
    val file: File,
    val durationMs: Long?,
    val generationTimeMs: Long?,
    val cacheHit: Boolean,
)

internal class TtsAudioCache(context: Context) {
    private val directory = File(context.cacheDir, DIRECTORY_NAME).apply { mkdirs() }

    suspend fun getOrGenerate(
        key: TtsCacheKey,
        generator: NarrationTtsEngine,
    ): CachedTtsAudio {
        val destination = File(directory, "${key.cacheId(generator)}.wav")
        if (destination.isValidWav()) {
            return CachedTtsAudio(destination, durationMs = null, generationTimeMs = null, cacheHit = true)
        }
        if (destination.exists()) destination.delete()

        val temporary = File(directory, "${destination.nameWithoutExtension}.tmp.wav")
        if (temporary.exists()) temporary.delete()
        return try {
            val generated = generator.generate(key.text, temporary)
            check(temporary.renameTo(destination) || copyIntoCache(temporary, destination)) {
                "无法提交朗读音频缓存"
            }
            CachedTtsAudio(
                file = destination,
                durationMs = generated.durationMs,
                generationTimeMs = generated.generationTimeMs,
                cacheHit = false,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun copyIntoCache(source: File, destination: File): Boolean = runCatching {
        source.copyTo(destination, overwrite = true)
        destination.isValidWav()
    }.getOrDefault(false)

    private fun File.isValidWav(): Boolean = isFile && length() > WAV_HEADER_BYTES

    private fun TtsCacheKey.cacheId(generator: NarrationTtsEngine): String {
        val textHash = text.sha256()
        return listOf(
            bookId,
            chapterId,
            textHash,
            voice.name,
            generator.modelVersion,
            generator.synthesisConfigVersion,
        ).joinToString(separator = "\u0000").sha256()
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val DIRECTORY_NAME = "narration_tts_wav"
        const val WAV_HEADER_BYTES = 44L
    }
}
