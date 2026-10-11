package com.samtex.eidreader.data.history

import com.samtex.eidreader.data.settings.SettingsRepository
import com.samtex.eidreader.diagnostics.ReportFormat
import com.samtex.eidreader.diagnostics.ReportGenerator
import com.samtex.eidreader.diagnostics.ReportOptions
import com.samtex.eidreader.domain.model.ScanResult
import com.samtex.eidreader.security.SecureStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

data class HistoryItem(
    val id: String,
    val timestampMs: Long,
    val cardType: String,
    val technologies: String,
    val signature: String?,
    val status: String,
)

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: ScanHistoryDao,
    private val settings: SettingsRepository,
    private val reportGenerator: ReportGenerator,
    private val secureStorage: SecureStorage,
) {
    fun observe(): Flow<List<HistoryItem>> = dao.observeAll().map { list ->
        list.map { HistoryItem(it.id, it.timestampMs, it.cardType, it.technologies, it.signature, it.status) }
    }

    /** Stores the scan only if the user explicitly consented. Returns true when stored. */
    suspend fun saveIfConsented(result: ScanResult): Boolean {
        if (!settings.settings.value.historyConsent) return false
        val report = reportGenerator.generate(result, ReportOptions(ReportFormat.TXT, includeIdentifiers = false))
        val encrypted = secureStorage.encrypt(report.toByteArray(Charsets.UTF_8))
        dao.insert(
            ScanHistoryEntity(
                id = result.id,
                timestampMs = result.timestampMs,
                cardType = result.classification.cardType,
                technologies = result.classification.technologies.joinToString(", "),
                signature = result.classification.signature?.text,
                status = result.error?.kind?.name ?: "COMPLETED",
                encryptedReport = encrypted,
            ),
        )
        dao.trim(MAX_ENTRIES)
        return true
    }

    suspend fun loadReport(id: String): String? {
        val entity = dao.get(id) ?: return null
        return try {
            String(secureStorage.decrypt(entity.encryptedReport), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** Deletes all rows and destroys the encryption key. */
    suspend fun clear() {
        dao.clear()
        secureStorage.destroyKey()
    }

    private companion object {
        const val MAX_ENTRIES = 100
    }
}
