package com.chuckerteam.chucker.internal.ui.transaction

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.databinding.ChuckerListItemTransactionBinding
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.data.entity.HttpTransactionTuple
import com.chuckerteam.chucker.internal.support.GrpcStatusCodes
import com.chuckerteam.chucker.internal.support.TransactionDiffCallback
import java.text.DateFormat
import javax.net.ssl.HttpsURLConnection

/**
 * Adapter for displaying a list of [HttpTransactionTuple] items in a RecyclerView.
 * Supports single-tap and long-tap interaction for click and multi-select operations.
 *
 * @param context The application context for resource access.
 * @param onTransactionClick Callback invoked on single click with transaction ID.
 * @param onTransactionLongClick Callback invoked on long click with transaction ID.
 */
internal class TransactionAdapter internal constructor(
    context: Context,
    private val onTransactionClick: (Long) -> Unit,
    private val onTransactionLongClick: (Long) -> Unit,
) : ListAdapter<HttpTransactionTuple, TransactionAdapter.TransactionViewHolder>(
        TransactionDiffCallback,
    ) {
    private var isSelectionMode = false
    private val selectedTransactionIds = mutableSetOf<Long>()
    private val colorDefault: Int = ContextCompat.getColor(context, R.color.chucker_status_default)
    private val colorRequested: Int =
        ContextCompat.getColor(context, R.color.chucker_status_requested)
    private val colorError: Int = ContextCompat.getColor(context, R.color.chucker_status_error)
    private val color500: Int = ContextCompat.getColor(context, R.color.chucker_status_500)
    private val color400: Int = ContextCompat.getColor(context, R.color.chucker_status_400)
    private val color300: Int = ContextCompat.getColor(context, R.color.chucker_status_300)
    private val colorSelected =
        ContextCompat.getColor(context, R.color.chucker_status_multiple_selection)
    private val colorLive: Int = ContextCompat.getColor(context, R.color.chucker_status_live)

    /** The gRPC streams that are open, which are marked as live. */
    private var liveIds: Set<Long> = emptySet()

    private val isPulseEnabled = GrpcPresentation.areAnimationsEnabled(context)

    /** Fallback background from theme for unselected items */
    private val backgroundSelectableAttr =
        TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): TransactionViewHolder {
        val viewBinding =
            ChuckerListItemTransactionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        return TransactionViewHolder(viewBinding)
    }

    override fun onBindViewHolder(
        holder: TransactionViewHolder,
        position: Int,
    ) = holder.bind(getItem(position))

    override fun onBindViewHolder(
        holder: TransactionViewHolder,
        position: Int,
        payloads: List<Any>,
    ) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_LIVE_DURATION }) {
            holder.bindLiveDuration(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: TransactionViewHolder) {
        holder.setPulsing(false)
    }

    /**
     * Marks the transactions with the given ids as open gRPC streams, refreshing only the rows that changed.
     */
    internal fun setLiveIds(ids: Set<Long>) {
        if (ids == liveIds) return
        val changedIds = (ids - liveIds) + (liveIds - ids)
        liveIds = ids
        currentList.forEachIndexed { position, transaction ->
            if (transaction.id in changedIds) notifyItemChanged(position)
        }
    }

    /** Updates how long the open gRPC streams have been running. */
    internal fun refreshLiveDurations() {
        if (liveIds.isEmpty()) return
        currentList.forEachIndexed { position, transaction ->
            if (isLive(transaction)) notifyItemChanged(position, PAYLOAD_LIVE_DURATION)
        }
    }

    private fun isLive(tuple: HttpTransactionTuple): Boolean = tuple.id in liveIds && tuple.responseCode == null

    /**
     * Updates the adapter's internal selection mode state.
     * When enabled, regular taps on items will toggle selection instead of triggering click actions.
     *
     * This is typically called from the UI layer (e.g., Activity) in response to
     * changes in the ViewModel's selection state.
     *
     * @param enabled True to enable selection mode, false to disable it.
     */
    internal fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
    }

    /**
     * Clears all currently selected transaction items and refreshes only the affected rows in the adapter.
     *
     * This method ensures that selection highlights are removed without unnecessarily rebinding
     * unaffected items. It looks up the adapter position of each previously selected transaction ID
     * and triggers a UI refresh for that specific position.
     *
     * Typically called when exiting selection mode or after bulk operations like deletion.
     */
    internal fun clearSelections() {
        val previouslySelectedIds = selectedTransactionIds.toSet()
        selectedTransactionIds.clear()

        previouslySelectedIds.forEachIndexed { index, id ->
            val position = currentList.indexOfFirst { it.id == id }
            if (position != -1) notifyItemChanged(position)
        }
    }

    /**
     * Sets the list of selected transaction IDs.
     *
     * This is typically used to restore selection state after a configuration change (e.g., screen rotation),
     * ensuring that the UI reflects the correct selection with proper background highlights.
     *
     * Since this affects potentially all items, a full data set refresh is triggered using [notifyDataSetChanged].
     *
     * @param ids The list of transaction IDs to mark as selected.
     */
    @SuppressLint("NotifyDataSetChanged")
    internal fun setSelectedTransactionIds(ids: List<Long>) {
        selectedTransactionIds.clear()
        selectedTransactionIds.addAll(ids)
        notifyDataSetChanged()
    }

    inner class TransactionViewHolder(
        private val itemBinding: ChuckerListItemTransactionBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root) {
        private var transactionId: Long? = null
        private val defaultDurationColors = itemBinding.duration.textColors

        init {
            itemView.setOnClickListener {
                val id = transactionId ?: return@setOnClickListener
                if (isSelectionMode) {
                    onTransactionLongClick(id)
                    toggleSelection(id)
                } else {
                    onTransactionClick(id)
                }
            }

            itemView.setOnLongClickListener {
                val id = transactionId ?: return@setOnLongClickListener false
                onTransactionLongClick(id)
                toggleSelection(id)
                true
            }
        }

        @SuppressLint("SetTextI18n")
        fun bind(transaction: HttpTransactionTuple) {
            transactionId = transaction.id

            updateSelectionState(transaction.id)

            itemBinding.apply {
                displayGraphQlFields(transaction.graphQlOperationName, transaction.graphQlDetected)
                displayGrpcFields(transaction)
                path.text =
                    if (transaction.isGrpc) {
                        GrpcPresentation.shortMethodName(transaction.path)
                    } else {
                        "${transaction.method} ${transaction.getFormattedPath(encode = false)}"
                    }
                host.text = transaction.host
                timeStart.text = DateFormat.getTimeInstance().format(transaction.requestDate)

                // gRPC only learns whether TLS is used once connected.
                ssl.isVisible = !transaction.isGrpc || transaction.scheme != null
                setProtocolImage(if (transaction.isSsl) ProtocolResources.Https() else ProtocolResources.Http())

                // A failed gRPC call still has a status code, which says more than "!!!".
                val hasGrpcStatus = transaction.isGrpc && transaction.responseCode != null
                if (transaction.status == HttpTransaction.Status.Complete || hasGrpcStatus) {
                    code.text = transaction.responseCode.toString()
                    duration.text = transaction.durationString
                    size.text = transaction.totalSizeString
                } else {
                    code.text = ""
                    duration.text = ""
                    size.text = ""
                }

                if (transaction.status == HttpTransaction.Status.Failed && !hasGrpcStatus) {
                    code.text = "!!!"
                }
            }

            setStatusColor(transaction)
            bindLiveState(transaction)
        }

        private fun bindLiveState(transaction: HttpTransactionTuple) {
            val isLive = isLive(transaction)
            itemBinding.apply {
                liveIndicator.isVisible = isLive
                // Invisible rather than gone, as the other views are aligned with it.
                code.visibility = if (isLive) View.INVISIBLE else View.VISIBLE
                duration.setTextColor(if (isLive) ColorStateList.valueOf(colorLive) else defaultDurationColors)
                if (isLive) {
                    size.text = transaction.totalSizeString
                    path.setTextColor(colorDefault)
                    grpcInfo.setTextColor(colorDefault)
                }
            }
            setPulsing(isLive)
            bindLiveDuration(transaction)
        }

        fun bindLiveDuration(transaction: HttpTransactionTuple) {
            if (!isLive(transaction)) return
            val startedAt = transaction.requestDate ?: return
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            itemBinding.duration.text = GrpcPresentation.formatOffset(elapsed)
        }

        fun setPulsing(isPulsing: Boolean) {
            val dot = itemBinding.liveDot
            if (isPulsing && isPulseEnabled) {
                if (dot.animation == null) dot.startAnimation(GrpcPresentation.pulseAnimation())
            } else {
                dot.clearAnimation()
            }
        }

        /**
         * Toggles the selection state of the given transaction ID.
         * If the item is already selected, it will be unselected.
         * If not selected, it will be added to the selection list.
         *
         * Triggers a UI update for the current item to reflect the selection change.
         *
         * @param id The unique transaction ID to toggle selection for.
         */
        private fun toggleSelection(id: Long) {
            if (selectedTransactionIds.contains(id)) {
                selectedTransactionIds.remove(id)
            } else {
                selectedTransactionIds.add(id)
            }
            notifyItemChanged(adapterPosition)
        }

        /**
         * Updates the visual appearance of the item based on its selection state.
         * Applies a highlighted background if selected, or the default background otherwise.
         *
         * This should be called during binding to reflect correct UI state.
         *
         * @param transactionId The ID of the transaction to check against the selected set.
         */
        private fun updateSelectionState(transactionId: Long) {
            if (selectedTransactionIds.contains(transactionId)) {
                itemView.setBackgroundColor(colorSelected)
            } else {
                itemView.setBackgroundResource(backgroundSelectableAttr.resourceId)
            }
        }

        private fun setProtocolImage(resources: ProtocolResources) {
            itemBinding.ssl.setImageDrawable(
                AppCompatResources.getDrawable(itemView.context, resources.icon),
            )
            ImageViewCompat.setImageTintList(
                itemBinding.ssl,
                ColorStateList.valueOf(ContextCompat.getColor(itemView.context, resources.color)),
            )
        }

        private fun setStatusColor(transaction: HttpTransactionTuple) {
            // gRPC codes are coloured like the HTTP status they're closest to.
            val httpCode =
                transaction.responseCode?.let { code ->
                    if (transaction.isGrpc) GrpcStatusCodes.httpEquivalent(code) else code
                }
            val color: Int =
                when {
                    (transaction.status === HttpTransaction.Status.Failed) -> colorError
                    (transaction.status === HttpTransaction.Status.Requested) -> colorRequested
                    (httpCode == null) -> colorDefault
                    (httpCode >= HttpsURLConnection.HTTP_INTERNAL_ERROR) -> color500
                    (httpCode >= HttpsURLConnection.HTTP_BAD_REQUEST) -> color400
                    (httpCode >= HttpsURLConnection.HTTP_MULT_CHOICE) -> color300
                    else -> colorDefault
                }
            itemBinding.code.setTextColor(color)
            itemBinding.path.setTextColor(color)
            itemBinding.grpcInfo.setTextColor(color)
        }
    }
}

private const val PAYLOAD_LIVE_DURATION = "live_duration"

private fun ChuckerListItemTransactionBinding.displayGraphQlFields(
    graphQlOperationName: String?,
    graphQLDetected: Boolean,
) {
    graphqlIcon.isVisible = graphQLDetected
    graphqlPath.isVisible = graphQLDetected

    if (graphQLDetected) {
        graphqlPath.text = graphQlOperationName
            ?: root.resources.getString(R.string.chucker_graphql_operation_is_empty)
    }
}

private fun ChuckerListItemTransactionBinding.displayGrpcFields(transaction: HttpTransactionTuple) {
    grpcBadge.isVisible = transaction.isGrpc
    grpcInfo.isVisible = transaction.isGrpc
    if (!transaction.isGrpc) return

    val resources = root.resources
    grpcInfo.text =
        listOfNotNull(
            transaction.responseCode?.let(GrpcStatusCodes::name),
            resources.getString(GrpcPresentation.callTypeLabel(transaction.grpcMethodType)),
            resources
                .getString(
                    R.string.chucker_grpc_message_counts,
                    transaction.requestMessageCount,
                    transaction.responseMessageCount,
                ).takeIf { GrpcPresentation.isStreaming(transaction.grpcMethodType) },
        ).joinToString(" · ")
}
