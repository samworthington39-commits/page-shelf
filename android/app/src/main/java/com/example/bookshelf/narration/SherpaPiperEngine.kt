package com.example.bookshelf.narration

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlin.math.roundToLong

internal data class GeneratedTtsAudio(
    val file: File,
    val durationMs: Long,
    val generationTimeMs: Long,
)

/** Generates normal-speed speech. User playback speed never enters this class. */
internal class SherpaPiperEngine(
    context: Context,
    voice: NarrationVoice,
    private val polyphoneResolver: ChinesePolyphoneResolver? = null,
) : AutoCloseable {
    private val phraseProcessor: PhrasePinyinProcessor
    private val overrideMapper: PiperPronunciationOverrideMapper?
    private val tts: OfflineTts

    init {
        phraseProcessor = context.assets.open("$COMMON/phrase_trie.bin").use(PhrasePinyinProcessor::load)
        overrideMapper = polyphoneResolver?.let { G2pwPiperPronunciationOverrideMapper(context) }
        val nativeDictionaryStartedAt = System.nanoTime()
        tts = OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voice.modelAsset,
                        lexicon = "$COMMON/merged_lexicon.txt",
                        tokens = "$COMMON/tokens.txt",
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = "$COMMON/phone.fst,$COMMON/date.fst,$COMMON/number.fst",
                maxNumSentences = 1,
            ),
        )
        Log.i(
            TAG,
            "TTS dictionaries loaded once: phraseTrieMs=${phraseProcessor.loadTimeNanos.toMillis()} " +
                "sherpaLexiconMs=${(System.nanoTime() - nativeDictionaryStartedAt).toMillis()}",
        )
    }

    @Volatile
    private var closed = false

    fun findPhraseMatches(text: String): PhraseMatchResult = phraseProcessor.findMatches(text)

    suspend fun warmUpPolyphoneResolver() {
        runCatching { polyphoneResolver?.warmUp() }
            .onFailure { Log.w(TAG, "g2pW warm-up failed; Piper fallback remains active", it) }
    }

    suspend fun generate(text: String, outputFile: File): GeneratedTtsAudio {
        check(!closed) { "语音模型已经释放" }
        require(text.isNotBlank()) { "朗读文本不能为空" }
        outputFile.parentFile?.mkdirs()

        val resolved = polyphoneResolver?.let { resolver ->
            runCatching { resolver.resolve(text) }
                .onFailure { Log.w(TAG, "g2pW resolve failed; using Piper fallback", it) }
                .getOrNull()
        }
        val base = PiperPhonemeSequence(text)
        val mapped = if (resolved != null && overrideMapper != null) {
            overrideMapper.applyOverrides(text, base, resolved.overrides)
        } else base
        val preprocessing = phraseProcessor.preprocess(text, mapped.forcedPronunciationAliases)
        Log.d(
            TAG,
            "TTS phrase preprocessing: chars=${text.length} matches=${preprocessing.matchCount} " +
                "g2pwOverrides=${mapped.forcedPronunciationAliases.size} " +
                "elapsedUs=${preprocessing.elapsedNanos.toMicros()}",
        )
        val startedAt = System.nanoTime()
        val audio = tts.generateWithConfig(
            text = preprocessing.text,
            config = GenerationConfig(speed = SYNTHESIS_SPEED),
        )
        val generationTimeMs = (System.nanoTime() - startedAt) / 1_000_000
        check(audio.sampleRate > 0 && audio.samples.isNotEmpty()) { "语音模型没有生成有效音频" }
        check(audio.save(outputFile.absolutePath)) { "无法保存朗读音频" }
        check(outputFile.isFile && outputFile.length() > WAV_HEADER_BYTES) { "朗读音频文件无效" }

        return GeneratedTtsAudio(
            file = outputFile,
            durationMs = (audio.samples.size * 1_000.0 / audio.sampleRate).roundToLong(),
            generationTimeMs = generationTimeMs,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        tts.release()
    }

    companion object {
        const val SYNTHESIS_SPEED = 1.0f
        const val MODEL_VERSION = "sherpa-onnx-1.13.4-piper-zh-g2pw-v1"
        const val SYNTHESIS_CONFIG_VERSION = "normal-speed-phrase-pinyin-g2pw-v2-character-lexicon"
        private const val COMMON = "tts/piper_zh/common"
        private const val WAV_HEADER_BYTES = 44L
        private const val TAG = "PageShelfTts"

        private fun Long.toMillis(): Long = this / 1_000_000
        private fun Long.toMicros(): Long = this / 1_000
    }
}
