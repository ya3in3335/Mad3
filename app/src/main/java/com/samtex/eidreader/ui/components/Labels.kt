package com.samtex.eidreader.ui.components

import androidx.annotation.StringRes
import com.samtex.eidreader.R
import com.samtex.eidreader.domain.model.IsoDepStatus
import com.samtex.eidreader.domain.model.ScanPhase
import com.samtex.eidreader.nfc.protocol.ProbeStatus

@StringRes
fun phaseLabel(phase: ScanPhase): Int = when (phase) {
    ScanPhase.NFC_UNAVAILABLE -> R.string.phase_nfc_unavailable
    ScanPhase.NFC_DISABLED -> R.string.phase_nfc_disabled
    ScanPhase.WAITING -> R.string.phase_waiting
    ScanPhase.CARD_DETECTED -> R.string.phase_card_detected
    ScanPhase.CONNECTING -> R.string.phase_connecting
    ScanPhase.READING -> R.string.phase_reading
    ScanPhase.COMPLETED -> R.string.phase_completed
    ScanPhase.CONNECTION_LOST -> R.string.phase_connection_lost
    ScanPhase.ERROR -> R.string.phase_error
}

@StringRes
fun isoDepLabel(status: IsoDepStatus): Int = when (status) {
    IsoDepStatus.NOT_SUPPORTED -> R.string.isodep_not_supported
    IsoDepStatus.ESTABLISHED -> R.string.isodep_established
    IsoDepStatus.FAILED -> R.string.isodep_failed
    IsoDepStatus.CONNECTION_LOST -> R.string.isodep_lost
}

@StringRes
fun probeLabel(status: ProbeStatus): Int = when (status) {
    ProbeStatus.AVAILABLE -> R.string.probe_available
    ProbeStatus.NOT_PRESENT -> R.string.probe_not_present
    ProbeStatus.ACCESS_RESTRICTED -> R.string.probe_restricted
    ProbeStatus.ERROR -> R.string.probe_error
}
