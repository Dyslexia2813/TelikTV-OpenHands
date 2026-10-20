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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private AspectSurfaceView surface;
    private TextView statusView;
    private ExoPlayer player;
    private PowerManager.WakeLock wakeLock;

    private long retryDelay = RETRY_MIN_MS;
    private boolean resolving;
    private boolean started;
    private long lastProgressMs;
    private long lastPosition = -1L;
    private boolean playing;

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

        setContentView(root);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telik:playback");
        wakeLock.setReferenceCounted(false);

        registerNetworkCallback();
        started = true;
        start();
    }

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
        if (resolving) {
            return;
        }
        resolving = true;
        if (!playing) {
            showStatus("Подключение к потоку…");
        }
        worker.execute(() -> {
            try {
                String url = StreamResolver.resolve(sslSocketFactory());
                Log.i(TAG, "resolved stream: " + url);
                handler.post(() -> {
                    resolving = false;
                    play(url);
                });
            } catch (Exception e) {
                Log.e(TAG, "resolve failed", e);
                String detail = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                handler.post(() -> {
                    resolving = false;
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
        handler.postDelayed(this::start, retryDelay);
        retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS);
    }

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
                        handler.removeCallbacks(MainActivity.this::start);
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
