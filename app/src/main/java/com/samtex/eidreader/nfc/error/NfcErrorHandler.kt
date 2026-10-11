package com.samtex.eidreader.nfc.error

import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Maps arbitrary throwables raised during a card session to an [ErrorKind]. */
@Singleton
class NfcErrorHandler @Inject constructor() {

    fun classify(t: Throwable): ErrorKind = when (t) {
        is NfcEngineException -> t.kind
        is TimeoutCancellationException -> ErrorKind.TIMEOUT
        is IllegalArgumentException -> ErrorKind.INVALID_INPUT
        is IOException -> ErrorKind.COMMUNICATION
        is IllegalStateException -> ErrorKind.COMMUNICATION
        else -> ErrorKind.UNEXPECTED
    }

    fun isConnectionLoss(t: Throwable): Boolean = classify(t).isConnectionLoss
}
