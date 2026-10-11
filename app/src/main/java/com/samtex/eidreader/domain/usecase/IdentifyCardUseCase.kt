package com.samtex.eidreader.domain.usecase

import com.samtex.eidreader.nfc.classifier.CardClassification
import com.samtex.eidreader.nfc.classifier.CardClassifier
import com.samtex.eidreader.nfc.protocol.ProtocolProbeResult
import com.samtex.eidreader.nfc.tag.TagSnapshot
import javax.inject.Inject

class IdentifyCardUseCase @Inject constructor(private val classifier: CardClassifier) {
    operator fun invoke(snapshot: TagSnapshot, probes: List<ProtocolProbeResult> = emptyList()): CardClassification =
        classifier.classify(snapshot, probes)
}
