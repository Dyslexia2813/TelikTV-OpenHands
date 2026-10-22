package live.telik.tv;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import java.util.Locale;

/**
 * Collects playback facts and renders them as on-screen text.
 *
 * <p>This exists because the box is attached to a TV, not a workstation, so
 * {@code adb logcat} is not an option. Everything that would otherwise be read
 * from logcat is shown here instead, on the remote's info button.
 *
 * <p>Only the callbacks that are actually needed are overridden; the rest of
 * {@link AnalyticsListener} keeps its empty default implementations.
 */
final class Diagnostics implements AnalyticsListener {

    private volatile String decoderName;
    private volatile long totalDroppedFrames;
    private volatile long lastDropTimeMs;
    private volatile long bandwidthEstimate;
    private volatile long segmentBitrate;
    private volatile long throughputBytesPerSecond;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile int restarts;

    @Override
    public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                          long initializedTimestampMs, long initializationDurationMs) {
        this.decoderName = decoderName;
    }

    /**
     * Counters are cumulative across the whole session, but the numbers in this
     * callback are not: ExoPlayer resets its internal counter after every
     * notification, so the value has to be summed rather than stored. The
     * {@code elapsedMs} argument is deliberately ignored — it measures time
     * since the counter was last reset, not the duration of the stall, and
     * reading it as a stall length is misleading.
     */
    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
        totalDroppedFrames += droppedFrames;
        lastDropTimeMs = System.currentTimeMillis();
    }

    /** Called on every (re)start of playback so the panel can show churn. */
    void onPlayerRestart() {
        restarts++;
    }

    @Override
    public void onBandwidthEstimate(EventTime eventTime, int totalBytesLoaded,
                                    long bitrateEstimate, long totalLoadTimeMs) {
        if (bitrateEstimate > 0) {
            bandwidthEstimate = bitrateEstimate;
        }
    }

    @Override
    public void onVideoSizeChanged(EventTime eventTime, VideoSize videoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            videoWidth = videoSize.width;
            videoHeight = videoSize.height;
        }
    }

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo loadEventInfo,
                                MediaLoadData mediaLoadData) {
        long durationMs = loadEventInfo.loadDurationMs;
        if (durationMs > 0) {
            throughputBytesPerSecond = loadEventInfo.bytesLoaded * 1000L / durationMs;
        }
        if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO
                && mediaLoadData.trackFormat != null
                && mediaLoadData.trackFormat.bitrate > 0) {
            segmentBitrate = mediaLoadData.trackFormat.bitrate;
        }
    }

    /** Builds the text shown in the panel, or null when there is no player yet. */
    String report(ExoPlayer player, int mirrorIndex, int mirrorCount, String url) {
        if (player == null) {
            return "Плеер не запущен";
        }
        StringBuilder sb = new StringBuilder();

        String host = url == null ? "—" : hostOf(url);
        sb.append("Поток:    ").append(mirrorIndex + 1).append('/').append(mirrorCount)
                .append("  ").append(host).append('\n');

        Format format = player.getVideoFormat();
        int width = format != null && format.width > 0 ? format.width : videoWidth;
        int height = format != null && format.height > 0 ? format.height : videoHeight;
        sb.append("Видео:    ").append(width > 0 ? width + "x" + height : "нет данных");
        if (format != null && format.frameRate > 0f) {
            sb.append(String.format(Locale.US, " @ %.3f", format.frameRate));
        }
        sb.append('\n');

        if (format != null && format.codecs != null) {
            sb.append("Кодек:    ").append(format.codecs).append('\n');
        }

        int bitrate = format != null
                ? (format.averageBitrate > 0 ? format.averageBitrate : format.bitrate)
                : 0;
        if (bitrate > 0) {
            sb.append("Битрейт:  ").append(bitrate / 1000).append(" kbps");
            if (segmentBitrate > 0 && segmentBitrate != bitrate) {
                sb.append(" (сегмент ").append(segmentBitrate / 1000).append(")");
            }
            sb.append('\n');
        }

        sb.append("Декодер:  ").append(decoderName != null ? decoderName : "не запущен").append('\n');
        sb.append("Тип:      ").append(decoderKind(decoderName)).append('\n');

        sb.append("Пропуск:  ").append(totalDroppedFrames).append(" кадр(ов) за сессию");
        if (lastDropTimeMs > 0) {
            sb.append(", последний ")
                    .append(seconds(System.currentTimeMillis() - lastDropTimeMs)).append(" с назад");
        }
        sb.append('\n');

        if (bandwidthEstimate > 0) {
            sb.append("Сеть:     ").append(bandwidthEstimate / 1000).append(" kbps\n");
        }
        if (throughputBytesPerSecond > 0) {
            sb.append("Загрузка: ").append(throughputBytesPerSecond * 8 / 1000).append(" kbps\n");
        }

        // getCurrentPosition() is C.TIME_UNSET for live windows, and clamping a
        // negative to zero would print a confident "00:00:00" that looks like a
        // real position, so unknowns are shown as a dash instead.
        long position = player.getCurrentPosition();
        long buffered = player.getBufferedPosition();
        sb.append("Буфер:    ");
        if (buffered >= 0 && position >= 0) {
            sb.append(seconds(buffered - position)).append(" с");
        } else {
            sb.append("—");
        }
        sb.append('\n');
        sb.append("Позиция:  ").append(position >= 0 ? clock(position) : "—");
        if (player.isCurrentMediaItemLive() && player.getCurrentLiveOffset() != C.TIME_UNSET) {
            sb.append("  (задержка эфира ").append(seconds(player.getCurrentLiveOffset()))
                    .append(" с)");
        }
        sb.append('\n');
        sb.append("Плеер:    ").append(stateName(player.getPlaybackState()))
                .append(player.isPlaying() ? ", играет" : ", пауза")
                .append(", перезапусков ").append(restarts).append('\n');
        sb.append("Дорожки:  ").append(trackSummary(player.getCurrentTracks()));

        return sb.toString();
    }

    /**
     * Codec names encode their implementation: the {@code OMX.google} and
     * {@code c2.android} prefixes are software decoders shipped with Android,
     * while vendor prefixes are hardware.
     */
    private static String decoderKind(String name) {
        if (name == null) {
            return "неизвестно";
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.startsWith("omx.google.") || lower.startsWith("c2.android.")
                || lower.startsWith("c2.google.")) {
            return "программный";
        }
        return "аппаратный";
    }

    private static String trackSummary(Tracks tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return "нет";
        }
        StringBuilder sb = new StringBuilder();
        for (Tracks.Group group : tracks.getGroups()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(trackTypeName(group.getType()))
                    .append(group.isSelected() ? "+" : "-")
                    .append('(').append(group.length).append(')');
        }
        return sb.toString();
    }

    private static String trackTypeName(int type) {
        switch (type) {
            case C.TRACK_TYPE_VIDEO:
                return "видео";
            case C.TRACK_TYPE_AUDIO:
                return "аудио";
            case C.TRACK_TYPE_TEXT:
                return "текст";
            case C.TRACK_TYPE_IMAGE:
                return "изображение";
            case C.TRACK_TYPE_METADATA:
                return "метаданные";
            default:
                return "тип" + type;
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "idle";
            case Player.STATE_BUFFERING:
                return "буферизация";
            case Player.STATE_READY:
                return "готов";
            case Player.STATE_ENDED:
                return "конец";
            default:
                return String.valueOf(state);
        }
    }

    private static String hostOf(String url) {
        int start = url.indexOf("://");
        start = start < 0 ? 0 : start + 3;
        int end = url.indexOf('/', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    private static String seconds(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        return String.format(Locale.US, "%.1f", millis / 1000.0);
    }

    private static String clock(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long totalSeconds = millis / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, totalSeconds % 3600 / 60, totalSeconds % 60);
    }
}
