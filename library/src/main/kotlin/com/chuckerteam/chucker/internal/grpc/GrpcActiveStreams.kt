package com.chuckerteam.chucker.internal.grpc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The ids of the gRPC streams that are open in this process.
 *
 * The database alone can't tell this: a stream whose process was killed never gets a status, so it
 * would look open forever.
 */
internal object GrpcActiveStreams {
    private val mutableIds = MutableStateFlow<Set<Long>>(emptySet())

    val ids: StateFlow<Set<Long>> = mutableIds.asStateFlow()

    fun add(transactionId: Long) = mutableIds.update { it + transactionId }

    fun remove(transactionId: Long) = mutableIds.update { it - transactionId }
}
