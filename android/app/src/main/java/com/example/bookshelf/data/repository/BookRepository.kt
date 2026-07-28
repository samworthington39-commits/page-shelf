package com.example.bookshelf.data.repository

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.example.bookshelf.data.local.BookDao
import com.example.bookshelf.data.local.CachedBookEntity
import com.example.bookshelf.data.local.DownloadDao
import com.example.bookshelf.data.remote.BooksApi
import com.example.bookshelf.data.settings.ServerConfigStore
import com.example.bookshelf.data.settings.ShelfAccessStore
import com.example.bookshelf.data.settings.SecureCredentialStore
import com.example.bookshelf.domain.Book
import com.example.bookshelf.domain.Capabilities
import com.example.bookshelf.domain.PdfNavigationItem
import com.example.bookshelf.domain.LibraryShelf
import com.example.bookshelf.data.remote.ShelfUnlockRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class BookRepository(
    private val api: BooksApi,
    private val dao: BookDao,
    private val configStore: ServerConfigStore,
    private val shelfAccess: ShelfAccessStore,
    private val downloadDao: DownloadDao,
    private val credentials: SecureCredentialStore,
) {
    private val memory = ConcurrentHashMap<String, Book>()

    suspend fun shelves(): List<LibraryShelf> = try {
        api.shelves().map { shelf ->
            val books = shelf.books.map { it.toDomain() }
            rememberBooks(books)
            shelfAccess.register(shelf.id, books)
            dao.upsertAll(books.map(Book::toEntity))
            LibraryShelf(
                shelf.id,
                shelf.name,
                shelf.isHidden,
                shelf.locked,
                shelf.bookCount,
                shelf.totalBytes,
                books,
            )
        }
    } catch (error: Exception) {
        val cached = offlineBooks()
        if (cached.isEmpty()) throw error
        rememberBooks(cached)
        listOf(
            LibraryShelf(
                id = "__offline__",
                name = "离线书架",
                isHidden = false,
                locked = false,
                bookCount = cached.size,
                totalBytes = cached.sumOf(Book::fileSize),
                books = cached,
            )
        )
    }

    suspend fun unlockShelf(shelfId: String, pin: String): LibraryShelf {
        val shelf = api.unlockShelf(shelfId, ShelfUnlockRequest(pin))
        val books = shelf.books.map { it.toDomain() }
        rememberBooks(books)
        shelfAccess.register(shelf.id, books, pin)
        dao.upsertAll(books.map(Book::toEntity))
        return LibraryShelf(
            shelf.id,
            shelf.name,
            shelf.isHidden,
            shelf.locked,
            shelf.bookCount,
            shelf.totalBytes,
            books,
        )
    }

    suspend fun books(): List<Book> = try {
        api.books().map { it.toDomain() }.also {
            rememberBooks(it)
            dao.upsertAll(it.map(Book::toEntity))
        }
    } catch (error: Exception) {
        offlineBooks()
            .ifEmpty { throw error }
            .also(::rememberBooks)
    }

    suspend fun hasOfflineBooks(): Boolean = offlineBooks().isNotEmpty()

    suspend fun book(bookId: String): Book {
        memory[bookId]?.let { return it }
        dao.byId(bookId)?.toDomain()?.let { cached ->
            memory[bookId] = cached
            return cached
        }
        return api.book(bookId).toDomain().also { fetched ->
            memory[bookId] = fetched
            dao.upsertAll(listOf(fetched.toEntity()))
        }
    }

    suspend fun pdfNavigation(bookId: String): List<PdfNavigationItem> =
        api.pdfNavigation(bookId).items.map { it.toDomain() }

    fun coverUrl(bookId: String): String = "${configStore.current().apiBaseUrl}books/$bookId/cover"

    fun coverRequest(context: Context, bookId: String): ImageRequest {
        val builder = ImageRequest.Builder(context).data(coverUrl(bookId))
        credentials.bearerToken()?.let { token ->
            builder.httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $token").build())
        }
        return builder.build()
    }

    private fun rememberBooks(books: List<Book>) {
        books.forEach { book -> memory[book.id] = book }
    }

    private suspend fun offlineBooks(): List<Book> {
        val available = downloadDao.permanent()
            .filter { download -> download.localPath?.let(::File)?.isFile == true }
            .map { it.bookId }
            .toSet()
        if (available.isEmpty()) return emptyList()
        return dao.all().filter { it.id in available }.map(CachedBookEntity::toDomain)
    }
}

private const val WARNING_SEPARATOR = "\u001E"

private fun Book.toEntity() = CachedBookEntity(
    id = id,
    shelfId = shelfId,
    format = format,
    title = title,
    author = author,
    subject = subject,
    pageCount = pageCount,
    chapterCount = chapterCount,
    fileSize = fileSize,
    fingerprint = fingerprint,
    fileFingerprint = fileFingerprint,
    mimeType = mimeType,
    coverStatus = coverStatus,
    parseStatus = parseStatus,
    parseWarnings = parseWarnings.joinToString(WARNING_SEPARATOR),
    passwordRequired = passwordRequired,
    canOpen = canOpen,
    hasPdfNavigation = hasPdfNavigation,
    chaptersCapability = capabilities.chapters,
    reflowableTextCapability = capabilities.reflowableText,
    fontSettingsCapability = capabilities.fontSettings,
    pageNavigationCapability = capabilities.pageNavigation,
    zoomCapability = capabilities.zoom,
    offlineDownloadCapability = capabilities.offlineDownload,
    progressSyncCapability = capabilities.progressSync,
)

private fun CachedBookEntity.toDomain() = Book(
    id = id,
    shelfId = shelfId,
    format = format,
    title = title,
    author = author,
    subject = subject,
    pageCount = pageCount,
    chapterCount = chapterCount,
    fileSize = fileSize,
    fileFingerprint = fileFingerprint ?: fingerprint.substringBefore(':'),
    fingerprint = fingerprint,
    mimeType = mimeType,
    coverStatus = coverStatus,
    parseStatus = parseStatus,
    parseWarnings = parseWarnings.split(WARNING_SEPARATOR).filter(String::isNotBlank),
    passwordRequired = passwordRequired,
    canOpen = canOpen,
    hasPdfNavigation = hasPdfNavigation,
    capabilities = Capabilities(
        chaptersCapability, reflowableTextCapability, fontSettingsCapability, pageNavigationCapability,
        zoomCapability, offlineDownloadCapability, progressSyncCapability,
    ),
)
