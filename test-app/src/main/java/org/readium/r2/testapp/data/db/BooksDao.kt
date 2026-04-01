// File: test-app/src/main/java/org/readium/r2/testapp/data/db/BooksDao.kt

package org.readium.r2.testapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.readium.r2.testapp.data.model.Book
import org.readium.r2.testapp.data.model.Bookmark
import org.readium.r2.testapp.data.model.Highlight

@Dao
interface BooksDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Query("DELETE FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :bookId")
    suspend fun deleteBook(bookId: Long)

    @Query("SELECT * FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM " + Book.TABLE_NAME + " ORDER BY " + Book.CREATION_DATE + " desc")
    fun getAllBooks(): Flow<List<Book>>

    // Обновление статистики чтения
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

    // Остальные методы...
    @Query("SELECT * FROM " + Bookmark.TABLE_NAME + " WHERE " + Bookmark.BOOK_ID + " = :bookId")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

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
}