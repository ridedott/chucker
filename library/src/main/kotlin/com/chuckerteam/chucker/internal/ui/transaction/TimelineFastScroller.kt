package com.chuckerteam.chucker.internal.ui.transaction

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * A handle on the edge of a long timeline that can be dragged to move through it quickly. While it's
 * dragged, [label] names the row at the top of the list, as given by [labelFor].
 *
 * Short timelines keep the usual scrollbar instead. The handle is left to touch: screen readers
 * scroll the list itself.
 */
@SuppressLint("ClickableViewAccessibility")
internal class TimelineFastScroller(
    private val recyclerView: RecyclerView,
    private val handle: View,
    private val label: TextView,
    private val labelFor: (position: Int) -> String?,
) {
    private val hideHandle = Runnable { handle.animate().alpha(IDLE_ALPHA) }
    private var isDragging = false
    private var dragStartY = 0f
    private var dragStartTranslation = 0f

    init {
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    onListScrolled(isScrolling = dy != 0)
                }
            },
        )
        handle.setOnTouchListener(::onTouch)
    }

    private fun onListScrolled(isScrolling: Boolean) {
        val range = recyclerView.computeVerticalScrollRange()
        val isLong = range > recyclerView.computeVerticalScrollExtent() * MIN_SCREENS
        // Not gone, as this runs while the list lays out, too late for the handle to be measured.
        handle.isInvisible = !isLong
        recyclerView.isVerticalScrollBarEnabled = !isLong
        if (!isLong || isDragging) return

        handle.translationY = scrolledFraction() * track()
        if (isScrolling) {
            handle.animate().cancel()
            handle.alpha = 1f
            handle.removeCallbacks(hideHandle)
            handle.postDelayed(hideHandle, IDLE_DELAY_MS)
        }
    }

    private fun onTouch(
        view: View,
        event: MotionEvent,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragStartY = event.rawY
                dragStartTranslation = handle.translationY
                handle.removeCallbacks(hideHandle)
                handle.animate().cancel()
                handle.alpha = 1f
                recyclerView.stopScroll()
                view.parent.requestDisallowInterceptTouchEvent(true)
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                layoutManager?.let { showLabel(it.findFirstVisibleItemPosition()) }
            }
            MotionEvent.ACTION_MOVE -> {
                val track = track()
                val translation = (dragStartTranslation + event.rawY - dragStartY).coerceIn(0f, track)
                handle.translationY = translation
                if (track > 0) scrollTo(translation / track)?.let(::showLabel)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                label.isInvisible = true
                handle.postDelayed(hideHandle, IDLE_DELAY_MS)
            }
        }
        return true
    }

    /** Returns the position scrolled to, which the list only lays out on its next frame. */
    private fun scrollTo(fraction: Float): Int? {
        val itemCount = recyclerView.adapter?.itemCount ?: return null
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val position = (fraction * (itemCount - 1)).roundToInt()
        layoutManager.scrollToPositionWithOffset(position, 0)
        return position
    }

    private fun showLabel(position: Int) {
        val text = labelFor(position)
        label.text = text
        label.isInvisible = text == null
        label.translationY = handle.translationY + (handle.height - label.height) / 2f
    }

    private fun scrolledFraction(): Float {
        val scrollable = recyclerView.computeVerticalScrollRange() - recyclerView.computeVerticalScrollExtent()
        if (scrollable <= 0) return 0f
        return (recyclerView.computeVerticalScrollOffset().toFloat() / scrollable).coerceIn(0f, 1f)
    }

    private fun track(): Float = (recyclerView.height - handle.height).coerceAtLeast(0).toFloat()

    private companion object {
        // The handle is only worth it when the timeline is a few screens long.
        const val MIN_SCREENS = 3
        const val IDLE_ALPHA = 0.45f
        const val IDLE_DELAY_MS = 1_200L
    }
}
