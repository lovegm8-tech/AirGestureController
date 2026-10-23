package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Persistent, movable translation bubble. The compact menu is attached only
 * while the user opens it; it does not steal focus from the video player.
 * SECURE prevents the controls from being re-OCRed by MediaProjection.
 */
public final class SubtitleBubbleController {
    public interface Actions {
        void onRecalibrate();
        void onShowDiagnostics();
        void onTogglePaused();
        void onStop();
    }

    private final Context context;
    private final WindowManager manager;
    private final Actions actions;
    private final int ballSize;
    private TextView ball;
    private LinearLayout menu;
    private TextView pauseAction;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams menuParams;
    private boolean paused;

    public SubtitleBubbleController(Context context, WindowManager manager, Actions actions) {
        this.context = context;
        this.manager = manager;
        this.actions = actions;
        ballSize = dp(50);
    }

    private int dp(float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }

    private DisplayMetrics metrics() {
        DisplayMetrics m = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private static final int WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_SECURE;

    public void show() {
        if (ball != null) return;
        DisplayMetrics m = metrics();
        ball = new TextView(context);
        ball.setText("譯");
        ball.setGravity(Gravity.CENTER);
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(21f);
        ball.setBackground(background(Color.rgb(19, 121, 135), 30));
        ball.setContentDescription("懸浮翻譯小球，點選開啟操作，長按拖曳移動");
        ball.setElevation(dp(7));

        android.content.SharedPreferences pref =
                context.getSharedPreferences("floating_subtitle", Context.MODE_PRIVATE);
        String suffix = m.widthPixels > m.heightPixels ? "landscape" : "portrait";
        float yFrac = pref.getFloat("bubble_y_" + suffix, 0.43f);
        float xFrac = pref.getFloat("bubble_x_" + suffix, 0.92f);
        ballParams = new WindowManager.LayoutParams(
                ballSize, ballSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WINDOW_FLAGS,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.LEFT;
        ballParams.x = SubtitleBubblePosition.clamp(Math.round(xFrac * m.widthPixels),
                m.widthPixels - ballSize);
        ballParams.y = SubtitleBubblePosition.clamp(Math.round(yFrac * m.heightPixels),
                m.heightPixels - ballSize);
        ball.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private int originalX, originalY;
            private boolean dragging;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX(); startY = event.getRawY();
                        originalX = ballParams.x; originalY = ballParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - startX);
                        int dy = Math.round(event.getRawY() - startY);
                        if (Math.abs(dx) > dp(7) || Math.abs(dy) > dp(7)) dragging = true;
                        if (dragging) {
                            DisplayMetrics size = metrics();
                            ballParams.x = SubtitleBubblePosition.clamp(originalX + dx,
                                    size.widthPixels - ballSize);
                            ballParams.y = SubtitleBubblePosition.clamp(originalY + dy,
                                    size.heightPixels - ballSize);
                            try { manager.updateViewLayout(ball, ballParams); }
                            catch (IllegalArgumentException ignored) { }
                            if (menu != null) layoutMenu();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            DisplayMetrics size = metrics();
                            String suffix = size.widthPixels > size.heightPixels
                                    ? "landscape" : "portrait";
                            pref.edit()
                                    .putFloat("bubble_x_" + suffix,
                                            ballParams.x / (float) size.widthPixels)
                                    .putFloat("bubble_y_" + suffix,
                                            ballParams.y / (float) size.heightPixels)
                                    .apply();
                        } else {
                            if (menu == null) openMenu(); else closeMenu();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        try { manager.addView(ball, ballParams); }
        catch (Exception ignored) { ball = null; ballParams = null; }
    }

    public void updatePaused(boolean newPaused) {
        paused = newPaused;
        if (ball != null) {
            ball.setText(paused ? "‖" : "譯");
            ball.setBackground(background(
                    paused ? Color.rgb(94, 99, 109) : Color.rgb(19, 121, 135), 30));
        }
        if (pauseAction != null)
            pauseAction.setText(paused ? "▶ 恢復翻譯" : "Ⅱ 暫停翻譯");
    }

    private TextView item(String text, Runnable onClick) {
        TextView item = new TextView(context);
        item.setText(text);
        item.setTextSize(16f);
        item.setTextColor(Color.WHITE);
        item.setPadding(dp(17), dp(12), dp(17), dp(12));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setOnClickListener(v -> {
            closeMenu();
            onClick.run();
        });
        return item;
    }

    private void openMenu() {
        if (menu != null || ball == null) return;
        menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(background(Color.argb(244, 22, 32, 46), 16));
        menu.setElevation(dp(9));
        menu.addView(item("框選字幕位置", actions::onRecalibrate));
        menu.addView(item("檢查字幕畫面", actions::onShowDiagnostics));
        pauseAction = item(paused ? "▶ 恢復翻譯" : "Ⅱ 暫停翻譯", actions::onTogglePaused);
        menu.addView(pauseAction);
        menu.addView(item("停止懸浮翻譯", actions::onStop));
        menuParams = new WindowManager.LayoutParams(
                dp(190), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WINDOW_FLAGS, PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.LEFT;
        layoutMenu();
        try { manager.addView(menu, menuParams); }
        catch (Exception ignored) { menu = null; menuParams = null; pauseAction = null; }
    }

    private void layoutMenu() {
        if (menuParams == null || ballParams == null) return;
        DisplayMetrics size = metrics();
        int menuWidth = dp(190);
        int menuHeight = dp(214);
        int gap = dp(7);
        int proposedX = ballParams.x - menuWidth - gap;
        if (proposedX < 0) proposedX = ballParams.x + ballSize + gap;
        menuParams.x = SubtitleBubblePosition.clamp(proposedX, size.widthPixels - menuWidth);
        menuParams.y = SubtitleBubblePosition.clamp(ballParams.y,
                size.heightPixels - menuHeight);
        if (menu != null && menu.getParent() != null) {
            try { manager.updateViewLayout(menu, menuParams); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private void closeMenu() {
        if (menu != null) {
            try { manager.removeView(menu); }
            catch (IllegalArgumentException ignored) { }
            menu = null;
        }
        menuParams = null;
        pauseAction = null;
    }

    public void onDisplayChanged() {
        closeMenu();
        if (ball == null || ballParams == null) return;
        DisplayMetrics size = metrics();
        String suffix = size.widthPixels > size.heightPixels ? "landscape" : "portrait";
        android.content.SharedPreferences pref =
                context.getSharedPreferences("floating_subtitle", Context.MODE_PRIVATE);
        ballParams.x = SubtitleBubblePosition.clamp(
                Math.round(pref.getFloat("bubble_x_" + suffix, 0.92f) * size.widthPixels),
                size.widthPixels - ballSize);
        ballParams.y = SubtitleBubblePosition.clamp(
                Math.round(pref.getFloat("bubble_y_" + suffix, 0.43f) * size.heightPixels),
                size.heightPixels - ballSize);
        try { manager.updateViewLayout(ball, ballParams); }
        catch (IllegalArgumentException ignored) { }
    }

    public void hide() {
        closeMenu();
        if (ball != null) {
            try { manager.removeView(ball); }
            catch (IllegalArgumentException ignored) { }
            ball = null;
        }
        ballParams = null;
    }
}
