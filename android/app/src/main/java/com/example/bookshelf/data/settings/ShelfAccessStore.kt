package com.example.bookshelf.data.settings

import com.example.bookshelf.domain.Book

class ShelfAccessStore {
    private val shelfPins = mutableMapOf<String, String>()
    private val bookShelves = mutableMapOf<String, String>()

    @Synchronized
    fun register(shelfId: String, books: List<Book>, pin: String? = null) {
        books.forEach { book -> bookShelves[book.id] = shelfId }
        if (pin != null) shelfPins[shelfId] = pin
    }

    @Synchronized
    fun pinForBook(bookId: String): String? = bookShelves[bookId]?.let(shelfPins::get)
}
