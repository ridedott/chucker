package com.chuckerteam.chucker.internal.ui.transaction

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.map
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.data.repository.RepositoryProvider
import com.chuckerteam.chucker.internal.grpc.GrpcActiveStreams
import com.chuckerteam.chucker.internal.support.combineLatest

internal class TransactionViewModel(
    val transactionId: Long,
) : ViewModel() {
    private val mutableEncodeUrl = MutableLiveData<Boolean>(false)

    val encodeUrl: LiveData<Boolean> = mutableEncodeUrl

    val transactionTitle: LiveData<String> =
        RepositoryProvider
            .transaction()
            .getTransaction(transactionId)
            .combineLatest(encodeUrl) { transaction, encodeUrl ->
                when {
                    transaction == null -> ""
                    transaction.isGrpc -> GrpcPresentation.shortMethodName(transaction.path)
                    else -> "${transaction.method} ${transaction.getFormattedPath(encode = encodeUrl)}"
                }
            }

    val doesUrlRequireEncoding: LiveData<Boolean> =
        RepositoryProvider
            .transaction()
            .getTransaction(transactionId)
            .map { transaction ->
                if (transaction == null) {
                    false
                } else {
                    transaction.getFormattedPath(encode = true) != transaction.getFormattedPath(encode = false)
                }
            }

    val doesRequestBodyRequireEncoding: LiveData<Boolean> =
        RepositoryProvider
            .transaction()
            .getTransaction(transactionId)
            .map { transaction ->
                transaction?.requestContentType?.contains("x-www-form-urlencoded", ignoreCase = true) ?: false
            }

    val transaction: LiveData<HttpTransaction?> = RepositoryProvider.transaction().getTransaction(transactionId)

    val isGrpc: LiveData<Boolean> =
        transaction
            .map { it?.isGrpc == true }
            .distinctUntilChanged()

    /** Whether the call is a gRPC stream, which gets its own screen instead of the usual tabs. */
    val isGrpcStream: LiveData<Boolean> =
        transaction
            .map { it != null && it.isGrpc && GrpcPresentation.isStreaming(it.grpcMethodType) }
            .distinctUntilChanged()

    /** Whether the call is a gRPC stream that is still open. */
    val isLive: LiveData<Boolean> =
        GrpcActiveStreams.ids
            .asLiveData()
            .map { transactionId in it }
            .distinctUntilChanged()

    /** Where a gRPC call was made to, as its title only names the method. */
    val transactionSubtitle: LiveData<String?> =
        transaction
            .map { transaction -> transaction?.takeIf { it.isGrpc }?.let { GrpcPresentation.authority(it.url) } }
            .distinctUntilChanged()

    val formatRequestBody: LiveData<Boolean> =
        doesRequestBodyRequireEncoding
            .combineLatest(encodeUrl) { requiresEncoding, encodeUrl ->
                !(requiresEncoding && encodeUrl)
            }

    fun switchUrlEncoding() = encodeUrl(!encodeUrl.value!!)

    fun encodeUrl(encode: Boolean) {
        mutableEncodeUrl.value = encode
    }
}

internal class TransactionViewModelFactory(
    private val transactionId: Long = 0L,
) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == TransactionViewModel::class.java) { "Cannot create $modelClass" }
        @Suppress("UNCHECKED_CAST")
        return TransactionViewModel(transactionId) as T
    }
}
