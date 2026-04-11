package org.readium.r2.testapp.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
    tableName = "reading_stats",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "date"], unique = true)
    ]
)
data class ReadingStat(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "book_id")
    val bookId: Long,

    @ColumnInfo(name = "date")
    val date: LocalDate, // LocalDate будет автоматически конвертироваться через TypeConverter

    @ColumnInfo(name = "pages_read", defaultValue = "0")
    val pagesRead: Int = 0,

    @ColumnInfo(name = "hours_read", defaultValue = "0")
    val hoursRead: Double = 0.0,
)
