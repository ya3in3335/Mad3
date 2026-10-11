package com.samtex.eidreader.domain.usecase

import com.samtex.eidreader.nfc.apdu.ApduParser
import com.samtex.eidreader.nfc.apdu.ResponseApdu
import com.samtex.eidreader.security.InputValidator
import javax.inject.Inject

/** Offline decoding of response APDUs typed or pasted by the user. */
class ParseResponseUseCase @Inject constructor() {

    sealed interface Outcome {
        data class Parsed(val description: ApduParser.ResponseDescription) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    operator fun invoke(input: String): Outcome = when (val parsed = InputValidator.parseResponseApdu(input)) {
        is InputValidator.Result.Invalid -> Outcome.Invalid(parsed.reason)
        is InputValidator.Result.Valid -> Outcome.Parsed(ApduParser.describe(ResponseApdu(parsed.value)))
    }

    fun describe(response: ResponseApdu): ApduParser.ResponseDescription = ApduParser.describe(response)
}
