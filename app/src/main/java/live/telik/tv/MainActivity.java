package live.telik.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-screen TV player. Resolves the channel stream and plays it forever,
 * restarting automatically on any error or network loss.
 */
public class MainActivity extends Activity implements Player.Listener {

    private static final String TAG = "TelikTV";

    private static final long RETRY_MIN_MS = 2_000L;
    private static final long RETRY_MAX_MS = 30_000L;
    private static final long WATCHDOG_MS = 10_000L;
    private static final long OVERLAY_HIDE_MS = 3_000L;
    private static final long DIAG_REFRESH_MS = 1_000L;
    private static final long DIAG_AUTO_HIDE_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private AspectSurfaceView surface;
    private TextView statusView;
    private TextView overlayView;
    private TextView diagView;
    private ExoPlayer player;
    private PowerManager.WakeLock wakeLock;

    private final Diagnostics diagnostics = new Diagnostics();
    private final Runnable diagRefresh = this::refreshDiagnostics;
    private boolean diagVisible;
    private String currentUrl;

    private long retryDelay = RETRY_MIN_MS;
    private boolean started;
    private long lastProgressMs;
    private long lastPosition = -1L;
    private boolean playing;
    private int mirrorIndex;
    private int mirrorCount;
    private int resolveSeq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new AspectSurfaceView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
        root.addView(surface);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(48, 48, 48, 48);
        statusView.setVisibility(View.GONE);
        root.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        overlayView = new TextView(this);
        overlayView.setTextColor(Color.WHITE);
        overlayView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        overlayView.setGravity(Gravity.CENTER);
        overlayView.setPadding(40, 24, 40, 24);
        overlayView.setBackgroundColor(0x99000000);
        overlayView.setVisibility(View.GONE);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(overlayView, overlayParams);

        diagView = new TextView(this);
        diagView.setTextColor(Color.WHITE);
        diagView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        diagView.setGravity(Gravity.START);
        diagView.setPadding(40, 32, 40, 32);
        diagView.setBackgroundColor(0xCC000000);
        diagView.setVisibility(View.GONE);
        FrameLayout.LayoutParams diagParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        diagParams.setMargins(40, 40, 40, 40);
        root.addView(diagView, diagParams);

        setContentView(root);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telik:playback");
        wakeLock.setReferenceCounted(false);

        registerNetworkCallback();
        started = true;
        start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isDiagnosticsKey(keyCode)) {
            if (event.getRepeatCount() == 0) {
                toggleDiagnostics();
            }
            return true;
        }
        if (mirrorCount > 1) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getRepeatCount() == 0) {
                    switchMirror(1);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.getRepeatCount() == 0) {
                    switchMirror(-1);
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Several key codes are accepted because cheap box remotes vary a lot: some
     * send INFO, some MENU, and many only have a plain OK button.
     */
    private static boolean isDiagnosticsKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_INFO
                || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    /**
     * Toggles the diagnostics panel. While visible it refreshes on a timer so
     * the dropped-frame and bandwidth numbers stay live, and it hides itself
     * after a minute so an accidental press cannot leave it on screen forever.
     */
    private void toggleDiagnostics() {
        handler.removeCallbacks(diagRefresh);
        handler.removeCallbacks(diagHide);
        diagVisible = !diagVisible;
        if (diagVisible) {
            diagView.setVisibility(View.VISIBLE);
            refreshDiagnostics();
        } else {
            diagView.setVisibility(View.GONE);
        }
    }

    private void refreshDiagnostics() {
        if (!diagVisible) {
            return;
        }
        diagView.setText(diagnostics.report(player, mirrorIndex, mirrorCount, currentUrl));
        handler.removeCallbacks(diagRefresh);
        handler.postDelayed(diagRefresh, DIAG_REFRESH_MS);
        handler.removeCallbacks(diagHide);
        handler.postDelayed(diagHide, DIAG_AUTO_HIDE_MS);
    }

    private final Runnable diagHide = () -> {
        diagVisible = false;
        handler.removeCallbacks(diagRefresh);
        diagView.setVisibility(View.GONE);
    };

    /** Overrides the automatic mirror choice and restarts playback on it. */
    private void switchMirror(int delta) {
        mirrorIndex = Math.floorMod(mirrorIndex + delta, mirrorCount);
        showOverlay("Поток " + (mirrorIndex + 1) + " из " + mirrorCount);
        retryDelay = RETRY_MIN_MS;
        releasePlayer();
        handler.removeCallbacks(retryRunnable);
        start();
    }

    /** Shows a message in the middle of the screen and fades it out again. */
    private void showOverlay(String message) {
        overlayView.setText(message);
        overlayView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, OVERLAY_HIDE_MS);
    }

    private final Runnable hideOverlay = () -> overlayView.setVisibility(View.GONE);

    /** Shows a plain status message. Hidden entirely while playback is healthy. */
    private void showStatus(String message) {
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusView.setVisibility(View.GONE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
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

    private void start() {
        int seq = ++resolveSeq;
        if (!playing) {
            showStatus("Подключение к потоку…");
        }
        worker.execute(() -> {
            try {
                StreamResolver.Resolved resolved =
                        StreamResolver.resolve(sslSocketFactory(), mirrorIndex);
                Log.i(TAG, "resolved stream " + (resolved.index + 1) + "/" + resolved.count
                        + ": " + resolved.url);
                handler.post(() -> {
                    if (seq != resolveSeq) {
                        return;
                    }

                    mirrorIndex = resolved.index;
                    mirrorCount = resolved.count;
                    play(resolved.url);
                });
            } catch (Exception e) {
                Log.e(TAG, "resolve failed", e);
                String detail = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                handler.post(() -> {
                    if (seq != resolveSeq) {
                        return;
                    }

                    showStatus("Не удалось получить поток\n" + detail
                            + "\nПовтор через " + (retryDelay / 1000) + " с");
                    scheduleRetry();
                });
            }
        });
    }

    private javax.net.ssl.SSLSocketFactory sslSocketFactory() {
        try {
            return TlsTrust.socketFactory(this);
        } catch (Exception e) {
            return null;
        }
    }

    private void play(String url) {
        releasePlayer();
        currentUrl = url;
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
                .build();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(StreamResolver.UA)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true);

        // Let ExoPlayer fall back to software decoding when the box's hardware
        // decoder rejects the stream; cheap SoCs often advertise profiles they
        // cannot actually decode.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .build();

        player.addListener(this);
        player.addAnalyticsListener(diagnostics);
        player.setVideoSurfaceView(surface);
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        MediaSource source = new HlsMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(url));
        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();

        lastProgressMs = System.currentTimeMillis();
        lastPosition = -1L;
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (player == null) {
                return;
            }
            long position = player.getCurrentPosition();
            if (position != lastPosition) {
                lastPosition = position;
                lastProgressMs = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastProgressMs > RETRY_MAX_MS) {
                scheduleRetry();
                return;
            }
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void scheduleRetry() {
        if (!started) {
            return;
        }
        handler.removeCallbacks(watchdog);
        releasePlayer();
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, retryDelay);
        retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS);
    }

    /**
     * Stable instance so {@code removeCallbacks} can actually cancel it; a
     * method reference would allocate a fresh object per call and never match.
     */
    private final Runnable retryRunnable = this::start;

    private void releasePlayer() {
        handler.removeCallbacks(watchdog);
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> {
                    if (player == null && started) {
                        handler.removeCallbacks(retryRunnable);
                        retryDelay = RETRY_MIN_MS;
                        start();
                    }
                });
            }
        });
    }

    // Player.Listener

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) {
            retryDelay = RETRY_MIN_MS;
            lastProgressMs = System.currentTimeMillis();
        } else if (state == Player.STATE_ENDED) {
            scheduleRetry();
        }
    }

    /** Fires only once real pixels reach the surface, so the status hides on visible video. */
    @Override
    public void onRenderedFirstFrame() {
        playing = true;
        hideStatus();
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            float pixelRatio = videoSize.pixelWidthHeightRatio;
            if (pixelRatio <= 0f) {
                pixelRatio = 1f;
            }
            surface.setAspect((float) videoSize.width * pixelRatio / videoSize.height);
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e(TAG, "playback error " + error.getErrorCodeName(), error);
        String cause = error.getCause() != null
                ? error.getCause().getClass().getSimpleName()
                + (error.getCause().getMessage() != null
                        ? ": " + error.getCause().getMessage() : "")
                : "";
        showStatus("Ошибка воспроизведения\n"
                + error.getErrorCodeName()
                + (cause.isEmpty() ? "" : "\n" + cause)
                + "\nПовтор через " + (retryDelay / 1000) + " с");
        scheduleRetry();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        worker.shutdownNow();
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /** SurfaceView that letterboxes the video to its aspect ratio inside a black frame. */
    private static final class AspectSurfaceView extends SurfaceView {

        private float aspect;

        AspectSurfaceView(Context context) {
            super(context);
        }

        void setAspect(float value) {
            if (value > 0f && Math.abs(value - aspect) > 0.001f) {
                aspect = value;
                requestLayout();
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            if (aspect > 0f && width > 0 && height > 0) {
                float viewAspect = (float) width / height;
                if (aspect > viewAspect) {
                    height = Math.round(width / aspect);
                } else {
                    width = Math.round(height * aspect);
                }
            }
            setMeasuredDimension(width, height);
        }
    }
}
