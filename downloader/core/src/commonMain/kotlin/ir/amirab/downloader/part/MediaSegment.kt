package ir.amirab.downloader.part

import ir.amirab.downloader.utils.calcPercent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class MediaSegment(
    val segmentIndex: Long,
    val link: String,
    var duration: Double,
    override var isCompleted: Boolean = false,
    var length: Long? = null,
    @Transient
    override var current: Long = 0,
    /**
     * `true` when this part is an fMP4 initialization section (`#EXT-X-MAP`) rather than a media segment.
     * Init parts use a negative [segmentIndex] (`-(indexOfFollowingSegment + 1)`) so their IDs never
     * collide with media segment IDs, and they are placed right before the first segment that uses them.
     */
    val isInit: Boolean = false,
) : DownloadPart {

    override fun howMuchProceed() = current

    override fun resetCurrent() {
        current = 0
    }

    @Transient
    override val statusFlow = MutableStateFlow<PartDownloadStatus>(PartDownloadStatus.IDLE)

    override val percent: Int?
        get() = length?.let {
            calcPercent(howMuchProceed(), it)
        }

    override fun getID(): Long {
        return segmentIndex.toLong()
    }

    companion object {
    }
}
