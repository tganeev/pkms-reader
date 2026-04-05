// File: test-app/src/main/java/org/readium/r2/testapp/data/BookRepository.kt

package org.readium.r2.testapp.data

import androidx.annotation.ColorInt
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import org.joda.time.DateTime
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.testapp.data.db.BooksDao
import org.readium.r2.testapp.data.model.Book
import org.readium.r2.testapp.data.model.Bookmark
import org.readium.r2.testapp.data.model.Highlight
import org.readium.r2.testapp.data.model.ReadingStat
import org.readium.r2.testapp.utils.extensions.readium.authorName

class BookRepository(
    private val booksDao: BooksDao,
) {
    fun books(): Flow<List<Book>> = booksDao.getAllBooks()

    suspend fun get(id: Long) = booksDao.get(id)

    suspend fun saveProgression(locator: Locator, bookId: Long) =
        booksDao.saveProgression(locator.toJSON().toString(), bookId)

    // Обновление статистики чтения
    suspend fun updateReadingStats(
        bookId: Long,
        readingTime: Long,
        pagesRead: Int,
        locator: Locator
    ) {
        booksDao.updateReadingStats(
            id = bookId,
            readingTime = readingTime,
            pagesRead = pagesRead,
            locator = locator.toJSON().toString(),
            lastReadDate = System.currentTimeMillis()
        )
    }

    // Получение книги для обновления
    suspend fun getBook(bookId: Long): Book? = booksDao.get(bookId)

    suspend fun updateBook(book: Book) = booksDao.updateBook(book)

    suspend fun insertBookmark(bookId: Long, publication: Publication, locator: Locator): Long {
        val resource = publication.readingOrder.indexOfFirstWithHref(locator.href)!!
        val bookmark = Bookmark(
            creation = DateTime().toDate().time,
            bookId = bookId,
            resourceIndex = resource.toLong(),
            resourceHref = locator.href.toString(),
            resourceType = locator.mediaType.toString(),
            resourceTitle = locator.title.orEmpty(),
            location = locator.locations.toJSON().toString(),
            locatorText = Locator.Text().toJSON().toString()
        )

        return booksDao.insertBookmark(bookmark)
    }

    fun bookmarksForBook(bookId: Long): Flow<List<Bookmark>> =
        booksDao.getBookmarksForBook(bookId)

    suspend fun deleteBookmark(bookmarkId: Long) = booksDao.deleteBookmark(bookmarkId)

    suspend fun highlightById(id: Long): Highlight? =
        booksDao.getHighlightById(id)

    suspend fun updateBookTitleAndAuthor(bookId: Long, title: String, author: String?) {
        booksDao.updateBookTitleAndAuthor(bookId, title, author)
    }



    fun highlightsForBook(bookId: Long): Flow<List<Highlight>> =
        booksDao.getHighlightsForBook(bookId)

    suspend fun addHighlight(
        bookId: Long,
        style: Highlight.Style,
        @ColorInt tint: Int,
        locator: Locator,
        annotation: String,
    ): Long =
        booksDao.insertHighlight(Highlight(bookId, style, tint, locator, annotation))

    suspend fun deleteHighlight(id: Long) = booksDao.deleteHighlight(id)

    suspend fun updateHighlightAnnotation(id: Long, annotation: String) {
        booksDao.updateHighlightAnnotation(id, annotation)
    }

    suspend fun updateHighlightStyle(id: Long, style: Highlight.Style, @ColorInt tint: Int) {
        booksDao.updateHighlightStyle(id, style, tint)
    }

    suspend fun insertBook(
        url: Url,
        mediaType: MediaType,
        publication: Publication,
        cover: File,
    ): Long {
        val book = Book(
            creation = DateTime().toDate().time,
            title = publication.metadata.title ?: url.filename,
            author = publication.metadata.authorName,
            identifier = publication.metadata.identifier, // Добавляем identifier
            href = url.toString(),
            mediaType = mediaType,
            progression = "{}",
            cover = cover.path,
            readingTime = 0,
            pagesRead = 0,
            lastReadDate = null
        )
        return booksDao.insertBook(book)
    }

    fun getReadingStatsForBook(bookId: Long): Flow<List<ReadingStat>> =
        booksDao.getReadingStatsForBook(bookId)

    suspend fun saveReadingStat(stat: ReadingStat) {
        booksDao.insertReadingStat(stat)
    }

    suspend fun deleteReadingStat(bookId: Long, date: LocalDate) {
        // Конвертируем LocalDate в строку для запроса
        booksDao.deleteReadingStat(bookId, date.toString())
    }

    suspend fun getTotalPagesRead(bookId: Long): Int =
        booksDao.getTotalPagesRead(bookId) ?: 0

    suspend fun getTotalHoursRead(bookId: Long): Double =
        booksDao.getTotalHoursRead(bookId) ?: 0.0

    suspend fun deleteBook(id: Long) =
        booksDao.deleteBook(id)


}