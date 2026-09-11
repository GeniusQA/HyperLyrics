package com.genius.hyperlyrics;

import com.genius.hyperlyrics.IAppleMusicTranslationReceiver;

oneway interface IAppleMusicLyricBridge {
    void registerTranslationReceiver(IAppleMusicTranslationReceiver receiver);
    void onSongChanged(in byte[] compressedSong);
    void onPlaybackStateChanged(boolean isPlaying);
    void onPositionChanged(long position);
    void onSeekTo(long position);
    void onReceiveText(String text);
    void onDisplayTranslationChanged(boolean isDisplayTranslation);
    void onDisplayRomaChanged(boolean isDisplayRoma);
    void requestOnlineLyricContentSource(
        long requestId,
        String songId,
        String contentType,
        String source
    );
}
