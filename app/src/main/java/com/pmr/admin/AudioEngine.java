package com.pmr.admin;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/* Звуковой движок Android Link PMR V1.12.
 *
 * Изменения V1.12:
 *   - добавлен импорт android.media.AudioManager (был пропущен в V1.11).
 *   - всё остальное как в V1.11: без усиления микрофона, параметры передачи V4.2.
 */
public class AudioEngine {

    public static final int CMD_PCM8_16K  = 19;
    public static final int CMD_PCM16_16K = 21;
    public static final int CMD_G711_16K  = 22;
    public static final int CMD_PCM16_8K  = 25;
    public static final int CMD_G711_8K   = 26;
    public static final int CMD_PCM8_8K   = 27;

    private static final int SAMPLE_RATE_16K = 16000;
    private static final int SAMPLE_RATE_8K  = 8000;
    private static final int SLOTS_PER_FORMAT = 20;
    private static final int OFFSET_8K = 20;

    private static final double RMS_DIVISOR = 25.0;

    /* Усиление приёма — как в V4.2. */
    private static final int    onUsilDin     = 1;
    private static final double DinUsildouble = 0.4;

    /* Передача — как в V4.2. */
    private static final int    BUF_ELEMENTS  = 320;
    private static final int    BYTES_PER_ELEM = 2;

    private final Context appCtx;
    private final PmrSocket pmrSocket;
    private final G711Ua g711 = new G711Ua();

    private AudioTrack[] tracks = new AudioTrack[40];
    private volatile boolean isPlaying = false;
    private volatile int lastRxRms = 0;

    private AudioRecord recorder;
    private volatile boolean isRecording = false;
    private Thread recThread;

    /* Режим передачи — как в V4.2. */
    private volatile int rej = 22;

    public AudioEngine(Context ctx, PmrSocket sock) {
        this.appCtx = ctx;
        this.pmrSocket = sock;
    }

    public void setRej(int r) { this.rej = r; }
    public int  getRej()      { return rej; }
    public boolean isRecording() { return isRecording; }
    public boolean isPlaying()   { return isPlaying; }

    public boolean isDuplex() { return true; }

    /* ========================= ВОСПРОИЗВЕДЕНИЕ ========================= */

    public void startPlaying() {
        if (isPlaying) return;

        for (int i = 0; i < SLOTS_PER_FORMAT; i++) {
            if (tracks[i] == null) {
                tracks[i] = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE_16K,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(SAMPLE_RATE_16K,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT),
                        AudioTrack.MODE_STREAM);
                try { tracks[i].setVolume(1.7f); } catch (Exception ignored) {}
            }
        }
        for (int i = OFFSET_8K; i < OFFSET_8K + SLOTS_PER_FORMAT; i++) {
            if (tracks[i] == null) {
                tracks[i] = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE_8K,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(SAMPLE_RATE_8K,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT),
                        AudioTrack.MODE_STREAM);
                try { tracks[i].setVolume(1.7f); } catch (Exception ignored) {}
            }
        }
        isPlaying = true;
        AppLog.add("AudioEngine: startPlaying, slots=" + tracks.length);
    }

    public void stopPlaying() {
        isPlaying = false;
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null) {
                try {
                    tracks[i].flush();
                    tracks[i].stop();
                    tracks[i].release();
                } catch (Exception ignored) {}
                tracks[i] = null;
            }
        }
    }

    public void playall() {
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null && tracks[i].getState() == AudioTrack.STATE_INITIALIZED) {
                try { tracks[i].play(); } catch (Exception ignored) {}
            }
        }
    }

    public void pauseall() {
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null && tracks[i].getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    tracks[i].pause();
                    tracks[i].flush();
                } catch (Exception ignored) {}
            }
        }
    }

    private boolean isValid16(int client) {
        return client >= 0 && client < SLOTS_PER_FORMAT;
    }

    private boolean isValid8(int client) {
        return client >= 0 && client < SLOTS_PER_FORMAT;
    }

    private void updateRxRms(byte[] pcm, int len) {
        long sum = 0;
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            sum += (long) s * s;
        }
        if (n == 0) { lastRxRms = 0; return; }
        double mean = (double) sum / n;
        double rms = Math.sqrt(mean);
        double usil = (onUsilDin != 0) ? DinUsildouble : 1.0;
        int scaled = (int) ((rms * usil) / RMS_DIVISOR);
        if (scaled > 1000) scaled = 1000;
        lastRxRms = scaled;
    }

    public void playG711_16k(int client, byte[] buf, int len) {
        if (!isPlaying || !isValid16(client)) return;
        if (tracks[client] == null) return;
        byte[] pcm = new byte[640];
        g711.decode(buf, 4, 320, pcm);
        updateRxRms(pcm, 640);
        try {
            tracks[client].write(pcm, 0, 640);
            if (tracks[client].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client].play();
            }
        } catch (Exception ignored) {}
    }

    public void playG711_8k(int client, byte[] buf, int len) {
        if (!isPlaying || !isValid8(client)) return;
        int slot = client + OFFSET_8K;
        if (tracks[slot] == null) return;
        byte[] pcm = new byte[320];
        g711.decode(buf, 4, 160, pcm);
        updateRxRms(pcm, 320);
        try {
            tracks[slot].write(pcm, 0, 320);
            if (tracks[slot].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[slot].play();
            }
        } catch (Exception ignored) {}
    }

    public void playPCM16_16k(int client, byte[] buf, int len) {
        if (!isPlaying || !isValid16(client)) return;
        if (tracks[client] == null) return;
        updateRxRms(buf, 640);
        try {
            tracks[client].write(buf, 4, 640);
            if (tracks[client].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client].play();
            }
        } catch (Exception ignored) {}
    }

    public void playPCM16_8k(int client, byte[] buf, int len) {
        if (!isPlaying || !isValid8(client)) return;
        int slot = client + OFFSET_8K;
        if (tracks[slot] == null) return;
        updateRxRms(buf, 320);
        try {
            tracks[slot].write(buf, 4, 320);
            if (tracks[slot].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[slot].play();
            }
        } catch (Exception ignored) {}
    }

    public void playPCM8_16k(int client, byte[] buf, int len) {
        if (!isPlaying || !isValid16(client)) return;
        if (tracks[client] == null) return;
        short[] pcm = new short[320];
        for (int i = 4; i < 324; i++) {
            pcm[i - 4] = (short) (buf[i] * 256);
        }
        byte[] out = short2byte(pcm);
        updateRxRms(out, 640);
        try {
            tracks[client].write(out, 0, 640);
            if (tracks[client].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client].play();
            }
        } catch (Exception ignored) {}
    }

    public void playPCM8_8k(int client, byte[] buf, int len) {
        if (!isPlaying || !isValid8(client)) return;
        int slot = client + OFFSET_8K;
        if (tracks[slot] == null) return;
        short[] pcm = new short[160];
        for (int i = 4; i < 164; i++) {
            pcm[i - 4] = (short) (buf[i] * 256);
        }
        byte[] out = short2byte(pcm);
        updateRxRms(out, 320);
        try {
            tracks[slot].write(out, 0, 320);
            if (tracks[slot].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[slot].play();
            }
        } catch (Exception ignored) {}
    }

    /* ============================ ЗАПИСЬ ============================ */

    public void startRecording() {
        if (isRecording) return;

        int sampleRate;
        int bufSize;
        if (rej == CMD_G711_16K || rej == CMD_PCM16_16K || rej == CMD_PCM8_16K) {
            sampleRate = SAMPLE_RATE_16K;
            bufSize = BUF_ELEMENTS * BYTES_PER_ELEM;
        } else {
            sampleRate = SAMPLE_RATE_8K;
            bufSize = BUF_ELEMENTS * BYTES_PER_ELEM / 2;
        }

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 2);
            recorder.startRecording();
            isRecording = true;

            recThread = new Thread(() -> recLoop(sampleRate), "pmr-rec");
            recThread.start();

            AppLog.add("AudioEngine: startRecording, rate=" + sampleRate
                    + ", rej=" + rej);
        } catch (Exception e) {
            AppLog.add("AudioEngine: startRecording FAIL: " + e);
        }
    }

    public void stopRecording() {
        isRecording = false;
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception ignored) {}
            recorder = null;
        }
        recThread = null;
        AppLog.add("AudioEngine: stopRecording");
    }

    private void recLoop(int sampleRate) {
        byte[] pcm16 = new byte[BUF_ELEMENTS * BYTES_PER_ELEM];
        byte[] g711buf = new byte[BUF_ELEMENTS];
        int totalSent = 0;

        while (isRecording) {
            int read;
            try {
                read = recorder.read(pcm16, 0, pcm16.length);
            } catch (Exception e) {
                break;
            }
            if (read <= 0) continue;

            /* Никакого усиления — как в V4.2. */
            int cmd;
            int payloadLen;
            byte[] payload;

            if (rej == CMD_G711_16K) {
                cmd = CMD_G711_16K;
                g711.encode(pcm16, 0, read, g711buf);
                payloadLen = read / 2;
                payload = new byte[payloadLen];
                System.arraycopy(g711buf, 0, payload, 0, payloadLen);
            } else if (rej == CMD_PCM16_16K) {
                cmd = CMD_PCM16_16K;
                payloadLen = read;
                payload = new byte[payloadLen];
                System.arraycopy(pcm16, 0, payload, 0, payloadLen);
            } else if (rej == CMD_PCM16_8K) {
                cmd = CMD_PCM16_8K;
                payloadLen = read;
                payload = new byte[payloadLen];
                System.arraycopy(pcm16, 0, payload, 0, payloadLen);
            } else if (rej == CMD_PCM8_16K) {
                cmd = CMD_PCM8_16K;
                short[] s = byte2short(pcm16);
                payloadLen = s.length;
                payload = new byte[payloadLen];
                for (int i = 0; i < s.length; i++) {
                    payload[i] = (byte) (s[i] / 256);
                }
            } else if (rej == CMD_G711_8K) {
                cmd = CMD_G711_8K;
                g711.encode(pcm16, 0, read, g711buf);
                payloadLen = read / 2;
                payload = new byte[payloadLen];
                System.arraycopy(g711buf, 0, payload, 0, payloadLen);
            } else {
                continue;
            }

            /* Резервный пакет 326 байт (с secret). */
            int secret = (pmrSocket != null) ? pmrSocket.getKanalSecretInstance() : 0;
            byte[] packet = new byte[6 + payloadLen];
            packet[0] = (byte) cmd;
            packet[1] = 0;
            packet[2] = (byte) (PmrSocket.Priznak_pmr & 0xFF);
            packet[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 0xFF);
            packet[4] = (byte) (secret & 0xFF);
            packet[5] = (byte) ((secret >> 8) & 0xFF);
            System.arraycopy(payload, 0, packet, 6, payloadLen);

            /* Основной пакет 324 байта — без secret. */
            byte[] mainPacket = new byte[4 + payloadLen];
            mainPacket[0] = (byte) cmd;
            mainPacket[1] = 0;
            mainPacket[2] = (byte) (PmrSocket.Priznak_pmr & 0xFF);
            mainPacket[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 0xFF);
            System.arraycopy(payload, 0, mainPacket, 4, payloadLen);

            /* sendVoice отправит только mainPacket (логика V4.2). */
            if (pmrSocket != null) {
                pmrSocket.sendVoice(mainPacket, packet);
            }
            totalSent++;
            if (totalSent % 50 == 0) {
                AppLog.add("AudioEngine: rec priznak=" + PmrSocket.Priznak_pmr
                        + ", sent=" + totalSent);
            }
        }
    }

    private static byte[] short2byte(short[] sArr) {
        int length = sArr.length;
        byte[] bArr = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            int i2 = i * 2;
            bArr[i2] = (byte) (sArr[i] & 255);
            bArr[i2 + 1] = (byte) (sArr[i] >> 8);
        }
        return bArr;
    }

    private static short[] byte2short(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[i * 2] & 255) | (b[i * 2 + 1] << 8));
        }
        return s;
    }
}