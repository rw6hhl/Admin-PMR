package com.pmr.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/* Звуковой движок Android Link PMR V1.13.
 *
 * Изменения V1.13 (V5.1.1):
 *   - приём возвращён к архитектуре V4.4: кольцевой буфер ringBuf[100],
 *     отдельный поток playLoop, защита от переполнения (droppedCount).
 *   - передача оставлена как в V1.12: без усиления, pkt[2..3]=Priznak_pmr,
 *     два пакета (324/326), sendVoice отправляет только main.
 *   - приём использует один AudioTrack, пересоздаваемый при смене client/rate.
 *   - передача использует AudioRecord с параметрами V4.2.
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

    /* Передача — как в V4.2. */
    private static final int    BUF_ELEMENTS  = 320;
    private static final int    BYTES_PER_ELEM = 2;

    private static final double RMS_DIVISOR = 25.0;

    /* Усиление приёма — как в V4.2. */
    private static final int    onUsilDin     = 1;
    private static final double DinUsildouble = 0.4;

    /* Громкость воспроизведения. */
    private static final float VOLUME_BOOST = 1.7f;

    /* Кольцевой буфер — как в V4.4. */
    private static final int RING_SIZE = 100;

    private final Context appCtx;
    private final PmrSocket pmrSocket;
    private final G711Ua g711 = new G711Ua();

    /* ====== ПРИЁМ (кольцевой буфер) ====== */
    private final byte[][] ringBuf = new byte[RING_SIZE][];
    private final int[] ringClient = new int[RING_SIZE];
    private final int[] ringRate = new int[RING_SIZE];
    private volatile int writeIdx = 0;
    private volatile int readIdx = 0;
    private volatile int ringCount = 0;
    private final Object ringLock = new Object();

    private volatile int activeClient = -1;

    private Thread playThread = null;
    private volatile boolean isPlaying = false;
    private AudioTrack track = null;
    private int trackClient = -1;
    private int trackRate = 0;

    private volatile boolean diagEnabled = true;
    private volatile int lastRxRms = 0;

    private int totalPlayed = 0;
    private int underrunCount = 0;
    private int droppedCount = 0;

    /* ====== ПЕРЕДАЧА ====== */
    private AudioRecord recorder;
    private volatile boolean isRecording = false;
    private Thread recThread;
    private volatile int rej = 22;

    public AudioEngine(Context ctx, PmrSocket sock) {
        this.appCtx = ctx;
        this.pmrSocket = sock;
    }

    public void setRej(int r) { this.rej = r; }
    public int  getRej()      { return rej; }
    public boolean isRecording() { return isRecording; }
    public boolean isPlaying()   { return isPlaying; }
    public int getLastRxRms()    { return lastRxRms; }
    public boolean isDuplex()    { return true; }

    /* ============================ ПРИЁМ ============================ */

    public void startPlaying() {
        if (isPlaying) return;

        SharedPreferences sp = appCtx.getSharedPreferences(
                PasswordActivity.PREFS, Context.MODE_PRIVATE);
        diagEnabled = sp.getBoolean(PasswordActivity.KEY_DIAG_ENABLED,
                PasswordActivity.DEFAULT_DIAG_ENABLED);

        logCurrentAudioDevice("AudioEngine");
        AppLog.add("AudioEngine: использование " +
                (isUsbPresent() ? "USB-аудио" : "встроенного динамика"));

        running_play = true;
        isPlaying = true;
        playThread = new Thread(this::playLoop, "pmr-play");
        playThread.start();

        AppLog.add("AudioEngine: startPlaying, ringSize=" + RING_SIZE);
    }

    private volatile boolean running_play = false;

    public void stopPlaying() {
        running_play = false;
        isPlaying = false;
        releaseTrack();
        playThread = null;
    }

    private void releaseTrack() {
        if (track != null) {
            try {
                track.flush();
                track.stop();
                track.release();
            } catch (Exception ignored) {}
            track = null;
        }
        trackClient = -1;
        trackRate = 0;
    }

    private void ensureTrack(int client, int rate) {
        if (track != null && trackClient == client && trackRate == rate) {
            return;
        }
        releaseTrack();
        int minBuf = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = 2560;   // 80 мс при 16 кГц — как в V4.4
        if (bufSize < minBuf) bufSize = minBuf;
        track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
                AudioTrack.MODE_STREAM);
        try { track.setVolume(VOLUME_BOOST); } catch (Exception ignored) {}
        track.play();
        trackClient = client;
        trackRate = rate;
        AppLog.add("AudioEngine: created AudioTrack client=" + client
                + ", rate=" + rate + ", minBuf=" + minBuf
                + ", bufSize=" + bufSize);
    }

    private void playLoop() {
        byte[] shortSilence = new byte[320];   // BUF_FRAMES / 8
        int startThreshold = 2;
        int currentClient = -1;
        int currentRate = 0;

        int diagTick = 0;
        int checkTick = 0;

        while (running_play) {
            int count;
            synchronized (ringLock) { count = ringCount; }

            if (currentClient == -1) {
                if (count < startThreshold) {
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    checkTick++;
                    if (diagEnabled && checkTick >= 200) {
                        AppLog.add("AudioEngine: waiting, ringCount=" + count);
                        checkTick = 0;
                    }
                    continue;
                }
                synchronized (ringLock) {
                    if (ringCount > 0) {
                        currentClient = ringClient[readIdx];
                        currentRate = ringRate[readIdx];
                    }
                }
                ensureTrack(currentClient, currentRate);
            }

            byte[] pcm = null;
            int pcmClient = -1;
            int pcmRate = 0;
            synchronized (ringLock) {
                if (ringCount > 0) {
                    pcm = ringBuf[readIdx];
                    pcmClient = ringClient[readIdx];
                    pcmRate = ringRate[readIdx];
                    ringBuf[readIdx] = null;
                    readIdx = (readIdx + 1) % RING_SIZE;
                    ringCount--;
                }
            }

            if (pcm != null) {
                if (pcmClient != currentClient || pcmRate != currentRate) {
                    currentClient = pcmClient;
                    currentRate = pcmRate;
                    ensureTrack(currentClient, currentRate);
                }
                try {
                    track.write(pcm, 0, pcm.length);
                    totalPlayed++;
                } catch (Exception e) {
                    droppedCount++;
                    if (diagEnabled) AppLog.add("AudioEngine: write fail=" + e);
                }
            } else {
                if (track != null) {
                    try {
                        track.write(shortSilence, 0, shortSilence.length);
                        underrunCount++;
                    } catch (Exception ignored) {}
                }
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }

            if (diagEnabled) {
                diagTick++;
                if (diagTick >= 100) {
                    int state = 0, head = 0, buf = 0;
                    if (track != null) {
                        try {
                            state = track.getPlayState();
                            head = track.getPlaybackHeadPosition();
                            buf = track.getBufferSizeInFrames();
                        } catch (Exception ignored) {}
                    }
                    int lag = (buf > 0 && head > 0) ? (buf - head) / 16 : 0;
                    int rc;
                    synchronized (ringLock) { rc = ringCount; }
                    AppLog.add("AudioEngine: client=" + currentClient
                            + ", state=" + state
                            + ", head=" + head
                            + ", bufSize=" + buf
                            + ", lag=" + lag + "ms"
                            + ", ring=" + rc
                            + ", underrun=" + underrunCount
                            + ", dropped=" + droppedCount
                            + ", played=" + totalPlayed);
                    diagTick = 0;
                }
            }
        }

        releaseTrack();
    }

    private void push(byte[] pcm, int client, int rate) {
        synchronized (ringLock) {
            if (ringCount >= RING_SIZE) {
                ringBuf[readIdx] = null;
                readIdx = (readIdx + 1) % RING_SIZE;
                ringCount--;
                droppedCount++;
            }
            ringBuf[writeIdx] = pcm;
            ringClient[writeIdx] = client;
            ringRate[writeIdx] = rate;
            writeIdx = (writeIdx + 1) % RING_SIZE;
            ringCount++;
            activeClient = client;
        }
    }

    private boolean isUsbPresent() {
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devs) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_USB_DEVICE
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET
                    || t == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                return true;
            }
        }
        return false;
    }

    private void logCurrentAudioDevice(String tag) {
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devs) {
            CharSequence pn = d.getProductName();
            String name = (pn != null) ? pn.toString() : "?";
            AppLog.add(tag + ": output device type=" + d.getType()
                    + ", name=" + name);
        }
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
        if (!isPlaying) return;
        byte[] pcm = new byte[640];
        g711.decode(buf, 4, 320, pcm);
        updateRxRms(pcm, 640);
        push(pcm, client, SAMPLE_RATE_16K);
    }

    public void playG711_8k(int client, byte[] buf, int len) {
        if (!isPlaying) return;
        byte[] pcm = new byte[320];
        g711.decode(buf, 4, 160, pcm);
        updateRxRms(pcm, 320);
        push(pcm, client + 20, SAMPLE_RATE_8K);
    }

    public void playPCM16_16k(int client, byte[] buf, int len) {
        if (!isPlaying) return;
        byte[] pcm = new byte[640];
        System.arraycopy(buf, 4, pcm, 0, 640);
        updateRxRms(pcm, 640);
        push(pcm, client, SAMPLE_RATE_16K);
    }

    public void playPCM16_8k(int client, byte[] buf, int len) {
        if (!isPlaying) return;
        byte[] pcm = new byte[320];
        System.arraycopy(buf, 4, pcm, 0, 320);
        updateRxRms(pcm, 320);
        push(pcm, client + 20, SAMPLE_RATE_8K);
    }

    public void playPCM8_16k(int client, byte[] buf, int len) {
        if (!isPlaying) return;
        short[] pcm = new short[320];
        for (int i = 4; i < 324; i++) {
            pcm[i - 4] = (short) (buf[i] * 256);
        }
        byte[] out = short2byte(pcm);
        updateRxRms(out, 640);
        push(out, client, SAMPLE_RATE_16K);
    }

    public void playPCM8_8k(int client, byte[] buf, int len) {
        if (!isPlaying) return;
        short[] pcm = new short[160];
        for (int i = 4; i < 164; i++) {
            pcm[i - 4] = (short) (buf[i] * 256);
        }
        byte[] out = short2byte(pcm);
        updateRxRms(out, 320);
        push(out, client + 20, SAMPLE_RATE_8K);
    }

    public void playall() { /* заглушка — совместимость с MainActivity V4.0 */ }
    public void pauseall() { /* заглушка — совместимость с MainActivity V4.0 */ }

    /* ============================ ПЕРЕДАЧА ============================ */

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

            int secret = (pmrSocket != null) ? pmrSocket.getKanalSecretInstance() : 0;
            byte[] packet = new byte[6 + payloadLen];
            packet[0] = (byte) cmd;
            packet[1] = 0;
            packet[2] = (byte) (PmrSocket.Priznak_pmr & 0xFF);
            packet[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 0xFF);
            packet[4] = (byte) (secret & 0xFF);
            packet[5] = (byte) ((secret >> 8) & 0xFF);
            System.arraycopy(payload, 0, packet, 6, payloadLen);

            byte[] mainPacket = new byte[4 + payloadLen];
            mainPacket[0] = (byte) cmd;
            mainPacket[1] = 0;
            mainPacket[2] = (byte) (PmrSocket.Priznak_pmr & 0xFF);
            mainPacket[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 0xFF);
            System.arraycopy(payload, 0, mainPacket, 4, payloadLen);

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