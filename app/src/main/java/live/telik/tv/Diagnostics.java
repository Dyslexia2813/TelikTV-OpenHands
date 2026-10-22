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
    private volatile long droppedFrames;
    private volatile long droppedTimeMs;
    private volatile long bandwidthEstimate;
    private volatile long segmentBitrate;
    private volatile long throughputBytesPerSecond;
    private volatile int videoWidth;
    private volatile int videoHeight;

    @Override
    public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                          long initializedTimestampMs, long initializationDurationMs) {
        this.decoderName = decoderName;
    }

    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
        this.droppedFrames = droppedFrames;
        this.droppedTimeMs = elapsedMs;
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

        sb.append("Пропуск:  ").append(droppedFrames).append(" кадр(ов), ")
                .append(droppedTimeMs).append(" мс\n");

        if (bandwidthEstimate > 0) {
            sb.append("Сеть:     ").append(bandwidthEstimate / 1000).append(" kbps\n");
        }
        if (throughputBytesPerSecond > 0) {
            sb.append("Загрузка: ").append(throughputBytesPerSecond * 8 / 1000).append(" kbps\n");
        }

        sb.append("Буфер:    ").append(seconds(player.getBufferedPosition()
                - player.getCurrentPosition())).append(" с\n");
        sb.append("Позиция:  ").append(clock(player.getCurrentPosition())).append('\n');
        sb.append("Состояние: ").append(stateName(player.getPlaybackState()))
                .append(player.isPlaying() ? ", играет" : ", пауза").append('\n');
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
