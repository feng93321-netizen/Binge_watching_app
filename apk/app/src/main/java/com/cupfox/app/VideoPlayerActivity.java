package com.cupfox.app;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

public class VideoPlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView topBar, timeText, centerIndicator;
    private View progWrap, progBar, playBtn, backBtn;
    private TextView lockBtn;
    private FrameLayout progWrapFrame;
    private FrameLayout.LayoutParams progBarLp;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean barsVisible = true;
    private boolean screenLocked = false;
    private String videoFilePath;

    private AudioManager audioManager;
    private int maxVolume;
    private int currentBrightness = -1;
    private float originalBrightness;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        videoFilePath = getIntent().getStringExtra("filePath");
        String title = getIntent().getStringExtra("title");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(playerView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        backBtn = makeText("\u2039", 32, Color.WHITE);
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        backLp.gravity = Gravity.TOP | Gravity.LEFT;
        backLp.setMargins(dp(12), dp(36), 0, 0);
        root.addView(backBtn, backLp);
        backBtn.setOnClickListener(v -> finish());

        topBar = makeText(title != null ? title : "", 15, Color.WHITE);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(48));
        topLp.gravity = Gravity.TOP;
        topLp.setMargins(dp(60), dp(36), dp(16), 0);
        root.addView(topBar, topLp);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        progWrapFrame = new FrameLayout(this);
        FrameLayout.LayoutParams progLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        progLp.gravity = Gravity.BOTTOM;
        progLp.setMargins(dp(16), 0, dp(16), dp(56));
        root.addView(progWrapFrame, progLp);
        progWrap = progWrapFrame;

        progBar = new View(this);
        progBar.setBackgroundColor(Color.WHITE);
        progBarLp = new FrameLayout.LayoutParams(0, dp(3));
        progBar.setLayoutParams(progBarLp);
        progWrapFrame.addView(progBar);

        playBtn = makeText("\u23F8", 24, Color.WHITE);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        playLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        playLp.setMargins(dp(12), 0, 0, dp(8));
        root.addView(playBtn, playLp);
        playBtn.setOnClickListener(v -> togglePlay());

        timeText = makeText("0:00 / 0:00", 13, Color.WHITE);
        FrameLayout.LayoutParams timeLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(48));
        timeLp.gravity = Gravity.BOTTOM | Gravity.END;
        timeLp.setMargins(0, 0, dp(16), dp(14));
        root.addView(timeText, timeLp);

        centerIndicator = makeText("", 15, Color.WHITE);
        FrameLayout.LayoutParams ciLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ciLp.gravity = Gravity.CENTER;
        ciLp.topMargin = dp(120);
        centerIndicator.setBackgroundColor(0xAA000000);
        centerIndicator.setPadding(dp(18), dp(10), dp(18), dp(10));
        root.addView(centerIndicator, ciLp);
        centerIndicator.setVisibility(View.INVISIBLE);

        lockBtn = new TextView(this);
        lockBtn.setText("\u2298");
        lockBtn.setTextSize(28);
        lockBtn.setTextColor(Color.WHITE);
        lockBtn.setBackgroundColor(0x44000000);
        lockBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams lockLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lockLp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
        lockLp.setMargins(dp(6), 0, 0, 0);
        root.addView(lockBtn, lockLp);
        lockBtn.setOnClickListener(v -> toggleLock());

        setContentView(root);
        setupTouchGestures(root);
        showBars();
        initPlayer();
    }

    private void toggleLock() {
        screenLocked = !screenLocked;
        if (screenLocked) {
            lockBtn.setText("\u2299");
            hideBars();
            showCenter("LOCKED");
        } else {
            lockBtn.setText("\u2298");
            showBars();
        }
    }

    private Runnable lockFadeRunnable = () -> {};  // no-op, lock btn stays

    private void initPlayer() {
        if (videoFilePath == null || videoFilePath.isEmpty()) { finish(); return; }
        try {
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 60000, 1500, 5000)
                .build();
            player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();
            playerView.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        showBars();
                        handler.post(updateRunnable);
                    } else if (state == Player.STATE_ENDED) {
                        showBars();
                        updatePlayBtn();
                    }
                }
                @Override
                public void onPlayerError(PlaybackException error) {
                    android.util.Log.e("VideoPlayer", "ExoPlayer error", error);
                }
            });

            MediaItem mediaItem = MediaItem.fromUri("file://" + videoFilePath);
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            android.util.Log.e("VideoPlayer", "init failed", e);
            finish();
        }
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
        updatePlayBtn();
    }

    private void updatePlayBtn() {
        TextView pb = (TextView) playBtn;
        if (player != null && player.isPlaying()) pb.setText("\u23F8");
        else pb.setText("\u25B6");
    }

    private void showBars() {
        if (screenLocked) return;
        barsVisible = true;
        topBar.setVisibility(View.VISIBLE);
        progWrap.setVisibility(View.VISIBLE);
        playBtn.setVisibility(View.VISIBLE);
        timeText.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBarsRunnable);
        handler.postDelayed(hideBarsRunnable, 3500);
    }

    private Runnable hideBarsRunnable = this::hideBars;

    private void hideBars() {
        barsVisible = false;
        topBar.setVisibility(View.INVISIBLE);
        progWrap.setVisibility(View.INVISIBLE);
        playBtn.setVisibility(View.INVISIBLE);
        timeText.setVisibility(View.INVISIBLE);
        backBtn.setVisibility(View.INVISIBLE);
    }

    private void updateTime() {
        if (player == null) return;
        long cur = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur > 0) {
            timeText.setText(fmtMs(cur) + " / " + fmtMs(dur));
            float pct = (float) cur / dur;
            progBarLp.width = (int) (progWrapFrame.getWidth() * pct);
            progBar.setLayoutParams(progBarLp);
        }
        updatePlayBtn();
    }

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            updateTime();
            handler.postDelayed(this, 500);
        }
    };

    private void adjustVolume(float dy, int screenH) {
        int steps = (int) (-dy / screenH * maxVolume * 2);
        if (steps == 0) return;
        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int next = Math.max(0, Math.min(maxVolume, cur + steps));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
        int pct = (int) (next * 100f / maxVolume);
        showCenter("\uD83D\uDD0A " + pct + "%");
    }

    private void adjustBrightness(float dy, int screenH) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float curBright = lp.screenBrightness;
        if (curBright < 0) {
            try {
                curBright = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (Exception e) {
                curBright = 0.5f;
            }
        }
        float add = -dy / screenH * 0.5f;
        float next = Math.max(0.01f, Math.min(1.0f, curBright + add));
        lp.screenBrightness = next;
        getWindow().setAttributes(lp);
        int pct = (int) (next * 100);
        showCenter("\uD83D\uDD06 " + pct + "%");
    }

    private void setupTouchGestures(View root) {
        final float[] startXY = new float[2];
        final long[] lastTap = {0};
        final boolean[] dragging = {false};
        final String[] dragType = {null};
        final float[] seekTarget = {0};
        final float[] startTime = {0};
        final boolean[] wasPlaying = {true};

        root.setOnTouchListener((v, event) -> {
            float x = event.getX();
            float y = event.getY();
            int w = root.getWidth();
            int h = root.getHeight();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startXY[0] = x;
                    startXY[1] = y;
                    dragging[0] = false;
                    dragType[0] = null;
                    startTime[0] = player != null ? player.getCurrentPosition() / 1000f : 0;
                    wasPlaying[0] = player != null && player.isPlaying();

                    if (screenLocked) {
                        return true;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (screenLocked) return true;
                    float dx = x - startXY[0];
                    float dy = y - startXY[1];
                    if (!dragging[0] && (Math.abs(dx) > 15 || Math.abs(dy) > 15)) {
                        dragging[0] = true;
                        if (Math.abs(dy) > Math.abs(dx)) {
                            dragType[0] = x < w / 2 ? "volume" : "brightness";
                        } else {
                            dragType[0] = "seek";
                            if (player != null) player.pause();
                        }
                    }
                    if (dragType[0] == null) return true;
                    if ("seek".equals(dragType[0])) {
                        float dur = player != null && player.getDuration() > 0 ? player.getDuration() / 1000f : 1;
                        float add = dx / w * dur;
                        seekTarget[0] = Math.max(0, Math.min(dur, startTime[0] + add));
                        float pct = seekTarget[0] / dur;
                        progBarLp.width = (int) (progWrapFrame.getWidth() * pct);
                        progBar.setLayoutParams(progBarLp);
                        timeText.setText(fmtMs((long)(seekTarget[0] * 1000)) + " / " + fmtMs((long)(dur * 1000)));
                        showCenter((add > 0 ? "+" : "") + fmtMs((long)(Math.abs(add) * 1000))
                            + " \u2192 " + fmtMs((long)(seekTarget[0] * 1000)));
                    } else if ("volume".equals(dragType[0])) {
                        adjustVolume(dy, h);
                        startXY[1] = y;
                    } else if ("brightness".equals(dragType[0])) {
                        adjustBrightness(dy, h);
                        startXY[1] = y;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    hideIndicator(centerIndicator);
                    if (screenLocked) return true;
                    if (!dragging[0]) {
                        long now = System.currentTimeMillis();
                        if (now - lastTap[0] < 300) {
                            togglePlay();
                            lastTap[0] = 0;
                        } else {
                            lastTap[0] = now;
                            if (barsVisible) hideBars();
                            else showBars();
                        }
                    } else {
                        if ("seek".equals(dragType[0]) && player != null) {
                            player.seekTo((long)(seekTarget[0] * 1000));
                            if (wasPlaying[0]) player.play();
                        }
                        handler.postDelayed(this::showBars, 100);
                    }
                    dragging[0] = false;
                    dragType[0] = null;
                    return true;
            }
            return false;
        });
    }

    private void showCenter(String text) {
        centerIndicator.setText(text);
        centerIndicator.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCenterRunnable);
        handler.postDelayed(hideCenterRunnable, 1200);
    }

    private Runnable hideCenterRunnable = () -> centerIndicator.setVisibility(View.INVISIBLE);

    private void hideIndicator(TextView tv) {
        tv.setVisibility(View.INVISIBLE);
    }

    private String fmtMs(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        s = s % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private TextView makeText(String text, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() { finish(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(updateRunnable);
        handler.removeCallbacks(hideBarsRunnable);
        handler.removeCallbacks(hideCenterRunnable);
        handler.removeCallbacks(lockFadeRunnable);
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = -1;
        getWindow().setAttributes(lp);
        super.onDestroy();
    }
}
