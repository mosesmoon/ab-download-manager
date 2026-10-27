package ir.amirab.downloader.destination

import ir.amirab.downloader.part.DownloadPart
import ir.amirab.downloader.utils.calcPercent
import okhttp3.internal.closeQuietly
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

class SegmentedDownloadDestination(
    // a directory unique for this item!
    val tempDirectory: File,
    val getFileName: (DownloadPart) -> String,
    val getAllParts: () -> List<DownloadPart>,
    val appendMode: Boolean,
    outputFile: File,
    /** assemble parts in the order returned by [getAllParts] instead of sorting them by id */
    val keepPartOrder: Boolean = false,
    /** number of leading bytes of a part file to skip when assembling (e.g. a fake image header) */
    val getPayloadOffset: ((File) -> Long)? = null,
    /**
     * returns the bytes to write for a part instead of its file content (e.g. a decrypted segment),
     * or `null` to copy the file as-is
     */
    val preparePartData: ((DownloadPart, File) -> ByteArray?)? = null,
) : DownloadDestination(outputFile) {
    private fun getFileOfPart(downloadPart: DownloadPart): File {
        val id = downloadPart.getID()
        return tempDirectory.resolve("$id")
    }

    override fun getWriterFor(part: DownloadPart): DestWriter {
        tempDirectory.mkdirs()
        val tFile = getFileOfPart(part)
        val writer = FileSystem.SYSTEM.openReadWrite(tFile.toOkioPath(), mustCreate = false, mustExist = false)
        val newSize = if (appendMode) {
            writer.size()
        } else {
            // part starts from the beginning
            0
        }
        writer.resize(newSize)
        val destWriter = DestWriter(
            id = part.getID(),
            file = tFile,
            seekPos = newSize,
            writer = writer,
        )
        synchronized(this) {
            fileParts.add(destWriter)
        }
        return destWriter
    }

    override fun onPartCancelled(part: DownloadPart) {
        super.onPartCancelled(part)
        val id = part.getID()
        synchronized(this) {
            fileParts
                .find { it.id == id }
                ?.writer
                ?.closeQuietly()
        }
    }


    override fun canGetFileWriter(): Boolean {
        return true
    }

    override suspend fun prepareFile(onProgressUpdate: (Int?) -> Unit) {

    }

    override suspend fun isDownloadedPartsIsValid(): Boolean {
        return tempDirectory.exists()
    }

    fun isDownloadPartValid(part: DownloadPart): Boolean {
        return getFileOfPart(part).exists()
    }

    override fun cleanUpJunkFiles() {
        runCatching {
            FileSystem.SYSTEM.deleteRecursively(tempDirectory.toOkioPath())
        }
    }

    override fun onAllPartsCompleted(onProgressUpdate: (Int?) -> Unit) {
        val parts = getAllParts()
            .let { parts -> if (keepPartOrder) parts else parts.sortedBy { it.getID() } }
        assemble(
            parts.map { tempDirectory.resolve(getFileName(it)) },
            outputFile,
            onProgressUpdate,
            parts,
        )
        super.onAllPartsCompleted(onProgressUpdate)
    }

    fun assemble(
        sources: List<File>,
        destination: File,
        onProgress: (Int) -> Unit,
        parts: List<DownloadPart>? = null,
    ) {
        DownloadDestination.prepareDestinationFolder(outputFile)
        val offsets = sources.map { file ->
            getPayloadOffset
                ?.let { runCatching { it(file) }.getOrNull() }
                ?.coerceIn(0, file.length())
                ?: 0L
        }
        val totalLength = sources.indices.sumOf { sources[it].length() - offsets[it] }
        var totalWritten = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var percent = 0
        destination.outputStream().use { dst ->
            sources.forEachIndexed { index, sourceFile ->
                val prepared = parts?.getOrNull(index)?.let { part -> preparePartData?.invoke(part, sourceFile) }
                if (prepared != null) {
                    // already transformed in memory (e.g. decrypted)
                    dst.write(prepared)
                    totalWritten += sourceFile.length() - offsets[index]
                    val newPercent = calcPercent(totalWritten, totalLength)
                    if (newPercent != percent) {
                        onProgress(newPercent)
                        percent = newPercent
                    }
                    return@forEachIndexed
                }
                sourceFile.inputStream().use { src ->
                    // InputStream.skipNBytes is not available on older Android versions
                    var toSkip = offsets[index]
                    while (toSkip > 0) {
                        val skipped = src.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                    onProgress(percent)
                    while (true) {
                        val len = src.read(buffer)
                        if (len == -1) break
                        dst.write(buffer, 0, len)
                        totalWritten += len

                        val newPercent = calcPercent(totalWritten, totalLength)
                        if (newPercent != percent) {
                            onProgress(newPercent)
                            percent = newPercent
                        }
                    }
                }
            }
        }
        onProgress(100)
    }

    override fun flush() {
        synchronized(this) {
            fileParts.forEach {
                it.writer.flush()
            }
        }
    }
}
