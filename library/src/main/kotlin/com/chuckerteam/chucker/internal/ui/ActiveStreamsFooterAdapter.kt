package com.chuckerteam.chucker.internal.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chuckerteam.chucker.databinding.ChuckerListItemActiveStreamsFooterBinding

/**
 * Explains, below the list, that streams leave it when they close while the active streams filter is on.
 */
internal class ActiveStreamsFooterAdapter : RecyclerView.Adapter<ActiveStreamsFooterAdapter.ViewHolder>() {
    var isVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    override fun getItemCount(): Int = if (isVisible) 1 else 0

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder =
        ViewHolder(
            ChuckerListItemActiveStreamsFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = Unit

    class ViewHolder(
        binding: ChuckerListItemActiveStreamsFooterBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
