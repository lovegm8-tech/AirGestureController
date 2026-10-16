package tw.airgesture.controller.core

/** UI-only feedback, never used to infer or execute a control action. */
enum class HandVisualState {
    NO_HAND,
    HAND_DETECTED,
    OPEN_PALM,
    CLOSED_FIST,
    INDEX_FINGER
}
