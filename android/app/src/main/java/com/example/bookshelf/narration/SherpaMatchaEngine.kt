package com.example.bookshelf.narration

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import kotlin.math.roundToLong

internal data class GeneratedTtsAudio(
    val file: File,
    val durationMs: Long,
    val generationTimeMs: Long,
    val sampleRate: Int,
)

internal interface NarrationTtsEngine : AutoCloseable {
    val modelVersion: String
    val synthesisConfigVersion: String
    val synthesisSpeed: Float

    suspend fun generate(text: String, outputFile: File): GeneratedTtsAudio
}

/** Generates normal-speed Matcha speech. User playback speed never enters this class. */
internal class SherpaMatchaEngine(
    context: Context,
    voice: NarrationVoice,
) : NarrationTtsEngine {
    private val tts: OfflineTts

    override val modelVersion: String = MODEL_VERSION
    override val synthesisConfigVersion: String = SYNTHESIS_CONFIG_VERSION
    override val synthesisSpeed: Float = SYNTHESIS_SPEED

    init {
        require(voice == NarrationVoice.FEMALE) { "Matcha 测试模型只包含一个女声音色" }
        val dataDirStartedAt = System.nanoTime()
        val dataDir = materializeEspeakData(context)
        val modelStartedAt = System.nanoTime()
        tts = OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = "$MODEL_ROOT/model-steps-3.onnx",
                        vocoder = "$MODEL_ROOT/vocos-16khz-univ.onnx",
                        lexicon = MATCHA_LEXICONS,
                        tokens = "$MODEL_ROOT/tokens.txt",
                        dataDir = dataDir.absolutePath,
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = "$MODEL_ROOT/phone-zh.fst,$MODEL_ROOT/date-zh.fst,$MODEL_ROOT/number-zh.fst",
                maxNumSentences = 1,
                silenceScale = SILENCE_SCALE,
            ),
        )
        Log.i(
            TAG,
            "Matcha TTS ready: dataDirMs=${(modelStartedAt - dataDirStartedAt).toMillis()} " +
                "modelInitMs=${(System.nanoTime() - modelStartedAt).toMillis()} threads=" +
                Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        )
    }

    @Volatile
    private var closed = false

    override suspend fun generate(text: String, outputFile: File): GeneratedTtsAudio {
        check(!closed) { "语音模型已经释放" }
        require(text.isNotBlank()) { "朗读文本不能为空" }
        outputFile.parentFile?.mkdirs()

        val startedAt = System.nanoTime()
        val audio = tts.generateWithConfig(
            text = text,
            config = GenerationConfig(
                speed = SYNTHESIS_SPEED,
                silenceScale = SILENCE_SCALE,
            ),
        )
        val generationTimeMs = (System.nanoTime() - startedAt) / 1_000_000
        check(audio.sampleRate > 0 && audio.samples.isNotEmpty()) { "语音模型没有生成有效音频" }
        check(audio.save(outputFile.absolutePath)) { "无法保存朗读音频" }
        check(outputFile.isFile && outputFile.length() > WAV_HEADER_BYTES) { "朗读音频文件无效" }

        return GeneratedTtsAudio(
            file = outputFile,
            durationMs = (audio.samples.size * 1_000.0 / audio.sampleRate).roundToLong(),
            generationTimeMs = generationTimeMs,
            sampleRate = audio.sampleRate,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        tts.release()
    }

    companion object {
        const val SYNTHESIS_SPEED = 1.0f
        const val MODEL_VERSION = "sherpa-onnx-1.13.4-matcha-icefall-zh-en-271b804a-vocos-b599142a"
        const val SYNTHESIS_CONFIG_VERSION =
            "normal-speed-matcha-zh-en-silence-0.2-lexicon-210b7793-v2"
        private const val MODEL_ROOT = "tts/matcha_zh_en"
        internal const val MATCHA_LEXICONS =
            "$MODEL_ROOT/novel-phrase-lexicon.txt,$MODEL_ROOT/lexicon.txt"
        private const val ESPEAK_DATA = "$MODEL_ROOT/espeak-ng-data"
        private const val DATA_REVISION = "271b804af570400d3bcdcb53bf6e53cc9f75180ee763b9f13eb5eaf2b0d086ef"
        private const val SILENCE_SCALE = 0.2f
        private const val WAV_HEADER_BYTES = 44L
        private const val TAG = "PageShelfMatchaTts"
        private val DATA_INSTALL_LOCK = Any()

        private fun Long.toMillis(): Long = this / 1_000_000

        private fun materializeEspeakData(context: Context): File = synchronized(DATA_INSTALL_LOCK) {
            val installRoot = File(context.noBackupFilesDir, "matcha_tts")
            val versionRoot = File(installRoot, DATA_REVISION.take(16))
            val dataDir = File(versionRoot, "espeak-ng-data")
            val marker = File(versionRoot, ".complete")
            if (
                marker.isFile &&
                marker.readText(Charsets.UTF_8) == DATA_REVISION &&
                File(dataDir, "phondata").isFile
            ) {
                return@synchronized dataDir
            }

            installRoot.mkdirs()
            val stagingRoot = File(installRoot, ".${DATA_REVISION.take(16)}.tmp")
            check(!stagingRoot.exists() || stagingRoot.deleteRecursively()) {
                "无法清理 Matcha 文本处理资源的临时目录"
            }
            val stagingDataDir = File(stagingRoot, "espeak-ng-data")
            copyAssetTree(context.assets, ESPEAK_DATA, stagingDataDir)
            check(File(stagingDataDir, "phondata").isFile) { "Matcha eSpeak 资源不完整" }
            File(stagingRoot, ".complete").writeText(DATA_REVISION, Charsets.UTF_8)

            check(!versionRoot.exists() || versionRoot.deleteRecursively()) {
                "无法替换旧版 Matcha 文本处理资源"
            }
            if (!stagingRoot.renameTo(versionRoot)) {
                check(stagingRoot.copyRecursively(versionRoot, overwrite = true)) {
                    "无法安装 Matcha 文本处理资源"
                }
                check(stagingRoot.deleteRecursively()) {
                    "无法清理 Matcha 文本处理资源的临时目录"
                }
            }
            dataDir
        }

        private fun copyAssetTree(
            assets: AssetManager,
            sourcePath: String,
            destination: File,
        ) {
            val children = assets.list(sourcePath)
                ?: error("无法读取 Matcha 资源目录：$sourcePath")
            if (children.isEmpty()) {
                destination.parentFile?.mkdirs()
                assets.open(sourcePath).use { input ->
                    destination.outputStream().buffered().use(input::copyTo)
                }
                return
            }
            check(destination.mkdirs() || destination.isDirectory) {
                "无法创建 Matcha 资源目录：${destination.absolutePath}"
            }
            children.forEach { child ->
                copyAssetTree(assets, "$sourcePath/$child", File(destination, child))
            }
        }
    }
}
