package com.github.digitallyrefined.androidipcamera.helpers

/** Pure helpers for turning MediaCodec HEVC (H.265) output into self-contained Annex-B access units. */
internal object HevcBitstream {
    const val NAL_IDR_W_RADL = 19
    const val NAL_IDR_N_LP = 20
    const val NAL_CRA_NUT = 21
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34
    const val NAL_AUD = 35
    const val NAL_PREFIX_SEI = 39
    const val NAL_SUFFIX_SEI = 40

    fun isVcl(type: Int): Boolean = type in 0..31
    fun isIrap(type: Int): Boolean = type in 16..21

    fun nalRanges(annexB: ByteArray): List<H264Bitstream.NalRange> {
        val first = H264Bitstream.findStartCode(annexB, 0) ?: return emptyList()
        if (first.first > 3 || (0 until first.first).any { annexB[it].toInt() != 0 }) return emptyList()

        val ranges = mutableListOf<H264Bitstream.NalRange>()
        var marker: Pair<Int, Int>? = first
        while (marker != null) {
            val nalStart = marker.first + marker.second
            val next = H264Bitstream.findStartCode(annexB, nalStart)
            val nalEnd = next?.first ?: annexB.size
            if (nalEnd - nalStart < 2) return emptyList()
            val b0 = annexB[nalStart].toInt() and 0xff
            val b1 = annexB[nalStart + 1].toInt() and 0xff
            if ((b0 and 0x80) != 0 || (b1 and 0x07) == 0) return emptyList()
            val nalType = (b0 ushr 1) and 0x3f
            ranges += H264Bitstream.NalRange(
                nalStart,
                nalEnd,
                nalType,
                marker.first,
                marker.second
            )
            marker = next
        }
        return ranges
    }
}

/** Caches HEVC VPS/SPS/PPS parameter sets and prepends them to each IRAP (keyframe). */
internal class HevcParameterSetCache {
    private var vps: List<ByteArray> = emptyList()
    private var sps: List<ByteArray> = emptyList()
    private var pps: List<ByteArray> = emptyList()

    fun prepare(input: ByteArray, keyframeFlag: Boolean): H264ParameterSetCache.AccessUnit? {
        if (input.isEmpty()) return null
        val ranges = HevcBitstream.nalRanges(input)
        if (ranges.isEmpty()) return null

        val newVps = ranges.filter { it.type == HevcBitstream.NAL_VPS }.map { input.copyOfRange(it.start, it.end) }
        val newSps = ranges.filter { it.type == HevcBitstream.NAL_SPS }.map { input.copyOfRange(it.start, it.end) }
        val newPps = ranges.filter { it.type == HevcBitstream.NAL_PPS }.map { input.copyOfRange(it.start, it.end) }
        if (newVps.isNotEmpty()) vps = newVps
        if (newSps.isNotEmpty()) sps = newSps
        if (newPps.isNotEmpty()) pps = newPps

        if (ranges.none { HevcBitstream.isVcl(it.type) }) return null

        val isIrap = ranges.any { HevcBitstream.isIrap(it.type) }
        val canonicalInput = if (ranges.first().startCodeStart == 0 && ranges.all { it.startCodeLength == 4 }) {
            input
        } else {
            H264Bitstream.joinNals(ranges.map { input.copyOfRange(it.start, it.end) })
        }

        if (!keyframeFlag && !isIrap) return H264ParameterSetCache.AccessUnit(canonicalInput, false)
        if (!hasCompleteParameters()) return H264ParameterSetCache.AccessUnit(canonicalInput, false)

        val nals = ArrayList<ByteArray>(ranges.size + vps.size + sps.size + pps.size)
        for (range in ranges.filter { it.type == HevcBitstream.NAL_AUD }) {
            nals += input.copyOfRange(range.start, range.end)
        }
        nals += vps
        nals += sps
        nals += pps
        for (range in ranges) {
            if (range.type != HevcBitstream.NAL_VPS &&
                range.type != HevcBitstream.NAL_SPS &&
                range.type != HevcBitstream.NAL_PPS &&
                range.type != HevcBitstream.NAL_AUD
            ) {
                nals += input.copyOfRange(range.start, range.end)
            }
        }
        return H264ParameterSetCache.AccessUnit(H264Bitstream.joinNals(nals), true)
    }

    fun hasCompleteParameters(): Boolean = vps.isNotEmpty() && sps.isNotEmpty() && pps.isNotEmpty()
}
