package com.apricotiris.IrisShot.core.region

import android.graphics.Bitmap
import com.apricotiris.IrisShot.core.image.BitmapFrameUtils
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.MotionBlockScore
import com.apricotiris.IrisShot.core.model.MotionEstimate
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.RegionLearningResult
import com.apricotiris.IrisShot.core.model.ScrollDirection
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val NO_ANALYZABLE_PIXELS_ERROR = 255f
private const val MIN_RELIABLE_MOTION_SLICE_COVERAGE = 0.5f
private const val MIN_FINE_EDGE_PAIR_COUNT = 2
private const val MIN_FINE_EDGE_GLOBAL_CONFIDENCE = 0.15f
private const val MIN_FINE_EDGE_PAIR_CONFIDENCE = 0.10f
private const val MIN_FINE_EDGE_KNOWN_SCORE = 0.25f
private const val MIN_FINE_EDGE_MOVING_SCORE = 0.08f
private const val MIN_FINE_EDGE_CONTRAST = 0.14f
private const val MIN_FINE_EDGE_RELIABILITY = 0.10f
private const val MIN_CUT_EDGE_MOVING_SCORE = 0.035f
private const val MIN_CUT_EDGE_CONTRAST = 0.055f
private const val MIN_CUT_EDGE_RELIABILITY = 0.025f
private const val MIN_CUT_EDGE_PAIR_AGREEMENT = 0.34f
private const val MIN_CUT_EDGE_PAIR_SUPPORT = 2
private const val EDGE_FALLBACK_AXIS_DIVISOR = 6
private const val MIN_PLATEAU_RECOVERY_PAIR_COUNT = 2
private const val MIN_PLATEAU_RECOVERY_PAIR_CONFIDENCE = 0.10f
private const val PLATEAU_RECOVERY_MAGNITUDE_TOLERANCE_RATIO = 0.35f

private fun List<RectConfig>.containsPoint(x: Int, y: Int): Boolean = any { region ->
    x >= region.left && x < region.right && y >= region.top && y < region.bottom
}

/**
 * Lightweight multi-frame motion learner. It uses only luminance block matching, making it
 * suitable for an empty project without OpenCV while still separating scrolling content,
 * fixed chrome and unstable/HUD areas.
 */
class RegionLearningEngine(
    private val blockSize: Int = DefaultCaptureConfig.BLOCK_SIZE,
    private val analysisScale: Float = DefaultCaptureConfig.ANALYSIS_SCALE,
    private val minFrameCount: Int = 2
) {
    init {
        require(blockSize >= 8)
        require(analysisScale > 0f && analysisScale <= 1f)
        require(minFrameCount >= 2)
    }

    fun analyze(
        frames: List<Bitmap>,
        direction: ScrollDirection,
        gestureRegion: RectConfig? = null,
        excludedRegions: List<RectConfig> = emptyList(),
    ): RegionLearningResult {
        require(frames.size >= minFrameCount) { "区域识别至少需要 $minFrameCount 帧" }
        require(frames.none { it.isRecycled }) { "区域识别包含已释放 Bitmap" }

        val sourceWidth = frames.minOf { it.width }
        val sourceHeight = frames.minOf { it.height }
        require(sourceWidth > 0 && sourceHeight > 0)
        val analysisWidth = (sourceWidth * analysisScale).roundToInt().coerceAtLeast(1)
        val analysisHeight = (sourceHeight * analysisScale).roundToInt().coerceAtLeast(1)
        val grayFrames = frames.map { bitmap ->
            val cropped = if (bitmap.width == sourceWidth && bitmap.height == sourceHeight) {
                bitmap
            } else {
                BitmapFrameUtils.crop(bitmap, RectConfig.full(sourceWidth, sourceHeight))
            }
            val scaled = if (cropped.width == analysisWidth && cropped.height == analysisHeight) {
                cropped.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(cropped, analysisWidth, analysisHeight, true)
            }
            if (cropped !== bitmap) cropped.recycle()
            GrayFrame.from(scaled).also { scaled.recycle() }
        }

        val analysisGestureRegion = gestureRegion
            ?.scale(
                analysisWidth.toFloat() / sourceWidth,
                analysisHeight.toFloat() / sourceHeight,
            )
            ?.clampTo(analysisWidth, analysisHeight)
            ?.takeUnless { it.isEmpty }
        val analysisExcludedRegions = excludedRegions.mapNotNull { region ->
            region.scale(
                analysisWidth.toFloat() / sourceWidth,
                analysisHeight.toFloat() / sourceHeight,
            )
                .clampTo(analysisWidth, analysisHeight)
                .takeUnless { it.isEmpty }
        }
        val pairEstimates = grayFrames.zipWithNext { previous, current ->
            val focused = estimateTranslation(
                previous,
                current,
                direction,
                analysisGestureRegion,
                analysisExcludedRegions,
            )
            if (analysisGestureRegion == null || focused.confidence >= 0.12f) {
                focused
            } else {
                estimateTranslation(
                    previous,
                    current,
                    direction,
                    null,
                    analysisExcludedRegions,
                )
            }
        }
        val mainMotion = combineEstimates(pairEstimates, direction)
        if (mainMotion.dx == 0 && mainMotion.dy == 0) {
            return fallbackResult(
                sourceWidth,
                sourceHeight,
                gestureRegion,
                mainMotion,
                confidence = (mainMotion.confidence * 0.35f).coerceIn(0f, 0.25f),
                excludedRegions = excludedRegions,
            )
        }

        val analysisBlockSize = (blockSize * analysisScale).roundToInt().coerceAtLeast(8)
        val columns = (analysisWidth + analysisBlockSize - 1) / analysisBlockSize
        val rows = (analysisHeight + analysisBlockSize - 1) / analysisBlockSize
        val accumulators = Array(rows * columns) { BlockAccumulator() }
        val includedCells = BooleanArray(rows * columns) { index ->
            val row = index / columns
            val column = index % columns
            val bounds = RectConfig(
                column * analysisBlockSize,
                row * analysisBlockSize,
                min((column + 1) * analysisBlockSize, analysisWidth),
                min((row + 1) * analysisBlockSize, analysisHeight),
            )
            analysisExcludedRegions.none { excluded -> bounds.intersect(excluded) != null }
        }

        grayFrames.zipWithNext().forEachIndexed { pairIndex, (previous, current) ->
            val estimate = pairEstimates[pairIndex]
            // Outlier pairs use the robust median displacement but still contribute instability.
            val dx = if (estimate.confidence >= 0.15f) estimate.dx else mainMotion.dx
            val dy = if (estimate.confidence >= 0.15f) estimate.dy else mainMotion.dy
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val index = row * columns + column
                    if (!includedCells[index]) continue
                    val bounds = RectConfig(
                        column * analysisBlockSize,
                        row * analysisBlockSize,
                        min((column + 1) * analysisBlockSize, analysisWidth),
                        min((row + 1) * analysisBlockSize, analysisHeight)
                    )
                    val evidence = compareBlock(
                        previous,
                        current,
                        bounds,
                        dx,
                        dy,
                        analysisExcludedRegions,
                    )
                    accumulators[index].add(evidence)
                }
            }
        }

        val scores = accumulators.mapIndexed { index, accumulator ->
            val row = index / columns
            val column = index % columns
            accumulator.finish(column, row)
        }
        val grid = ScoreGrid(columns, rows, scores, includedCells)
        val detectedMovingRegion = inferMovingRegion(
            grid,
            analysisBlockSize,
            analysisWidth,
            analysisHeight,
            direction,
        )
        val scaleX = sourceWidth.toFloat() / analysisWidth
        val scaleY = sourceHeight.toFloat() / analysisHeight
        val sourceDetectedMovingRegion = detectedMovingRegion.scale(scaleX, scaleY)
            .clampTo(sourceWidth, sourceHeight)
        val sourceExcludedRegions = excludedRegions.mapNotNull { region ->
            region.clampTo(sourceWidth, sourceHeight).takeUnless { it.isEmpty }
        }
        val sourcePairEstimates = pairEstimates.map { estimate ->
            estimate.scaleDisplacement(scaleX, scaleY)
        }
        val sourceMainMotion = mainMotion.scaleDisplacement(scaleX, scaleY)
        val refinedMovingRegion = refineMovingRegion(
            detected = sourceDetectedMovingRegion,
            frames = frames,
            frameWidth = sourceWidth,
            frameHeight = sourceHeight,
            pairEstimates = sourcePairEstimates,
            mainMotion = sourceMainMotion,
            direction = direction,
            blockSize = blockSize,
            excludedRegions = sourceExcludedRegions,
        )
        val captureRegion = stabilizeMovingRegion(
            refinedMovingRegion.region,
            direction,
            sourceWidth,
            sourceHeight,
            leadingBoundaryReliable = refinedMovingRegion.leadingBoundaryReliable,
            trailingBoundaryReliable = refinedMovingRegion.trailingBoundaryReliable,
        )
            .clampTo(sourceWidth, sourceHeight)
            .takeUnless { it.isEmpty || it.width < sourceWidth / 12 || it.height < sourceHeight / 12 }
            ?: RectConfig.full(sourceWidth, sourceHeight)
        val movingRegionAnalysis = captureRegion.scale(
            analysisWidth.toFloat() / sourceWidth,
            analysisHeight.toFloat() / sourceHeight,
        ).clampTo(analysisWidth, analysisHeight)

        val topFixed = fixedEdgeRegion(
            top = true,
            capture = captureRegion,
            grid = grid,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
        val bottomFixed = fixedEdgeRegion(
            top = false,
            capture = captureRegion,
            grid = grid,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
        val suggestedGesture = gestureRegion?.clampTo(sourceWidth, sourceHeight)
            ?.takeUnless { it.isEmpty }
            ?: suggestedGestureRegion(captureRegion, sourceWidth, sourceHeight)

        val hudMasks = componentsToRegions(
            grid = grid,
            blockSize = analysisBlockSize,
            scaleX = scaleX,
            scaleY = scaleY,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            minimumCells = 1,
            maximumRegions = 10,
            excludedRegions = excludedRegions,
        ) { score ->
            score.unstableScore >= 0.52f ||
                (score.fixedScore > score.followMainMotionScore + 0.22f && score.fixedScore > 0.52f)
        }.mapNotNull { it.intersect(captureRegion) }

        val uncertain = componentsToRegions(
            grid = grid,
            blockSize = analysisBlockSize,
            scaleX = scaleX,
            scaleY = scaleY,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            minimumCells = 1,
            maximumRegions = 12,
            excludedRegions = excludedRegions,
        ) { score -> score.unknownScore >= 0.48f }

        val movingScores = scores.filterIndexed { index, score ->
            includedCells[index] &&
                blockCenterInside(score, movingRegionAnalysis, analysisBlockSize)
        }
        val meanFollow = movingScores.map { it.followMainMotionScore }.averageOrZero().toFloat()
        val meanFixed = movingScores.map { it.fixedScore }.averageOrZero().toFloat()
        val separation = (meanFollow - meanFixed + 0.5f).coerceIn(0f, 1f)
        val captureAnalyzableArea = nonExcludedArea(
            captureRegion,
            excludedRegions,
        )
        val totalAnalyzableArea = nonExcludedArea(
            RectConfig.full(sourceWidth, sourceHeight),
            excludedRegions,
        )
        val coverage = (
            captureAnalyzableArea.toFloat() / totalAnalyzableArea.coerceAtLeast(1L)
            ).coerceIn(0f, 1f)
        val confidence = (
            mainMotion.confidence * 0.50f +
                separation * 0.35f +
                coverage * 0.15f
            ).coerceIn(0f, 1f)

        return RegionLearningResult(
            suggestedStitchRegion = captureRegion,
            suggestedGestureRegion = suggestedGesture,
            suggestedTopFixedRegion = topFixed,
            suggestedBottomFixedRegion = bottomFixed,
            suggestedHudMasks = hudMasks,
            confidence = confidence,
            uncertainRegions = uncertain,
            motionEstimate = sourceMainMotion,
            blockScores = scores.filterIndexed { index, _ -> includedCells[index] }
        )
    }

    private fun estimateTranslation(
        previous: GrayFrame,
        current: GrayFrame,
        direction: ScrollDirection,
        searchRegion: RectConfig?,
        excludedRegions: List<RectConfig>,
    ): MotionEstimate {
        val dimension = if (direction.isVertical) previous.height else previous.width
        val maxShift = (dimension * 0.68f).roundToInt().coerceAtLeast(2)
        val coarseStep = (maxShift / 72).coerceAtLeast(1)
        val candidates = mutableListOf<ShiftScore>()
        var magnitude = 1
        while (magnitude <= maxShift) {
            val (dx, dy) = direction.toDisplacement(magnitude)
            candidates += ShiftScore(
                dx,
                dy,
                translationError(
                    previous,
                    current,
                    dx,
                    dy,
                    sampleStep = 6,
                    searchRegion = searchRegion,
                    excludedRegions = excludedRegions,
                ),
            )
            magnitude += coarseStep
        }
        val coarseBest = candidates.minByOrNull { it.error }
            ?: return MotionEstimate(0, 0, 0f, 0f)
        val bestMagnitude = abs(if (direction.isVertical) coarseBest.dy else coarseBest.dx)
        val refined = ((bestMagnitude - coarseStep).coerceAtLeast(1)..
            (bestMagnitude + coarseStep).coerceAtMost(maxShift)).map { amount ->
            val (dx, dy) = direction.toDisplacement(amount)
            ShiftScore(
                dx,
                dy,
                translationError(
                    previous,
                    current,
                    dx,
                    dy,
                    sampleStep = 3,
                    searchRegion = searchRegion,
                    excludedRegions = excludedRegions,
                ),
            )
        }.sortedBy { it.error }
        val best = refined.firstOrNull() ?: coarseBest
        val zeroError = translationError(
            previous,
            current,
            0,
            0,
            sampleStep = 3,
            searchRegion = searchRegion,
            excludedRegions = excludedRegions,
        )
        if (best.error >= NO_ANALYZABLE_PIXELS_ERROR &&
            zeroError >= NO_ANALYZABLE_PIXELS_ERROR
        ) {
            return MotionEstimate(0, 0, 0f, 0f)
        }
        val lowDifferenceWithoutMotionAdvantage = zeroError <= 3f &&
            (best.error >= zeroError * 0.72f || zeroError - best.error < 0.35f)
        if (lowDifferenceWithoutMotionAdvantage || zeroError + 2f < best.error * 0.72f) {
            val stationaryConfidence = (1f - zeroError / 48f).coerceIn(0f, 1f)
            return MotionEstimate(0, 0, stationaryConfidence, 1f)
        }
        val runnerUp = refined.drop(1).firstOrNull {
            abs(it.dx - best.dx) + abs(it.dy - best.dy) >= 2
        } ?: candidates.sortedBy { it.error }.drop(1).firstOrNull()

        val similarity = (1f - best.error / 72f).coerceIn(0f, 1f)
        val distinctness = if (runnerUp == null) 0f else {
            ((runnerUp.error - best.error) / (runnerUp.error + 1f)).coerceIn(0f, 1f)
        }
        val texture = previous.sampledTexture(searchRegion, excludedRegions).coerceIn(0f, 1f)
        val motionAdvantage = ((zeroError - best.error) / (zeroError + 1f)).coerceIn(0f, 1f)
        val evidenceStrength = max(texture, motionAdvantage * 0.75f)
        val overlap = if (direction.isVertical) {
            1f - abs(best.dy).toFloat() / previous.height
        } else {
            1f - abs(best.dx).toFloat() / previous.width
        }
        val confidence = (
            (similarity * 0.70f + distinctness * 0.30f) * evidenceStrength
            ).coerceIn(0f, 1f)
        return MotionEstimate(best.dx, best.dy, confidence, overlap.coerceIn(0f, 1f))
    }

    private fun translationError(
        previous: GrayFrame,
        current: GrayFrame,
        dx: Int,
        dy: Int,
        sampleStep: Int,
        searchRegion: RectConfig?,
        excludedRegions: List<RectConfig>,
    ): Float {
        val region = searchRegion?.clampTo(previous.width, previous.height)
            ?.takeUnless { it.isEmpty }
            ?: RectConfig.full(previous.width, previous.height)
        val left = max(region.left, -dx)
        val right = min(region.right, current.width - dx)
        val top = max(region.top, -dy)
        val bottom = min(region.bottom, current.height - dy)
        if (right <= left || bottom <= top) return 255f
        val coverage = (right - left).toFloat() * (bottom - top) /
            (region.width.toFloat() * region.height).coerceAtLeast(1f)
        if (coverage < 0.45f) return 255f
        var error = 0L
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val shiftedX = x + dx
                val shiftedY = y + dy
                if (!excludedRegions.containsPoint(x, y) &&
                    !excludedRegions.containsPoint(shiftedX, shiftedY)
                ) {
                    error += abs(previous[x, y] - current[shiftedX, shiftedY])
                    samples++
                }
                x += sampleStep
            }
            y += sampleStep
        }
        return if (samples == 0) NO_ANALYZABLE_PIXELS_ERROR else error.toFloat() / samples
    }

    private fun combineEstimates(
        estimates: List<MotionEstimate>,
        direction: ScrollDirection
    ): MotionEstimate {
        if (estimates.isEmpty()) return MotionEstimate(0, 0, 0f, 0f)
        val magnitudes = estimates.map { abs(if (direction.isVertical) it.dy else it.dx) }.sorted()
        val rawMedianMagnitude = magnitudes[magnitudes.size / 2]
        val recoveredMagnitude = if (rawMedianMagnitude == 0) {
            recoverPlateauMotionMagnitude(estimates, direction)
        } else {
            null
        }
        val medianMagnitude = recoveredMagnitude ?: rawMedianMagnitude
        val (dx, dy) = direction.toDisplacement(medianMagnitude)
        val tolerance = max(3, (medianMagnitude * 0.18f).roundToInt())
        val inliers = estimates.filter {
            val magnitude = abs(if (direction.isVertical) it.dy else it.dx)
            abs(magnitude - medianMagnitude) <= tolerance &&
                (recoveredMagnitude == null || magnitude > 0)
        }
        val inlierRatio = inliers.size.toFloat() / estimates.size
        val meanConfidence = inliers.map { it.confidence }.averageOrZero().toFloat()
        return MotionEstimate(
            dx,
            dy,
            (meanConfidence * (0.55f + 0.45f * inlierRatio)).coerceIn(0f, 1f),
            inlierRatio
        )
    }

    private fun recoverPlateauMotionMagnitude(
        estimates: List<MotionEstimate>,
        direction: ScrollDirection,
    ): Int? {
        var bestRun = emptyList<MotionEstimate>()
        var bestConfidence = 0f
        val currentRun = mutableListOf<MotionEstimate>()

        fun magnitude(estimate: MotionEstimate): Int =
            abs(if (direction.isVertical) estimate.dy else estimate.dx)

        fun considerCurrentRun() {
            if (currentRun.size < MIN_PLATEAU_RECOVERY_PAIR_COUNT) return
            val runMagnitudes = currentRun.map(::magnitude).sorted()
            val median = runMagnitudes[runMagnitudes.size / 2]
            val tolerance = max(
                3,
                (median * PLATEAU_RECOVERY_MAGNITUDE_TOLERANCE_RATIO).roundToInt(),
            )
            val consistent = currentRun.filter { estimate ->
                abs(magnitude(estimate) - median) <= tolerance
            }
            if (consistent.size < MIN_PLATEAU_RECOVERY_PAIR_COUNT) return
            val confidence = consistent.sumOf { it.confidence.toDouble() }.toFloat()
            if (consistent.size > bestRun.size ||
                (consistent.size == bestRun.size && confidence > bestConfidence)
            ) {
                bestRun = consistent.toList()
                bestConfidence = confidence
            }
        }

        estimates.forEach { estimate ->
            if (magnitude(estimate) > 0 &&
                estimate.confidence >= MIN_PLATEAU_RECOVERY_PAIR_CONFIDENCE
            ) {
                currentRun += estimate
            } else {
                considerCurrentRun()
                currentRun.clear()
            }
        }
        considerCurrentRun()
        if (bestRun.size < MIN_PLATEAU_RECOVERY_PAIR_COUNT) return null
        val bestMagnitudes = bestRun.map(::magnitude).sorted()
        return bestMagnitudes[bestMagnitudes.size / 2]
    }

    private fun compareBlock(
        previous: GrayFrame,
        current: GrayFrame,
        bounds: RectConfig,
        dx: Int,
        dy: Int,
        excludedRegions: List<RectConfig>,
    ): BlockEvidence {
        var fixedError = 0L
        var forwardError = 0L
        var reverseError = 0L
        var samples = 0
        var forwardSamples = 0
        var reverseSamples = 0
        var sum = 0L
        var sumSquares = 0L
        val step = 2
        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                if (excludedRegions.containsPoint(x, y)) {
                    x += step
                    continue
                }
                val before = previous[x, y]
                val after = current[x, y]
                fixedError += abs(before - after)
                sum += before
                sumSquares += before.toLong() * before
                samples++
                val shiftedX = x + dx
                val shiftedY = y + dy
                if (shiftedX in 0 until current.width &&
                    shiftedY in 0 until current.height &&
                    !excludedRegions.containsPoint(shiftedX, shiftedY)
                ) {
                    forwardError += abs(before - current[shiftedX, shiftedY])
                    forwardSamples++
                }
                // The reverse correspondence supplies evidence at the leading edge where the
                // forward lookup falls outside the frame (top for UP, bottom for DOWN, etc.).
                val sourceX = x - dx
                val sourceY = y - dy
                if (sourceX in 0 until previous.width &&
                    sourceY in 0 until previous.height &&
                    !excludedRegions.containsPoint(sourceX, sourceY)
                ) {
                    reverseError += abs(current[x, y] - previous[sourceX, sourceY])
                    reverseSamples++
                }
                x += step
            }
            y += step
        }
        if (samples == 0) return BlockEvidence(0f, 0f, 0f, 1f)
        val fixed = fixedError.toFloat() / samples
        val forward = if (forwardSamples == 0) 255f else forwardError.toFloat() / forwardSamples
        val reverse = if (reverseSamples == 0) 255f else reverseError.toFloat() / reverseSamples
        val translated = min(forward, reverse)
        val translatedCoverage = max(forwardSamples, reverseSamples)
        val mean = sum.toFloat() / samples
        val variance = (sumSquares.toFloat() / samples - mean * mean).coerceAtLeast(0f)
        val texture = (sqrt(variance) / 28f).coerceIn(0f, 1f)
        val total = fixed + translated + 1f
        val follow = (fixed / total) * (1f - translated / 96f).coerceIn(0f, 1f) * texture
        val fixedScore = (translated / total) * (1f - fixed / 72f).coerceIn(0f, 1f) * texture
        val unstable = (min(fixed, translated) / 72f).coerceIn(0f, 1f) * (0.45f + 0.55f * texture)
        val unknown = max(1f - texture, if (translatedCoverage < samples / 2) 0.65f else 0f)
        return BlockEvidence(follow, fixedScore, unstable, unknown)
    }

    private fun inferMovingRegion(
        grid: ScoreGrid,
        blockSize: Int,
        width: Int,
        height: Int,
        direction: ScrollDirection,
    ): RectConfig {
        val rowMotion = fillMissingMotionScores(
            (0 until grid.rows).map { row ->
                grid.row(row)
                    .takeIf {
                        it.isNotEmpty() &&
                            grid.rowCoverage(row) >= MIN_RELIABLE_MOTION_SLICE_COVERAGE
                    }
                    ?.map { it.followMainMotionScore - it.fixedScore * 0.45f }
                    ?.average()
                    ?.toFloat()
            },
        )
        val columnMotion = fillMissingMotionScores(
            (0 until grid.columns).map { column ->
                grid.column(column)
                    .takeIf {
                        it.isNotEmpty() &&
                            grid.columnCoverage(column) >= MIN_RELIABLE_MOTION_SLICE_COVERAGE
                    }
                    ?.map { it.followMainMotionScore - it.fixedScore * 0.45f }
                    ?.average()
                    ?.toFloat()
            },
        )
        return if (direction.isVertical) {
            val rowBand = largestUsefulBand(rowMotion)
            RectConfig(
                0,
                rowBand.first * blockSize,
                width,
                min(height, (rowBand.last + 1) * blockSize),
            )
        } else {
            val columnBand = largestUsefulBand(columnMotion)
            RectConfig(
                columnBand.first * blockSize,
                0,
                min(width, (columnBand.last + 1) * blockSize),
                height,
            )
        }
    }

    private fun refineMovingRegion(
        detected: RectConfig,
        frames: List<Bitmap>,
        frameWidth: Int,
        frameHeight: Int,
        pairEstimates: List<MotionEstimate>,
        mainMotion: MotionEstimate,
        direction: ScrollDirection,
        blockSize: Int,
        excludedRegions: List<RectConfig>,
    ): RefinedMovingRegion {
        val axisLength = if (direction.isVertical) frameHeight else frameWidth
        val usablePairs = reliableFineEdgePairs(pairEstimates, mainMotion, direction)
        if (usablePairs.size < MIN_FINE_EDGE_PAIR_COUNT || axisLength < 3) {
            return RefinedMovingRegion(detected)
        }
        val profile = fineAxisMotionProfile(
            frames = frames,
            pairEstimates = pairEstimates,
            usablePairs = usablePairs,
            direction = direction,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            excludedRegions = excludedRegions,
        )
        val coarseLeading = if (direction.isVertical) detected.top else detected.left
        val coarseTrailing = if (direction.isVertical) detected.bottom else detected.right
        val searchRadius = (blockSize * 2).coerceAtLeast(4)
        val edgeFallbackSearchRadius = fineEdgeFallbackSearchRadius(axisLength, blockSize)
        val windowSize = blockSize.coerceIn(4, 12)
        // The coarse moving band is normally contained by the real viewport. Search farther into
        // fixed chrome than into moving content on either axis so an interior card or text edge
        // cannot pull the leading crop inward.
        val leadingOutwardSearchRadius = edgeFallbackSearchRadius
        val leadingSearchStart = leadingFineSearchStart(
            coarseLeading = coarseLeading,
            outwardSearchRadius = leadingOutwardSearchRadius,
        )
        val leadingInwardSearchRadius = blockSize.coerceAtLeast(4)
        val leadingSearchEnd = leadingFineSearchEnd(
            coarseLeading = coarseLeading,
            axisLength = axisLength,
            fullSearchRadius = edgeFallbackSearchRadius,
            inwardSearchRadius = leadingInwardSearchRadius,
        )
        val trailingSearchStart = if (coarseTrailing >= axisLength) {
            (axisLength - edgeFallbackSearchRadius).coerceAtLeast(1)
        } else {
            (coarseTrailing - searchRadius).coerceAtLeast(1)
        }
        val trailingSearchEnd = if (coarseTrailing >= axisLength) {
            axisLength - 1
        } else {
            (coarseTrailing + searchRadius).coerceAtMost(axisLength - 1)
        }
        val leading = locateFineBoundary(
            profile = profile,
            searchStart = leadingSearchStart,
            searchEnd = leadingSearchEnd,
            windowSize = windowSize,
            leading = true,
            requirePersistentEvidence = true,
        )
        val trailing = locateFineBoundary(
            profile = profile,
            searchStart = trailingSearchStart,
            searchEnd = trailingSearchEnd,
            windowSize = windowSize,
            leading = false,
            requirePersistentEvidence = false,
        )
        val refinedLeading = leading?.coordinate ?: coarseLeading
        val refinedTrailing = trailing?.coordinate ?: coarseTrailing
        if (refinedLeading >= refinedTrailing) return RefinedMovingRegion(detected)
        val region = if (direction.isVertical) {
            RectConfig(detected.left, refinedLeading, detected.right, refinedTrailing)
        } else {
            RectConfig(refinedLeading, detected.top, refinedTrailing, detected.bottom)
        }
        return RefinedMovingRegion(
            region = region,
            leadingBoundaryReliable = leading != null,
            trailingBoundaryReliable = trailing != null,
        )
    }

    internal fun leadingFineSearchStart(
        coarseLeading: Int,
        outwardSearchRadius: Int,
    ): Int = (coarseLeading - outwardSearchRadius.coerceAtLeast(0)).coerceAtLeast(1)

    internal fun leadingFineSearchEnd(
        coarseLeading: Int,
        axisLength: Int,
        fullSearchRadius: Int,
        inwardSearchRadius: Int,
    ): Int {
        val radius = if (coarseLeading <= 0) fullSearchRadius else inwardSearchRadius
        return (coarseLeading.coerceAtLeast(0) + radius).coerceAtMost(axisLength - 1)
    }

    internal fun fineEdgeFallbackSearchRadius(axisLength: Int, blockSize: Int): Int {
        if (axisLength <= 1) return 0
        val blockFloor = blockSize.coerceAtLeast(1) * 4
        val viewportFraction = (axisLength + EDGE_FALLBACK_AXIS_DIVISOR - 1) /
            EDGE_FALLBACK_AXIS_DIVISOR
        return max(blockFloor, viewportFraction).coerceAtMost(axisLength - 1)
    }

    internal fun reliableFineEdgePairs(
        estimates: List<MotionEstimate>,
        mainMotion: MotionEstimate,
        direction: ScrollDirection,
    ): List<Int> {
        if (mainMotion.confidence < MIN_FINE_EDGE_GLOBAL_CONFIDENCE) return emptyList()
        val mainDisplacement = if (direction.isVertical) mainMotion.dy else mainMotion.dx
        if (!direction.matchesFineEdgeMotion(mainDisplacement)) return emptyList()
        return estimates.indices.filter { index ->
            val estimate = estimates[index]
            val displacement = if (direction.isVertical) estimate.dy else estimate.dx
            estimate.confidence >= MIN_FINE_EDGE_PAIR_CONFIDENCE &&
                direction.matchesFineEdgeMotion(displacement)
        }
    }

    private fun ScrollDirection.matchesFineEdgeMotion(displacement: Int): Boolean =
        if (isForward) displacement < 0 else displacement > 0

    private fun MotionEstimate.scaleDisplacement(scaleX: Float, scaleY: Float): MotionEstimate =
        MotionEstimate(
            dx = (dx * scaleX).roundToInt(),
            dy = (dy * scaleY).roundToInt(),
            confidence = confidence,
            inlierRatio = inlierRatio,
        )

    private fun fineAxisMotionProfile(
        frames: List<Bitmap>,
        pairEstimates: List<MotionEstimate>,
        usablePairs: List<Int>,
        direction: ScrollDirection,
        frameWidth: Int,
        frameHeight: Int,
        excludedRegions: List<RectConfig>,
    ): FineMotionProfile {
        val width = frameWidth
        val height = frameHeight
        val axisLength = if (direction.isVertical) height else width
        val included = BooleanArray(axisLength) { coordinate ->
            val bounds = if (direction.isVertical) {
                RectConfig(0, coordinate, width, coordinate + 1)
            } else {
                RectConfig(coordinate, 0, coordinate + 1, height)
            }
            val analyzableArea = nonExcludedArea(bounds, excludedRegions)
            analyzableArea.toFloat() /
                (bounds.width.toLong() * bounds.height).coerceAtLeast(1L) >=
                MIN_RELIABLE_MOTION_SLICE_COVERAGE
        }
        val pairProfiles = usablePairs.map { pairIndex ->
            val estimate = pairEstimates[pairIndex]
            val previous = refinementGrayFrame(frames[pairIndex], width, height)
            val current = refinementGrayFrame(frames[pairIndex + 1], width, height)
            List(axisLength) profile@ { coordinate ->
                if (!included[coordinate]) return@profile null
                val bounds = if (direction.isVertical) {
                    RectConfig(0, coordinate, width, coordinate + 1)
                } else {
                    RectConfig(coordinate, 0, coordinate + 1, height)
                }
                compareFineSlice(
                    previous = previous,
                    current = current,
                    bounds = bounds,
                    dx = estimate.dx,
                    dy = estimate.dy,
                    direction = direction,
                    excludedRegions = excludedRegions,
                )
            }
        }
        val aggregate = List(axisLength) { coordinate ->
            if (!included[coordinate]) return@List null
            val samples = pairProfiles.mapNotNull { profile -> profile[coordinate] }
            if (samples.isEmpty()) return@List null
            val motionScores = samples.map { it.motionScore }.sorted()
            val median = motionScores[motionScores.size / 2]
            val upper = motionScores[
                (motionScores.lastIndex * 0.70f).roundToInt().coerceIn(0, motionScores.lastIndex)
            ]
            FineMotionSample(
                // Sparse text or a card edge may cross a line in only part of the gesture. Keep
                // the median dominant, but retain a small upper-quantile contribution so those
                // repeated clipping traces are not averaged into a uniform background.
                motionScore = median * 0.70f + upper * 0.30f,
                knownScore = samples.map { it.knownScore }.averageOrZero().toFloat(),
            )
        }
        return FineMotionProfile(aggregate = aggregate, pairProfiles = pairProfiles)
    }

    private fun refinementGrayFrame(bitmap: Bitmap, width: Int, height: Int): GrayFrame {
        val cropped = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            BitmapFrameUtils.crop(bitmap, RectConfig.full(width, height))
        }
        return try {
            GrayFrame.from(cropped)
        } finally {
            if (cropped !== bitmap) cropped.recycle()
        }
    }

    private fun compareFineSlice(
        previous: GrayFrame,
        current: GrayFrame,
        bounds: RectConfig,
        dx: Int,
        dy: Int,
        direction: ScrollDirection,
        excludedRegions: List<RectConfig>,
    ): FineMotionSample? {
        val crossLength = if (direction.isVertical) bounds.width else bounds.height
        val step = if (crossLength > 720) 2 else 1
        var analyzed = 0
        var stable = 0
        var informative = 0
        var weightedMotion = 0f
        var totalWeight = 0f
        var signalStrength = 0f
        var cross = if (direction.isVertical) bounds.left else bounds.top
        val crossEnd = if (direction.isVertical) bounds.right else bounds.bottom
        while (cross < crossEnd) {
            val x = if (direction.isVertical) cross else bounds.left
            val y = if (direction.isVertical) bounds.top else cross
            if (excludedRegions.containsPoint(x, y)) {
                cross += step
                continue
            }
            val before = previous[x, y]
            val after = current[x, y]
            val fixedError = abs(before - after)
            var translatedError = Int.MAX_VALUE
            val shiftedX = x + dx
            val shiftedY = y + dy
            if (shiftedX in 0 until current.width && shiftedY in 0 until current.height &&
                !excludedRegions.containsPoint(shiftedX, shiftedY)
            ) {
                translatedError = abs(before - current[shiftedX, shiftedY])
            }
            val sourceX = x - dx
            val sourceY = y - dy
            if (sourceX in 0 until previous.width && sourceY in 0 until previous.height &&
                !excludedRegions.containsPoint(sourceX, sourceY)
            ) {
                translatedError = min(
                    translatedError,
                    abs(after - previous[sourceX, sourceY]),
                )
            }
            if (translatedError == Int.MAX_VALUE) {
                cross += step
                continue
            }
            analyzed++
            val signal = max(fixedError, translatedError)
            if (signal == 0) {
                stable++
            } else {
                informative++
                val weight = (signal / 24f).coerceIn(0.12f, 1f)
                val motion = (fixedError - translatedError).toFloat() /
                    (fixedError + translatedError + 4f)
                weightedMotion += motion * weight
                totalWeight += weight
                signalStrength += (signal / 24f).coerceIn(0f, 1f)
            }
            cross += step
        }
        if (analyzed == 0 || analyzed.toFloat() / (crossLength / step).coerceAtLeast(1) <
            MIN_RELIABLE_MOTION_SLICE_COVERAGE
        ) {
            return null
        }
        val informativeFraction = informative.toFloat() / analyzed
        val densityWeight = 0.15f + 0.85f * sqrt(informativeFraction)
        val rawMotion = if (totalWeight > 0f) weightedMotion / totalWeight else 0f
        val averageSignal = if (informative > 0) signalStrength / informative else 0f
        val stableKnown = stable.toFloat() / analyzed * 0.35f
        val motionKnown = sqrt(informativeFraction) * (0.40f + 0.60f * averageSignal)
        return FineMotionSample(
            motionScore = (rawMotion * densityWeight).coerceIn(-1f, 1f),
            knownScore = max(stableKnown, motionKnown).coerceIn(0f, 1f),
        )
    }

    private fun locateFineBoundary(
        profile: FineMotionProfile,
        searchStart: Int,
        searchEnd: Int,
        windowSize: Int,
        leading: Boolean,
        requirePersistentEvidence: Boolean,
    ): FineBoundary? {
        if (searchStart > searchEnd) return null
        val minimumSupport = max(2, windowSize / 3)
        val cutWindowSize = (windowSize / 3).coerceAtLeast(3)
        val cutMinimumSupport = max(2, cutWindowSize / 2)
        var best: FineBoundary? = null
        for (coordinate in searchStart..searchEnd) {
            val broad = boundaryContrast(
                samples = profile.aggregate,
                coordinate = coordinate,
                windowSize = windowSize,
                minimumSupport = minimumSupport,
                leading = leading,
            ) ?: continue
            val sharp = boundaryContrast(
                samples = profile.aggregate,
                coordinate = coordinate,
                windowSize = cutWindowSize,
                minimumSupport = cutMinimumSupport,
                leading = leading,
            )
            val pairSupport = profile.pairProfiles.count { pairProfile ->
                val evidence = boundaryContrast(
                    samples = pairProfile,
                    coordinate = coordinate,
                    windowSize = cutWindowSize,
                    minimumSupport = cutMinimumSupport,
                    leading = leading,
                )
                evidence != null &&
                    evidence.movingScore >= MIN_CUT_EDGE_MOVING_SCORE * 0.60f &&
                    evidence.contrast >= MIN_CUT_EDGE_CONTRAST * 0.65f
            }
            val persistentPairEvidence = hasPersistentFineEdgePairSupport(
                supportedPairCount = pairSupport,
                reliablePairCount = profile.pairProfiles.size,
            )
            val reliability = broad.contrast * broad.knownScore
            val strongMotionTransition =
                broad.movingScore >= MIN_FINE_EDGE_MOVING_SCORE &&
                    broad.contrast >= MIN_FINE_EDGE_CONTRAST &&
                    reliability >= MIN_FINE_EDGE_RELIABILITY &&
                    (!requirePersistentEvidence || persistentPairEvidence)
            val persistentCutTransition = sharp != null &&
                broad.movingScore >= MIN_CUT_EDGE_MOVING_SCORE &&
                broad.contrast >= MIN_CUT_EDGE_CONTRAST &&
                sharp.contrast >= MIN_CUT_EDGE_CONTRAST * 0.75f &&
                reliability >= MIN_CUT_EDGE_RELIABILITY &&
                persistentPairEvidence
            if (!strongMotionTransition && !persistentCutTransition) continue
            val candidate = FineBoundary(
                coordinate = coordinate,
                reliability = fineBoundaryRankScore(
                    reliability = reliability,
                    supportedPairCount = pairSupport,
                    reliablePairCount = profile.pairProfiles.size,
                ),
            )
            if (best == null || candidate.reliability > best.reliability) best = candidate
        }
        return best
    }

    internal fun fineEdgeTemporalSupport(
        supportedPairCount: Int,
        reliablePairCount: Int,
    ): Float {
        if (reliablePairCount <= 0) return 0f
        return supportedPairCount.coerceIn(0, reliablePairCount).toFloat() / reliablePairCount
    }

    internal fun hasPersistentFineEdgePairSupport(
        supportedPairCount: Int,
        reliablePairCount: Int,
    ): Boolean = supportedPairCount >= MIN_CUT_EDGE_PAIR_SUPPORT &&
        fineEdgeTemporalSupport(supportedPairCount, reliablePairCount) >=
        MIN_CUT_EDGE_PAIR_AGREEMENT

    internal fun fineBoundaryRankScore(
        reliability: Float,
        supportedPairCount: Int,
        reliablePairCount: Int,
    ): Float {
        val temporalSupport = fineEdgeTemporalSupport(supportedPairCount, reliablePairCount)
        return reliability * (0.25f + 0.75f * temporalSupport)
    }

    private fun boundaryContrast(
        samples: List<FineMotionSample?>,
        coordinate: Int,
        windowSize: Int,
        minimumSupport: Int,
        leading: Boolean,
    ): FineBoundaryContrast? {
        val before = samples.subList(
            (coordinate - windowSize).coerceAtLeast(0),
            coordinate,
        ).filterKnownFineMotionSamples()
        val after = samples.subList(
            coordinate,
            (coordinate + windowSize).coerceAtMost(samples.size),
        ).filterKnownFineMotionSamples()
        if (before.size < minimumSupport || after.size < minimumSupport) return null
        val moving = if (leading) after else before
        val fixed = if (leading) before else after
        val movingScore = moving.map { it.motionScore }.averageOrZero().toFloat()
        val fixedScore = fixed.map { it.motionScore }.averageOrZero().toFloat()
        return FineBoundaryContrast(
            movingScore = movingScore,
            contrast = movingScore - fixedScore,
            knownScore = min(
                moving.map { it.knownScore }.averageOrZero().toFloat(),
                fixed.map { it.knownScore }.averageOrZero().toFloat(),
            ),
        )
    }

    private fun List<FineMotionSample?>.filterKnownFineMotionSamples(): List<FineMotionSample> =
        mapNotNull { sample -> sample?.takeIf { it.knownScore >= MIN_FINE_EDGE_KNOWN_SCORE } }

    /**
     * A narrow false positive is more harmful than a conservative region because it permanently
     * discards scrollable content. Uncertain sides are therefore merged with a central two-thirds
     * prior, while sides backed by a fine motion transition remain at the observed boundary.
     */
    internal fun stabilizeMovingRegion(
        detected: RectConfig,
        direction: ScrollDirection,
        width: Int,
        height: Int,
        leadingBoundaryReliable: Boolean = false,
        trailingBoundaryReliable: Boolean = false,
    ): RectConfig {
        val dimension = if (direction.isVertical) height else width
        val detectedLength = if (direction.isVertical) detected.height else detected.width
        if (detectedLength >= (dimension * 0.58f).roundToInt()) return detected
        val desiredLength = (dimension * (2f / 3f)).roundToInt().coerceIn(1, dimension)
        // Gesture placement is often intentionally off-center to avoid buttons, so it must not
        // bias the inferred viewport boundary.
        val priorStart = ((dimension - desiredLength) / 2).coerceIn(0, dimension - desiredLength)
        val priorEnd = priorStart + desiredLength
        return if (direction.isVertical) {
            RectConfig(
                0,
                if (leadingBoundaryReliable) detected.top else min(detected.top, priorStart),
                width,
                if (trailingBoundaryReliable) detected.bottom else max(detected.bottom, priorEnd),
            ).clampTo(width, height)
        } else {
            RectConfig(
                if (leadingBoundaryReliable) detected.left else min(detected.left, priorStart),
                0,
                if (trailingBoundaryReliable) detected.right else max(detected.right, priorEnd),
                height,
            ).clampTo(width, height)
        }
    }

    internal fun largestUsefulBand(values: List<Float>): IntRange {
        if (values.isEmpty()) return 0..0
        val sorted = values.sorted()
        val percentile = sorted[(sorted.size * 0.55f).roundToInt().coerceIn(0, sorted.lastIndex)]
        val threshold = max(0.12f, percentile * 0.72f)
        val active = BooleanArray(values.size) { values[it] >= threshold }
        // Fill one- or two-block holes caused by animated HUD/card content.
        var holeStart = -1
        for (index in active.indices) {
            if (!active[index] && holeStart < 0) holeStart = index
            if (active[index] && holeStart >= 0) {
                if (holeStart > 0 && index - holeStart <= 2) {
                    for (hole in holeStart until index) active[hole] = true
                }
                holeStart = -1
            }
        }
        var bestStart = 0
        var bestEnd = values.lastIndex
        var bestLength = 0
        var start = -1
        for (index in 0..active.size) {
            if (index < active.size && active[index]) {
                if (start < 0) start = index
            } else if (start >= 0) {
                val length = index - start
                if (length > bestLength) {
                    bestLength = length
                    bestStart = start
                    bestEnd = index - 1
                }
                start = -1
            }
        }
        return if (bestLength == 0) 0..values.lastIndex else bestStart..bestEnd
    }

    /**
     * A row or column hidden across most of its cross axis has unreliable evidence. Carry the
     * surrounding evidence across that gap so a status banner cannot split one moving surface.
     */
    private fun fillMissingMotionScores(values: List<Float?>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val firstKnown = values.indexOfFirst { it != null }
        if (firstKnown < 0) return List(values.size) { 0f }
        val result = MutableList(values.size) { 0f }
        var previousKnown = firstKnown
        for (index in 0..firstKnown) result[index] = checkNotNull(values[firstKnown])
        for (index in firstKnown + 1 until values.size) {
            val value = values[index]
            if (value != null) {
                val previousValue = checkNotNull(values[previousKnown])
                val span = index - previousKnown
                for (gapIndex in previousKnown + 1 until index) {
                    val fraction = (gapIndex - previousKnown).toFloat() / span
                    result[gapIndex] = previousValue + (value - previousValue) * fraction
                }
                result[index] = value
                previousKnown = index
            }
        }
        val trailingValue = checkNotNull(values[previousKnown])
        for (index in previousKnown + 1 until values.size) result[index] = trailingValue
        return result
    }

    private fun fixedEdgeRegion(
        top: Boolean,
        capture: RectConfig,
        grid: ScoreGrid,
        sourceWidth: Int,
        sourceHeight: Int
    ): RectConfig? {
        val edge = if (top) capture.top else sourceHeight - capture.bottom
        if (edge <= 0) return null
        val rowsAtEdge = max(1, (edge.toFloat() / sourceHeight * grid.rows).roundToInt())
        val selectedRows = if (top) 0 until min(rowsAtEdge, grid.rows) else {
            max(0, grid.rows - rowsAtEdge) until grid.rows
        }
        val scores = selectedRows.flatMap(grid::row)
        val fixed = scores.map { it.fixedScore }.averageOrZero().toFloat()
        if (fixed < 0.20f) return null
        return if (top) {
            RectConfig(capture.left, 0, capture.right, capture.top)
        } else {
            RectConfig(capture.left, capture.bottom, capture.right, sourceHeight)
        }.clampTo(sourceWidth, sourceHeight).takeUnless { it.isEmpty }
    }

    private fun suggestedGestureRegion(capture: RectConfig, width: Int, height: Int): RectConfig {
        val insetX = (capture.width * 0.28f).roundToInt()
        val insetY = (capture.height * 0.22f).roundToInt()
        val candidate = RectConfig(
            capture.left + insetX,
            capture.top + insetY,
            capture.right - insetX,
            capture.bottom - insetY
        ).clampTo(width, height)
        return candidate.takeUnless { it.isEmpty } ?: capture
    }

    private fun componentsToRegions(
        grid: ScoreGrid,
        blockSize: Int,
        scaleX: Float,
        scaleY: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        minimumCells: Int,
        maximumRegions: Int,
        excludedRegions: List<RectConfig> = emptyList(),
        predicate: (MotionBlockScore) -> Boolean
    ): List<RectConfig> {
        val marked = BooleanArray(grid.scores.size) { index ->
            grid.isIncluded(index) && predicate(grid.scores[index])
        }
        val visited = BooleanArray(marked.size)
        val components = mutableListOf<Pair<Int, RectConfig>>()
        for (start in marked.indices) {
            if (!marked[start] || visited[start]) continue
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            var cells = 0
            var minColumn = grid.columns
            var maxColumn = 0
            var minRow = grid.rows
            var maxRow = 0
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val row = current / grid.columns
                val column = current % grid.columns
                cells++
                minColumn = min(minColumn, column)
                maxColumn = max(maxColumn, column)
                minRow = min(minRow, row)
                maxRow = max(maxRow, row)
                intArrayOf(current - 1, current + 1, current - grid.columns, current + grid.columns)
                    .forEach { neighbor ->
                        if (neighbor !in marked.indices || visited[neighbor] || !marked[neighbor]) return@forEach
                        val neighborRow = neighbor / grid.columns
                        val neighborColumn = neighbor % grid.columns
                        if (abs(neighborRow - row) + abs(neighborColumn - column) != 1) return@forEach
                        visited[neighbor] = true
                        queue.add(neighbor)
                    }
            }
            if (cells >= minimumCells) {
                components += cells to RectConfig(
                    minColumn * blockSize,
                    minRow * blockSize,
                    (maxColumn + 1) * blockSize,
                    (maxRow + 1) * blockSize
                ).scale(scaleX, scaleY).clampTo(sourceWidth, sourceHeight)
            }
        }
        return components
            .sortedByDescending { it.first }
            .flatMap { (_, region) -> subtractRegions(region, excludedRegions) }
            .take(maximumRegions)
    }

    private fun fallbackResult(
        width: Int,
        height: Int,
        gestureRegion: RectConfig?,
        estimate: MotionEstimate,
        confidence: Float,
        excludedRegions: List<RectConfig>,
    ): RegionLearningResult {
        val full = RectConfig.full(width, height)
        val capture = full
        return RegionLearningResult(
            suggestedStitchRegion = capture,
            suggestedGestureRegion = gestureRegion?.clampTo(width, height)?.takeUnless { it.isEmpty }
                ?: suggestedGestureRegion(capture, width, height),
            suggestedTopFixedRegion = null,
            suggestedBottomFixedRegion = null,
            suggestedHudMasks = emptyList(),
            confidence = confidence,
            uncertainRegions = subtractRegions(full, excludedRegions),
            motionEstimate = estimate
        )
    }

    private fun nonExcludedArea(
        bounds: RectConfig,
        excludedRegions: List<RectConfig>,
    ): Long = subtractRegions(bounds, excludedRegions).sumOf { region ->
        region.width.toLong() * region.height.toLong()
    }

    private fun subtractRegions(
        bounds: RectConfig,
        excludedRegions: List<RectConfig>,
    ): List<RectConfig> {
        var remaining = listOf(bounds.normalized()).filterNot { it.isEmpty }
        excludedRegions.forEach { excluded ->
            remaining = remaining.flatMap { region ->
                val overlap = region.intersect(excluded.normalized())
                    ?: return@flatMap listOf(region)
                listOf(
                    RectConfig(region.left, region.top, region.right, overlap.top),
                    RectConfig(region.left, overlap.bottom, region.right, region.bottom),
                    RectConfig(region.left, overlap.top, overlap.left, overlap.bottom),
                    RectConfig(overlap.right, overlap.top, region.right, overlap.bottom),
                ).filterNot { it.isEmpty }
            }
        }
        return remaining
    }

    private fun blockCenterInside(score: MotionBlockScore, rect: RectConfig, blockSize: Int): Boolean {
        val x = score.blockX * blockSize + blockSize / 2
        val y = score.blockY * blockSize + blockSize / 2
        return x in rect.left until rect.right && y in rect.top until rect.bottom
    }

    private fun ScrollDirection.toDisplacement(magnitude: Int): Pair<Int, Int> = when (this) {
        ScrollDirection.UP -> 0 to -magnitude
        ScrollDirection.DOWN -> 0 to magnitude
        ScrollDirection.LEFT -> -magnitude to 0
        ScrollDirection.RIGHT -> magnitude to 0
    }

    private data class GrayFrame(val width: Int, val height: Int, val pixels: ByteArray) {
        operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

        fun sampledTexture(
            region: RectConfig? = null,
            excludedRegions: List<RectConfig> = emptyList(),
        ): Float {
            var sum = 0L
            var sumSquares = 0L
            var count = 0
            val step = 8
            val bounds = region?.clampTo(width, height)?.takeUnless { it.isEmpty }
                ?: RectConfig.full(width, height)
            var y = bounds.top
            while (y < bounds.bottom) {
                var x = bounds.left
                while (x < bounds.right) {
                    if (!excludedRegions.containsPoint(x, y)) {
                        val value = get(x, y)
                        sum += value
                        sumSquares += value.toLong() * value
                        count++
                    }
                    x += step
                }
                y += step
            }
            if (count == 0) return 0f
            val mean = sum.toFloat() / count
            val variance = (sumSquares.toFloat() / count - mean * mean).coerceAtLeast(0f)
            return (sqrt(variance) / 32f).coerceIn(0f, 1f)
        }

        companion object {
            fun from(bitmap: Bitmap): GrayFrame {
                val colors = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val gray = ByteArray(colors.size)
                for (index in colors.indices) {
                    val color = colors[index]
                    val red = color shr 16 and 0xFF
                    val green = color shr 8 and 0xFF
                    val blue = color and 0xFF
                    gray[index] = ((red * 77 + green * 150 + blue * 29) shr 8).toByte()
                }
                return GrayFrame(bitmap.width, bitmap.height, gray)
            }
        }
    }

    private data class ShiftScore(val dx: Int, val dy: Int, val error: Float)
    private data class BlockEvidence(val follow: Float, val fixed: Float, val unstable: Float, val unknown: Float)
    private data class FineMotionSample(val motionScore: Float, val knownScore: Float)
    private data class FineMotionProfile(
        val aggregate: List<FineMotionSample?>,
        val pairProfiles: List<List<FineMotionSample?>>,
    )
    private data class FineBoundaryContrast(
        val movingScore: Float,
        val contrast: Float,
        val knownScore: Float,
    )
    private data class FineBoundary(val coordinate: Int, val reliability: Float)
    private data class RefinedMovingRegion(
        val region: RectConfig,
        val leadingBoundaryReliable: Boolean = false,
        val trailingBoundaryReliable: Boolean = false,
    )

    private class BlockAccumulator {
        private var follow = 0f
        private var fixed = 0f
        private var unstable = 0f
        private var unknown = 0f
        private var count = 0

        fun add(evidence: BlockEvidence) {
            follow += evidence.follow
            fixed += evidence.fixed
            unstable += evidence.unstable
            unknown += evidence.unknown
            count++
        }

        fun finish(column: Int, row: Int): MotionBlockScore {
            val divisor = count.coerceAtLeast(1).toFloat()
            return MotionBlockScore(
                blockX = column,
                blockY = row,
                followMainMotionScore = (follow / divisor).coerceIn(0f, 1f),
                fixedScore = (fixed / divisor).coerceIn(0f, 1f),
                unstableScore = (unstable / divisor).coerceIn(0f, 1f),
                unknownScore = (unknown / divisor).coerceIn(0f, 1f)
            )
        }
    }

    private data class ScoreGrid(
        val columns: Int,
        val rows: Int,
        val scores: List<MotionBlockScore>,
        val includedCells: BooleanArray,
    ) {
        init {
            require(scores.size == columns * rows)
            require(includedCells.size == scores.size)
        }

        fun isIncluded(index: Int): Boolean = includedCells[index]

        fun rowCoverage(row: Int): Float =
            (0 until columns).count { column -> isIncluded(row * columns + column) }.toFloat() /
                columns

        fun columnCoverage(column: Int): Float =
            (0 until rows).count { row -> isIncluded(row * columns + column) }.toFloat() /
                rows

        fun row(row: Int): List<MotionBlockScore> =
            (0 until columns).mapNotNull { column ->
                val index = row * columns + column
                scores[index].takeIf { isIncluded(index) }
            }

        fun column(column: Int): List<MotionBlockScore> =
            (0 until rows).mapNotNull { row ->
                val index = row * columns + column
                scores[index].takeIf { isIncluded(index) }
            }
    }
}

private fun Iterable<Float>.averageOrZero(): Double {
    var sum = 0.0
    var count = 0
    for (value in this) {
        sum += value
        count++
    }
    return if (count == 0) 0.0 else sum / count
}
