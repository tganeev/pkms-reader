/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalReadiumApi::class)

package org.readium.r2.testapp.reader

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.LocatorCollection
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.SearchTry
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.testapp.Application
import org.readium.r2.testapp.R
import org.readium.r2.testapp.data.BookRepository
import org.readium.r2.testapp.data.model.Highlight
import org.readium.r2.testapp.data.model.ReadingStat
import org.readium.r2.testapp.domain.toUserError
import org.readium.r2.testapp.reader.preferences.UserPreferencesViewModel
import org.readium.r2.testapp.reader.tts.TtsViewModel
import org.readium.r2.testapp.search.SearchPagingSource
import org.readium.r2.testapp.utils.EventChannel
import org.readium.r2.testapp.utils.UserError
import org.readium.r2.testapp.utils.createViewModelFactory
import org.readium.r2.testapp.utils.extensions.toHtml
import timber.log.Timber

@OptIn(ExperimentalReadiumApi::class)
class ReaderViewModel(
    private val bookId: Long,
    private val readerRepository: ReaderRepository,
    private val bookRepository: BookRepository,
) : ViewModel(),
    EpubNavigatorFragment.Listener,
    ImageNavigatorFragment.Listener,
    PdfNavigatorFragment.Listener {

    val readerInitData =
        try {
            checkNotNull(readerRepository[bookId])
        } catch (e: Exception) {
            DummyReaderInitData(bookId)
        }

    val publication: Publication =
        readerInitData.publication

    val activityChannel: EventChannel<ActivityCommand> =
        EventChannel(Channel(Channel.BUFFERED), viewModelScope)

    val fragmentChannel: EventChannel<FragmentFeedback> =
        EventChannel(Channel(Channel.BUFFERED), viewModelScope)

    val visualFragmentChannel: EventChannel<VisualFragmentCommand> =
        EventChannel(Channel(Channel.BUFFERED), viewModelScope)

    val searchChannel: EventChannel<SearchCommand> =
        EventChannel(Channel(Channel.BUFFERED), viewModelScope)

    val tts: TtsViewModel? = TtsViewModel(
        viewModelScope = viewModelScope,
        readerInitData = readerInitData
    )

    val settings: UserPreferencesViewModel<*, *>? = UserPreferencesViewModel(
        viewModelScope = viewModelScope,
        readerInitData = readerInitData
    )

    // ===== ТАЙМЕР ЧТЕНИЯ И СТАТИСТИКА =====

    private val readingTimer = ReadingTimer(viewModelScope)
    val readingTime: StateFlow<Long> = readingTimer.elapsedSeconds

    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    private var lastSavedPage: Int = 0
    private var maxReachedPage: Int = 0
    private var _totalPositions: Int = 0
    val totalPositions: Int get() = _totalPositions

    private var lastSavedTotalSeconds: Long = 0

    init {
        viewModelScope.launch {
            bookRepository.get(bookId)?.let { book ->
                readingTimer.setTime(book.readingTime)
                lastSavedTotalSeconds = book.readingTime // ✅ Инициализируем точку отсчета
                lastSavedPage = book.pagesRead
                maxReachedPage = book.pagesRead
                Timber.d("Loaded stats: time=${book.readingTime}s, pages=${book.pagesRead}")
            }

            try {
                _totalPositions = publication.positions().size
                Timber.d("Total positions for book $bookId: $_totalPositions")
            } catch (e: Exception) {
                Timber.e(e, "Failed to get total positions, using fallback")
                _totalPositions = 100 // fallback для PDF
            }
        }
    }
    fun startReadingTimer() {
        readingTimer.start()
        Timber.d("Reading timer started for book $bookId")
    }

    fun pauseReadingTimer() {
        readingTimer.pause()
        Timber.d("Reading timer paused for book $bookId, elapsed: ${readingTimer.elapsedSeconds.value}s")
    }

    fun updateCurrentLocator(locator: Locator) {
        _currentLocator.value = locator
    }

    private fun getCurrentLocator(): Locator {
        _currentLocator.value?.let { return it }
        return when (val data = readerInitData) {
            is VisualReaderInitData -> data.initialLocation
            is MediaReaderInitData -> null
            else -> null
        } ?: publication.locatorFromLink(publication.readingOrder.firstOrNull()!!)!!
    }

    /**
     * Вычисляет номер текущей страницы на основе локатора
     * Работает для EPUB, PDF и других форматов
     */
    fun calculateCurrentPage(locator: Locator): Int {
        val position = locator.locations.position ?: 1
        return position.coerceAtLeast(1)
    }

    fun getFormattedReadingTime(): String {
        return readingTimer.formatTime()
    }

    fun getCurrentPage(): Int {
        val locator = _currentLocator.value ?: return lastSavedPage
        return calculateCurrentPage(locator)
    }

    // ===== СОХРАНЕНИЕ ПРОГРЕССА =====

    fun saveProgression(locator: Locator) = viewModelScope.launch {
        Timber.d("=== SAVE PROGRESSION ===")
        Timber.d("bookId: $bookId")
        Timber.d("locator href: ${locator.href}")
        Timber.d("locator position: ${locator.locations.position}")
        Timber.d("locator progression: ${locator.locations.totalProgression}")

        updateCurrentLocator(locator)
        bookRepository.saveProgression(locator, bookId)
        savePagesAndTime()
    }

    private suspend fun savePagesAndTime() {
        val currentTotalSeconds = readingTimer.elapsedSeconds.value
        val deltaSeconds = (currentTotalSeconds - lastSavedTotalSeconds).coerceAtLeast(0)

        // Если время не изменилось, нет смысла писать в БД
        if (deltaSeconds == 0L) return

        val currentLocator = getCurrentLocator()
        val currentPage = calculateCurrentPage(currentLocator)

        // Обновляем максимум страниц
        if (currentPage > maxReachedPage) maxReachedPage = currentPage
        val incrementalPages = maxOf(0, maxReachedPage - lastSavedPage)

        val today = LocalDate.now()
        val existingStats = bookRepository.getReadingStatsForBook(bookId).first()
        val todayStat = existingStats.find { it.date == today }

        // 1. Сохраняем/обновляем запись за сегодня (для графиков и синхронизации)
        val deltaHours = deltaSeconds / 3600.0
        val newDailyHours = (todayStat?.hoursRead ?: 0.0) + deltaHours
        val newDailyPages = (todayStat?.pagesRead ?: 0) + incrementalPages

        bookRepository.saveReadingStat(
            ReadingStat(
                bookId = bookId,
                date = today,
                pagesRead = newDailyPages,
                hoursRead = newDailyHours
            )
        )

        // 2. Обновляем ОБЩЕЕ время на обложке НАПРЯМУЮ (без SUM, без потери точности)
        val book = bookRepository.get(bookId)
        val newTotalReadingTime = (book?.readingTime ?: 0L) + deltaSeconds
        val newTotalPages = (book?.pagesRead ?: 0) + incrementalPages

        bookRepository.updateReadingStats(
            bookId = bookId,
            readingTime = newTotalReadingTime,
            pagesRead = newTotalPages,
            locator = currentLocator
        )

        Timber.d("Saved delta: ${deltaSeconds}s | Total now: ${newTotalReadingTime}s | Pages: +$incrementalPages")

        // ✅ Сдвигаем точку отсчета на текущее время
        lastSavedTotalSeconds = currentTotalSeconds
        lastSavedPage = maxReachedPage
    }

    // ===== ЗАКЛАДКИ =====

    fun getBookmarks() = bookRepository.bookmarksForBook(bookId)

    fun insertBookmark(locator: Locator) = viewModelScope.launch {
        val id = bookRepository.insertBookmark(bookId, publication, locator)
        if (id != -1L) {
            fragmentChannel.send(FragmentFeedback.BookmarkSuccessfullyAdded)
        } else {
            fragmentChannel.send(FragmentFeedback.BookmarkFailed)
        }
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch {
        bookRepository.deleteBookmark(id)
    }

    // ===== ВЫДЕЛЕНИЯ (HIGHLIGHTS) =====

    val highlights: Flow<List<Highlight>> by lazy {
        bookRepository.highlightsForBook(bookId)
    }

    var activeHighlightId = MutableStateFlow<Long?>(null)

    val highlightDecorations: Flow<List<Decoration>> by lazy {
        highlights.combine(activeHighlightId) { highlights, activeId ->
            highlights.flatMap { highlight ->
                highlight.toDecorations(isActive = (highlight.id == activeId))
            }
        }
    }

    private fun Highlight.toDecorations(isActive: Boolean): List<Decoration> {
        fun createDecoration(idSuffix: String, style: Decoration.Style) = Decoration(
            id = "$id-$idSuffix",
            locator = locator,
            style = style,
            extras = mapOf("id" to id)
        )

        return listOfNotNull(
            createDecoration(
                idSuffix = "highlight",
                style = when (style) {
                    Highlight.Style.HIGHLIGHT -> Decoration.Style.Highlight(
                        tint = tint,
                        isActive = isActive
                    )
                    Highlight.Style.UNDERLINE -> Decoration.Style.Underline(
                        tint = tint,
                        isActive = isActive
                    )
                }
            ),
            annotation.takeIf { it.isNotEmpty() }?.let {
                createDecoration(
                    idSuffix = "annotation",
                    style = DecorationStyleAnnotationMark(tint = tint)
                )
            }
        )
    }

    suspend fun highlightById(id: Long): Highlight? =
        bookRepository.highlightById(id)

    fun addHighlight(
        locator: Locator,
        style: Highlight.Style,
        @ColorInt tint: Int,
        annotation: String = "",
    ) = viewModelScope.launch {
        bookRepository.addHighlight(bookId, style, tint, locator, annotation)
    }

    fun updateHighlightAnnotation(id: Long, annotation: String) = viewModelScope.launch {
        bookRepository.updateHighlightAnnotation(id, annotation)
    }

    fun updateHighlightStyle(id: Long, style: Highlight.Style, @ColorInt tint: Int) = viewModelScope.launch {
        bookRepository.updateHighlightStyle(id, style, tint)
    }

    fun deleteHighlight(id: Long) = viewModelScope.launch {
        bookRepository.deleteHighlight(id)
    }

    // ===== ПОИСК =====

    fun search(query: String) = viewModelScope.launch {
        if (query == lastSearchQuery) return@launch
        lastSearchQuery = query
        _searchLocators.value = emptyList()
        searchIterator = publication.search(query)
            ?: run {
                activityChannel.send(
                    ActivityCommand.ToastError(
                        UserError(R.string.search_error_not_searchable, cause = null)
                    )
                )
                null
            }
        pagingSourceFactory.invalidate()
        searchChannel.send(SearchCommand.StartNewSearch)
    }

    fun cancelSearch() = viewModelScope.launch {
        _searchLocators.value = emptyList()
        searchIterator?.close()
        searchIterator = null
        pagingSourceFactory.invalidate()
    }

    val searchLocators: StateFlow<List<Locator>> get() = _searchLocators
    private var _searchLocators = MutableStateFlow<List<Locator>>(emptyList())

    val searchDecorations: Flow<List<Decoration>> by lazy {
        searchLocators.map {
            it.mapIndexed { index, locator ->
                Decoration(
                    id = index.toString(),
                    locator = locator,
                    style = Decoration.Style.Underline(tint = Color.RED)
                )
            }
        }
    }

    private var lastSearchQuery: String? = null
    private var searchIterator: SearchIterator? = null
    private val pagingSourceFactory = InvalidatingPagingSourceFactory {
        SearchPagingSource(listener = PagingSourceListener())
    }

    val searchResult: Flow<PagingData<Locator>> =
        Pager(PagingConfig(pageSize = 20), pagingSourceFactory = pagingSourceFactory)
            .flow.cachedIn(viewModelScope)

    // ===== NAVIGATOR LISTENER =====

    override fun onResourceLoadFailed(href: Url, error: ReadError) {
        activityChannel.send(
            ActivityCommand.ToastError(error.toUserError())
        )
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        activityChannel.send(ActivityCommand.OpenExternalLink(url))
    }

    override fun shouldFollowInternalLink(
        link: Link,
        context: HyperlinkNavigator.LinkContext?,
    ): Boolean =
        when (context) {
            is HyperlinkNavigator.FootnoteContext -> {
                val text = if (link.mediaType?.isHtml == true) {
                    context.noteContent.toHtml()
                } else {
                    context.noteContent
                }
                visualFragmentChannel.send(VisualFragmentCommand.ShowPopup(text))
                false
            }
            else -> true
        }

    // ===== SEARCH PAGING SOURCE =====

    inner class PagingSourceListener : SearchPagingSource.Listener {
        override suspend fun next(): SearchTry<LocatorCollection?> {
            val iterator = searchIterator ?: return Try.success(null)
            return iterator.next().onSuccess {
                _searchLocators.value += (it?.locators ?: emptyList())
            }
        }
    }

    // ===== ON CLEARED =====

    override fun onCleared() {
        viewModelScope.launch {
            savePagesAndTime() // ✅ Гарантированно сохраняем "хвост" времени перед уничтожением VM
        }
        readingTimer.pause()
        readerRepository.close(bookId)
    }

    // ===== EVENTS =====

    sealed class ActivityCommand {
        object OpenOutlineRequested : ActivityCommand()
        object OpenDrmManagementRequested : ActivityCommand()
        class OpenExternalLink(val url: AbsoluteUrl) : ActivityCommand()
        class ToastError(val error: UserError) : ActivityCommand()
    }

    sealed class FragmentFeedback {
        object BookmarkSuccessfullyAdded : FragmentFeedback()
        object BookmarkFailed : FragmentFeedback()
    }

    sealed class VisualFragmentCommand {
        class ShowPopup(val text: CharSequence) : VisualFragmentCommand()
    }

    sealed class SearchCommand {
        object StartNewSearch : SearchCommand()
    }

    companion object {
        fun createFactory(application: Application, arguments: ReaderActivityContract.Arguments) =
            createViewModelFactory {
                ReaderViewModel(
                    arguments.bookId,
                    application.readerRepository,
                    application.bookRepository
                )
            }
    }
}
