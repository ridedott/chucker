package com.chuckerteam.chucker.internal.ui.transaction

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.bold
import androidx.core.text.inSpans
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.databinding.ChuckerItemStreamDetailRowBinding
import com.chuckerteam.chucker.databinding.ChuckerListItemStreamDetailsBinding
import com.chuckerteam.chucker.databinding.ChuckerListItemStreamEventBinding
import com.chuckerteam.chucker.databinding.ChuckerListItemStreamSummaryBinding
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.data.entity.HttpHeader
import com.chuckerteam.chucker.internal.support.FormatUtils
import com.chuckerteam.chucker.internal.support.SpanTextUtil

/** The stream screen: a summary pinned above a timeline. */
internal data class StreamScreen(
    val summary: StreamSummary,
    val items: List<StreamItem>,
)

/** A row of the timeline, newest first. */
internal sealed interface StreamItem {
    val id: String

    /** The rest of the overview, shown at the top of the timeline when the user asks for it. */
    data class Details(
        val rows: List<Pair<String, String>>,
        val error: String?,
    ) : StreamItem {
        override val id: String get() = "details"
    }

    data class Event(
        val row: StreamEventRow,
    ) : StreamItem {
        override val id: String get() = "event:${row.position}"
    }
}

/** The essentials of the call, pinned above its timeline. */
internal data class StreamSummary(
    val status: String,
    @ColorInt val statusColor: Int,
    val isLive: Boolean,
    val description: String?,
    val callType: String,
    val durationLabel: String,
    val duration: String,
    val sentCount: Int,
    val sentSize: String,
    val receivedCount: Int,
    val receivedSize: String,
    val startedAndDeadline: String,
    val isExpanded: Boolean,
)

/** One event of the timeline. [position] identifies it, since events are only ever appended. */
internal data class StreamEventRow(
    val position: Int,
    val event: GrpcEvent,
    val title: String,
    val timestamp: String,
    @ColorInt val titleColor: Int?,
    @DrawableRes val icon: Int,
    @ColorInt val iconColor: Int,
    val subtitle: String?,
    val isSubtitleCode: Boolean,
    val keepsSubtitleWhenExpanded: Boolean,
    val detailsLabel: String?,
    val headers: List<HttpHeader>,
    val isExpanded: Boolean,
) {
    val hasMessageBody: Boolean get() = event.isMessage && event.body != null

    val isExpandable: Boolean get() = hasMessageBody || headers.isNotEmpty()
}

/**
 * Shows the timeline of a streaming gRPC call. Rows expand in place, and long messages only show their
 * start, with a way to open the full message.
 */
internal class GrpcStreamAdapter(
    private val onToggle: (key: String) -> Unit,
    private val onOpenMessage: (position: Int) -> Unit,
) : ListAdapter<StreamItem, RecyclerView.ViewHolder>(DiffCallback) {
    // Events never change once recorded, so a prepared message can be kept for as long as the screen lives.
    private val preparedMessages = HashMap<Int, PreparedMessage>()

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is StreamItem.Details -> TYPE_DETAILS
            is StreamItem.Event -> TYPE_EVENT
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DETAILS -> DetailsViewHolder(ChuckerListItemStreamDetailsBinding.inflate(inflater, parent, false))
            else -> EventViewHolder(ChuckerListItemStreamEventBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = getItem(position)) {
            is StreamItem.Details -> (holder as DetailsViewHolder).bind(item)
            is StreamItem.Event -> (holder as EventViewHolder).bind(item.row)
        }
    }

    class DetailsViewHolder(
        private val binding: ChuckerListItemStreamDetailsBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var rows: List<Pair<String, String>>? = null

        fun bind(details: StreamItem.Details) {
            binding.errorText.text = details.error
            binding.errorText.isVisible = details.error != null
            if (rows == details.rows) return
            rows = details.rows
            val inflater = LayoutInflater.from(binding.root.context)
            binding.detailRows.removeAllViews()
            for ((label, value) in details.rows) {
                val row = ChuckerItemStreamDetailRowBinding.inflate(inflater, binding.detailRows, true)
                row.detailLabel.text = label
                row.detailValue.text = value
            }
        }
    }

    inner class EventViewHolder(
        private val binding: ChuckerListItemStreamEventBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val context = binding.root.context
        private val defaultTitleColors = binding.title.textColors
        private val spanTextUtil by lazy(LazyThreadSafetyMode.NONE) { SpanTextUtil(context) }

        fun bind(row: StreamEventRow) {
            binding.apply {
                time.text =
                    context.getString(
                        R.string.chucker_grpc_time_offset,
                        GrpcPresentation.formatOffset(row.event.timeOffsetMs),
                    )
                title.text = row.title
                title.setTextColor(row.titleColor?.let(ColorStateList::valueOf) ?: defaultTitleColors)
                icon.setImageResource(row.icon)
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(row.iconColor))

                subtitle.text = row.subtitle
                subtitle.typeface = if (row.isSubtitleCode) Typeface.MONOSPACE else Typeface.DEFAULT
                subtitle.isVisible = row.subtitle != null && (!row.isExpanded || row.keepsSubtitleWhenExpanded)
                timestamp.text = row.timestamp
                timestamp.isVisible = row.isExpanded

                expandIcon.isVisible = row.isExpandable
                expandIcon.setImageResource(
                    if (row.isExpanded) R.drawable.chucker_ic_arrow_up else R.drawable.chucker_ic_arrow_down,
                )
                details.isVisible = row.isExpandable && row.isExpanded
                if (details.isVisible) {
                    if (row.hasMessageBody) bindMessage(row) else bindHeaders(row)
                }

                root.isClickable = row.isExpandable
                val onClick = View.OnClickListener { onToggle(eventKey(row.position)) }
                root.setOnClickListener(if (row.isExpandable) onClick else null)
            }
        }

        private fun bindMessage(row: StreamEventRow) {
            val message = preparedMessages.getOrPut(row.position) { prepare(row.event) }
            binding.apply {
                detailsLabel.isVisible = false
                detailsText.text = message.text
                detailsFade.isVisible = message.isPartial
                detailsFooter.isVisible = true
                detailsFooterText.text =
                    when {
                        message.isPartial && message.shownLines < message.totalLines ->
                            context.getString(R.string.chucker_grpc_lines_shown, message.shownLines, message.totalLines)
                        message.isPartial -> context.getString(R.string.chucker_grpc_message_partial)
                        row.event.isTruncated -> context.getString(R.string.chucker_grpc_message_truncated)
                        else -> null
                    }
                openMessageButton.setOnClickListener { onOpenMessage(row.position) }
            }
        }

        private fun bindHeaders(row: StreamEventRow) {
            binding.apply {
                detailsLabel.text = row.detailsLabel
                detailsLabel.isVisible = row.detailsLabel != null
                detailsText.text =
                    SpannableStringBuilder().apply {
                        row.headers.forEachIndexed { index, header ->
                            if (index > 0) append('\n')
                            bold { append(header.name).append(':') }
                            append(' ').append(header.value)
                        }
                    }
                detailsFade.isVisible = false
                detailsFooter.isVisible = false
            }
        }

        /** Formats a message, keeping only its start when it's too long to show in the timeline. */
        private fun prepare(event: GrpcEvent): PreparedMessage {
            val body = event.body.orEmpty()
            val isJson = event.format?.isJson == true
            val text = if (isJson && !event.isTruncated) FormatUtils.formatJson(body) else body
            val totalLines = text.count { it == '\n' } + 1
            var shown = text.lineSequence().take(MAX_INLINE_LINES).joinToString("\n")
            if (shown.length > MAX_INLINE_CHARS) shown = shown.take(MAX_INLINE_CHARS)
            val isPartial = shown.length < text.length
            val shownLines = shown.count { it == '\n' } + 1
            // The JSON highlighter stops at the first unterminated string, so a partial message stays readable.
            val spanned: CharSequence = if (isJson) spanTextUtil.spanJson(shown) else shown
            return PreparedMessage(spanned, isPartial, shownLines, totalLines)
        }
    }

    private class PreparedMessage(
        val text: CharSequence,
        val isPartial: Boolean,
        val shownLines: Int,
        val totalLines: Int,
    )

    companion object {
        const val KEY_DETAILS = "details"

        fun eventKey(position: Int): String = "event:$position"

        private const val TYPE_DETAILS = 0
        private const val TYPE_EVENT = 1

        private const val MAX_INLINE_LINES = 12
        private const val MAX_INLINE_CHARS = 2_000
        private val DiffCallback =
            object : DiffUtil.ItemCallback<StreamItem>() {
                override fun areItemsTheSame(
                    oldItem: StreamItem,
                    newItem: StreamItem,
                ) = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: StreamItem,
                    newItem: StreamItem,
                ) = oldItem == newItem
            }
    }
}

/** Binds the summary pinned above the timeline. */
internal class StreamSummaryBinder(
    private val binding: ChuckerListItemStreamSummaryBinding,
    private val isPulseEnabled: Boolean,
    onToggleDetails: () -> Unit,
) {
    private val context = binding.root.context

    init {
        binding.detailsButton.setOnClickListener { onToggleDetails() }
    }

    fun bind(summary: StreamSummary) {
        binding.apply {
            statusText.text = summary.status
            statusText.setTextColor(summary.statusColor)
            ViewCompat.setBackgroundTintList(
                statusPill,
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(summary.statusColor, PILL_BACKGROUND_ALPHA)),
            )
            statusDot.isVisible = summary.isLive
            statusDot.setPulsing(summary.isLive && isPulseEnabled)
            callType.text = summary.callType
            description.text = summary.description
            description.setTextColor(summary.statusColor)
            description.isVisible = summary.description != null
            durationLabel.text = summary.durationLabel
            durationValue.text = summary.duration
            sentValue.text = countWithSize("↑ ${summary.sentCount}", summary.sentSize, R.color.chucker_color_primary)
            receivedValue.text =
                countWithSize("↓ ${summary.receivedCount}", summary.receivedSize, R.color.chucker_status_live)
            startedText.text = summary.startedAndDeadline
            if (summary.isExpanded) {
                detailsButton.setText(R.string.chucker_grpc_hide_details)
                detailsButton.setIconResource(R.drawable.chucker_ic_arrow_up)
            } else {
                detailsButton.setText(R.string.chucker_grpc_details)
                detailsButton.setIconResource(R.drawable.chucker_ic_arrow_down)
            }
        }
    }

    private fun countWithSize(
        count: String,
        size: String,
        colorRes: Int,
    ): CharSequence =
        SpannableStringBuilder()
            .inSpans(ForegroundColorSpan(ContextCompat.getColor(context, colorRes))) { append(count) }
            .append(' ')
            .inSpans(
                RelativeSizeSpan(SIZE_RELATIVE_TEXT_SIZE),
                ForegroundColorSpan(binding.startedText.currentTextColor),
            ) { append(size) }

    private companion object {
        const val PILL_BACKGROUND_ALPHA = 0x2E
        const val SIZE_RELATIVE_TEXT_SIZE = 0.65f
    }
}

/** Pulses the marker of an open stream, or stops it. */
private fun ImageView.setPulsing(isPulsing: Boolean) {
    if (isPulsing) {
        if (animation == null) startAnimation(GrpcPresentation.pulseAnimation())
    } else {
        clearAnimation()
    }
}
