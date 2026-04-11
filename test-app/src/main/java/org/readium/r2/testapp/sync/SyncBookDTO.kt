package org.readium.r2.testapp.sync

import com.google.gson.annotations.SerializedName

data class SyncBookDTO(
    @SerializedName("identifier")
    val identifier: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("author")
    val author: String? = null,

    @SerializedName("totalPages")
    val totalPages: Int? = null,

    @SerializedName("language")
    val language: String? = null,

    @SerializedName("categoryId")
    val categoryId: Long? = null,

    @SerializedName("readingStats")
    val readingStats: List<SyncReadingStatDTO> = emptyList(),
)

data class SyncReadingStatDTO(
    @SerializedName("date")
    val date: String, // Используем String для даты в формате ISO

    @SerializedName("pagesRead")
    val pagesRead: Int,

    @SerializedName("hoursRead")
    val hoursRead: Double,
)

data class SyncRequestDTO(
    @SerializedName("username")
    val username: String,

    @SerializedName("books")
    val books: List<SyncBookDTO>,
)

data class SyncResponseDTO(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("booksCreated")
    val booksCreated: Int,

    @SerializedName("booksUpdated")
    val booksUpdated: Int,

    @SerializedName("statsCreated")
    val statsCreated: Int,

    @SerializedName("statsUpdated")
    val statsUpdated: Int,

    @SerializedName("message")
    val message: String,

    @SerializedName("error")
    val error: String? = null,
)
