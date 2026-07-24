package com.example.bookshelf.narration

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream

internal data class PhraseMatch(
    val start: Int,
    val end: Int,
)

internal data class PhraseMatchResult(
    val matches: List<PhraseMatch>,
    val elapsedNanos: Long,
)

internal data class PhrasePreprocessingResult(
    val text: String,
    val matchCount: Int,
    val elapsedNanos: Long,
)

/** Compact, dictionary-only, left-to-right longest phrase matcher. */
internal class PhrasePinyinProcessor private constructor(
    private val firstEdges: IntArray,
    private val edgeCounts: IntArray,
    private val terminalNodes: BooleanArray,
    private val edgeChars: CharArray,
    private val edgeTargets: IntArray,
    val loadTimeNanos: Long,
) {
    fun findMatches(text: String): PhraseMatchResult {
        val startedAt = System.nanoTime()
        val matches = findMatchesUntimed(text)
        return PhraseMatchResult(matches, System.nanoTime() - startedAt)
    }

    fun preprocess(text: String): PhrasePreprocessingResult {
        return preprocess(text, emptyMap())
    }

    fun preprocess(text: String, forcedAliases: Map<Int, String>): PhrasePreprocessingResult {
        val startedAt = System.nanoTime()
        val aliases = forcedAliases.mapNotNull { (start, alias) ->
            if (start !in text.indices) return@mapNotNull null
            val end = start + Character.charCount(text.codePointAt(start))
            PreprocessEvent(start, end, alias)
        }.sortedBy(PreprocessEvent::start)
        val matches = findMatchesUntimed(text).filter { match ->
            aliases.none { it.start >= match.start && it.start < match.end }
        }
        if (aliases.isEmpty()) {
            return PhrasePreprocessingResult(text, matches.size, System.nanoTime() - startedAt)
        }

        val rewritten = StringBuilder(text.length)
        var copiedUntil = 0
        aliases.forEach { event ->
            if (event.start < copiedUntil) return@forEach
            rewritten.append(text, copiedUntil, event.start)
            rewritten.append(event.replacement)
            copiedUntil = event.end
        }
        rewritten.append(text, copiedUntil, text.length)
        return PhrasePreprocessingResult(
            text = rewritten.toString(),
            matchCount = matches.size + aliases.size,
            elapsedNanos = System.nanoTime() - startedAt,
        )
    }

    private fun findMatchesUntimed(text: String): List<PhraseMatch> {
        if (text.length < 2) return emptyList()
        val matches = mutableListOf<PhraseMatch>()
        var start = 0
        while (start < text.length) {
            var node = ROOT_NODE
            var cursor = start
            var longestEnd = -1
            while (cursor < text.length) {
                node = findChild(node, text[cursor])
                if (node < 0) break
                cursor++
                if (terminalNodes[node]) longestEnd = cursor
            }
            if (longestEnd > start) {
                matches += PhraseMatch(start, longestEnd)
                start = longestEnd
            } else {
                start++
            }
        }
        return matches
    }

    private data class PreprocessEvent(val start: Int, val end: Int, val replacement: String)

    private fun findChild(node: Int, char: Char): Int {
        var low = firstEdges[node]
        var high = low + edgeCounts[node] - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            when {
                edgeChars[middle] < char -> low = middle + 1
                edgeChars[middle] > char -> high = middle - 1
                else -> return edgeTargets[middle]
            }
        }
        return -1
    }

    companion object {
        private const val TRIE_MAGIC = 0x50505431
        private const val ROOT_NODE = 0

        fun load(input: InputStream): PhrasePinyinProcessor {
            val startedAt = System.nanoTime()
            DataInputStream(BufferedInputStream(input)).use { data ->
                check(data.readInt() == TRIE_MAGIC) { "无效的词组拼音 Trie" }
                val nodeCount = data.readInt()
                val edgeCount = data.readInt()
                check(nodeCount > 0 && edgeCount > 0) { "词组拼音 Trie 为空" }

                val firstEdges = IntArray(nodeCount)
                val edgeCounts = IntArray(nodeCount)
                val terminalNodes = BooleanArray(nodeCount)
                repeat(nodeCount) { index ->
                    firstEdges[index] = data.readInt()
                    edgeCounts[index] = data.readInt()
                    terminalNodes[index] = data.readBoolean()
                }
                val edgeChars = CharArray(edgeCount)
                val edgeTargets = IntArray(edgeCount)
                repeat(edgeCount) { index ->
                    edgeChars[index] = data.readChar()
                    edgeTargets[index] = data.readInt()
                }
                validateTrie(firstEdges, edgeCounts, edgeTargets)
                return PhrasePinyinProcessor(
                    firstEdges = firstEdges,
                    edgeCounts = edgeCounts,
                    terminalNodes = terminalNodes,
                    edgeChars = edgeChars,
                    edgeTargets = edgeTargets,
                    loadTimeNanos = System.nanoTime() - startedAt,
                )
            }
        }

        internal fun fromPhrases(phrases: Collection<String>): PhrasePinyinProcessor {
            data class Node(
                var terminal: Boolean = false,
                val children: MutableMap<Char, Int> = sortedMapOf(),
            )

            val nodes = mutableListOf(Node())
            phrases.filter { it.length >= 2 }.forEach { phrase ->
                var node = ROOT_NODE
                phrase.forEach { char ->
                    node = nodes[node].children.getOrPut(char) {
                        nodes.add(Node())
                        nodes.lastIndex
                    }
                }
                nodes[node].terminal = true
            }
            val firstEdges = IntArray(nodes.size)
            val edgeCounts = IntArray(nodes.size)
            val edgeChars = mutableListOf<Char>()
            val edgeTargets = mutableListOf<Int>()
            nodes.forEachIndexed { index, node ->
                firstEdges[index] = edgeChars.size
                edgeCounts[index] = node.children.size
                node.children.forEach { (char, target) ->
                    edgeChars += char
                    edgeTargets += target
                }
            }
            return PhrasePinyinProcessor(
                firstEdges,
                edgeCounts,
                BooleanArray(nodes.size) { nodes[it].terminal },
                edgeChars.toCharArray(),
                edgeTargets.toIntArray(),
                loadTimeNanos = 0,
            )
        }

        private fun validateTrie(
            firstEdges: IntArray,
            edgeCounts: IntArray,
            edgeTargets: IntArray,
        ) {
            firstEdges.indices.forEach { node ->
                val first = firstEdges[node]
                val count = edgeCounts[node]
                check(first >= 0 && count >= 0 && first + count <= edgeTargets.size) {
                    "词组拼音 Trie 节点越界"
                }
            }
            check(edgeTargets.all { it in firstEdges.indices }) { "词组拼音 Trie 边越界" }
        }
    }
}
