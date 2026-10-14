import tw.airgesture.controller.core.GestureDecisionEngine

fun main() {
    val o = GestureDecisionEngine.Shape.OPEN_PALM
    val f = GestureDecisionEngine.Shape.CLOSED_FIST
    val n = GestureDecisionEngine.Shape.NO_HAND
    val E = GestureDecisionEngine()
    fun feed(s: GestureDecisionEngine.Shape, y: Float? = null, t: Long) = E.accept(GestureDecisionEngine.Frame(s, y), t)
    check(feed(o, .70f, 0) == null)
    check(feed(o, .70f, 100) == null)
    check(feed(o, .50f, 250) == GestureDecisionEngine.Action.SWIPE_UP)
    check(feed(o, .25f, 350) == null)
    check(feed(n, t = 400) == null)
    check(feed(n, t = 800) == null)
    check(feed(o, .30f, 1200) == null)
    check(feed(o, .30f, 1300) == null)
    check(feed(o, .55f, 1500) == GestureDecisionEngine.Action.SWIPE_DOWN)
    E.reset()
    check(feed(f, t = 0) == null)
    check(feed(f, t = 100) == null)
    check(feed(o, .5f, 200) == null)
    check(feed(o, .5f, 280) == null)
    check(feed(f, t = 380) == null)
    check(feed(f, t = 460) == GestureDecisionEngine.Action.TAP_CENTER)
    check(feed(f, t = 550) == null)
    println("PASS: 上滑、下滑、握拳點擊、單次觸發、離開鏡頭重置")
}
