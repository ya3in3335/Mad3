package com.samtex.eidreader.nfc.protocol

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.BerTlv
import com.samtex.eidreader.nfc.apdu.StatusWordInfo
import com.samtex.eidreader.nfc.apdu.StatusWords
import com.samtex.eidreader.nfc.transport.ApduChannel
import java.io.ByteArrayOutputStream

sealed interface EfReadResult {
    data class Success(val content: ByteArray, val truncated: Boolean) : EfReadResult
    data class SelectFailed(val status: StatusWordInfo) : EfReadResult
    data class ReadFailed(val status: StatusWordInfo, val partial: ByteArray) : EfReadResult
}

/**
 * Reads a public transparent EF that contains a single BER-TLV object (e.g. EF.CardAccess).
 * The total length is derived from the TLV header so that no read goes past the end of file.
 */
object TransparentFileReader {
    private const val HEADER_READ = 4

    suspend fun readTlvFile(channel: ApduChannel, fid: Int, label: String, maxBytes: Int = 4096): EfReadResult {
        val select = channel.transmit(ApduCommands.selectEf(fid), "SELECT $label (${Hex.u16(fid)})", publicData = true)
        if (!select.status.isSuccess) return EfReadResult.SelectFailed(select.status)

        val buffer = ByteArrayOutputStream()
        val head = channel.transmit(ApduCommands.readBinary(0, HEADER_READ), "READ BINARY $label header", publicData = true)
        val headOk = head.status.isSuccess || head.response.sw == StatusWords.SW_END_OF_FILE
        if (!headOk || head.response.dataLength == 0) return EfReadResult.ReadFailed(head.status, ByteArray(0))
        buffer.write(head.response.data)
        if (head.response.sw == StatusWords.SW_END_OF_FILE) {
            return EfReadResult.Success(buffer.toByteArray(), truncated = false)
        }

        val total = BerTlv.encodedLength(buffer.toByteArray())
            ?: return EfReadResult.Success(buffer.toByteArray(), truncated = true)
        val target = minOf(total, maxBytes)
        while (buffer.size() < target) {
            val offset = buffer.size()
            if (offset > 0x7FFF) break
            val len = minOf(channel.maxShortReadLength, target - offset)
            val chunk = channel.transmit(ApduCommands.readBinary(offset, len), "READ BINARY $label @$offset", publicData = true)
            val ok = chunk.status.isSuccess || chunk.response.sw == StatusWords.SW_END_OF_FILE
            if (!ok) return EfReadResult.ReadFailed(chunk.status, buffer.toByteArray())
            if (chunk.response.dataLength == 0) break
            buffer.write(chunk.response.data)
            if (chunk.response.sw == StatusWords.SW_END_OF_FILE) break
        }
        return EfReadResult.Success(buffer.toByteArray(), truncated = total > maxBytes || buffer.size() < total)
    }
}
