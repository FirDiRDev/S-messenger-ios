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

import android.util.Log;

@SuppressWarnings({"unused", "checkstyle:MethodName"})
public final class MainActivity {
    private static final String TAG = "TrifaBridge";

    /** Получатель событий. Ставится из ToxWrapper до init(). */
    public static volatile TrifaCallbacks callbacks = null;

    private static volatile boolean loaded = false;

    /** Загружает libjni-c-toxcore.so. Бросает UnsatisfiedLinkError, если библиотеки нет. */
    public static synchronized void loadLibrary() {
        if (!loaded) {
            System.loadLibrary("jni-c-toxcore");
            loaded = true;
        }
    }

    public MainActivity() {}

    // ---------------- natives (сигнатуры как в TRIfA MainActivity.java) ----------------

    public native void init(String data_dir, int udp_enabled, int local_discovery_enabled, int orbot_enabled,
            String orbot_host, long orbot_port, String tox_encrypt_passphrase_hash, int enable_ipv6,
            int force_udp_only_mode, int ngc_video_bitrate, int max_quantizer, int ngc_audio_bitrate,
            int ngc_audio_sampling_rate, int ngc_audio_channel_count);

    public static native String getNativeLibGITHASH();
    public static native String jnictoxcore_version();
    public static native long tox_version_major();
    public static native long tox_version_minor();
    public static native long tox_version_patch();

    public static native void init_tox_callbacks();
    public static native void tox_iterate();
    public static native long tox_iteration_interval();
    public static native void tox_kill();
    public static native void update_savedata_file(String tox_encrypt_passphrase_hash);

    public static native String get_my_toxid();
    public static native int bootstrap_single(String ip, String key_hex, long port);
    public static native int add_tcp_relay_single(String ip, String key_hex, long port);
    public static native int tox_self_get_connection_status();

    public static native long tox_self_get_nospam();
    public static native void tox_self_set_nospam(long nospam);
    public static native String tox_self_get_name();
    public static native int tox_self_set_name(String name);
    public static native String tox_self_get_status_message();
    public static native int tox_self_set_status_message(String status_message);
    public static native void tox_self_set_status(int a_TOX_USER_STATUS);
    public static native int tox_self_set_typing(long friend_number, int typing);

    public static native long tox_friend_add(String toxid_str, String message);
    public static native long tox_friend_add_norequest(String public_key_str);
    public static native int tox_friend_delete(long friend_number);
    public static native long tox_friend_by_public_key(String friend_public_key_string);
    public static native String tox_friend_get_public_key(long friend_number);
    public static native long[] tox_self_get_friend_list();
    public static native int tox_friend_get_connection_status(long friend_number);
    public static native long tox_friend_send_message(long friendnum, int a_TOX_MESSAGE_TYPE, String message);
    public static native int tox_friend_send_lossless_packet(long friend_number, byte[] data, int data_length);

    public static native int tox_file_control(long friend_number, long file_number, int a_TOX_FILE_CONTROL);
    // Докачка: продолжить входящий файл с позиции (вызывается до принятия передачи).
    public static native int tox_file_seek(long friend_number, long file_number, long position);
    public static native long tox_file_send(long friend_number, long kind, long file_size,
            java.nio.ByteBuffer file_id_buffer, String file_name, long filename_length);
    public static native int tox_file_send_chunk(long friend_number, long file_number, long position,
            java.nio.ByteBuffer data_buffer, long data_length);

    public static native int toxav_call(long friendnum, long audio_bit_rate, long video_bit_rate);
    public static native int toxav_answer(long friendnum, long audio_bit_rate, long video_bit_rate);
    public static native int toxav_call_control(long friendnum, int a_TOXAV_CALL_CONTROL);
    public static native void set_av_call_status(int status);
    public static native void set_audio_play_volume_percent(int volume_percent);
    public static native void set_JNI_audio_buffer(java.nio.ByteBuffer audio_buffer);
    public static native void set_JNI_audio_buffer2(java.nio.ByteBuffer audio_buffer2);
    // Видео: buffer - ПРИЁМ (ядро кладёт сюда кадр собеседника), buffer2 - ОТПРАВКА (мы кладём I420).
    public static native long set_JNI_video_buffer(java.nio.ByteBuffer buffer, int frame_width_px, int frame_height_px);
    public static native void set_JNI_video_buffer2(java.nio.ByteBuffer buffer2, int frame_width_px, int frame_height_px);
    public static native int toxav_video_send_frame(long friendnum, int frame_width_px, int frame_height_px);
    public static native int toxav_bit_rate_set(long friendnum, long audio_bit_rate, long video_bit_rate);
    public static native int toxav_audio_send_frame(long friend_number, long sample_count, int channels,
            long sampling_rate);
    public static native int jni_iterate_videocall_audio(int delta_new, int want_ms_output, int channels,
            int sample_rate, int send_emtpy_buffer);

    // ---------------- группы и каналы (NGC) ----------------
    // Роли: 0 основатель, 1 модератор, 2 участник, 3 наблюдатель. Приватность: 0 публичная, 1 приватная.

    public static native long tox_group_new(int privacy_state, String group_name, String my_peer_name);
    public static native long tox_group_join(java.nio.ByteBuffer chat_id_buffer, long chat_id_length,
            String my_peer_name, String password);
    public static native long tox_group_invite_accept(long friend_number, java.nio.ByteBuffer invite_data_buffer,
            long invite_data_length, String my_peer_name, String password);
    public static native int tox_group_invite_friend(long group_number, long friend_number);
    public static native int tox_group_leave(long group_number, String part_message);
    public static native long tox_group_send_message(long group_number, int type, String message);
    public static native int tox_group_send_custom_packet(long group_number, int lossless, byte[] data, int data_length);
    public static native int tox_group_send_custom_private_packet(long group_number, long peer_id, int lossless,
            byte[] data, int data_length);
    public static native String tox_group_self_get_public_key(long group_number);
    public static native int tox_group_get_chat_id(long group_number, java.nio.ByteBuffer chat_id_buffer);
    public static native long tox_group_by_chat_id(java.nio.ByteBuffer chat_id_buffer);
    public static native long[] tox_group_get_grouplist();
    public static native String tox_group_get_name(long group_number);
    public static native int tox_group_self_get_role(long group_number);
    public static native long tox_group_self_get_peer_id(long group_number);
    public static native int tox_group_peer_get_role(long group_number, long peer_id);
    public static native int tox_group_mod_set_role(long group_number, long peer_id, int role);
    public static native String tox_group_peer_get_name(long group_number, long peer_id);
    public static native String tox_group_peer_get_public_key(long group_number, long peer_id);
    public static native long[] tox_group_get_peerlist(long group_number);
    public static native long tox_group_peer_count(long group_number);
    public static native int tox_group_is_connected(long group_number);
    /** Удалить участника (владелец/модератор). */
    public static native int tox_group_mod_kick_peer(long group_number, long peer_id);

    /** Opus-кодек групповых звонков: 48 кГц, моно, кадр 1920 сэмплов (40 мс). */
    public static native int toxav_ngc_audio_encode(byte[] pcm, int sample_count_per_frame, byte[] encoded_frame_bytes);
    public static native int toxav_ngc_audio_decode(byte[] encoded_frame_bytes, int encoded_frame_size_bytes,
            byte[] pcm_decoded);

    // ---------------- вызываются из C (имена и сигнатуры фиксированы) ----------------
    // Исключения не должны уходить обратно в C - иначе падение процесса.

    static void android_tox_callback_self_connection_status_cb_method(int a0) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.selfConnectionStatus(a0);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_self_connection_status_cb_method", t);
        }
    }

    static void android_tox_callback_friend_name_cb_method(long a0, String a1, long a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendName(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_name_cb_method", t);
        }
    }

    static void android_tox_callback_friend_status_message_cb_method(long a0, String a1, long a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendStatusMessage(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_status_message_cb_method", t);
        }
    }

    static void android_tox_callback_friend_lossless_packet_cb_method(long a0, byte[] a1, long a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendLosslessPacket(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_lossless_packet_cb_method", t);
        }
    }

    static void android_tox_callback_friend_status_cb_method(long a0, int a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendStatus(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_status_cb_method", t);
        }
    }

    static void android_tox_callback_friend_connection_status_cb_method(long a0, int a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendConnectionStatus(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_connection_status_cb_method", t);
        }
    }

    static void android_tox_callback_friend_typing_cb_method(long a0, int a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendTyping(a0, a1 != 0);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_typing_cb_method", t);
        }
    }

    static void android_tox_callback_friend_read_receipt_cb_method(long a0, long a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendReadReceipt(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_read_receipt_cb_method", t);
        }
    }

    static void android_tox_callback_friend_request_cb_method(String a0, String a1, long a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendRequest(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_request_cb_method", t);
        }
    }

    static void android_tox_callback_friend_message_cb_method(long a0, int a1, String a2, long a3, byte[] a4, long a5) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendMessage(a0, a1, a2);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_message_cb_method", t);
        }
    }

    static void android_tox_callback_friend_message_v2_cb_method(long a0, String a1, long a2, long a3, long a4, byte[] a5, long a6) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.friendMessage(a0, 0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_friend_message_v2_cb_method", t);
        }
    }

    static void android_tox_callback_friend_sync_message_v2_cb_method(long a0, long a1, long a2, byte[] a3, long a4, byte[] a5, long a6) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_friend_read_receipt_message_v2_cb_method(long a0, long a1, byte[] a2) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_file_recv_control_cb_method(long a0, long a1, int a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.fileRecvControl(a0, a1, a2);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_file_recv_control_cb_method", t);
        }
    }

    static void android_tox_callback_file_chunk_request_cb_method(long a0, long a1, long a2, long a3) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.fileChunkRequest(a0, a1, a2, a3);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_file_chunk_request_cb_method", t);
        }
    }

    static void android_tox_callback_file_recv_cb_method(long a0, long a1, int a2, long a3, String a4, long a5) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.fileRecv(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_file_recv_cb_method", t);
        }
    }

    static void android_tox_callback_file_recv_chunk_cb_method(long a0, long a1, long a2, byte[] a3, long a4) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.fileRecvChunk(a0, a1, a2, a3);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_file_recv_chunk_cb_method", t);
        }
    }

    static void android_tox_callback_conference_invite_cb_method(long a0, int a1, byte[] a2, long a3) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_conference_connected_cb_method(long a0) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_conference_message_cb_method(long a0, long a1, int a2, String a3, long a4) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_conference_title_cb_method(long a0, long a1, String a2, long a3) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_conference_peer_name_cb_method(long a0, long a1, String a2, long a3) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_conference_peer_list_changed_cb_method(long a0) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_conference_namelist_change_cb_method(long a0, long a1, int a2) {
        // не используется в StoykoMessenger
    }

    static void android_tox_log_cb_method(int a0, String a1, long a2, String a3, String a4) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.toxLog(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_log_cb_method", t);
        }
    }

    static void android_tox_callback_group_message_cb_method(long a0, long a1, int a2, String a3, long a4, long a5) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupMessage(a0, a1, a2, a3);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_message_cb_method", t);
        }
    }

    // ВНИМАНИЕ: в поставляемой libjni-c-toxcore.so (0.99.95) у этого колбэка 5 параметров -
    // сигнатура (JJILjava/lang/String;J)V. В более новых версиях библиотеки - 6. Если нужной
    // сигнатуры нет, библиотека при запуске ядра получает NoSuchMethodError: debug-сборка падает,
    // release выдаёт «не удалось создать профиль». Поэтому объявлены ОБЕ версии.
    static void android_tox_callback_group_private_message_cb_method(long a0, long a1, int a2, String a3, long a4) {
        // личные сообщения внутри группы не используются
    }

    static void android_tox_callback_group_private_message_cb_method(long a0, long a1, int a2, String a3, long a4, long a5) {
        // личные сообщения внутри группы не используются
    }

    static void android_tox_callback_group_invite_cb_method(long a0, byte[] a1, long a2, String a3) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupInvite(a0, a1, a3);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_invite_cb_method", t);
        }
    }

    static void android_tox_callback_group_peer_join_cb_method(long a0, long a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupPeerJoin(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_peer_join_cb_method", t);
        }
    }

    static void android_tox_callback_group_peer_exit_cb_method(long a0, long a1, int a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupPeerExit(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_peer_exit_cb_method", t);
        }
    }

    static void android_tox_callback_group_peer_name_cb_method(long a0, long a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupPeerName(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_peer_name_cb_method", t);
        }
    }

    static void android_tox_callback_group_moderation_cb_method(long a0, long a1, long a2, int a3) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_group_connection_status_cb_method(long a0, int a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupSelfJoin(a0);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_connection_status_cb_method", t);
        }
    }

    static void android_tox_callback_group_join_fail_cb_method(long a0, int a1) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_group_self_join_cb_method(long a0) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupSelfJoin(a0);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_self_join_cb_method", t);
        }
    }

    static void android_tox_callback_group_topic_cb_method(long a0, long a1, String a2, long a3) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_group_privacy_state_cb_method(long a0, int a1) {
        // не используется в StoykoMessenger
    }

    static void android_tox_callback_group_custom_packet_cb_method(long a0, long a1, byte[] a2, long a3) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupCustomPacket(a0, a1, a2);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_custom_packet_cb_method", t);
        }
    }

    static void android_tox_callback_group_custom_private_packet_cb_method(long a0, long a1, byte[] a2, long a3) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.groupCustomPacket(a0, a1, a2);
        } catch (Throwable t) {
            Log.e(TAG, "android_tox_callback_group_custom_private_packet_cb_method", t);
        }
    }

    static void android_tox_callback_group_mid_peer_list_changed_cb(String a0) {
        // не используется в StoykoMessenger
    }

    static void android_toxav_callback_call_cb_method(long a0, int a1, int a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.avCall(a0, a1 != 0, a2 != 0);
        } catch (Throwable t) {
            Log.e(TAG, "android_toxav_callback_call_cb_method", t);
        }
    }

    static void android_toxav_callback_video_receive_frame_cb_method(long a0, long a1, long a2, long a3, long a4, long a5) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.avVideoFrame(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            Log.e(TAG, "android_toxav_callback_video_receive_frame_cb_method", t);
        }
    }

    static void android_toxav_callback_video_receive_frame_pts_cb_method(long a0, long a1, long a2, long a3, long a4, long a5, long a6) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.avVideoFrame(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            Log.e(TAG, "android_toxav_callback_video_receive_frame_pts_cb_method", t);
        }
    }

    static void android_toxav_callback_video_receive_frame_h264_cb_method(long a0, long a1) {
        // не используется в StoykoMessenger
    }

    static void android_toxav_callback_call_state_cb_method(long a0, int a1) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.avCallState(a0, a1);
        } catch (Throwable t) {
            Log.e(TAG, "android_toxav_callback_call_state_cb_method", t);
        }
    }

    static void android_toxav_callback_bit_rate_status_cb_method(long a0, long a1, long a2) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.avBitRate(a0, a1, a2);
        } catch (Throwable t) {
            Log.e(TAG, "android_toxav_callback_bit_rate_status_cb_method", t);
        }
    }

    static void android_toxav_callback_audio_receive_frame_cb_method(long a0, long a1, int a2, long a3) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.avAudioFrame(a0, a1, a2, a3);
        } catch (Throwable t) {
            Log.e(TAG, "android_toxav_callback_audio_receive_frame_cb_method", t);
        }
    }

    static void android_toxav_callback_audio_receive_frame_pts_cb_method(long a0, long a1, int a2, long a3, long a4) {
        final TrifaCallbacks c = callbacks;
        if (c == null) return;
        try {
            c.avAudioFrame(a0, a1, a2, a3);
        } catch (Throwable t) {
            Log.e(TAG, "android_toxav_callback_audio_receive_frame_pts_cb_method", t);
        }
    }

    static void android_toxav_callback_group_audio_receive_frame_cb_method(long a0, long a1, long a2, int a3, long a4) {
        // не используется в StoykoMessenger
    }

    static void android_toxav_callback_call_comm_cb_method(long a0, long a1, long a2) {
        // не используется в StoykoMessenger
    }
}
