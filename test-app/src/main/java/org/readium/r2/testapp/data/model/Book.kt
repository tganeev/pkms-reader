package org.readium.r2.testapp.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@Entity(tableName = Book.TABLE_NAME)
data class Book(
    @PrimaryKey
    @ColumnInfo(name = ID)
    var id: Long? = null,
    @ColumnInfo(name = Bookmark.CREATION_DATE, defaultValue = "CURRENT_TIMESTAMP")
    val creation: Long? = null,
    @ColumnInfo(name = HREF)
    val href: String,
    @ColumnInfo(name = TITLE)
    val title: String?,
    @ColumnInfo(name = AUTHOR)
    val author: String? = null,
    @ColumnInfo(name = IDENTIFIER)
    val identifier: String? = null,
    @ColumnInfo(name = PROGRESSION)
    val progression: String? = null,
    @ColumnInfo(name = MEDIA_TYPE)
    val rawMediaType: String,
    @ColumnInfo(name = COVER)
    val cover: String,
    @ColumnInfo(name = READING_TIME, defaultValue = "0")
    var readingTime: Long = 0,
    @ColumnInfo(name = PAGES_READ, defaultValue = "0")
    var pagesRead: Int = 0,
    @ColumnInfo(name = LAST_READ_DATE)
    var lastReadDate: Long? = null,
) : Serializable {
    constructor(
        id: Long? = null,
        creation: Long? = null,
        href: String,
        title: String?,
        author: String? = null,
        identifier: String? = null,
        progression: String? = null,
        mediaType: MediaType,
        cover: String,
        readingTime: Long = 0,
        pagesRead: Int = 0,
        lastReadDate: Long? = null,
    ) : this(
        id = id,
        creation = creation,
        href = href,
        title = title,
        author = author,
        identifier = identifier,
        progression = progression,
        rawMediaType = mediaType.toString(),
        cover = cover,
        readingTime = readingTime,
        pagesRead = pagesRead,
        lastReadDate = lastReadDate
    )

    val url: AbsoluteUrl get() = AbsoluteUrl(href)!!

    val mediaType: MediaType get() = MediaType(rawMediaType)!!

    companion object {
        const val TABLE_NAME = "books"
        const val ID = "id"
        const val CREATION_DATE = "creation_date"
        const val HREF = "href"
        const val TITLE = "title"
        const val AUTHOR = "author"
        const val IDENTIFIER = "identifier"
        const val PROGRESSION = "progression"
        const val MEDIA_TYPE = "media_type"
        const val COVER = "cover"
        const val READING_TIME = "reading_time"
        const val PAGES_READ = "pages_read"
        const val LAST_READ_DATE = "last_read_date"
    }
}
