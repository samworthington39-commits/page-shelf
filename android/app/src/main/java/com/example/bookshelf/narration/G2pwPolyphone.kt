package com.example.bookshelf.narration

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import com.example.bookshelf.BuildConfig
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

open class TextIndexMapping(
    open val utf16Index: Int,
    open val codePointIndex: Int,
    val tokenIndex: Int?,
)

data class PronunciationOverride(
    val charIndex: Int,
    val character: String,
    val pinyin: String,
    val tone: Int,
    val confidence: Float?,
)

data class PolyphoneResult(
    val originalText: String,
    val overrides: List<PronunciationOverride>,
    val processingTimeMs: Long,
    val fromCache: Boolean,
)

interface ChinesePolyphoneResolver {
    suspend fun resolve(text: String): PolyphoneResult
    suspend fun warmUp() = Unit
}

/** The cache intentionally excludes voice, speed, pitch and playback state. */
class PolyphoneCache(private val maxEntries: Int = 256) {
    private val values = object : LinkedHashMap<String, List<PronunciationOverride>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PronunciationOverride>>?): Boolean =
            size > maxEntries
    }

    @Synchronized fun get(key: String): List<PronunciationOverride>? = values[key]

    @Synchronized fun put(key: String, value: List<PronunciationOverride>) {
        values[key] = value
    }

    @Synchronized fun clear() = values.clear()
}

data class G2pwPrediction(
    val requestIndex: Int,
    val label: String,
    val confidence: Float,
)

interface G2pwRuntime : AutoCloseable {
    val modelVersion: String
    val configVersion: String
    fun candidateCharacters(): Set<Char>
    fun normalizeForModel(text: String): String = text
    fun labelToPinyin(label: String): Pair<String, Int>? = label.lastOrNull()?.digitToIntOrNull()?.let { label.dropLast(1) to it }
    suspend fun predict(text: String, queryCodePointIndices: List<Int>): List<G2pwPrediction>
    suspend fun warmUp()
}

/**
 * g2pW is queried once per sentence, but only for characters which the model
 * marks as polyphonic. All text passed here is a model-only copy; the result
 * is always mapped back to the original UTF-16 offsets by the resolver.
 */
class G2pwOnnxPolyphoneResolver(
    private val runtime: G2pwRuntime,
    private val cache: PolyphoneCache = PolyphoneCache(),
    private val debugLog: (String) -> Unit = { message -> Log.d(TAG, message) },
) : ChinesePolyphoneResolver {
    override suspend fun warmUp() = runtime.warmUp()
    override suspend fun resolve(text: String): PolyphoneResult {
        val startedAt = System.nanoTime()
        val cacheKey = "$TEXT_HASH_VERSION:${text.sha256()}:${runtime.modelVersion}:${runtime.configVersion}"
        cache.get(cacheKey)?.let { cached ->
            return PolyphoneResult(text, cached, elapsedMs(startedAt), fromCache = true)
        }

        val mappings = text.codePointMappings()
        val modelText = runtime.normalizeForModel(text)
        val modelMappings = modelText.codePointMappings()
        if (modelMappings.size != mappings.size) {
            Log.w(TAG, "G2PW normalization changed code-point count; using Piper fallback")
            return PolyphoneResult(text, emptyList(), elapsedMs(startedAt), fromCache = false)
        }
        val candidateCodePoints = mappings.filter { mapping ->
            modelMappings[mapping.codePointIndex].character.singleOrNull() in runtime.candidateCharacters()
        }
        if (candidateCodePoints.isEmpty()) {
            cache.put(cacheKey, emptyList())
            return PolyphoneResult(text, emptyList(), elapsedMs(startedAt), fromCache = false)
        }

        val queryIndices = candidateCodePoints.map(TextIndexMapping::codePointIndex)
        val predictions = runCatching { runtime.predict(modelText, queryIndices) }.getOrElse { error ->
            Log.w(TAG, "G2PW inference failed; using Piper fallback", error)
            return PolyphoneResult(text, emptyList(), elapsedMs(startedAt), fromCache = false)
        }
        val byQuery = predictions.associateBy(G2pwPrediction::requestIndex)
        val parsedOverrides = candidateCodePoints.map { mapping ->
            val prediction = byQuery[mapping.codePointIndex] ?: return@map null
            val parsed = runtime.labelToPinyin(prediction.label) ?: return@map null
            PronunciationOverride(
                charIndex = mapping.utf16Index,
                character = mapping.character,
                pinyin = parsed.first,
                tone = parsed.second,
                confidence = prediction.confidence.takeIf { it.isFinite() },
            )
        }
        if (parsedOverrides.any { it == null } || parsedOverrides.size != candidateCodePoints.size) {
            Log.w(TAG, "G2PW returned an invalid label or index; using Piper fallback")
            return PolyphoneResult(text, emptyList(), elapsedMs(startedAt), fromCache = false)
        }
        val overrides = parsedOverrides.filterNotNull()
        cache.put(cacheKey, overrides)
        debugLog("G2PW resolve: textHash=${text.sha256().take(12)} polyphoneCount=${candidateCodePoints.size} " +
            "cacheHit=false inferenceMs=${elapsedMs(startedAt)} fallback=${overrides.size != predictions.size}")
        return PolyphoneResult(text, overrides, elapsedMs(startedAt), fromCache = false)
    }

    private companion object {
        const val TAG = "PageShelfG2PW"
        const val TEXT_HASH_VERSION = "utf16-v1"
        fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
    }
}

private data class CodePointMapping(
    override val utf16Index: Int,
    override val codePointIndex: Int,
    val character: String,
) : TextIndexMapping(utf16Index, codePointIndex, null)

private fun String.codePointMappings(): List<CodePointMapping> {
    val result = ArrayList<CodePointMapping>(codePointCount(0, length))
    var utf16 = 0
    var codePoint = 0
    while (utf16 < length) {
        val cp = codePointAt(utf16)
        val width = Character.charCount(cp)
        result += CodePointMapping(utf16, codePoint++, substring(utf16, utf16 + width))
        utf16 += width
    }
    return result
}

private fun StringBuilder.appendCodePoint(codePoint: Int) = append(String(Character.toChars(codePoint)))

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** Android implementation backed by the ONNX Runtime already shipped by sherpa-onnx. */
class G2pwOnnxRuntime(
    private val context: Context,
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor {
        Thread(it, "g2pw-inference").apply { isDaemon = true }
    }.asCoroutineDispatcher(),
) : G2pwRuntime {
    override val modelVersion: String = "g2pW-v2-int8-onnxruntime-1.27.0"
    override val configVersion: String = "window32-wordpiece-v1"

    private val resources by lazy { G2pwResources.load(context) }
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var options: SessionOptions? = null
    private var closed = false

    override fun candidateCharacters(): Set<Char> = resources.polyphonicCharacters

    override fun normalizeForModel(text: String): String = resources.toTraditional(text)

    override fun labelToPinyin(label: String): Pair<String, Int>? = resources.labelToPinyin(label)

    override suspend fun warmUp() {
        withContext(dispatcher) {
            ensureSession()
            predictLocked("重要", listOf(0))
            Unit
        }
    }

    override suspend fun predict(text: String, queryCodePointIndices: List<Int>): List<G2pwPrediction> =
        withContext(dispatcher) { predictLocked(text, queryCodePointIndices) }

    private fun predictLocked(text: String, queryCodePointIndices: List<Int>): List<G2pwPrediction> {
        check(!closed) { "g2pW runtime already closed" }
        if (queryCodePointIndices.isEmpty()) return emptyList()
        val ortSession = ensureSession()
        val tokenizeStartedAt = System.nanoTime()
        val inputs = queryCodePointIndices.map { resources.prepare(text, it) }
        val tokenizeMs = elapsedMs(tokenizeStartedAt)
        val batch = inputs.size
        val width = inputs.maxOf { it.inputIds.size }
        val inputIds = LongArray(batch * width)
        val tokenTypes = LongArray(batch * width)
        val attention = LongArray(batch * width)
        inputs.forEachIndexed { row, input ->
            input.inputIds.copyInto(inputIds, row * width)
            input.attentionMask.copyInto(attention, row * width)
        }
        val phonemeMask = FloatArray(batch * resources.labels.size) { 1f }
        inputs.forEachIndexed { row, input -> input.phonemeMask.copyInto(phonemeMask, row * resources.labels.size) }
        val charIds = LongArray(batch) { inputs[it].charId.toLong() }
        val positionIds = LongArray(batch) { inputs[it].positionId.toLong() }
        val tensors = listOf(
            OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), longArrayOf(batch.toLong(), width.toLong())),
            OnnxTensor.createTensor(environment, LongBuffer.wrap(tokenTypes), longArrayOf(batch.toLong(), width.toLong())),
            OnnxTensor.createTensor(environment, LongBuffer.wrap(attention), longArrayOf(batch.toLong(), width.toLong())),
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(phonemeMask), longArrayOf(batch.toLong(), resources.labels.size.toLong())),
            OnnxTensor.createTensor(environment, LongBuffer.wrap(charIds), longArrayOf(batch.toLong())),
            OnnxTensor.createTensor(environment, LongBuffer.wrap(positionIds), longArrayOf(batch.toLong())),
        )
        val inferenceStartedAt = System.nanoTime()
        return try {
            ortSession.run(mapOf(
                "input_ids" to tensors[0], "token_type_ids" to tensors[1], "attention_mask" to tensors[2],
                "phoneme_mask" to tensors[3], "char_ids" to tensors[4], "position_ids" to tensors[5],
            )).use { result ->
                val inferenceMs = elapsedMs(inferenceStartedAt)
                val values = result[0].value as Array<*>
                inputs.indices.map { row ->
                    val rowValues = values[row] as FloatArray
                    var best = 0
                    for (index in 1 until rowValues.size) if (rowValues[index] > rowValues[best]) best = index
                    G2pwPrediction(queryCodePointIndices[row], resources.labels[best], rowValues[best])
                }.also {
                    if (BuildConfig.DEBUG) Log.d(
                        TAG,
                        "G2PW runtime: candidates=$batch tokenizeMs=$tokenizeMs inferenceMs=$inferenceMs",
                    )
                }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    private fun ensureSession(): OrtSession {
        session?.let { return it }
        val startedAt = System.nanoTime()
        val model = resources.materializeModel(context)
        val sessionOptions = SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
        }
        options = sessionOptions
        return environment.createSession(model.absolutePath, sessionOptions).also {
            session = it
            Log.i(TAG, "G2PW initialized: modelBytes=${model.length()} initMs=${elapsedMs(startedAt)}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        session?.close()
        options?.close()
        dispatcher.close()
    }

    private companion object {
        const val TAG = "PageShelfG2PW"
        fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
    }
}

private data class PreparedG2pwInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val phonemeMask: FloatArray,
    val charId: Int,
    val positionId: Int,
)

private class G2pwResources private constructor(
    val labels: List<String>,
    val polyphonicCharacters: Set<Char>,
    private val charLabels: Map<Char, Set<Int>>,
    private val tokenizer: G2pwWordPieceTokenizer,
    private val modelAsset: String,
    private val simplifiedToTraditional: Map<Char, Char>,
    private val pinyinByLabel: Map<String, String>,
) {
    fun toTraditional(text: String): String = buildString {
        text.forEachCodePoint { cp ->
            val character = String(Character.toChars(cp))
            append(simplifiedToTraditional[character.singleOrNull()] ?: character)
        }
    }

    fun labelToPinyin(label: String): Pair<String, Int>? {
        val tone = label.lastOrNull()?.digitToIntOrNull()?.takeIf { it in 1..5 } ?: return null
        return (pinyinByLabel[label] ?: return null) to tone
    }
    fun prepare(text: String, queryCodePointIndex: Int): PreparedG2pwInput {
        val start = max(0, queryCodePointIndex - WINDOW_SIZE / 2)
        val end = min(text.codePointCount(0, text.length), queryCodePointIndex + WINDOW_SIZE / 2)
        val codePoints = text.codePoints().toArray().copyOfRange(start, end)
        val query = queryCodePointIndex - start
        val tokenized = tokenizer.tokenize(codePoints)
        val queryToken = tokenized.codePointToToken[query]
        val inputIds = LongArray(tokenized.ids.size + 2)
        inputIds[0] = tokenizer.clsId.toLong()
        tokenized.ids.forEachIndexed { index, id -> inputIds[index + 1] = id.toLong() }
        inputIds[inputIds.lastIndex] = tokenizer.sepId.toLong()
        val mask = FloatArray(labels.size)
        charLabels[codePoints[query].toChar()]?.forEach { mask[it] = 1f }
        return PreparedG2pwInput(inputIds, LongArray(inputIds.size) { 1 }, mask, tokenizer.charIds.indexOf(codePoints[query].toChar()), queryToken + 1)
    }

    fun materializeModel(context: Context): File {
        val target = File(context.noBackupFilesDir, "g2pw/g2pw-int8.onnx")
        if (!target.isFile || target.length() != MODEL_BYTES) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            context.assets.open(modelAsset).use { input -> temporary.outputStream().use(input::copyTo) }
            check(temporary.length() == MODEL_BYTES) { "g2pW model asset is incomplete" }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target) || runCatching {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
                true
            }.getOrDefault(false)) { "Unable to materialize g2pW model" }
        }
        return target
    }

    companion object {
        const val WINDOW_SIZE = 32
        const val MODEL_BYTES = 159_287_333L
        fun load(context: Context): G2pwResources {
            val labels = context.assets.open("g2pw/labels.txt").bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
            val polyphones = context.assets.open("g2pw/polyphones.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 } }.toList()
            }
            val charLabels = polyphones.groupBy({ it[0].single() }, { labels.indexOf(it[1]) })
                .mapValues { it.value.filter { index -> index >= 0 }.toSet() }
            val s2t = context.assets.open("g2pw/simplified_to_traditional.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0].singleOrNull() to it[1].singleOrNull() } }
                    .filter { it.first != null && it.second != null }.associate { it.first!! to it.second!! }
            }
            val pinyinByLabel = context.assets.open("g2pw/bopomofo_to_pinyin.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1].dropLast(1) } }.toMap()
            }
            return G2pwResources(
                labels,
                charLabels.keys,
                charLabels,
                G2pwWordPieceTokenizer.load(context, charLabels.keys.sorted()),
                "g2pw/model.onnx",
                s2t,
                pinyinByLabel,
            )
        }
    }
}

private class G2pwWordPieceTokenizer private constructor(
    val tokenIds: Map<String, Int>,
    val charIds: List<Char>,
) {
    val clsId = tokenIds["[CLS]"] ?: 101
    val sepId = tokenIds["[SEP]"] ?: 102
    private val unknownId = tokenIds["[UNK]"] ?: 100

    data class Tokenized(val ids: LongArray, val codePointToToken: IntArray)

    fun tokenize(codePoints: IntArray): Tokenized {
        val ids = ArrayList<Long>()
        val mapping = IntArray(codePoints.size)
        var index = 0
        while (index < codePoints.size) {
            val cp = codePoints[index]
            if (cp == 0x20 || Character.isWhitespace(cp)) { mapping[index] = max(0, ids.lastIndex); index++; continue }
            val start = index
            if (cp <= 0x7f && (Character.isLetterOrDigit(cp))) {
                while (index < codePoints.size && codePoints[index] <= 0x7f && Character.isLetterOrDigit(codePoints[index])) index++
            } else index++
            val word = String(codePoints, start, index - start)
            val subtokens = wordPiece(word)
            subtokens.forEach { token -> ids += (tokenIds[token] ?: unknownId).toLong() }
            for (cpIndex in start until index) mapping[cpIndex] = ids.size - subtokens.size
        }
        return Tokenized(ids.toLongArray(), mapping)
    }

    private fun wordPiece(word: String): List<String> {
        tokenIds[word]?.let { return listOf(word) }
        if (word.length == 1) return listOf("[UNK]")
        val result = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: String? = null
            while (end > start) {
                val part = word.substring(start, end)
                val candidate = if (start == 0) part else "##$part"
                if (candidate in tokenIds) { found = candidate; break }
                end--
            }
            if (found == null) return listOf("[UNK]")
            result += found
            start = end
        }
        return result
    }

    companion object {
        fun load(context: Context, modelCharacters: List<Char>): G2pwWordPieceTokenizer {
            val ids = context.assets.open("g2pw/vocab.txt").bufferedReader().useLines { lines ->
                lines.mapIndexed { index, line -> line.trimEnd() to index }.toMap()
            }
            return G2pwWordPieceTokenizer(ids, modelCharacters)
        }
    }
}

private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        action(codePoint)
        index += Character.charCount(codePoint)
    }
}
