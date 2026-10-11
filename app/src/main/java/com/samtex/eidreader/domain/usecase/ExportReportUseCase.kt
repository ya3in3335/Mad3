package com.samtex.eidreader.domain.usecase

import com.samtex.eidreader.diagnostics.ReportGenerator
import com.samtex.eidreader.diagnostics.ReportOptions
import com.samtex.eidreader.domain.model.ScanResult
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

/**
 * Writes a report to a caller-provided stream (opened through the Storage Access Framework,
 * so SAMTEX never needs storage permissions and the user chooses the destination).
 */
class ExportReportUseCase @Inject constructor(private val generator: ReportGenerator) {

    fun suggestedFileName(result: ScanResult, options: ReportOptions): String = generator.fileName(result, options.format)

    fun render(result: ScanResult, options: ReportOptions): String = generator.generate(result, options)

    /** @throws IOException when writing fails. */
    fun export(result: ScanResult, options: ReportOptions, output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { it.write(render(result, options)) }
    }
}
