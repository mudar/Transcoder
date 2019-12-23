package com.otaliastudios.transcoder.source;


import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.otaliastudios.transcoder.engine.TrackType;
import com.otaliastudios.transcoder.internal.Logger;

import org.jetbrains.annotations.Contract;

/**
 * A {@link DataSource} wrapper that trims source at both ends.
 */
public class TrimDataSource implements DataSource {
    private static final String TAG = "TrimDataSource";
    private static final Logger LOG = new Logger(TAG);
    @NonNull
    private MediaExtractorDataSource source;
    private long trimStartUs;
    private long trimDurationUs;
    private boolean isAudioTrackReady;
    private boolean isVideoTrackReady;

    public TrimDataSource(@NonNull MediaExtractorDataSource source, long trimStartUs, long trimEndUs) throws IllegalArgumentException {
        if (trimStartUs < 0 || trimEndUs < 0) {
            throw new IllegalArgumentException("Trim values cannot be negative.");
        }
        this.source = source;
        this.trimStartUs = trimStartUs;
        this.trimDurationUs = computeTrimDuration(source.getDurationUs(), trimStartUs, trimEndUs);
        this.isAudioTrackReady = !hasTrack(TrackType.AUDIO) || trimStartUs == 0;
        this.isVideoTrackReady = !hasTrack(TrackType.VIDEO) || trimStartUs == 0;
    }

    @Contract(pure = true)
    private static long computeTrimDuration(long duration, long trimStart, long trimEnd) throws IllegalArgumentException {
        if (trimStart + trimEnd > duration) {
            throw new IllegalArgumentException("Trim values cannot be greater than media duration.");
        }
        return duration - trimStart - trimEnd;
    }

    @Override
    public int getOrientation() {
        return source.getOrientation();
    }

    @Nullable
    @Override
    public double[] getLocation() {
        return source.getLocation();
    }

    @Override
    public long getDurationUs() {
        return trimDurationUs;
    }

    @Nullable
    @Override
    public MediaFormat getTrackFormat(@NonNull TrackType type) {
        final MediaFormat trackFormat = source.getTrackFormat(type);
        if (trackFormat != null) {
            trackFormat.setLong(MediaFormat.KEY_DURATION, trimDurationUs);
        }
        return trackFormat;
    }

    private boolean hasTrack(@NonNull TrackType type) {
        return source.getTrackFormat(type) != null;
    }

    @Override
    public void selectTrack(@NonNull TrackType type) {
        source.selectTrack(type);
    }

    @Override
    public boolean canReadTrack(@NonNull TrackType type) {
        if (source.canReadTrack(type)) {
            if (isAudioTrackReady && isVideoTrackReady) {
                return true;
            }
            final MediaExtractor extractor = source.requireExtractor();
            switch (type) {
                case AUDIO:
                    if (!isAudioTrackReady) {
                        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        updateTrimValues(extractor.getSampleTime());
                        isAudioTrackReady = true;
                    }
                    return isVideoTrackReady;
                case VIDEO:
                    if (!isVideoTrackReady) {
                        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        updateTrimValues(extractor.getSampleTime());
                        isVideoTrackReady = true;
                        if (isAudioTrackReady) {
                            // Seeking a second time helps the extractor with Audio sampleTime issues
                            extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        }
                    }
                    return isAudioTrackReady;
            }
        }
        return false;
    }

    private void updateTrimValues(long timestampUs) {
        trimDurationUs += trimStartUs - timestampUs;
        trimStartUs = timestampUs;
    }

    @Override
    public void readTrack(@NonNull Chunk chunk) {
        source.readTrack(chunk);
        chunk.timestampUs -= trimStartUs;
    }

    @Override
    public long getReadUs() {
        return source.getReadUs();
    }

    @Override
    public boolean isDrained() {
        return source.isDrained();
    }

    @Override
    public void releaseTrack(@NonNull TrackType type) {
        switch (type) {
            case AUDIO:
                isAudioTrackReady = false;
                break;
            case VIDEO:
                isVideoTrackReady = false;
                break;
        }
        source.releaseTrack(type);
    }

    @Override
    public void rewind() {
        isAudioTrackReady = false;
        isVideoTrackReady = false;
        source.rewind();
    }
}
