package org.readium.r2.testapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.readium.r2.testapp.data.model.*


@Dao
interface BooksDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Query("DELETE FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :bookId")
    suspend fun deleteBook(bookId: Long)

    @Query("SELECT * FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM " + Book.TABLE_NAME + " WHERE " + Book.IDENTIFIER + " = :identifier")
    suspend fun getBookByIdentifier(identifier: String): Book?

    @Query("SELECT * FROM " + Book.TABLE_NAME + " ORDER BY " + Book.CREATION_DATE + " desc")
    fun getAllBooks(): Flow<List<Book>>

    @Update
    suspend fun updateBook(book: Book)

    @Query(
        "UPDATE " + Book.TABLE_NAME +
            " SET " + Book.READING_TIME + " = :readingTime, " +
            Book.PAGES_READ + " = :pagesRead, " +
            Book.PROGRESSION + " = :locator, " +
            Book.LAST_READ_DATE + " = :lastReadDate " +
            " WHERE " + Book.ID + "= :id"
    )
    suspend fun updateReadingStats(
        id: Long,
        readingTime: Long,
        pagesRead: Int,
        locator: String,
        lastReadDate: Long
    )

    @Query(
        "UPDATE " + Book.TABLE_NAME +
            " SET " + Book.PROGRESSION + " = :locator WHERE " + Book.ID + "= :id"
    )
    suspend fun saveProgression(locator: String, id: Long)

    // Методы для работы с закладками
    @Query("SELECT * FROM " + Bookmark.TABLE_NAME + " WHERE " + Bookmark.BOOK_ID + " = :bookId")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    // Методы для работы с подсветками
    @Query(
        "SELECT * FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.BOOK_ID} = :bookId ORDER BY ${Highlight.TOTAL_PROGRESSION} ASC"
    )
    fun getHighlightsForBook(bookId: Long): Flow<List<Highlight>>

    @Query("SELECT * FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.ID} = :highlightId")
    suspend fun getHighlightById(highlightId: Long): Highlight?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: Highlight): Long

    @Query(
        "UPDATE ${Highlight.TABLE_NAME} SET ${Highlight.ANNOTATION} = :annotation WHERE ${Highlight.ID} = :id"
    )
    suspend fun updateHighlightAnnotation(id: Long, annotation: String)

    @Query(
        "UPDATE ${Highlight.TABLE_NAME} SET ${Highlight.TINT} = :tint, ${Highlight.STYLE} = :style WHERE ${Highlight.ID} = :id"
    )
    suspend fun updateHighlightStyle(id: Long, style: Highlight.Style, tint: Int)

    @Query("DELETE FROM " + Bookmark.TABLE_NAME + " WHERE " + Bookmark.ID + " = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.ID} = :id")
    suspend fun deleteHighlight(id: Long)

    // Методы для работы со статистикой чтения - используем String для даты
    @Query("SELECT * FROM reading_stats WHERE book_id = :bookId ORDER BY date ASC")
    fun getReadingStatsForBook(bookId: Long): Flow<List<ReadingStat>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadingStat(stat: ReadingStat)

    @Query("DELETE FROM reading_stats WHERE book_id = :bookId AND date = :date")
    suspend fun deleteReadingStat(bookId: Long, date: String)  // Используем String вместо LocalDate

    @Query("SELECT SUM(pages_read) FROM reading_stats WHERE book_id = :bookId")
    suspend fun getTotalPagesRead(bookId: Long): Int?

    @Query("SELECT SUM(hours_read) FROM reading_stats WHERE book_id = :bookId")
    suspend fun getTotalHoursRead(bookId: Long): Double?

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :bookId")
    suspend fun updateBookTitleAndAuthor(bookId: Long, title: String, author: String?)
}