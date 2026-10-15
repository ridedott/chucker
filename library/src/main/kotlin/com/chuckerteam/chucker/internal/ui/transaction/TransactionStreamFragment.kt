package com.chuckerteam.chucker.internal.ui.transaction

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.databinding.ChuckerFragmentTransactionStreamBinding
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.support.combineLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The screen of a streaming gRPC call: the essentials of the call pinned above a timeline of its
 * events, newest first. Metadata, headers and trailers are shown on the events they arrived with.
 *
 * While the stream is open the screen updates live. New events appear at the top, and when the user
 * has scrolled away from it a button brings them back.
 */
internal class TransactionStreamFragment : Fragment() {
    private val viewModel: TransactionViewModel by activityViewModels { TransactionViewModelFactory() }

    private lateinit var binding: ChuckerFragmentTransactionStreamBinding
    private lateinit var streamAdapter: GrpcStreamAdapter
    private lateinit var summaryBinder: StreamSummaryBinder
    private lateinit var itemsFactory: GrpcStreamItemsFactory
    private val expandedKeys = mutableSetOf<String>()

    // -1 until the first render, so opening a stream doesn't count its events as new.
    private var renderedEventCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        savedInstanceState?.getStringArrayList(STATE_EXPANDED)?.let { expandedKeys += it }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = ChuckerFragmentTransactionStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        renderedEventCount = -1
        itemsFactory = GrpcStreamItemsFactory(requireContext())
        val isPulseEnabled = GrpcPresentation.areAnimationsEnabled(requireContext())
        summaryBinder = StreamSummaryBinder(binding.summary, isPulseEnabled) { toggle(GrpcStreamAdapter.KEY_DETAILS) }
        streamAdapter = GrpcStreamAdapter(isPulseEnabled, onToggle = ::toggle, onOpenMessage = ::openMessage)
        binding.streamRecyclerView.apply {
            adapter = streamAdapter
            // Rows only change when expanded or collapsed, which looks better without the change animation.
            itemAnimator?.changeDuration = 0
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(
                        recyclerView: RecyclerView,
                        dx: Int,
                        dy: Int,
                    ) {
                        if (isAtTop()) binding.newMessagesButton.isVisible = false
                    }
                },
            )
        }
        binding.newMessagesButton.setOnClickListener { scrollToTop() }

        viewModel.transaction.combineLatest(viewModel.isLive).observe(viewLifecycleOwner) { (transaction, isLive) ->
            if (transaction != null) render(transaction, isLive)
        }
        // How long an open stream has been running is shown, and updated every second.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(LIVE_REFRESH_INTERVAL_MS)
                    val transaction = viewModel.transaction.value ?: continue
                    val isLive = viewModel.isLive.value == true
                    if (isLive) render(transaction, isLive)
                }
            }
        }
    }

    private fun openMessage(position: Int) {
        val event =
            viewModel.transaction.value
                ?.getGrpcEvents()
                ?.getOrNull(position) ?: return
        val isSent = event.type == GrpcEvent.Type.MESSAGE_SENT
        GrpcMessageActivity.start(requireContext(), viewModel.transactionId, position, isSent)
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        inflater: MenuInflater,
    ) {
        menu.findItem(R.id.save_body).isVisible = false
        menu.findItem(R.id.encode_url).isVisible = false
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_EXPANDED, ArrayList(expandedKeys))
    }

    private fun toggle(key: String) {
        if (!expandedKeys.remove(key)) expandedKeys += key
        val transaction = viewModel.transaction.value ?: return
        render(transaction, viewModel.isLive.value == true)
    }

    private fun render(
        transaction: HttpTransaction,
        isLiveStream: Boolean,
    ) {
        val eventCount = transaction.getGrpcEvents().size
        val screen = itemsFactory.create(transaction, isLiveStream, expandedKeys, System.currentTimeMillis())
        summaryBinder.bind(screen.summary)

        val previousEventCount = renderedEventCount
        val wasAtTop = isAtTop()
        renderedEventCount = eventCount
        streamAdapter.submitList(screen.items) {
            if (previousEventCount < 0 || eventCount <= previousEventCount) return@submitList
            // The list keeps its first visible row in place, so new rows above it need a scroll to be seen.
            if (wasAtTop) binding.streamRecyclerView.scrollToPosition(0) else binding.newMessagesButton.isVisible = true
        }
    }

    private fun isAtTop(): Boolean = !binding.streamRecyclerView.canScrollVertically(-1)

    private fun scrollToTop() {
        binding.newMessagesButton.isVisible = false
        binding.streamRecyclerView.smoothScrollToPosition(0)
    }

    private companion object {
        const val STATE_EXPANDED = "expanded_keys"
        const val LIVE_REFRESH_INTERVAL_MS = 1_000L
    }
}
