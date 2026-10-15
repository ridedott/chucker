package com.chuckerteam.chucker.internal.ui.transaction

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.databinding.ChuckerFragmentTransactionOverviewBinding
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.support.combineLatest

internal class TransactionOverviewFragment : Fragment() {
    private val viewModel: TransactionViewModel by activityViewModels { TransactionViewModelFactory() }

    private lateinit var overviewBinding: ChuckerFragmentTransactionOverviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        overviewBinding = ChuckerFragmentTransactionOverviewBinding.inflate(inflater, container, false)
        return overviewBinding.root
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        inflater: MenuInflater,
    ) {
        menu.findItem(R.id.save_body).isVisible = false
        viewModel.doesUrlRequireEncoding.observe(
            viewLifecycleOwner,
            Observer { menu.findItem(R.id.encode_url).isVisible = it },
        )

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.transaction.combineLatest(viewModel.encodeUrl).observe(
            viewLifecycleOwner,
            Observer { (transaction, encodeUrl) -> populateUI(transaction, encodeUrl) },
        )
    }

    private fun populateUI(
        transaction: HttpTransaction?,
        encodeUrl: Boolean,
    ) {
        val isGrpc = transaction?.isGrpc == true
        with(overviewBinding) {
            url.text = transaction?.getFormattedUrl(encodeUrl)
            hostIp.text = transaction?.hostIp
            method.text = if (isGrpc) transaction.grpcFullMethodName else transaction?.method
            protocol.text = transaction?.protocol
            status.text = transaction?.status.toString()
            response.text = transaction?.responseSummaryText
            populateGrpcRows(transaction?.takeIf { isGrpc })
            // gRPC calls only learn whether they use TLS once connected.
            when (transaction?.isSsl.takeUnless { isGrpc && transaction.scheme == null }) {
                null -> {
                    sslGroup.visibility = View.GONE
                }
                true -> {
                    sslGroup.visibility = View.VISIBLE
                    sslValue.setText(R.string.chucker_yes)
                }
                else -> {
                    sslGroup.visibility = View.VISIBLE
                    sslValue.setText(R.string.chucker_no)
                }
            }
            if (transaction?.responseTlsVersion != null) {
                tlsVersionValue.text = transaction.responseTlsVersion
                tlsGroup.visibility = View.VISIBLE
            }
            if (transaction?.responseCipherSuite != null) {
                cipherSuiteValue.text = transaction.responseCipherSuite
                cipherSuiteGroup.visibility = View.VISIBLE
            }
            requestTime.text = transaction?.requestDateString
            responseTime.text = transaction?.responseDateString
            duration.text = transaction?.durationString
            requestSize.text = transaction?.requestSizeString
            responseSize.text = transaction?.responseSizeString
            totalSize.text = transaction?.totalSizeString
        }
    }

    private fun populateGrpcRows(transaction: HttpTransaction?) {
        with(overviewBinding) {
            showRow(
                grpcCallTypeLabel,
                grpcCallType,
                transaction?.let { getString(GrpcPresentation.callTypeLabel(it.grpcMethodType)) },
            )
            showRow(grpcDescriptionLabel, grpcDescription, transaction?.grpcStatusDescription)
            showRow(
                grpcMessagesLabel,
                grpcMessages,
                transaction?.let {
                    getString(R.string.chucker_grpc_messages_value, it.requestMessageCount, it.responseMessageCount)
                },
            )
            showRow(
                grpcDeadlineLabel,
                grpcDeadline,
                transaction?.let {
                    it.grpcDeadlineMs?.let { ms -> "$ms ms" } ?: getString(R.string.chucker_grpc_no_deadline)
                },
            )
            showRow(errorLabel, errorValue, transaction?.error)
        }
    }

    private fun showRow(
        label: TextView,
        value: TextView,
        text: String?,
    ) {
        label.isVisible = text != null
        value.isVisible = text != null
        value.text = text
    }
}
