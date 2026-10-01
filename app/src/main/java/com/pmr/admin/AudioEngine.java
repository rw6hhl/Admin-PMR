package com.pmr.admin;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/* Звуковой движок Admin PMR V3.2.
 *
 * Задачи:
 *   - воспроизведение входящих голосовых пакетов (G711 и PCM);
 *   - запись микрофона и отправка голоса через PmrSocket.sendRaw().
 *
 * Архитектура:
 *   - AudioTrack[20]:  индексы 0..9  — частота 16000 Гц,
 *                      индексы 10..19 — частота 8000 Гц.
 *   - AudioRecord:    один, для режима PTT.
 *   - Кодек G711Ua:   для µ-law encode/decode.
 *
 * Потоки:
 *   - поток записи (recorderThread) читает PCM с микрофона
 *     и отправляет пакеты через PmrSocket.
 */
public class AudioEngine {

    /* Коды команд — совпадают с протоколом рации. */
    public static final int CMD_PCM8_16K  = 19;   // PCM  8 бит 16 кГц,  324 байта
    public static final int CMD_PCM16_16K = 21;   // PCM 16 бит 16 кГц,  644 байта
    public static final int CMD_G711_16K  = 22;   // G711    16 кГц,     324 байта
    public static final int CMD_PCM16_8K  = 25;   // PCM 16 бит 8 кГц,   324 байта
    public static final int CMD_G711_8K   = 26;   // G711    8 кГц,      164 байта

    private static final int SAMPLE_RATE_16K = 16000;
    private static final int SAMPLE_RATE_8K  = 8000;
    private static final int BUF_ELEMENTS    = 320;   // 20 мс @ 16 кГц
    private static final int BYTES_PER_ELEM  = 2;     // 16 бит PCM

    private final Context appCtx;
    private final PmrSocket pmrSocket;
    private final G711Ua g711 = new G711Ua();

    private AudioTrack[] tracks = new AudioTrack[20];
    private AudioRecord recorder = null;
    private Thread recorderThread = null;
    private volatile boolean isRecording = false;
    private volatile boolean isPlaying = false;

    /* Режим передачи (0..6). По умолчанию 22 — G711 16 кГц. */
    private volatile int rej = 22;

    public AudioEngine(Context ctx, PmrSocket sock) {
        this.appCtx = ctx;
        this.pmrSocket = sock;
    }

    public void setRej(int r) { this.rej = r; }
    public int  getRej()      { return rej; }

    public boolean isRecording() { return isRecording; }
    public boolean isPlaying()   { return isPlaying; }

    /* ============================ ВОСПРОИЗВЕДЕНИЕ ============================ */

    public void startPlaying() {
        if (isPlaying) return;

        for (int i = 0; i < 10; i++) {
            if (tracks[i] == null) {
                tracks[i] = new AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        SAMPLE_RATE_16K,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(SAMPLE_RATE_16K,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT),
                        AudioTrack.MODE_STREAM);
            }
        }
        for (int i = 10; i < 20; i++) {
            if (tracks[i] == null) {
                tracks[i] = new AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        SAMPLE_RATE_8K,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(SAMPLE_RATE_8K,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT),
                        AudioTrack.MODE_STREAM);
            }
        }
        isPlaying = true;
    }

    public void stopPlaying() {
        isPlaying = false;
        for (int i = 0; i < 20; i++) {
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
        for (int i = 0; i < 20; i++) {
            if (tracks[i] != null && tracks[i].getState() == AudioTrack.STATE_INITIALIZED) {
                try { tracks[i].play(); } catch (Exception ignored) {}
            }
        }
    }

    public void pauseall() {
        for (int i = 0; i < 20; i++) {
            if (tracks[i] != null && tracks[i].getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    tracks[i].pause();
                    tracks[i].flush();
                } catch (Exception ignored) {}
            }
        }
    }

    /* G711 16 кГц — вызывается из PmrSocket.udpLoop() при command=22.
     * buf: 4 байта заголовка + 320 байт µ-law. */
    public void playG711_16k(int client, byte[] buf, int len) {
        if (!isPlaying || tracks[client] == null) return;
        byte[] pcm = new byte[640];
        g711.decode(buf, 4, 320, pcm);
        try {
            tracks[client].write(pcm, 0, 640);
            if (tracks[client].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client].play();
            }
        } catch (Exception ignored) {}
    }

    /* G711 8 кГц — command=26.
     * buf: 4 байта заголовка + 160 байт µ-law. */
    public void playG711_8k(int client, byte[] buf, int len) {
        if (!isPlaying || tracks[client + 10] == null) return;
        byte[] pcm = new byte[320];
        g711.decode(buf, 4, 160, pcm);
        try {
            tracks[client + 10].write(pcm, 0, 320);
            if (tracks[client + 10].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client + 10].play();
            }
        } catch (Exception ignored) {}
    }

    /* PCM 16 бит 16 кГц — command=21.
     * buf: 4 байта заголовка + 640 байт PCM. */
    public void playPCM16_16k(int client, byte[] buf, int len) {
        if (!isPlaying || tracks[client] == null) return;
        try {
            tracks[client].write(buf, 4, 640);
            if (tracks[client].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client].play();
            }
        } catch (Exception ignored) {}
    }

    /* PCM 16 бит 8 кГц — command=25.
     * buf: 4 байта заголовка + 320 байт PCM. */
    public void playPCM16_8k(int client, byte[] buf, int len) {
        if (!isPlaying || tracks[client + 10] == null) return;
        try {
            tracks[client + 10].write(buf, 4, 320);
            if (tracks[client + 10].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client + 10].play();
            }
        } catch (Exception ignored) {}
    }

    /* PCM 8 бит 16 кГц — command=19.
     * buf: 4 байта заголовка + 320 байт PCM (8 бит). */
    public void playPCM8_16k(int client, byte[] buf, int len) {
        if (!isPlaying || tracks[client] == null) return;
        short[] pcm = new short[320];
        for (int i = 4; i < 324; i++) {
            pcm[i - 4] = (short) (buf[i] * 256);
        }
        byte[] out = short2byte(pcm);
        try {
            tracks[client].write(out, 0, 640);
            if (tracks[client].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client].play();
            }
        } catch (Exception ignored) {}
    }

    /* PCM 8 бит 8 кГц — command=27.
     * buf: 4 байта заголовка + 160 байт PCM (8 бит). */
    public void playPCM8_8k(int client, byte[] buf, int len) {
        if (!isPlaying || tracks[client + 10] == null) return;
        short[] pcm = new short[160];
        for (int i = 4; i < 164; i++) {
            pcm[i - 4] = (short) (buf[i] * 256);
        }
        byte[] out = short2byte(pcm);
        try {
            tracks[client + 10].write(out, 0, 320);
            if (tracks[client + 10].getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                tracks[client + 10].play();
            }
        } catch (Exception ignored) {}
    }

    /* ============================== ЗАПИСЬ =============================== */

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

            final int rate = sampleRate;
            recorderThread = new Thread(() -> recordLoop(rate), "pmr-rec");
            recorderThread.start();

            AppLog.add("AudioEngine: запись стартовала, rate=" + sampleRate
                    + ", rej=" + rej);
        } catch (Exception e) {
            AppLog.add("AudioEngine: ошибка записи — " + e);
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
        recorderThread = null;
        AppLog.add("AudioEngine: запись остановлена");
    }

    private void recordLoop(int sampleRate) {
        byte[] pcm16 = new byte[BUF_ELEMENTS * BYTES_PER_ELEM];
        byte[] g711buf = new byte[BUF_ELEMENTS];
        byte[] packet = new byte[4 + BUF_ELEMENTS + 2]; // заголовок + данные + secret

        while (isRecording) {
            int read;
            try {
                read = recorder.read(pcm16, 0, pcm16.length);
            } catch (Exception e) {
                break;
            }
            if (read <= 0) continue;

            int cmd;
            int payloadLen;
            byte[] payload;

            if (rej == CMD_G711_16K) {
                cmd = CMD_G711_16K;
                g711.encode(pcm16, 0, read, g711buf);
                payloadLen = read / 2;    // µ-law: 1 байт на 1 sample
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

            /* Собираем пакет: [cmd][kanal][client_lo][client_hi][secret_lo][secret_hi][данные] */
            packet[0] = (byte) cmd;
            packet[1] = 0;
            packet[2] = (byte) (PmrSocket.Priznak_pmr & 0xFF);
            packet[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 0xFF);
            int secret = (pmrSocket != null) ? pmrSocket.getKanalSecretInstance() : 0;
            packet[4] = (byte) (secret & 0xFF);
            packet[5] = (byte) ((secret >> 8) & 0xFF);
            System.arraycopy(payload, 0, packet, 6, payloadLen);

            byte[] sendBuf = new byte[6 + payloadLen];
            System.arraycopy(packet, 0, sendBuf, 0, 6 + payloadLen);

            pmrSocket.sendRawPublic(sendBuf);
        }
    }

    /* ============================ УТИЛИТЫ ================================ */

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

    public boolean isDuplex() { return true; }
}