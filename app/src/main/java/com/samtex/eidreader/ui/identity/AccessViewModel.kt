package com.samtex.eidreader.ui.identity

import androidx.lifecycle.ViewModel
import com.samtex.eidreader.data.scan.ScanController
import com.samtex.eidreader.icao.access.AccessCredentials
import com.samtex.eidreader.icao.reader.ReadOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject

data class AccessForm(
    val documentNumber: String = "",
    val dateOfBirth: LocalDate? = null,
    val dateOfExpiry: LocalDate? = null,
    val can: String = "",
    val readPortrait: Boolean = false,
    val consent: Boolean = false,
    val problems: Set<AccessCredentials.Problem> = emptySet(),
)

/** Holds the access form only while the screen is shown; it is cleared on submit and on exit. */
@HiltViewModel
class AccessViewModel @Inject constructor(private val controller: ScanController) : ViewModel() {

    private val _form = MutableStateFlow(AccessForm())
    val form: StateFlow<AccessForm> = _form.asStateFlow()

    fun setDocumentNumber(v: String) = _form.update {
        it.copy(documentNumber = AccessCredentials.normalizeDocumentNumber(v).take(22), problems = it.problems - AccessCredentials.Problem.DOCUMENT_NUMBER)
    }

    fun setDateOfBirth(v: LocalDate?) = _form.update { it.copy(dateOfBirth = v, problems = it.problems - AccessCredentials.Problem.DATE_OF_BIRTH) }
    fun setDateOfExpiry(v: LocalDate?) = _form.update { it.copy(dateOfExpiry = v, problems = it.problems - AccessCredentials.Problem.DATE_OF_EXPIRY) }
    fun setCan(v: String) = _form.update { it.copy(can = v.filter(Char::isDigit).take(6), problems = it.problems - AccessCredentials.Problem.CAN) }
    fun setReadPortrait(v: Boolean) = _form.update { it.copy(readPortrait = v) }
    fun setConsent(v: Boolean) = _form.update { it.copy(consent = v) }

    /** Validates and hands the credentials to the controller. Returns true when the read was requested. */
    fun submit(): Boolean {
        val f = _form.value
        if (!f.consent) return false
        return when (
            val v = AccessCredentials.create(
                documentNumber = f.documentNumber.ifBlank { null },
                dateOfBirth = f.dateOfBirth,
                dateOfExpiry = f.dateOfExpiry,
                can = f.can.ifBlank { null },
            )
        ) {
            is AccessCredentials.Validation.Invalid -> {
                _form.update { it.copy(problems = v.errors) }
                false
            }
            is AccessCredentials.Validation.Valid -> {
                controller.requestIdentityRead(v.credentials, ReadOptions(readPortrait = f.readPortrait))
                _form.value = AccessForm()
                true
            }
        }
    }

    override fun onCleared() {
        _form.value = AccessForm()
    }
}
