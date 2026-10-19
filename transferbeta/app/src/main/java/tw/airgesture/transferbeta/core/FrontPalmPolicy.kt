package tw.airgesture.transferbeta.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Conservative palm-facing-camera test for ARMING A SWIPE ONLY.
 *
 * MediaPipe Tasks handedness refers to the physical left/right hand
 * in the UNMIRRORED CameraX analysis image used by this app. The legacy
 * selfie-mirror assumption reverses palm/back (seen on S26 Ultra v1.0.14).
 * Compare the reported hand against the thumb side IN THE SAME INPUT IMAGE;
 * never use the horizontally flipped coordinates of the position overlay.
 *
 * 0=wrist, 2/3/4=thumb, 5=index MCP, 9=middle MCP, 17=pinky MCP.
 * The projected signed-side test is invariant to in-plane hand rotation.
 * World-landmark palm normal rejects edge-on hands, without running a second
 * ML model or involving the screen's mirrored overlay coordinates.
 *
 * This is an estimate, not an anatomical palm/back classifier. Ambiguous
 * camera frames fail closed rather than arming a new swipe.
 */
object FrontPalmPolicy {
    private const val MIN_HANDEDNESS = .72f
    private const val MIN_NORMAL_FACING = .38f
    private const val MIN_THUMB_SEPARATION = .12f

    fun facesCamera(
        normalizedXY: FloatArray?,
        worldXYZ: FloatArray?,
        handedness: String?,
        handednessScore: Float
    ): Boolean {
        if (normalizedXY == null || normalizedXY.size < 42 ||
            worldXYZ == null || worldXYZ.size < 63 ||
            handednessScore < MIN_HANDEDNESS) return false

        // CameraX sends UNMIRRORED analysis pixels to MediaPipe Tasks:
        // physical RIGHT palm toward lens => thumb on image-right;
        // physical LEFT palm toward lens => thumb on image-left.
        // v1.0.14 had these signs swapped, reporting the hand's back as palm.
        val expected = when (handedness) {
            "Left" -> -1f
            "Right" -> 1f
            else -> return false
        }
        if (!handednessScore.isFinite()) return false
        if ((0 until 42).any { !normalizedXY[it].isFinite() } ||
            (0 until 63).any { !worldXYZ[it].isFinite() }) return false

        fun x(i: Int) = normalizedXY[i * 2]
        fun y(i: Int) = normalizedXY[i * 2 + 1]
        val ax = x(9) - x(0)
        val ay = y(9) - y(0)
        val axisSize = hypot(ax, ay)
        val palmWidth = hypot(x(5) - x(17), y(5) - y(17))
        if (axisSize < .06f || palmWidth < .04f) return false

        // Signed distance to the hand's long axis. Using the average of 3
        // thumb landmarks resists a single curled/occluded fingertip.
        val tx = (x(2) + x(3) + x(4)) / 3f - x(17)
        val ty = (y(2) + y(3) + y(4)) / 3f - y(17)
        val signedSeparation = (ax * ty - ay * tx) * expected / axisSize
        if (signedSeparation < palmWidth * MIN_THUMB_SEPARATION) return false

        // 3D palm plane projected onto camera Z; absolute strength only.
        // Back/front sign is determined by handedness + 2D thumb placement.
        fun wx(i: Int) = worldXYZ[i * 3]
        fun wy(i: Int) = worldXYZ[i * 3 + 1]
        fun wz(i: Int) = worldXYZ[i * 3 + 2]
        val ux = wx(5) - wx(0)
        val uy = wy(5) - wy(0)
        val uz = wz(5) - wz(0)
        val vx = wx(17) - wx(0)
        val vy = wy(17) - wy(0)
        val vz = wz(17) - wz(0)
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val normal = sqrt(nx * nx + ny * ny + nz * nz)
        return normal > 0.00001f && abs(nz) / normal >= MIN_NORMAL_FACING
    }
}
