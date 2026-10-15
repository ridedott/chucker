package com.chuckerteam.chucker.internal.ui.transaction

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.databinding.ChuckerActivityGrpcMessageBinding
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.ui.BaseChuckerActivity

/**
 * Shows one message of a gRPC stream on its own, for messages too long to read in the timeline.
 * It can be searched, copied and saved like any request or response body.
 */
internal class GrpcMessageActivity : BaseChuckerActivity() {
    private val viewModel: TransactionViewModel by viewModels {
        TransactionViewModelFactory(intent.getLongExtra(EXTRA_TRANSACTION_ID, 0))
    }

    private lateinit var binding: ChuckerActivityGrpcMessageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ChuckerActivityGrpcMessageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val position = intent.getIntExtra(EXTRA_EVENT_POSITION, 0)
        viewModel.transaction.observe(this) { transaction ->
            val event = transaction?.getGrpcEvents()?.getOrNull(position) ?: return@observe
            binding.toolbarTitle.text = title(event)
            binding.toolbarSubtitle.text =
                getString(
                    R.string.chucker_grpc_message_subtitle,
                    GrpcPresentation.formatSize(event.size),
                    GrpcPresentation.formatOffset(event.timeOffsetMs),
                )
        }

        if (savedInstanceState == null) {
            val isSent = intent.getBooleanExtra(EXTRA_IS_SENT, false)
            val payloadType = if (isSent) PayloadType.REQUEST else PayloadType.RESPONSE
            supportFragmentManager.commit {
                replace(R.id.messageContainer, TransactionPayloadFragment.newMessageInstance(payloadType, position))
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chucker_grpc_message, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun title(event: GrpcEvent): String {
        val index = event.index ?: 0
        return when (event.type) {
            GrpcEvent.Type.MESSAGE_SENT -> getString(R.string.chucker_grpc_message_sent_title, index)
            else -> getString(R.string.chucker_grpc_message_received_title, index)
        }
    }

    private fun applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = insets.top)
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    companion object {
        private const val EXTRA_TRANSACTION_ID = "transaction_id"
        private const val EXTRA_EVENT_POSITION = "event_position"
        private const val EXTRA_IS_SENT = "is_sent"

        fun start(
            context: Context,
            transactionId: Long,
            eventPosition: Int,
            isSent: Boolean,
        ) {
            val intent =
                Intent(context, GrpcMessageActivity::class.java)
                    .putExtra(EXTRA_TRANSACTION_ID, transactionId)
                    .putExtra(EXTRA_EVENT_POSITION, eventPosition)
                    .putExtra(EXTRA_IS_SENT, isSent)
            context.startActivity(intent)
        }
    }
}
