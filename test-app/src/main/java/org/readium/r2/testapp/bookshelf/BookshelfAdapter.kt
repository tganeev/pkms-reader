package org.readium.r2.testapp.bookshelf

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.readium.r2.testapp.R
import org.readium.r2.testapp.data.model.Book
import org.readium.r2.testapp.databinding.ItemRecycleBookBinding
import org.readium.r2.testapp.utils.singleClick

class BookshelfAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onBookLongClick: (Book) -> Unit,
) : ListAdapter<Book, BookshelfAdapter.ViewHolder>(BookListDiff()) {

    private var onEditBookClick: ((Book) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    fun setOnEditBookClick(listener: (Book) -> Unit) {
        onEditBookClick = listener
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        return ViewHolder(
            ItemRecycleBookBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val book = getItem(position)
        viewHolder.bind(book)
    }

    inner class ViewHolder(private val binding: ItemRecycleBookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(book: Book) {
            binding.bookshelfTitleText.text = book.title
            Picasso.get()
                .load(File(book.cover))
                .placeholder(R.drawable.cover)
                .into(binding.bookshelfCoverImage)

            // Отображаем статистику
            binding.readingStatsLayout.visibility = View.VISIBLE

            // Форматируем время чтения
            val readingTimeText = formatReadingTime(book.readingTime)
            binding.readingTimeText.text = "⏱️ $readingTimeText"

            // Отображаем количество страниц
            binding.pagesReadText.text = "📄 ${book.pagesRead} стр."

            // Если книга была открыта, показываем дату последнего чтения
            book.lastReadDate?.let { date ->
                val lastReadText = formatLastReadDate(date)
                binding.lastReadText.text = "🕒 $lastReadText"
                binding.lastReadText.visibility = View.VISIBLE
            } ?: run {
                binding.lastReadText.visibility = View.GONE
            }

            // Скрываем overlay по умолчанию
            binding.actionOverlay.visibility = View.GONE

            // Обработка долгого нажатия - показываем overlay
            binding.root.setOnLongClickListener {
                showOverlay(book)
                true
            }

            // Клик по карточке - открыть книгу (только если overlay не виден)
            binding.root.setOnClickListener {
                if (binding.actionOverlay.visibility == View.VISIBLE) {
                    hideOverlay()
                } else {
                    onBookClick(book)
                }
            }

            // Кнопка редактирования
            binding.btnEdit.setOnClickListener {
                hideOverlay()
                onEditBookClick?.invoke(book)
            }

            // Кнопка удаления
            binding.btnDelete.setOnClickListener {
                hideOverlay()
                onBookLongClick(book)
            }
        }

        private fun showOverlay(book: Book) {
            // Скрываем предыдущий таймер
            hideRunnable?.let { handler.removeCallbacks(it) }

            // Показываем overlay
            binding.actionOverlay.visibility = View.VISIBLE

            // Автоматически скрываем через 3 секунды
            hideRunnable = Runnable {
                hideOverlay()
            }
            handler.postDelayed(hideRunnable!!, 3000)
        }

        private fun hideOverlay() {
            binding.actionOverlay.visibility = View.GONE
            hideRunnable?.let { handler.removeCallbacks(it) }
        }

        private fun formatReadingTime(seconds: Long): String {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60

            return when {
                hours > 0 -> "${hours}ч ${minutes}мин"
                minutes > 0 -> "${minutes}мин"
                else -> "менее минуты"
            }
        }

        private fun formatLastReadDate(timestamp: Long): String {
            val date = Date(timestamp)
            val now = Date()
            val diff = now.time - date.time
            val days = diff / (24 * 60 * 60 * 1000)

            return when {
                days == 0L -> "сегодня"
                days == 1L -> "вчера"
                days < 7 -> "${days} дн. назад"
                else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(date)
            }
        }
    }

    private class BookListDiff : DiffUtil.ItemCallback<Book>() {

        override fun areItemsTheSame(
            oldItem: Book,
            newItem: Book,
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: Book,
            newItem: Book,
        ): Boolean {
            return oldItem.title == newItem.title &&
                oldItem.href == newItem.href &&
                oldItem.author == newItem.author &&
                oldItem.identifier == newItem.identifier &&
                oldItem.readingTime == newItem.readingTime &&
                oldItem.pagesRead == newItem.pagesRead &&
                oldItem.lastReadDate == newItem.lastReadDate
        }
    }
}