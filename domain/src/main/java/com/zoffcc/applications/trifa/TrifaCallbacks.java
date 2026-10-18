// SPDX-License-Identifier: GPL-3.0-only
//
// Мост к готовой нативной библиотеке toxcore проекта TRIfA
// (https://github.com/zoff99/ToxAndroidRefImpl, libjni-c-toxcore.so).
// Библиотека жёстко привязана к имени класса com.zoffcc.applications.trifa.MainActivity:
// JNI-функции называются Java_com_zoffcc_applications_trifa_MainActivity_*,
// а из C вызываются статические методы этого класса по точным сигнатурам.
// Поэтому имя класса, имена и сигнатуры методов МЕНЯТЬ НЕЛЬЗЯ.
// Это НЕ Android Activity - просто Java-класс.

package com.zoffcc.applications.trifa;

/**
 * Приёмник событий от нативного toxcore. Номера друзей/файлов - беззнаковые uint32 в long.
 * Реализация - в домене (ToxWrapper). Вызовы приходят из потока tox_iterate
 * (события ядра) или из нативных AV-потоков (звонки).
 */
public abstract class TrifaCallbacks {
    public void selfConnectionStatus(int connection) {}
    public void friendName(long friend, String name) {}
    public void friendStatusMessage(long friend, String message) {}
    public void friendLosslessPacket(long friend, byte[] data) {}
    public void friendStatus(long friend, int status) {}
    public void friendConnectionStatus(long friend, int connection) {}
    public void friendTyping(long friend, boolean typing) {}
    public void friendReadReceipt(long friend, long messageId) {}
    public void friendRequest(String publicKeyHex, String message) {}
    public void friendMessage(long friend, int type, String message) {}
    public void fileRecvControl(long friend, long file, int control) {}
    public void fileChunkRequest(long friend, long file, long position, long length) {}
    public void fileRecv(long friend, long file, int kind, long size, String name) {}
    public void fileRecvChunk(long friend, long file, long position, byte[] data) {}
    public void toxLog(int level, String file, long line, String function, String message) {}
    public void avCall(long friend, boolean audio, boolean video) {}
    public void avCallState(long friend, int state) {}
    public void avBitRate(long friend, long audioBitRate, long videoBitRate) {}
    // ---- группы и каналы ----
    public void groupMessage(long group, long peer, int type, String message) {}
    public void groupInvite(long friend, byte[] inviteData, String groupName) {}
    public void groupPeerJoin(long group, long peer) {}
    public void groupPeerExit(long group, long peer) {}
    public void groupSelfJoin(long group) {}
    public void groupCustomPacket(long group, long peer, byte[] data) {}
    public void groupPeerName(long group, long peer) {}

    /** Кадр видео собеседника лежит в буфере, переданном в set_JNI_video_buffer() (Y, U, V с учётом stride). */
    public void avVideoFrame(long friend, long width, long height, long yStride, long uStride, long vStride) {}
    /** PCM лежит в буфере, переданном в set_JNI_audio_buffer2(). */
    public void avAudioFrame(long friend, long sampleCount, int channels, long samplingRate) {}
}
