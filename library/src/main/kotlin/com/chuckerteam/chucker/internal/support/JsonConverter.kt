package com.chuckerteam.chucker.internal.support

import com.google.gson.Gson
import com.google.gson.GsonBuilder

internal object JsonConverter {
    val nonNullSerializerInstance: Gson by lazy {
        GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()
    }

    /** Writes JSON without indentation, to keep what is stored in the database small. */
    val compactInstance: Gson by lazy {
        GsonBuilder()
            .disableHtmlEscaping()
            .create()
    }

    val instance: Gson by lazy {
        nonNullSerializerInstance
            .newBuilder()
            .serializeNulls()
            .create()
    }
}
