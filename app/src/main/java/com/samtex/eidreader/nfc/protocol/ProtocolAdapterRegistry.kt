package com.samtex.eidreader.nfc.protocol

import com.samtex.eidreader.nfc.tag.TagSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/** Ordered set of adapters run by the public probe. Order matters: MF-level files first. */
@Singleton
class ProtocolAdapterRegistry(private val adapters: List<CardProtocolAdapter>) {

    @Inject
    constructor(
        cardAccess: IcaoCardAccessAdapter,
        emrtd: IcaoEmrtdApplicationAdapter,
    ) : this(listOf(cardAccess, emrtd))

    fun all(): List<CardProtocolAdapter> = adapters

    fun applicableTo(snapshot: TagSnapshot): List<CardProtocolAdapter> = adapters.filter { it.isApplicable(snapshot) }
}
