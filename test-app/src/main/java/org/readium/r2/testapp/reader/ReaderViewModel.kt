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
    private var maxReachedPage: Int = 0

) : ViewModel(),
    EpubNavigatorFragment.Listener,
    ImageNavigatorFragment.Listener,
    PdfNavigatorFragment.Listener {

    val readerInitData =
        try {
            checkNotNull(readerRepository[bookId])
        } catch (e: Exception) {
            // Fallbacks on a dummy Publication to avoid crashing the app until the Activity finishes.
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

    // ===== НОВЫЙ КОД: Таймер чтения и статистика =====

    /**
     * Таймер для отслеживания времени чтения
     */
    private val readingTimer = ReadingTimer(viewModelScope)
    val readingTime: StateFlow<Long> = readingTimer.elapsedSeconds

    /**
     * Текущий локатор (обновляется из навигатора при каждом изменении)
     */
    private val _currentLocator = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

    /**
     * Сохраненные значения для вычисления инкрементальных изменений
     */
    private var lastSavedPosition: Locator? = null
    private var lastSavedPage: Int = 0
    private var lastSavedTime: Long = 0
    private var _totalPositions: Int = 0
    val totalPositions: Int get() = _totalPositions

    /**
     * Загрузка сохраненной статистики при инициализации
     */
    init {
        viewModelScope.launch {
            bookRepository.get(bookId)?.let { book ->
                readingTimer.setTime(book.readingTime)
                lastSavedPage = book.pagesRead
                maxReachedPage = book.pagesRead  // Загружаем максимальную страницу
                Timber.d("Loaded stats for book $bookId: time=${book.readingTime}s, pages=${book.pagesRead}")
            }

            _totalPositions = publication.positions().size
            Timber.d("Total positions for book $bookId: $_totalPositions")
        }
    }

    /**
     * Запускает таймер чтения
     */
    fun startReadingTimer() {
        readingTimer.start()
        Timber.d("Reading timer started for book $bookId")
    }

    /**
     * Приостанавливает таймер чтения
     */
    fun pauseReadingTimer() {
        readingTimer.pause()
        Timber.d("Reading timer paused")
        viewModelScope.launch {
            saveReadingStats(updateTime = true)
        }
    }

    /**
     * Обновляет текущий локатор из навигатора
     */
    fun updateCurrentLocator(locator: Locator) {
        _currentLocator.value = locator
    }

    /**
     * Получает текущий локатор (из навигатора или fallback)
     */
    private fun getCurrentLocator(): Locator {
        // Сначала пытаемся получить актуальный локатор из навигатора
        _currentLocator.value?.let { return it }

        // Fallback: используем initialLocation
        return when (val data = readerInitData) {
            is VisualReaderInitData -> data.initialLocation
            is MediaReaderInitData -> null
            else -> null
        } ?: publication.locatorFromLink(publication.readingOrder.firstOrNull()!!)!!
    }

    /**
     * Вычисляет номер текущей страницы на основе локатора
     */
    private fun calculateCurrentPage(locator: Locator): Int {
        val position = locator.locations.position ?: 1
        return position.coerceAtLeast(1)
    }

    /**
     * Сохраняет текущую статистику чтения в базу данных
     */
    private suspend fun saveReadingStats() {
        val currentLocator = getCurrentLocator()
        val currentPage = calculateCurrentPage(currentLocator)
        val currentTime = readingTimer.elapsedSeconds.value

        // Обновляем максимальную достигнутую страницу
        if (currentPage > maxReachedPage) {
            maxReachedPage = currentPage
        }

        // Для отображения на обложке используем МАКСИМАЛЬНУЮ страницу
        val displayPage = maxReachedPage

        val today = LocalDate.now()

        // Получаем существующую статистику за сегодня
        val existingStats = bookRepository.getReadingStatsForBook(bookId).first()
        val todayStat = existingStats.find { it.date == today }

        // Вычисляем инкрементальные значения на основе МАКСИМАЛЬНОЙ страницы
        val incrementalPages = if (lastSavedPage > 0) {
            maxOf(0, displayPage - lastSavedPage)
        } else {
            displayPage
        }

        val incrementalHours = if (lastSavedTime > 0) {
            maxOf(0.0, (currentTime - lastSavedTime) / 3600.0)
        } else {
            currentTime / 3600.0
        }

        // Если есть изменения, сохраняем
        if (incrementalPages > 0 || incrementalHours > 0) {
            // Сохраняем ежедневную статистику
            val readingStat = ReadingStat(
                bookId = bookId,
                date = today,
                pagesRead = (todayStat?.pagesRead ?: 0) + incrementalPages,
                hoursRead = (todayStat?.hoursRead ?: 0.0) + incrementalHours
            )
            bookRepository.saveReadingStat(readingStat)

            // Обновляем агрегированные значения в книге
            val totalPages = bookRepository.getTotalPagesRead(bookId)
            val totalHours = bookRepository.getTotalHoursRead(bookId)

            bookRepository.updateReadingStats(
                bookId = bookId,
                readingTime = (totalHours * 3600).toLong(),
                pagesRead = totalPages,
                locator = currentLocator
            )

            Timber.d("Saved stats - Current page: $currentPage, Max page: $maxReachedPage, Display page: $displayPage, Increment: +$incrementalPages")
        }

        // Обновляем сохраненные значения
        lastSavedPage = displayPage
        lastSavedTime = currentTime
    }


    /**
     * Получает отформатированное время чтения для отображения
     */
    fun getFormattedReadingTime(): String {
        return readingTimer.formatTime()
    }

    /**
     * Получает текущий прогресс чтения в процентах
     */
    suspend fun getReadingProgress(): Float {
        val currentLocator = getCurrentLocator()
        val currentPage = calculateCurrentPage(currentLocator)
        return if (totalPositions > 0) {
            currentPage.toFloat() / totalPositions.toFloat()
        } else {
            0f
        }
    }

    // ===== СОХРАНЕНИЕ ПРОГРЕССА =====

    /**
     * Сохраняет прогресс (вызывается при каждом перелистывании)
     * Сохраняет только позицию, НЕ время
     */
    fun saveProgression(locator: Locator) = viewModelScope.launch {
        Timber.v("Saving locator for book $bookId: $locator.")
        updateCurrentLocator(locator)
        bookRepository.saveProgression(locator, bookId)

        // Сохраняем ТОЛЬКО страницы, время не трогаем
        saveReadingStats(updateTime = false)
    }

    /**
     * Сохраняет полную статистику (вызывается при паузе/закрытии)
     */
    private suspend fun saveFullStats() {
        saveReadingStats(updateTime = true)
    }

    /**
     * Сохраняет статистику
     * @param updateTime - если true, обновляет время; если false - только страницы
     */
    private suspend fun saveReadingStats(updateTime: Boolean = true) {
        val currentLocator = getCurrentLocator()
        val currentPage = calculateCurrentPage(currentLocator)

        // Обновляем максимальную достигнутую страницу
        if (currentPage > maxReachedPage) {
            maxReachedPage = currentPage
        }

        val displayPage = maxReachedPage

        // Время обновляем только если нужно
        val currentTime = if (updateTime) readingTimer.elapsedSeconds.value else lastSavedTime

        val today = LocalDate.now()
        val existingStats = bookRepository.getReadingStatsForBook(bookId).first()
        val todayStat = existingStats.find { it.date == today }

        // Вычисляем добавленные страницы
        val incrementalPages = if (displayPage > lastSavedPage) {
            displayPage - lastSavedPage
        } else {
            0
        }

        // Вычисляем добавленное время (только если updateTime = true)
        val incrementalHours = if (updateTime && lastSavedTime > 0 && currentTime > lastSavedTime) {
            (currentTime - lastSavedTime) / 3600.0
        } else if (updateTime && lastSavedTime == 0L) {
            currentTime / 3600.0
        } else {
            0.0
        }

        if (incrementalPages > 0 || incrementalHours > 0) {
            val readingStat = ReadingStat(
                bookId = bookId,
                date = today,
                pagesRead = (todayStat?.pagesRead ?: 0) + incrementalPages,
                hoursRead = (todayStat?.hoursRead ?: 0.0) + incrementalHours
            )
            bookRepository.saveReadingStat(readingStat)

            val totalPages = bookRepository.getTotalPagesRead(bookId)
            val totalHours = bookRepository.getTotalHoursRead(bookId)

            bookRepository.updateReadingStats(
                bookId = bookId,
                readingTime = (totalHours * 3600).toLong(),
                pagesRead = totalPages,
                locator = currentLocator
            )

            Timber.d("Saved stats - Pages: +$incrementalPages, Hours: +$incrementalHours")
        }

        lastSavedPage = displayPage
        if (updateTime) {
            lastSavedTime = currentTime
        }
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

    /**
     * Database ID of the active highlight for the current highlight pop-up. This is used to show
     * the highlight decoration in an "active" state.
     */
    var activeHighlightId = MutableStateFlow<Long?>(null)

    /**
     * Current state of the highlight decorations.
     *
     * It will automatically be updated when the highlights database table or the current
     * [activeHighlightId] change.
     */
    val highlightDecorations: Flow<List<Decoration>> by lazy {
        highlights.combine(activeHighlightId) { highlights, activeId ->
            highlights.flatMap { highlight ->
                highlight.toDecorations(isActive = (highlight.id == activeId))
            }
        }
    }

    /**
     * Creates a list of [Decoration] for the receiver [Highlight].
     */
    private fun Highlight.toDecorations(isActive: Boolean): List<Decoration> {
        fun createDecoration(idSuffix: String, style: Decoration.Style) = Decoration(
            id = "$id-$idSuffix",
            locator = locator,
            style = style,
            extras = mapOf(
                "id" to id
            )
        )

        return listOfNotNull(
            // Decoration for the actual highlight / underline.
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
            // Additional page margin icon decoration, if the highlight has an associated note.
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

    /**
     * Maps the current list of search result locators into a list of [Decoration] objects to
     * underline the results in the navigator.
     */
    val searchDecorations: Flow<List<Decoration>> by lazy {
        searchLocators.map {
            it.mapIndexed { index, locator ->
                Decoration(
                    // The index in the search result list is a suitable Decoration ID, as long as
                    // we clear the search decorations between two searches.
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

    // ===== NAVIGATOR LISTENER =====

    override fun onResourceLoadFailed(href: Url, error: ReadError) {
        activityChannel.send(
            ActivityCommand.ToastError(error.toUserError())
        )
    }

    // HyperlinkNavigator.Listener
    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        activityChannel.send(ActivityCommand.OpenExternalLink(url))
    }

    override fun shouldFollowInternalLink(
        link: Link,
        context: HyperlinkNavigator.LinkContext?,
    ): Boolean =
        when (context) {
            is HyperlinkNavigator.FootnoteContext -> {
                val text =
                    if (link.mediaType?.isHtml == true) {
                        context.noteContent.toHtml()
                    } else {
                        context.noteContent
                    }

                val command = VisualFragmentCommand.ShowPopup(text)
                visualFragmentChannel.send(command)
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

    val searchResult: Flow<PagingData<Locator>> =
        Pager(PagingConfig(pageSize = 20), pagingSourceFactory = pagingSourceFactory)
            .flow.cachedIn(viewModelScope)

    // ===== ON CLEARED =====

    override fun onCleared() {
        viewModelScope.launch {
            saveReadingStats(updateTime = true)
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