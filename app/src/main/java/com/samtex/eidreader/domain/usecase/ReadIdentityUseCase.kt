package com.samtex.eidreader.domain.usecase

import com.samtex.eidreader.domain.model.DiscoveredCard
import com.samtex.eidreader.icao.IdentityReadResult
import com.samtex.eidreader.icao.ReadOutcome
import com.samtex.eidreader.icao.ReadStep
import com.samtex.eidreader.icao.access.AccessCredentials
import com.samtex.eidreader.icao.reader.IcaoDocumentReader
import com.samtex.eidreader.icao.reader.ReadOptions
import javax.inject.Inject

/** Reads holder data from a discovered card. The caller owns and wipes the credentials. */
class ReadIdentityUseCase @Inject constructor(private val reader: IcaoDocumentReader) {

    suspend operator fun invoke(
        card: DiscoveredCard,
        credentials: AccessCredentials,
        options: ReadOptions,
        onStep: (ReadStep) -> Unit = {},
    ): IdentityReadResult {
        val transport = if (card.snapshot.isoDep != null) card.openIsoDepTransport() else null
        if (transport == null) return IdentityReadResult.failure(ReadOutcome.ISO_DEP_UNAVAILABLE, "The card does not expose ISO-DEP")
        return reader.read(transport, credentials, options, onStep)
    }
}
