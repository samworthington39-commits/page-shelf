import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("androidx.room")
}

@CacheableTask
abstract class GeneratePhrasePinyinAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pinyinFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localOverridesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseLexiconFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tokensFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val g2pwPolyphonicFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bopomofoMapFile: RegularFileProperty

    @get:Input
    abstract val expectedPinyinSha256: Property<String>

    @get:Input
    abstract val sourceRevision: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = pinyinFile.get().asFile
        val actualHash = source.sha256()
        check(actualHash.equals(expectedPinyinSha256.get(), ignoreCase = true)) {
            "phrase-pinyin-data pinyin.txt SHA-256 mismatch: $actualHash"
        }

        val supportedTokens = tokensFile.get().asFile.useLines(Charsets.UTF_8) { lines ->
            lines.mapNotNull { line ->
                line.trimEnd().substringBefore(' ').takeIf(String::isNotEmpty)
            }.toSet()
        }
        val phraseEntries = linkedMapOf<String, List<String>>()
        val skipped = mutableListOf<String>()
        parseEntries(
            source,
            supportedTokens,
            phraseEntries,
            skipped,
            allowSingleCharacter = false,
            overrideExisting = false,
        )
        parseEntries(
            localOverridesFile.get().asFile,
            supportedTokens,
            phraseEntries,
            skipped,
            allowSingleCharacter = true,
            overrideExisting = true,
        )

        @Suppress("UNCHECKED_CAST")
        val bopomofoToPinyin = (JsonSlurper().parse(bopomofoMapFile.get().asFile) as Map<String, String>).toMutableMap().apply {
            // Rare labels in the released checkpoint are absent from the upstream helper map.
            putAll(mapOf(
                "ㄈㄨㄥ" to "feng", "ㄉㄧㄤ" to "diang", "ㄌㄩㄢ" to "lvan",
                "ㄌㄩㄣ" to "lvn", "ㄍㄧ" to "gi", "ㄝ" to "e", "ㄩㄤ" to "uang",
            ))
        }
        val polyphones = g2pwPolyphonicFile.get().asFile.useLines(Charsets.UTF_8) { lines ->
            lines.mapNotNull { raw ->
                val columns = raw.trim().split('\t')
                if (columns.size != 2 || columns[0].codePointCount(0, columns[0].length) != 1) return@mapNotNull null
                val bopomofo = columns[1]
                val tone = bopomofo.lastOrNull()?.digitToIntOrNull() ?: return@mapNotNull null
                val pinyin = bopomofoToPinyin[bopomofo.dropLast(1)] ?: return@mapNotNull null
                Triple(columns[0], bopomofo, "$pinyin$tone")
            }.toList()
        }
        val pinyinAliases = polyphones.map { it.third }.distinct().sorted().mapIndexed { index, pinyin ->
            check(index < PRIVATE_USE_CAPACITY) { "Too many g2pW pinyin aliases" }
            pinyin to (PRIVATE_USE_START + index).toChar().toString()
        }.toMap()

        val outputRoot = outputDirectory.get().asFile
        val commonOutput = outputRoot.resolve("tts/piper_zh/common").apply { mkdirs() }
        val mergedLexicon = commonOutput.resolve("merged_lexicon.txt")
        mergedLexicon.bufferedWriter(Charsets.UTF_8).use { writer ->
            pinyinAliases.forEach { (pinyin, alias) ->
                val tokens = syllableToTokens(pinyin)
                check(tokens.isNotEmpty() && tokens.all { it in supportedTokens }) {
                    "Unsupported g2pW pronunciation: $pinyin"
                }
                writer.append(alias).append(' ').append(tokens.joinToString(" ")).append('\n')
            }
            phraseEntries.forEach { (phrase, tokens) ->
                writer.append(phrase).append(' ').append(tokens.joinToString(" ")).append('\n')
            }
            baseLexiconFile.get().asFile.forEachLine(Charsets.UTF_8) { line ->
                val word = line.substringBefore(' ')
                if (word !in phraseEntries) writer.append(line).append('\n')
            }
        }

        writeTrie(
            commonOutput.resolve("phrase_trie.bin"),
            phraseEntries.keys.filter { it.length >= 2 },
        )
        commonOutput.resolve("phrase_lexicon_metadata.txt").writeText(
            buildString {
                appendLine("source=https://github.com/mozillazg/phrase-pinyin-data")
                appendLine("revision=${sourceRevision.get()}")
                appendLine("pinyin_sha256=$actualHash")
                appendLine("phrase_count=${phraseEntries.size}")
                appendLine("skipped_count=${skipped.size}")
                skipped.forEach { appendLine("skipped=$it") }
            },
            Charsets.UTF_8,
        )
        val g2pwOutput = outputRoot.resolve("g2pw").apply { mkdirs() }
        g2pwOutput.resolve("polyphones.txt").writeText(
            polyphones.joinToString(separator = "\n", postfix = "\n") { (char, bopomofo, _) -> "$char\t$bopomofo" },
            Charsets.UTF_8,
        )
        g2pwOutput.resolve("labels.txt").writeText(
            polyphones.map { it.second }.distinct().sorted().joinToString(separator = "\n", postfix = "\n"),
            Charsets.UTF_8,
        )
        g2pwOutput.resolve("bopomofo_to_pinyin.tsv").writeText(
            polyphones.map { it.second to it.third }.distinct().sortedBy { it.first }
                .joinToString(separator = "\n", postfix = "\n") { (bopomofo, pinyin) -> "$bopomofo\t$pinyin" },
            Charsets.UTF_8,
        )
        g2pwOutput.resolve("piper_aliases.tsv").writeText(
            pinyinAliases.entries.joinToString(separator = "\n", postfix = "\n") { (pinyin, alias) -> "$pinyin\t$alias" },
            Charsets.UTF_8,
        )
        check(REQUIRED_PHRASES.all(phraseEntries::containsKey)) {
            "Required phrase pronunciation is missing from generated lexicon"
        }
        logger.lifecycle(
            "Generated ${phraseEntries.size} phrase pronunciations; " +
                "skipped ${skipped.size}; merged lexicon=${mergedLexicon.length()} bytes",
        )
    }

    private fun parseEntries(
        file: java.io.File,
        supportedTokens: Set<String>,
        destination: MutableMap<String, List<String>>,
        skipped: MutableList<String>,
        allowSingleCharacter: Boolean,
        overrideExisting: Boolean,
    ) {
        file.forEachLine(Charsets.UTF_8) { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEachLine
            val phrase = line.substring(0, separator).trim()
            val minimumLength = if (allowSingleCharacter) 1 else 2
            if (phrase.length < minimumLength || phrase.any(Char::isWhitespace)) return@forEachLine
            if (!overrideExisting && phrase in destination) return@forEachLine
            val syllables = line.substring(separator + 1).trim().split(Regex("\\s+"))
            if (phrase.codePointCount(0, phrase.length) != syllables.size) {
                skipped += "$phrase (character/pinyin count mismatch)"
                return@forEachLine
            }
            val tokens = syllables.flatMap(::syllableToTokens)
            val unknown = tokens.firstOrNull { it !in supportedTokens }
            if (tokens.isEmpty() || unknown != null) {
                skipped += "$phrase (unsupported token: ${unknown ?: "empty"})"
                return@forEachLine
            }
            destination[phrase] = tokens
        }
    }

    private fun syllableToTokens(markedSyllable: String): List<String> {
        var tone = markedSyllable.lastOrNull()?.digitToIntOrNull()?.takeIf { it in 1..5 } ?: 5
        val base = StringBuilder()
        val syllable = if (markedSyllable.lastOrNull()?.isDigit() == true) markedSyllable.dropLast(1) else markedSyllable
        Normalizer.normalize(syllable.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .forEach { char ->
                when (char) {
                    '\u0304' -> tone = 1
                    '\u0301' -> tone = 2
                    '\u030C' -> tone = 3
                    '\u0300' -> tone = 4
                    '\u0308' -> if (base.isNotEmpty() && base.last() == 'u') {
                        base.setCharAt(base.lastIndex, 'v')
                    }
                    '\u0302' -> Unit
                    else -> if (char in 'a'..'z') base.append(char)
                }
            }

        val normalized = base.toString()
        if (normalized.isEmpty()) return emptyList()
        if (normalized == "m" || normalized == "n") {
            return listOf("Ø", normalized, tone.toString(), "_")
        }
        val initial = INITIALS.firstOrNull(normalized::startsWith).orEmpty()
        var final = normalized.removePrefix(initial)
        final = when (final) {
            "iou" -> "iu"
            "uei" -> "ui"
            "uen" -> "un"
            else -> final
        }
        if (final.isEmpty()) return emptyList()
        return listOf(initial.ifEmpty { "Ø" }, final, tone.toString(), "_")
    }

    private fun writeTrie(file: java.io.File, phrases: Collection<String>) {
        data class Node(
            var terminal: Boolean = false,
            val children: MutableMap<Char, Int> = sortedMapOf(),
        )

        val nodes = mutableListOf(Node())
        phrases.forEach { phrase ->
            var nodeIndex = 0
            phrase.forEach { char ->
                nodeIndex = nodes[nodeIndex].children.getOrPut(char) {
                    nodes.add(Node())
                    nodes.lastIndex
                }
            }
            nodes[nodeIndex].terminal = true
        }

        val firstEdges = IntArray(nodes.size)
        val edgeCounts = IntArray(nodes.size)
        val edges = mutableListOf<Pair<Char, Int>>()
        nodes.forEachIndexed { index, node ->
            firstEdges[index] = edges.size
            edgeCounts[index] = node.children.size
            edges += node.children.entries.map { it.key to it.value }
        }

        DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
            output.writeInt(TRIE_MAGIC)
            output.writeInt(nodes.size)
            output.writeInt(edges.size)
            nodes.forEachIndexed { index, node ->
                output.writeInt(firstEdges[index])
                output.writeInt(edgeCounts[index])
                output.writeBoolean(node.terminal)
            }
            edges.forEach { (char, target) ->
                output.writeChar(char.code)
                output.writeInt(target)
            }
        }
    }

    private fun java.io.File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TRIE_MAGIC = 0x50505431
        const val PRIVATE_USE_START = 0xE000
        const val PRIVATE_USE_CAPACITY = 0xF8FF - PRIVATE_USE_START + 1
        val INITIALS = listOf(
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
            "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w",
        )
        val REQUIRED_PHRASES = setOf(
            "银行", "行长", "重新", "重复", "重量", "长大", "长度", "音乐",
            "快乐", "首都", "都是", "最差", "大夫",
        )
    }
}

val generatedPhrasePinyinAssets = layout.buildDirectory.dir("generated/phrasePinyinAssets")
val generatePhrasePinyinAssets by tasks.registering(GeneratePhrasePinyinAssets::class) {
    pinyinFile.set(layout.projectDirectory.file("src/main/phrase-pinyin-data/pinyin.txt"))
    localOverridesFile.set(layout.projectDirectory.file("src/main/phrase-pinyin-data/local_overrides.txt"))
    baseLexiconFile.set(layout.projectDirectory.file("src/main/phrase-pinyin-data/base_lexicon.txt"))
    tokensFile.set(layout.projectDirectory.file("src/main/assets/tts/piper_zh/common/tokens.txt"))
    g2pwPolyphonicFile.set(layout.projectDirectory.file("src/main/g2pw-data/POLYPHONIC_CHARS.txt"))
    bopomofoMapFile.set(layout.projectDirectory.file("src/main/g2pw-data/bopomofo_to_pinyin.json"))
    expectedPinyinSha256.set("dff030d54e9c9ba48d187fba037d00af410f01c9a867528db6899f539f6e86f7")
    sourceRevision.set("cee0ed6e6e4898580cafd2bd5e3723e20b214aa0")
    outputDirectory.set(generatedPhrasePinyinAssets)
}

android {
    namespace = "com.example.bookshelf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.bookshelf"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000/api/v1/\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("onnx", "fst", "txt", "bin")
    }

    sourceSets.getByName("main").assets.srcDir(generatedPhrasePinyinAssets)

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // This project is distributed directly inside the private network. Signing the
            // optimized build with the existing debug key keeps it upgrade-compatible.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // sherpa-onnx and the Java ORT bridge use the same 1.27.0 native runtime.
        jniLibs.pickFirsts += "**/libonnxruntime.so"
    }
}

tasks.named("preBuild").configure { dependsOn(generatePhrasePinyinAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.media3:media3-exoplayer:1.8.0")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt { correctErrorTypes = true }

room { schemaDirectory("$projectDir/schemas") }
