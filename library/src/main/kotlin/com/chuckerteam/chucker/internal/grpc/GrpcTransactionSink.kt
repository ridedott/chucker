package com.chuckerteam.chucker.internal.grpc

import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction

/** Where recorded gRPC calls are stored. Calls to a sink for a single call never overlap. */
internal interface GrpcTransactionSink {
    /** Stores a new [transaction] and returns its id. */
    suspend fun insert(transaction: HttpTransaction): Long

    /**
     * Stores the latest state of a transaction previously stored with [insert].
     *
     * @param isFinal whether the call is over, so this is the last update.
     */
    suspend fun update(
        transaction: HttpTransaction,
        isFinal: Boolean,
    )
}

internal class CollectorTransactionSink(
    private val collector: ChuckerCollector,
) : GrpcTransactionSink {
    override suspend fun insert(transaction: HttpTransaction): Long = collector.insertTransaction(transaction)

    override suspend fun update(
        transaction: HttpTransaction,
        isFinal: Boolean,
    ) = collector.updateTransaction(transaction, notify = isFinal)
}

/** Serializes the messages of one direction of a call, to measure and decode them. */
internal interface GrpcMessageCodec {
    /** Returns the size of [message] once serialized, in bytes, or null when it can't be known. */
    fun size(message: Any): Long?

    /** Returns [message] serialized, as sent over the wire, or null when it can't be. */
    fun serialize(message: Any): ByteArray?
}

/** What the transport tells about the connection a call is using, when it's known. */
internal class GrpcConnectionInfo(
    val scheme: String?,
    val tlsVersion: String?,
    val cipherSuite: String?,
    val remoteAddress: String?,
)
