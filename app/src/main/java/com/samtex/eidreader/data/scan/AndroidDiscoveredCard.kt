package com.samtex.eidreader.data.scan

import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.samtex.eidreader.domain.model.DiscoveredCard
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.nfc.transport.ApduTransport
import com.samtex.eidreader.nfc.transport.IsoDepTransport

/** [DiscoveredCard] backed by a real Android [Tag]. */
class AndroidDiscoveredCard(
    private val tag: Tag,
    override val snapshot: TagSnapshot,
) : DiscoveredCard {
    override fun openIsoDepTransport(): ApduTransport? = try {
        IsoDep.get(tag)?.let { IsoDepTransport(it) }
    } catch (_: RuntimeException) {
        null
    }
}
