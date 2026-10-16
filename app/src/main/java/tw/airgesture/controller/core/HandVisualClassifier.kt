package tw.airgesture.controller.core

/** Recognizer -> HUD state. This is display-only and cannot trigger touches. */
object HandVisualClassifier {
    fun classify(
        shape: GestureDecisionEngine.Shape,
        confidence: Float,
        hasHandLandmarks: Boolean,
        pointingUp: Boolean
    ): HandVisualState = when {
        shape == GestureDecisionEngine.Shape.OPEN_PALM && confidence >= .65f ->
            HandVisualState.OPEN_PALM
        shape == GestureDecisionEngine.Shape.CLOSED_FIST && confidence >= .65f ->
            HandVisualState.CLOSED_FIST
        pointingUp && hasHandLandmarks -> HandVisualState.INDEX_FINGER
        hasHandLandmarks -> HandVisualState.HAND_DETECTED
        else -> HandVisualState.NO_HAND
    }
}
