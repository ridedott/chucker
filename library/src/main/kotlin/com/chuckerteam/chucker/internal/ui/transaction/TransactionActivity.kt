package com.chuckerteam.chucker.internal.ui.transaction

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.databinding.ChuckerActivityTransactionBinding
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.support.HarUtils
import com.chuckerteam.chucker.internal.support.Sharable
import com.chuckerteam.chucker.internal.support.TransactionCurlCommandSharable
import com.chuckerteam.chucker.internal.support.TransactionDetailsHarSharable
import com.chuckerteam.chucker.internal.support.TransactionDetailsSharable
import com.chuckerteam.chucker.internal.support.TransactionGrpcurlCommandSharable
import com.chuckerteam.chucker.internal.support.shareAsFile
import com.chuckerteam.chucker.internal.support.shareAsUtf8Text
import com.chuckerteam.chucker.internal.ui.BaseChuckerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TransactionActivity : BaseChuckerActivity() {
    private val viewModel: TransactionViewModel by viewModels {
        TransactionViewModelFactory(intent.getLongExtra(EXTRA_TRANSACTION_ID, 0))
    }

    private lateinit var transactionBinding: ChuckerActivityTransactionBinding
    private var isShowingStream = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transactionBinding = ChuckerActivityTransactionBinding.inflate(layoutInflater)

        with(transactionBinding) {
            setContentView(root)
            applyInset()
            setSupportActionBar(toolbar)
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // The list says whether the call is a gRPC stream, so its screen shows up right away.
        if (intent.getBooleanExtra(EXTRA_IS_GRPC_STREAM, false)) showStream() else showTabs()

        viewModel.transactionTitle.observe(
            this,
            Observer { transactionBinding.toolbarTitle.text = it },
        )
        viewModel.transactionSubtitle.observe(this) { subtitle ->
            transactionBinding.toolbarSubtitle.text = subtitle
            transactionBinding.toolbarSubtitle.isVisible = !subtitle.isNullOrEmpty()
        }
        viewModel.isGrpcStream.observe(this) { isGrpcStream -> if (isGrpcStream) showStream() }
        viewModel.isGrpc.observe(this) { invalidateOptionsMenu() }
    }

    private fun showTabs() {
        with(transactionBinding) {
            setupViewPager(viewPager)
            tabLayout.setupWithViewPager(viewPager)
        }
    }

    private fun showStream() {
        if (isShowingStream) return
        isShowingStream = true
        with(transactionBinding) {
            tabLayout.setupWithViewPager(null)
            viewPager.adapter = null
            tabLayout.isVisible = false
            viewPager.isVisible = false
            streamContainer.isVisible = true
        }
        if (supportFragmentManager.findFragmentById(R.id.streamContainer) == null) {
            supportFragmentManager.commit { replace(R.id.streamContainer, TransactionStreamFragment()) }
        }
        invalidateOptionsMenu()
    }

    private fun applyInset() {
        // Set up window insets to properly handle the UI around system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Apply insets to the main content to avoid overlap with system bars
        ViewCompat.setOnApplyWindowInsetsListener(transactionBinding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            transactionBinding.appBarLayout.updatePadding(top = insets.top)
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onDestroy() {
        // Detach TabLayout so its TabViews (which hold a ContextThemeWrapper
        // pointing at this Activity) don't outlive us via delayed MessageQueue
        // callbacks. Nullifying the adapter releases fragment references.
        transactionBinding.tabLayout.setupWithViewPager(null)
        transactionBinding.viewPager.adapter = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chucker_transaction, menu)
        setUpUrlEncoding(menu)
        // A gRPC call can't be replayed with curl, but it can be with grpcurl.
        val isGrpc = viewModel.isGrpc.value == true
        menu.findItem(R.id.share_curl).isVisible = !isGrpc
        menu.findItem(R.id.share_grpcurl).isVisible = isGrpc
        return super.onCreateOptionsMenu(menu)
    }

    private fun setUpUrlEncoding(menu: Menu) {
        val encodeUrlMenuItem = menu.findItem(R.id.encode_url)
        encodeUrlMenuItem.setOnMenuItemClickListener {
            viewModel.switchUrlEncoding()
            return@setOnMenuItemClickListener true
        }
        viewModel.encodeUrl.observe(
            this,
            Observer { encode ->
                val icon =
                    if (encode) {
                        R.drawable.chucker_ic_encoded_url_white
                    } else {
                        R.drawable.chucker_ic_decoded_url_white
                    }
                encodeUrlMenuItem.setIcon(icon)
            },
        )
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        when (item.itemId) {
            R.id.share_text ->
                shareTransactionAsText { transaction ->
                    val encodeUrls = viewModel.encodeUrl.value!!
                    TransactionDetailsSharable(transaction, encodeUrls)
                }
            R.id.share_curl ->
                shareTransactionAsText { transaction ->
                    TransactionCurlCommandSharable(transaction)
                }
            R.id.share_grpcurl ->
                shareTransactionAsText { transaction ->
                    TransactionGrpcurlCommandSharable(transaction)
                }
            R.id.share_file ->
                shareTransactionAsFile(EXPORT_TXT_FILE_NAME) { transaction ->
                    val encodeUrls = viewModel.encodeUrl.value!!
                    TransactionDetailsSharable(transaction, encodeUrls)
                }
            R.id.share_har ->
                shareTransactionAsFile(EXPORT_HAR_FILE_NAME) { transaction ->
                    TransactionDetailsHarSharable(
                        HarUtils.harStringFromTransactions(
                            listOf(transaction),
                            getString(R.string.chucker_name),
                            getString(R.string.chucker_version),
                        ),
                    )
                }
            else -> super.onOptionsItemSelected(item)
        }

    private fun shareTransactionAsText(block: (HttpTransaction) -> Sharable): Boolean {
        val transaction = viewModel.transaction.value
        if (transaction == null) {
            showToast(getString(R.string.chucker_request_not_ready))
            return true
        }
        val sharable = block(transaction)
        lifecycleScope.launch {
            val shareIntent =
                sharable.shareAsUtf8Text(
                    activity = this@TransactionActivity,
                    intentTitle = getString(R.string.chucker_share_transaction_title),
                    intentSubject = getString(R.string.chucker_share_transaction_subject),
                )
            startActivity(shareIntent)
        }
        return true
    }

    private fun shareTransactionAsFile(
        fileName: String,
        block: suspend (HttpTransaction) -> Sharable,
    ): Boolean {
        lifecycleScope.launch {
            val transaction = viewModel.transaction.value
            if (transaction == null) {
                showToast(getString(R.string.chucker_request_not_ready))
                return@launch
            }

            val sharable = block(transaction)
            val shareIntent =
                withContext(Dispatchers.IO) {
                    sharable.shareAsFile(
                        activity = this@TransactionActivity,
                        fileName = fileName,
                        intentTitle = getString(R.string.chucker_share_transaction_title),
                        intentSubject = getString(R.string.chucker_share_transaction_subject),
                        clipDataLabel = "transaction",
                    )
                }
            if (shareIntent != null) {
                startActivity(shareIntent)
            } else {
                Toast.makeText(applicationContext, R.string.chucker_export_no_file, Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun setupViewPager(viewPager: ViewPager) {
        viewPager.adapter = TransactionPagerAdapter(this, supportFragmentManager)
        viewPager.addOnPageChangeListener(
            object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    selectedTabPosition = position
                }
            },
        )
        viewPager.currentItem = selectedTabPosition
    }

    companion object {
        private const val EXPORT_TXT_FILE_NAME = "transaction.txt"
        private const val EXPORT_HAR_FILE_NAME = "transaction.har"
        private const val EXTRA_TRANSACTION_ID = "transaction_id"
        private const val EXTRA_IS_GRPC_STREAM = "is_grpc_stream"
        private var selectedTabPosition = 0

        fun start(
            context: Context,
            transactionId: Long,
            isGrpcStream: Boolean = false,
        ) {
            val intent = Intent(context, TransactionActivity::class.java)
            intent.putExtra(EXTRA_TRANSACTION_ID, transactionId)
            intent.putExtra(EXTRA_IS_GRPC_STREAM, isGrpcStream)
            context.startActivity(intent)
        }
    }
}
