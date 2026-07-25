package com.apricotiris.IrisShot.core.stitch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.core.model.StitchMatchResult
import com.apricotiris.IrisShot.core.model.StitchRejectionReason
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun combineStitchConfidence(
    structuralSimilarity: Float,
    pixelAgreement: Float,
    distinctness: Float,
    geometryConsistency: Float,
    overlapRatio: Float,
    texturedCoverage: Float,
): Float {
    val structure = structuralSimilarity.coerceIn(0f, 1f)
    val pixels = pixelAgreement.coerceIn(0f, 1f)
    val uniqueness = distinctness.coerceIn(0f, 1f)
    val geometry = geometryConsistency.coerceIn(0f, 1f)
    val overlapSupport = sqrt(overlapRatio.coerceIn(0f, 1f))
    val coverageSupport = sqrt(texturedCoverage.coerceIn(0f, 1f))

    // translationMetrics already discards the noisiest spatial blocks, so its structural score
    // is the most reliable signal for animated game screens. Blend rather than multiply the raw
    // all-pixel agreement; multiplying both used to punish the same dynamic content twice.
    val structuralWeight = 0.58f + coverageSupport * 0.16f
    val appearance = structure * structuralWeight + pixels * (1f - structuralWeight)

    // Expected gesture distance is a soft motion prior: real scroll distance can differ after an
    // eased gesture. Actual overlap still contributes evidence, while the hard overlap bounds in
    // match() continue to reject impossible seams.
    val motionSupport = 0.74f + geometry * 0.11f + overlapSupport * 0.15f
    val evidenceSupport = 0.90f + coverageSupport * 0.05f + uniqueness * 0.05f

    // A locally perfect-looking anchor is unsafe when the rest of the overlap, textured area and
    // runner-up separation all provide no support. Penalize that conjunction strongly without
    // suppressing repetitive lists (low uniqueness) or animated cards (lower pixel agreement)
    // when either of the other signals remains healthy.
    val weakEvidence = (1f - pixels) * (1f - coverageSupport) * (1f - uniqueness)
    val weakEvidencePenalty = 1f - 0.72f * weakEvidence * weakEvidence
    return (
        appearance * motionSupport * evidenceSupport * weakEvidencePenalty
        ).coerceIn(0f, 1f)
}

internal data class ExpectedShiftConstraint(
    val expectedShiftPx: Int,
    val tolerancePx: Float,
    val maximumShiftPx: Int,
) {
    fun accepts(actualShiftPx: Int): Boolean =
        abs(actualShiftPx - expectedShiftPx) <= tolerancePx
}

internal fun expectedShiftConstraint(
    axisLength: Int,
    expectedShiftPx: Int?,
    minimumShiftPx: Int,
    maximumShiftPx: Int,
): ExpectedShiftConstraint? {
    if (expectedShiftPx == null || expectedShiftPx <= 0 || axisLength <= 1) return null
    if (maximumShiftPx < minimumShiftPx) return null
    val minimum = minimumShiftPx.coerceIn(1, axisLength - 1)
    val maximum = maximumShiftPx.coerceIn(minimum, axisLength - 1)
    val expected = expectedShiftPx.coerceIn(minimum, maximum)
    val sigma = max(axisLength * 0.08f, expected * 0.25f).coerceAtLeast(1f)
    val tolerance = sigma * 4f
    return ExpectedShiftConstraint(
        expectedShiftPx = expected,
        tolerancePx = tolerance,
        maximumShiftPx = floor(expected + tolerance).toInt().coerceAtMost(maximum),
    )
}

internal fun guardedStitchRejectionReason(
    qualityRejectionReason: StitchRejectionReason?,
    unexpectedShift: Boolean,
): StitchRejectionReason? = if (
    unexpectedShift &&
    (qualityRejectionReason == null || qualityRejectionReason == StitchRejectionReason.LOW_CONFIDENCE)
) {
    StitchRejectionReason.UNEXPECTED_SHIFT
} else {
    qualityRejectionReason
}

internal fun hasDecisiveOppositeDirectionEvidence(
    selectedError: Float,
    oppositeError: Float?,
    selectedConfidence: Float,
    selectedMatchesExpectedShift: Boolean,
    minimumConfidence: Float,
): Boolean {
    if (oppositeError == null ||
        !selectedError.isFinite() ||
        !oppositeError.isFinite() ||
        !selectedConfidence.isFinite()
    ) return false
    // Repetitive text and code rows often provide a locally cleaner anchor in the reverse search.
    // A reliable forward candidate that agrees with the gesture prior is stronger evidence than
    // that isolated reverse anchor. Without such a prior, retain the conservative rejection.
    val trustedRequestedMotion = selectedMatchesExpectedShift &&
        selectedConfidence >= minimumConfidence
    return !trustedRequestedMotion &&
        oppositeError < selectedError * 0.80f &&
        oppositeError < 30f
}

internal fun stationaryAxisPartitionCount(
    direction: ScrollDirection,
    alignedBlockColumns: Int,
    alignedBlockRows: Int,
): Int = if (direction.isVertical) alignedBlockRows else alignedBlockColumns

/**
 * Uses the same sampled pixels to decide whether a visually quiet frame actually stayed put.
 * Sparse text and dividers can move while changing well under one percent of a mostly white page,
 * so the absolute whole-frame thresholds are only a gate, not proof of stationarity.
 */
internal fun hasPixelStationaryEvidence(
    frameDifference: Float,
    changedPixelRatio: Float,
    bestScrollingDifference: Float,
    bestScrollingChangedRatio: Float,
): Boolean {
    if (!frameDifference.isFinite() ||
        !changedPixelRatio.isFinite() ||
        !bestScrollingDifference.isFinite() ||
        !bestScrollingChangedRatio.isFinite() ||
        frameDifference > PIXEL_STATIONARY_MAX_FRAME_DIFFERENCE ||
        changedPixelRatio > PIXEL_STATIONARY_MAX_CHANGED_RATIO
    ) return false

    return frameDifference <= bestScrollingDifference + PIXEL_STATIONARY_DIFFERENCE_EPSILON &&
        changedPixelRatio <=
        bestScrollingChangedRatio + PIXEL_STATIONARY_CHANGED_RATIO_EPSILON
}

/**
 * Recognizes a stationary frame after small rendering changes such as overscroll recovery.
 *
 * A relaxed pixel threshold is only allowed when textured content still aligns at zero shift and
 * is no worse than the best legal scrolling displacement. This keeps sparse real scrolling from
 * being mistaken for the end of a repetitive list.
 */
internal fun hasStructuralStationaryEvidence(
    frameDifference: Float,
    changedPixelRatio: Float,
    zeroShiftError: Float,
    zeroShiftCoverage: Float,
    zeroShiftAxisPartitions: Int,
    bestScrollingError: Float,
): Boolean {
    if (!frameDifference.isFinite() ||
        !changedPixelRatio.isFinite() ||
        !zeroShiftError.isFinite() ||
        !zeroShiftCoverage.isFinite() ||
        !bestScrollingError.isFinite() ||
        zeroShiftCoverage < STATIONARY_MIN_TEXTURE_COVERAGE ||
        zeroShiftAxisPartitions < STATIONARY_MIN_AXIS_PARTITIONS ||
        zeroShiftError > STATIONARY_MAX_STRUCTURE_ERROR
    ) return false

    val clearlyPrefersZeroShift =
        frameDifference <= STATIONARY_STRUCTURAL_MAX_FRAME_DIFFERENCE &&
            changedPixelRatio <= STATIONARY_STRUCTURAL_MAX_CHANGED_RATIO &&
            zeroShiftError + STATIONARY_WIN_MARGIN <= bestScrollingError
    val lightlyRedrawnAtSamePosition =
        frameDifference <= STATIONARY_REDRAW_MAX_FRAME_DIFFERENCE &&
            changedPixelRatio <= STATIONARY_REDRAW_MAX_CHANGED_RATIO &&
            zeroShiftError <= STATIONARY_REDRAW_MAX_STRUCTURE_ERROR &&
            zeroShiftError <= bestScrollingError
    return clearlyPrefersZeroShift || lightlyRedrawnAtSamePosition
}

/** Dependency-free, translation-only stitcher with conservative quality rejection. */
class StitchEngine(
    private val minOverlapRatio: Float = DefaultCaptureConfig.MIN_OVERLAP_RATIO,
    private val maxOverlapRatio: Float = DefaultCaptureConfig.MAX_OVERLAP_RATIO,
    private val minMatchConfidence: Float = DefaultCaptureConfig.MIN_MATCH_CONFIDENCE,
    private val analysisMaxDimension: Int = 720,
    private val maxOutputDimension: Int = DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
    private val maxOutputPixels: Long = DefaultCaptureConfig.MAX_SINGLE_BITMAP_PIXELS
) {
    init {
        require(minOverlapRatio in 0f..1f)
        require(maxOverlapRatio in 0f..1f && maxOverlapRatio > minOverlapRatio)
        require(minMatchConfidence in 0f..1f)
        require(analysisMaxDimension >= 128)
    }

    /**
     * Finds the displacement of matching content in [current] relative to [previous].
     * A negative dy therefore means content moved upward and new pixels appeared at the bottom.
     */
    fun match(
        previous: Bitmap,
        current: Bitmap,
        direction: ScrollDirection,
        expectedShiftPx: Int? = null,
        allowTinyShift: Boolean = false,
        allowStructuralStationary: Boolean = false,
        maximumShiftPx: Int? = null,
    ): StitchMatchResult {
        require(!previous.isRecycled && !current.isRecycled) { "不能匹配已释放 Bitmap" }
        require(previous.width == current.width && previous.height == current.height) {
            "关键帧尺寸必须一致: ${previous.width}x${previous.height} / ${current.width}x${current.height}"
        }
        if (previous.sameAs(current)) {
            return StitchMatchResult(
                dx = 0,
                dy = 0,
                overlapRatio = 1f,
                confidence = 1f,
                accepted = false,
                reason = "相邻画面完全相同",
                rejectionReason = StitchRejectionReason.DUPLICATE,
                frameDifference = 0f,
                changedPixelRatio = 0f,
            )
        }

        val (width, height) = if (allowTinyShift) {
            // Preserve the scrolling axis at source resolution so a 1px terminal remainder does
            // not round to zero. Only the cross axis is reduced to keep analysis memory bounded.
            if (direction.isVertical) {
                minOf(
                    previous.width,
                    (TINY_SHIFT_MAX_ANALYSIS_PIXELS / previous.height.coerceAtLeast(1))
                        .coerceAtLeast(1),
                ) to previous.height
            } else {
                previous.width to minOf(
                    previous.height,
                    (TINY_SHIFT_MAX_ANALYSIS_PIXELS / previous.width.coerceAtLeast(1))
                        .coerceAtLeast(1),
                )
            }
        } else {
            val scale = minOf(
                1f,
                analysisMaxDimension.toFloat() / max(previous.width, previous.height),
            )
            (previous.width * scale).roundToInt().coerceAtLeast(1) to
                (previous.height * scale).roundToInt().coerceAtLeast(1)
        }
        val before = GrayFrame.fromScaled(previous, width, height)
        val after = GrayFrame.fromScaled(current, width, height)

        val differenceStepX = if (allowTinyShift && !direction.isVertical) 1 else 3
        val differenceStepY = if (allowTinyShift && direction.isVertical) 1 else 3
        val zeroDifference = luminanceDifferenceStats(
            before,
            after,
            dx = 0,
            dy = 0,
            sampleStepX = differenceStepX,
            sampleStepY = differenceStepY,
        )
        val sourceMainDimension = if (direction.isVertical) previous.height else previous.width
        val analysisMainDimension = if (direction.isVertical) height else width
        val axisScale = analysisMainDimension.toFloat() / sourceMainDimension
        val expectedShiftAtAnalysis = expectedShiftPx?.let { (it * axisScale).roundToInt() }
        val maximumShiftAtAnalysis = maximumShiftPx
            ?.takeIf { it > 0 }
            ?.let { (it * axisScale).roundToInt().coerceAtLeast(1) }
        val effectiveMaxOverlapRatio = if (allowTinyShift) {
            1f - 1f / sourceMainDimension.coerceAtLeast(2)
        } else {
            maxOverlapRatio
        }
        val analysisMaxOverlapRatio = if (allowTinyShift) {
            1f - 1f / analysisMainDimension.coerceAtLeast(2)
        } else {
            maxOverlapRatio
        }
        val expected = searchBest(
            before,
            after,
            direction,
            expectedShiftAtAnalysis,
            analysisMaxOverlapRatio,
            preserveScrollingAxis = allowTinyShift,
            maximumShift = maximumShiftAtAnalysis,
        )
            ?: return StitchMatchResult(
                dx = 0,
                dy = 0,
                overlapRatio = 0f,
                confidence = 0f,
                accepted = false,
                reason = "没有足够的重叠区域",
                rejectionReason = StitchRejectionReason.NO_OVERLAP,
                frameDifference = zeroDifference.mean,
                changedPixelRatio = zeroDifference.changedRatio,
            )
        val opposite = if (expected.best.error >= 8f) {
            searchBest(
                before,
                after,
                direction.opposite(),
                expectedShiftAtAnalysis,
                analysisMaxOverlapRatio,
                preserveScrollingAxis = allowTinyShift,
                maximumShift = maximumShiftAtAnalysis,
            )
        } else null

        val coarseSourceShift = (
            abs(if (direction.isVertical) expected.best.dy else expected.best.dx) / axisScale
            ).roundToInt()
        val refinedSourceShift = refineSourceShift(
            previous = previous,
            current = current,
            direction = direction,
            coarseSourceShift = coarseSourceShift,
            axisScale = axisScale,
            expectedShiftPx = expectedShiftPx,
            effectiveMaxOverlapRatio = effectiveMaxOverlapRatio,
            maximumShiftPx = maximumShiftPx,
        )
        val (sourceDx, sourceDy) = direction.displacement(refinedSourceShift)
        val mainShift = abs(if (direction.isVertical) sourceDy else sourceDx)
        val mainDimension = if (direction.isVertical) previous.height else previous.width
        val overlap = (1f - mainShift.toFloat() / mainDimension).coerceIn(0f, 1f)
        val matchedDifference = luminanceDifferenceStats(
            before,
            after,
            expected.best.dx,
            expected.best.dy,
            sampleStepX = differenceStepX,
            sampleStepY = differenceStepY,
        )
        val bestScrollingDifference = luminanceDifferenceStats(
            before,
            after,
            expected.visualBest.dx,
            expected.visualBest.dy,
            sampleStepX = differenceStepX,
            sampleStepY = differenceStepY,
        )
        val matchedStructure = translationMetrics(
            before,
            after,
            expected.best.dx,
            expected.best.dy,
            sampleStep = 3,
        )
        val zeroShiftStructure = translationMetrics(
            before,
            after,
            dx = 0,
            dy = 0,
            sampleStep = 3,
        )
        val structuralSimilarity = (1f - matchedStructure.error / 42f).coerceIn(0f, 1f)
        val pixelAgreement = (
            (1f - matchedDifference.mean / 32f).coerceIn(0f, 1f) * 0.55f +
                (1f - matchedDifference.changedRatio).coerceIn(0f, 1f) * 0.45f
            )
        val distinctness = expected.runnerUp?.let {
            ((it.error - expected.best.error) / 5f).coerceIn(0f, 1f)
        } ?: 1f
        val minExpectedShift = ceil(mainDimension * (1f - effectiveMaxOverlapRatio)).toInt()
            .coerceAtLeast(1)
        val maxExpectedShift = minOf(
            floor(mainDimension * (1f - minOverlapRatio)).toInt(),
            maximumShiftPx?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        ).coerceAtMost(mainDimension - 1)
        val sourceExpectedConstraint = expectedShiftConstraint(
            axisLength = mainDimension,
            expectedShiftPx = expectedShiftPx,
            minimumShiftPx = minExpectedShift,
            maximumShiftPx = maxExpectedShift,
        )
        val geometryConsistency = sourceExpectedConstraint?.let { constraint ->
            val sigma = constraint.tolerancePx / 4f
            val normalized = (mainShift - constraint.expectedShiftPx) / sigma
            exp(-0.5f * normalized * normalized)
        } ?: 1f
        val confidence = combineStitchConfidence(
            structuralSimilarity = structuralSimilarity,
            pixelAgreement = pixelAgreement,
            distinctness = distinctness,
            geometryConsistency = geometryConsistency,
            overlapRatio = overlap,
            texturedCoverage = matchedStructure.coverage,
        )

        // Raw pixels catch a completely still frame. The structural path additionally tolerates
        // animated portraits, effects and a small redraw after stretch recovery. The latter is
        // accepted only when zero-shift structure remains competitive with the best translation.
        val pixelStationary = hasPixelStationaryEvidence(
            frameDifference = zeroDifference.mean,
            changedPixelRatio = zeroDifference.changedRatio,
            bestScrollingDifference = bestScrollingDifference.mean,
            bestScrollingChangedRatio = bestScrollingDifference.changedRatio,
        )
        val structurallyStationary = hasStructuralStationaryEvidence(
            frameDifference = zeroDifference.mean,
            changedPixelRatio = zeroDifference.changedRatio,
            zeroShiftError = zeroShiftStructure.error,
            zeroShiftCoverage = zeroShiftStructure.coverage,
            zeroShiftAxisPartitions = stationaryAxisPartitionCount(
                direction = direction,
                alignedBlockColumns = zeroShiftStructure.alignedBlockColumns,
                alignedBlockRows = zeroShiftStructure.alignedBlockRows,
            ),
            bestScrollingError = expected.visualBest.error,
        )
        val noMovement = pixelStationary || (allowStructuralStationary && structurallyStationary)
        val wrongDirection = hasDecisiveOppositeDirectionEvidence(
            selectedError = expected.best.error,
            oppositeError = opposite?.best?.error,
            selectedConfidence = confidence,
            selectedMatchesExpectedShift = sourceExpectedConstraint?.accepts(mainShift) == true,
            minimumConfidence = minMatchConfidence,
        )
        val unexpectedShift = sourceExpectedConstraint?.accepts(mainShift) == false
        val overlapEpsilon = 1f / mainDimension.coerceAtLeast(1)
        val qualityRejection = when {
            noMovement -> StitchRejectionReason.DUPLICATE to "相邻画面几乎完全相同"
            wrongDirection -> StitchRejectionReason.WRONG_DIRECTION to "检测到的位移与所选滚动方向不一致"
            overlap < minOverlapRatio - overlapEpsilon -> StitchRejectionReason.OVERLAP_TOO_LOW to "重叠比例过低"
            overlap > effectiveMaxOverlapRatio + overlapEpsilon -> StitchRejectionReason.OVERLAP_TOO_HIGH to "重叠比例过高，新增内容不足"
            confidence < minMatchConfidence -> StitchRejectionReason.LOW_CONFIDENCE to "匹配置信度过低"
            else -> null
        }
        val guardedRejectionReason = guardedStitchRejectionReason(
            qualityRejectionReason = qualityRejection?.first,
            unexpectedShift = unexpectedShift,
        )
        val rejection = if (guardedRejectionReason == StitchRejectionReason.UNEXPECTED_SHIFT) {
            StitchRejectionReason.UNEXPECTED_SHIFT to "检测到的位移超出预期范围"
        } else {
            qualityRejection
        }
        return StitchMatchResult(
            dx = sourceDx,
            dy = sourceDy,
            overlapRatio = overlap,
            confidence = confidence,
            accepted = rejection == null,
            reason = rejection?.second,
            rejectionReason = rejection?.first,
            frameDifference = zeroDifference.mean,
            changedPixelRatio = zeroDifference.changedRatio,
        )
    }

    /** Adds only the non-overlapping strip, deliberately keeping a crisp game-UI seam. */
    fun append(
        base: Bitmap,
        next: Bitmap,
        match: StitchMatchResult,
        direction: ScrollDirection
    ): Bitmap {
        require(match.accepted) { match.reason ?: "不能拼接未通过质量检查的帧" }
        require(!base.isRecycled && !next.isRecycled)
        if (direction.isVertical) {
            require(base.width == next.width) { "纵向拼接要求宽度一致" }
        } else {
            require(base.height == next.height) { "横向拼接要求高度一致" }
        }
        val shift = abs(if (direction.isVertical) match.dy else match.dx)
        val frameLength = if (direction.isVertical) next.height else next.width
        require(shift in 1 until frameLength) { "有效位移必须小于关键帧尺寸" }

        val outputWidth = if (direction.isVertical) base.width else base.width + shift
        val outputHeight = if (direction.isVertical) base.height + shift else base.height
        validateOutputSize(outputWidth, outputHeight)
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
        when (direction) {
            ScrollDirection.UP -> {
                canvas.drawBitmap(base, 0f, 0f, paint)
                val source = Rect(0, next.height - shift, next.width, next.height)
                val target = Rect(0, base.height, next.width, base.height + shift)
                canvas.drawBitmap(next, source, target, paint)
            }
            ScrollDirection.DOWN -> {
                val source = Rect(0, 0, next.width, shift)
                val target = Rect(0, 0, next.width, shift)
                canvas.drawBitmap(next, source, target, paint)
                canvas.drawBitmap(base, 0f, shift.toFloat(), paint)
            }
            ScrollDirection.LEFT -> {
                canvas.drawBitmap(base, 0f, 0f, paint)
                val source = Rect(next.width - shift, 0, next.width, next.height)
                val target = Rect(base.width, 0, base.width + shift, next.height)
                canvas.drawBitmap(next, source, target, paint)
            }
            ScrollDirection.RIGHT -> {
                val source = Rect(0, 0, shift, next.height)
                val target = Rect(0, 0, shift, next.height)
                canvas.drawBitmap(next, source, target, paint)
                canvas.drawBitmap(base, shift.toFloat(), 0f, paint)
            }
        }
        return output
    }

    /** Returns only the newly exposed, non-overlapping strip from an accepted keyframe. */
    fun extractNewContent(
        next: Bitmap,
        match: StitchMatchResult,
        direction: ScrollDirection,
    ): Bitmap {
        require(match.accepted) { match.reason ?: "不能提取未通过质量检查的帧" }
        require(!next.isRecycled)
        val shift = abs(if (direction.isVertical) match.dy else match.dx)
        val frameLength = if (direction.isVertical) next.height else next.width
        require(shift in 1 until frameLength) { "有效位移必须小于关键帧尺寸" }
        val source = when (direction) {
            ScrollDirection.UP -> Rect(0, next.height - shift, next.width, next.height)
            ScrollDirection.DOWN -> Rect(0, 0, next.width, shift)
            ScrollDirection.LEFT -> Rect(next.width - shift, 0, next.width, next.height)
            ScrollDirection.RIGHT -> Rect(0, 0, shift, next.height)
        }
        return Bitmap.createBitmap(next, source.left, source.top, source.width(), source.height())
    }

    /** Refines the downsampled winner without quantizing the scrolling axis. */
    private fun refineSourceShift(
        previous: Bitmap,
        current: Bitmap,
        direction: ScrollDirection,
        coarseSourceShift: Int,
        axisScale: Float,
        expectedShiftPx: Int?,
        effectiveMaxOverlapRatio: Float,
        maximumShiftPx: Int?,
    ): Int {
        val sourceMainDimension = if (direction.isVertical) previous.height else previous.width
        val maximumCrossDimension = (
            SOURCE_SHIFT_REFINE_MAX_PIXELS / sourceMainDimension.coerceAtLeast(1)
            ).coerceAtLeast(1)
        val refineWidth = if (direction.isVertical) {
            minOf(previous.width, maximumCrossDimension)
        } else {
            previous.width
        }
        val refineHeight = if (direction.isVertical) {
            previous.height
        } else {
            minOf(previous.height, maximumCrossDimension)
        }
        val before = GrayFrame.fromScaled(previous, refineWidth, refineHeight)
        val after = GrayFrame.fromScaled(current, refineWidth, refineHeight)
        val minimumShift = ceil(
            sourceMainDimension * (1f - effectiveMaxOverlapRatio),
        ).toInt().coerceAtLeast(1)
        val legalMaximumShift = minOf(
            floor(sourceMainDimension * (1f - minOverlapRatio)).toInt(),
            maximumShiftPx?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        ).coerceAtMost(sourceMainDimension - 1)
        val expectedConstraint = expectedShiftConstraint(
            axisLength = sourceMainDimension,
            expectedShiftPx = expectedShiftPx,
            minimumShiftPx = minimumShift,
            maximumShiftPx = legalMaximumShift,
        )
        val maximumShift = expectedConstraint?.maximumShiftPx ?: legalMaximumShift
        if (maximumShift < minimumShift) return coarseSourceShift
        val radius = ceil(1f / axisScale.coerceAtLeast(0.0001f)).toInt() + 2
        val nearbyExpectedShift = expectedConstraint?.expectedShiftPx?.takeIf {
            abs(it - coarseSourceShift) <= radius * 4
        }
        val candidateShifts = buildSet {
            listOfNotNull(
                coarseSourceShift.coerceIn(minimumShift, maximumShift),
                nearbyExpectedShift,
            ).forEach { center ->
                val start = (center - radius).coerceAtLeast(minimumShift)
                val end = (center + radius).coerceAtMost(maximumShift)
                if (end >= start) addAll(start..end)
            }
        }
        if (candidateShifts.isEmpty()) {
            return coarseSourceShift.coerceIn(minimumShift, maximumShift)
        }
        val candidates = candidateShifts.map { shift ->
            val (dx, dy) = direction.displacement(shift)
            ShiftScore(
                dx = dx,
                dy = dy,
                // This is the final source-resolution pass. Keep the scrolling axis exact so a
                // 1px list divider cannot be skipped, while sparsely sampling the cross axis.
                error = translationMetrics(
                    previous = before,
                    current = after,
                    dx = dx,
                    dy = dy,
                    sampleStepX = if (direction.isVertical) 3 else 1,
                    sampleStepY = if (direction.isVertical) 1 else 3,
                ).error,
            )
        }
        val rawBest = candidates.minByOrNull(ShiftScore::error)
            ?: return coarseSourceShift.coerceIn(minimumShift, maximumShift)
        val tied = candidates.filter { it.error <= rawBest.error + SOURCE_SHIFT_REFINE_TIE_EPSILON }
        val expectedSourceShift = expectedConstraint?.expectedShiftPx
        val selected = if (expectedSourceShift != null && tied.size > 1) {
            tied.minWithOrNull(
                compareBy<ShiftScore> {
                    abs(
                        abs(if (direction.isVertical) it.dy else it.dx) - expectedSourceShift,
                    )
                }.thenBy(ShiftScore::error),
            ) ?: rawBest
        } else {
            rawBest
        }
        return abs(if (direction.isVertical) selected.dy else selected.dx)
    }

    private fun searchBest(
        previous: GrayFrame,
        current: GrayFrame,
        direction: ScrollDirection,
        expectedShift: Int? = null,
        searchMaxOverlapRatio: Float = maxOverlapRatio,
        preserveScrollingAxis: Boolean = false,
        maximumShift: Int? = null,
    ): SearchResult? {
        val dimension = if (direction.isVertical) previous.height else previous.width
        val minShift = ceil(dimension * (1f - searchMaxOverlapRatio)).toInt().coerceAtLeast(1)
        val legalMaxShift = minOf(
            floor(dimension * (1f - minOverlapRatio)).toInt(),
            maximumShift?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        ).coerceAtMost(dimension - 1)
        val expectedConstraint = expectedShiftConstraint(
            axisLength = dimension,
            expectedShiftPx = expectedShift,
            minimumShiftPx = minShift,
            maximumShiftPx = legalMaxShift,
        )
        val maxShift = expectedConstraint?.maximumShiftPx ?: legalMaxShift
        if (maxShift < minShift) return null
        val coarseStep = ((maxShift - minShift) / 80).coerceAtLeast(1)
        val coarse = mutableListOf<ShiftScore>()
        var shift = minShift
        while (shift <= maxShift) {
            val (dx, dy) = direction.displacement(shift)
            coarse += ShiftScore(dx, dy, translationError(previous, current, dx, dy, sampleStep = 6))
            shift += coarseStep
        }
        if (coarse.lastOrNull()?.let { abs(if (direction.isVertical) it.dy else it.dx) } != maxShift) {
            val (dx, dy) = direction.displacement(maxShift)
            coarse += ShiftScore(dx, dy, translationError(previous, current, dx, dy, sampleStep = 6))
        }
        val expectedCenter = expectedConstraint?.expectedShiftPx
        val rawCoarseBest = coarse.minByOrNull { it.error } ?: return null
        val guidedCoarseBest = selectBestWithExpectedTieBreak(
            coarse,
            direction,
            expectedCenter,
            dimension,
        ) ?: rawCoarseBest
        // Always refine the raw visual winner as well as the expected-distance tie-break winner.
        // A motion prior may choose between genuinely close fine matches, but must never prevent
        // the globally best visual peak from receiving the same fine-grained evaluation.
        val refineCenters = linkedSetOf<Int>()
        listOf(rawCoarseBest, guidedCoarseBest).forEach { center ->
            refineCenters += abs(if (direction.isVertical) center.dy else center.dx)
        }
        // Long horizontal strips can have one-pixel-wide correlation peaks around vertical card
        // edges. A coarse step may skip that peak completely, so always refine the measured
        // gesture prior as a third seed while still evaluating the global visual winners above.
        expectedCenter?.let(refineCenters::add)
        val refineValues = linkedSetOf<Int>()
        refineCenters.forEach { magnitude ->
            val radius = if (magnitude == expectedCenter) {
                max(coarseStep, (dimension * 0.08f).roundToInt())
            } else {
                coarseStep
            }
            val refineStart = (magnitude - radius).coerceAtLeast(minShift)
            val refineEnd = (magnitude + radius).coerceAtMost(maxShift)
            refineValues += refineStart..refineEnd
        }
        if (preserveScrollingAxis) {
            refineValues += minShift..minOf(maxShift, TINY_SHIFT_FINE_SEARCH_LIMIT)
        }
        val refined = refineValues.map { value ->
            val (dx, dy) = direction.displacement(value)
            val error = if (preserveScrollingAxis) {
                translationMetrics(
                    previous = previous,
                    current = current,
                    dx = dx,
                    dy = dy,
                    sampleStepX = if (direction.isVertical) 3 else 1,
                    sampleStepY = if (direction.isVertical) 1 else 3,
                ).error
            } else {
                translationError(previous, current, dx, dy, sampleStep = 3)
            }
            ShiftScore(dx, dy, error)
        }.sortedBy { it.error }
        val visualBest = refined.minByOrNull(ShiftScore::error) ?: rawCoarseBest
        val best = selectBestWithExpectedTieBreak(
            refined,
            direction,
            expectedCenter,
            dimension,
        ) ?: rawCoarseBest
        // Adjacent translations naturally have near-identical scores. Comparing the winner with
        // a candidate at least 2% of the frame away produces meaningful distinctness instead of
        // penalizing every otherwise good match.
        val minimumRunnerDistance = (dimension * 0.02f).roundToInt().coerceAtLeast(3)
        val refinedRunner = refined.asSequence()
            .filter { it.dx != best.dx || it.dy != best.dy }
            .filter {
                abs(if (direction.isVertical) it.dy - best.dy else it.dx - best.dx) >=
                    minimumRunnerDistance
            }
            .minByOrNull { it.error }
        val coarseRunner = coarse.asSequence()
            .filter {
                abs(if (direction.isVertical) it.dy - best.dy else it.dx - best.dx) >=
                    minimumRunnerDistance
            }
            .minByOrNull { it.error }
            ?.let { candidate ->
                candidate.copy(
                    error = translationError(
                        previous,
                        current,
                        candidate.dx,
                        candidate.dy,
                        sampleStep = 3,
                    )
                )
            }
        val runnerUp = listOfNotNull(refinedRunner, coarseRunner).minByOrNull { it.error }
        return SearchResult(best, visualBest, runnerUp)
    }

    private fun selectBestWithExpectedTieBreak(
        candidates: List<ShiftScore>,
        direction: ScrollDirection,
        expectedShift: Int?,
        dimension: Int,
    ): ShiftScore? {
        val rawBest = candidates.minByOrNull { it.error } ?: return null
        val expected = expectedShift ?: return rawBest
        val errorTolerance = 2.2f
        val nearBest = candidates.filter { it.error <= rawBest.error + errorTolerance }
        if (nearBest.size <= 1) return rawBest
        val sigma = max(dimension * 0.08f, expected * 0.25f).coerceAtLeast(1f)
        return nearBest.minByOrNull { candidate ->
            val shift = abs(if (direction.isVertical) candidate.dy else candidate.dx)
            candidate.error + (abs(shift - expected) / sigma).coerceAtMost(3f) * 0.55f
        } ?: rawBest
    }

    private fun translationError(
        previous: GrayFrame,
        current: GrayFrame,
        dx: Int,
        dy: Int,
        sampleStep: Int
    ): Float = translationMetrics(previous, current, dx, dy, sampleStep).error

    private fun translationMetrics(
        previous: GrayFrame,
        current: GrayFrame,
        dx: Int,
        dy: Int,
        sampleStep: Int,
    ): TranslationMetrics = translationMetrics(
        previous = previous,
        current = current,
        dx = dx,
        dy = dy,
        sampleStepX = sampleStep,
        sampleStepY = sampleStep,
    )

    private fun translationMetrics(
        previous: GrayFrame,
        current: GrayFrame,
        dx: Int,
        dy: Int,
        sampleStepX: Int,
        sampleStepY: Int,
    ): TranslationMetrics {
        val left = max(0, -dx)
        val right = min(previous.width, current.width - dx)
        val top = max(0, -dy)
        val bottom = min(previous.height, current.height - dy)
        if (right <= left || bottom <= top) return TranslationMetrics(255f, 0f, 0, 0)
        val blockColumns = 8
        val blockRows = 6
        val blockErrors = DoubleArray(blockColumns * blockRows)
        val blockWeights = DoubleArray(blockErrors.size)
        var totalSamples = 0
        var texturedSamples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                totalSamples++
                val currentX = x + dx
                val currentY = y + dy
                val previousGradientX = previous.gradientX(x, y)
                val previousGradientY = previous.gradientY(x, y)
                val currentGradientX = current.gradientX(currentX, currentY)
                val currentGradientY = current.gradientY(currentX, currentY)
                val texture = min(
                    abs(previousGradientX) + abs(previousGradientY),
                    abs(currentGradientX) + abs(currentGradientY),
                )
                // Flat color is not an alignment anchor. Exclude it from block ranking entirely;
                // if the whole image is flat, the explicit luminance fallback below still works.
                if (texture < 6) {
                    x += sampleStepX
                    continue
                }
                texturedSamples++
                val weight = 1.0 + (texture / 72f).coerceIn(0f, 1.5f)
                val censusDifference = Integer.bitCount(
                    previous.census(x, y) xor current.census(currentX, currentY)
                )
                val gradientDifference = (
                    abs(previousGradientX - currentGradientX) +
                        abs(previousGradientY - currentGradientY)
                    ) / 4f
                val luminanceDifference = abs(previous[x, y] - current[currentX, currentY])
                val sampleError = censusDifference * 6.5f +
                    gradientDifference.coerceAtMost(64f) * 0.55f +
                    luminanceDifference.coerceAtMost(64) * 0.12f
                val column = ((x - left).toLong() * blockColumns / (right - left))
                    .toInt().coerceIn(0, blockColumns - 1)
                val row = ((y - top).toLong() * blockRows / (bottom - top))
                    .toInt().coerceIn(0, blockRows - 1)
                val block = row * blockColumns + column
                blockErrors[block] += sampleError * weight
                blockWeights[block] += weight
                x += sampleStepX
            }
            y += sampleStepY
        }
        val means = FloatArray(blockErrors.size)
        val alignedColumns = BooleanArray(blockColumns)
        val alignedRows = BooleanArray(blockRows)
        var meanCount = 0
        blockErrors.indices.forEach { index ->
            val weight = blockWeights[index]
            if (weight >= 2.0) {
                val mean = (blockErrors[index] / weight).toFloat()
                means[meanCount++] = mean
                if (mean <= STATIONARY_MAX_STRUCTURE_ERROR) {
                    alignedColumns[index % blockColumns] = true
                    alignedRows[index / blockColumns] = true
                }
            }
        }
        if (meanCount == 0) {
            return TranslationMetrics(
                error = luminanceError(
                    previous,
                    current,
                    dx,
                    dy,
                    sampleStepX,
                    sampleStepY,
                ),
                coverage = 0f,
                alignedBlockColumns = 0,
                alignedBlockRows = 0,
            )
        }
        java.util.Arrays.sort(means, 0, meanCount)
        // Discard the noisiest quarter of spatial blocks. Animated portraits, particles and
        // changing card contents can then move independently without hiding the stable layout.
        val retained = (meanCount * 0.72f).roundToInt().coerceIn(1, meanCount)
        var retainedError = 0.0
        repeat(retained) { index -> retainedError += means[index] }
        val validBlockRatio = meanCount.toFloat() / blockErrors.size
        val texturedSampleRatio = texturedSamples.toFloat() / totalSamples.coerceAtLeast(1)
        return TranslationMetrics(
            error = (retainedError / retained).toFloat(),
            coverage = sqrt(validBlockRatio * texturedSampleRatio).coerceIn(0f, 1f),
            alignedBlockColumns = alignedColumns.count { it },
            alignedBlockRows = alignedRows.count { it },
        )
    }

    private fun luminanceError(
        previous: GrayFrame,
        current: GrayFrame,
        dx: Int,
        dy: Int,
        sampleStepX: Int,
        sampleStepY: Int,
    ): Float {
        val left = max(0, -dx)
        val right = min(previous.width, current.width - dx)
        val top = max(0, -dy)
        val bottom = min(previous.height, current.height - dy)
        if (right <= left || bottom <= top) return 255f
        var error = 0L
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                error += abs(previous[x, y] - current[x + dx, y + dy]).coerceAtMost(96)
                samples++
                x += sampleStepX
            }
            y += sampleStepY
        }
        return if (samples == 0) 255f else error.toFloat() / samples
    }

    private fun luminanceDifferenceStats(
        previous: GrayFrame,
        current: GrayFrame,
        dx: Int,
        dy: Int,
        sampleStepX: Int,
        sampleStepY: Int,
    ): DifferenceStats {
        val left = max(0, -dx)
        val right = min(previous.width, current.width - dx)
        val top = max(0, -dy)
        val bottom = min(previous.height, current.height - dy)
        if (right <= left || bottom <= top) return DifferenceStats(255f, 1f)
        var error = 0L
        var changed = 0
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val difference = abs(previous[x, y] - current[x + dx, y + dy])
                error += difference.coerceAtMost(96)
                if (difference > 8) changed++
                samples++
                x += sampleStepX
            }
            y += sampleStepY
        }
        return if (samples == 0) {
            DifferenceStats(255f, 1f)
        } else {
            DifferenceStats(error.toFloat() / samples, changed.toFloat() / samples)
        }
    }

    private fun validateOutputSize(width: Int, height: Int) {
        require(width <= maxOutputDimension && height <= maxOutputDimension) {
            "拼接结果超过最大边长 $maxOutputDimension"
        }
        require(width.toLong() * height <= maxOutputPixels) {
            "拼接结果超过最大像素数 $maxOutputPixels"
        }
    }

    private data class ShiftScore(val dx: Int, val dy: Int, val error: Float)
    private data class SearchResult(
        val best: ShiftScore,
        val visualBest: ShiftScore,
        val runnerUp: ShiftScore?,
    )
    private data class DifferenceStats(val mean: Float, val changedRatio: Float)
    private data class TranslationMetrics(
        val error: Float,
        val coverage: Float,
        val alignedBlockColumns: Int,
        val alignedBlockRows: Int,
    )

    private companion object {
        const val TINY_SHIFT_MAX_ANALYSIS_PIXELS = 1_000_000
        const val TINY_SHIFT_FINE_SEARCH_LIMIT = 16
        const val SOURCE_SHIFT_REFINE_MAX_PIXELS = 480_000
        const val SOURCE_SHIFT_REFINE_TIE_EPSILON = 0.02f
    }

    private data class GrayFrame(
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
        val gradientsX: ShortArray,
        val gradientsY: ShortArray,
        val censusSignatures: ByteArray,
    ) {
        operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

        fun gradientX(x: Int, y: Int): Int = gradientsX[y * width + x].toInt()

        fun gradientY(x: Int, y: Int): Int = gradientsY[y * width + x].toInt()

        fun census(x: Int, y: Int): Int = censusSignatures[y * width + x].toInt() and 0xFF

        companion object {
            private const val PIXEL_READ_ROWS = 64
            private val CENSUS_OFFSETS = intArrayOf(
                -1, -1, 0, -1, 1, -1, 1, 0,
                1, 1, 0, 1, -1, 1, -1, 0,
            )

            fun fromScaled(bitmap: Bitmap, width: Int, height: Int): GrayFrame {
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                return try {
                    from(scaled)
                } finally {
                    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                }
            }

            fun from(bitmap: Bitmap): GrayFrame {
                val pixelCount = Math.multiplyExact(bitmap.width, bitmap.height)
                val pixels = ByteArray(pixelCount)
                val rowsPerRead = minOf(PIXEL_READ_ROWS, bitmap.height)
                val colors = IntArray(Math.multiplyExact(bitmap.width, rowsPerRead))
                var firstRow = 0
                while (firstRow < bitmap.height) {
                    val rowCount = minOf(rowsPerRead, bitmap.height - firstRow)
                    bitmap.getPixels(
                        colors,
                        0,
                        bitmap.width,
                        0,
                        firstRow,
                        bitmap.width,
                        rowCount,
                    )
                    val colorCount = bitmap.width * rowCount
                    val pixelOffset = firstRow * bitmap.width
                    for (index in 0 until colorCount) {
                        val color = colors[index]
                        val red = color shr 16 and 0xFF
                        val green = color shr 8 and 0xFF
                        val blue = color and 0xFF
                        pixels[pixelOffset + index] =
                            ((red * 77 + green * 150 + blue * 29) shr 8).toByte()
                    }
                    firstRow += rowCount
                }
                fun value(x: Int, y: Int): Int = pixels[y * bitmap.width + x].toInt() and 0xFF
                val gradientsX = ShortArray(pixels.size)
                val gradientsY = ShortArray(pixels.size)
                val census = ByteArray(pixels.size)
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        val pixelIndex = y * bitmap.width + x
                        gradientsX[pixelIndex] = (
                            value((x + 1).coerceAtMost(bitmap.width - 1), y) -
                                value((x - 1).coerceAtLeast(0), y)
                            ).toShort()
                        gradientsY[pixelIndex] = (
                            value(x, (y + 1).coerceAtMost(bitmap.height - 1)) -
                                value(x, (y - 1).coerceAtLeast(0))
                            ).toShort()
                        val center = value(x, y)
                        var signature = 0
                        var offsetIndex = 0
                        while (offsetIndex < CENSUS_OFFSETS.size) {
                            val sampleX = (x + CENSUS_OFFSETS[offsetIndex])
                                .coerceIn(0, bitmap.width - 1)
                            val sampleY = (y + CENSUS_OFFSETS[offsetIndex + 1])
                                .coerceIn(0, bitmap.height - 1)
                            signature = signature shl 1
                            if (value(sampleX, sampleY) >= center) signature = signature or 1
                            offsetIndex += 2
                        }
                        census[pixelIndex] = signature.toByte()
                    }
                }
                return GrayFrame(
                    bitmap.width,
                    bitmap.height,
                    pixels,
                    gradientsX,
                    gradientsY,
                    census,
                )
            }
        }
    }
}

private const val STATIONARY_MIN_TEXTURE_COVERAGE = 0.18f
private const val STATIONARY_MIN_AXIS_PARTITIONS = 3
private const val STATIONARY_MAX_STRUCTURE_ERROR = 11f
private const val STATIONARY_WIN_MARGIN = 3f
private const val STATIONARY_STRUCTURAL_MAX_FRAME_DIFFERENCE = 24f
private const val STATIONARY_STRUCTURAL_MAX_CHANGED_RATIO = 0.26f
private const val STATIONARY_REDRAW_MAX_FRAME_DIFFERENCE = 4f
private const val STATIONARY_REDRAW_MAX_CHANGED_RATIO = 0.10f
private const val STATIONARY_REDRAW_MAX_STRUCTURE_ERROR = 6f
private const val PIXEL_STATIONARY_MAX_FRAME_DIFFERENCE = 1.5f
private const val PIXEL_STATIONARY_MAX_CHANGED_RATIO = 0.015f
private const val PIXEL_STATIONARY_DIFFERENCE_EPSILON = 0.01f
private const val PIXEL_STATIONARY_CHANGED_RATIO_EPSILON = 0.0001f

private fun ScrollDirection.displacement(magnitude: Int): Pair<Int, Int> = when (this) {
    ScrollDirection.UP -> 0 to -magnitude
    ScrollDirection.DOWN -> 0 to magnitude
    ScrollDirection.LEFT -> -magnitude to 0
    ScrollDirection.RIGHT -> magnitude to 0
}

private fun ScrollDirection.opposite(): ScrollDirection = when (this) {
    ScrollDirection.UP -> ScrollDirection.DOWN
    ScrollDirection.DOWN -> ScrollDirection.UP
    ScrollDirection.LEFT -> ScrollDirection.RIGHT
    ScrollDirection.RIGHT -> ScrollDirection.LEFT
}
