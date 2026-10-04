package com.pmr.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

/* Звуковой движок Admin PMR V4.7.
 *
 * Изменения V4.7:
 *   - добавлено усиление динамика spk_gain (0..100 → 1.0..2.0);
 *   - применяется к PCM перед write() в AudioTrack;
 *   - setVolume(1.7f) остаётся как дополнительный boost;
 *   - воспроизведение (RING_SIZE, LOW_LATENCY) не менялось.
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
    private static final float VOLUME_BOOST = 1.7f;
    private static final double RMS_DIVISOR = 25.0;

    private static final int onUsilDin = 1;
    private static final double DinUsildouble = 0.4;

    private static final int RING_SIZE = 20;

    private static final int REC_BUF_SAMPLES = 320;
    private static final int REC_BUF_BYTES   = REC_BUF_SAMPLES * 2;
    private static final int REC_G711_BYTES  = REC_BUF_SAMPLES;

    private static final int START_THRESHOLD = 2;

    private final Context appCtx;
    private final PmrSocket pmrSocket;
    private final G711Ua g711 = new G711Ua();

    private final byte[][] ringBuf = new byte[RING_SIZE][];
    private final int[] ringClient = new int[RING_SIZE];
    private final int[] ringRate = new int[RING_SIZE];
    private volatile int writeIdx = 0;
    private volatile int readIdx = 0;
    private volatile int ringCount = 0;
    private final Object ringLock = new Object();

    private volatile int activeClient = -1;

    private Thread playThread = null;
    private volatile boolean running = false;
    private AudioTrack track = null;
    private int trackClient = -1;
    private int trackRate = 0;
    private int trackMinBuf = 0;

    private volatile boolean diagEnabled = true;
    private volatile int lastRxRms = 0;
    private volatile boolean isPlaying = false;

    private int totalPlayed = 0;
    private int underrunCount = 0;
    private int droppedCount = 0;
    private int writeErrors = 0;

    private AudioRecord recorder = null;
    private Thread recordThread = null;
    private volatile boolean isRecording = false;
    private volatile int lastTxRms = 0;
    private int totalSent = 0;

    public AudioEngine(Context ctx, PmrSocket sock) {
        this.appCtx = ctx;
        this.pmrSocket = sock;
    }

    public boolean isPlaying() { return isPlaying; }
    public int getLastRxRms()  { return lastRxRms; }
    public boolean isRecording() { return isRecording; }
    public int getLastTxRms() { return lastTxRms; }

    /* ======================== ВОСПРОИЗВЕДЕНИЕ ======================== */

    public void startPlaying() {
        if (isPlaying) return;

        SharedPreferences sp = appCtx.getSharedPreferences(
                PasswordActivity.PREFS, Context.MODE_PRIVATE);
        diagEnabled = sp.getBoolean(PasswordActivity.KEY_DIAG_ENABLED,
                PasswordActivity.DEFAULT_DIAG_ENABLED);

        logCurrentAudioDevice("AudioEngine");
        AppLog.add("AudioEngine: использование " +
                (isUsbPresent() ? "USB-аудио" : "встроенного динамика"));

        running = true;
        isPlaying = true;

        playThread = new Thread(this::playLoop, "pmr-play");
        playThread.start();

        AppLog.add("AudioEngine: startPlaying, diag=" + diagEnabled
                + ", ringSize=" + RING_SIZE
                + ", lowLatency=" + isLowLatencySupported());
    }

    public void stopPlaying() {
        running = false;
        isPlaying = false;
        releaseTrack();
        playThread = null;
    }

    private void releaseTrack() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
                track.release();
            } catch (Exception ignored) {}
            track = null;
        }
        trackClient = -1;
        trackRate = 0;
        trackMinBuf = 0;
    }

    private boolean isLowLatencySupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    private void ensureTrack(int client, int rate) {
        if (track != null && trackClient == client && trackRate == rate) {
            return;
        }
        releaseTrack();

        int minBuf = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 1280;

        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        try {
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = b.build();
        } catch (Exception e) {
            AppLog.add("AudioEngine: Builder FAIL — " + e + ", fallback");
            track = new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    rate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                    AudioTrack.MODE_STREAM);
        }

        try { track.setVolume(VOLUME_BOOST); } catch (Exception ignored) {}
        track.play();

        trackClient = client;
        trackRate = rate;
        trackMinBuf = minBuf;

        AppLog.add("AudioEngine: created AudioTrack client=" + client
                + ", rate=" + rate
                + ", minBuf=" + minBuf
                + ", lowLatency=" + isLowLatencySupported()
                + ", setVolume=" + VOLUME_BOOST);
    }

    private void playLoop() {
        int currentClient = -1;
        int currentRate = 0;

        int diagTick = 0;
        int checkTick = 0;

        while (running) {
            int count;
            synchronized (ringLock) { count = ringCount; }

            if (currentClient == -1) {
                if (count < START_THRESHOLD) {
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

                /* Усиление динамика: 0..100 → 1.0..2.0. */
                SharedPreferences sp = appCtx.getSharedPreferences(
                        PasswordActivity.PREFS, Context.MODE_PRIVATE);
                int spkGain = sp.getInt(PasswordActivity.KEY_SPK_GAIN,
                        PasswordActivity.DEFAULT_SPK_GAIN);
                byte[] out = applyGain(pcm, pcm.length, spkGain);

                if (track != null) {
                    try {
                        int st = track.getPlayState();
                        if (st != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play();
                        }
                    } catch (Exception ignored) {}

                    try {
                        int written = track.write(out, 0, out.length);
                        if (written > 0) {
                            totalPlayed++;
                        } else {
                            writeErrors++;
                            if (diagEnabled && writeErrors % 100 == 1) {
                                AppLog.add("AudioEngine: write err=" + written
                                        + ", state=" + track.getPlayState());
                            }
                        }
                    } catch (Exception e) {
                        writeErrors++;
                        if (diagEnabled && writeErrors % 100 == 1) {
                            AppLog.add("AudioEngine: write exception=" + e);
                        }
                    }
                }
            } else {
                underrunCount++;
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }

            if (diagEnabled) {
                diagTick++;
                if (diagTick >= 50) {
                    int state = 0, head = 0, buf = 0;
                    if (track != null) {
                        try {
                            state = track.getPlayState();
                            head = track.getPlaybackHeadPosition();
                            buf = track.getBufferSizeInFrames();
                        } catch (Exception ignored) {}
                    }
                    int lag = (buf > 0) ? (buf - (head % buf)) / 16 : 0;
                    int rc;
                    synchronized (ringLock) { rc = ringCount; }
                    AppLog.add("AudioEngine: play client=" + currentClient
                            + ", state=" + state
                            + ", head=" + head
                            + ", bufSize=" + buf
                            + ", lag=" + lag + "ms"
                            + ", ring=" + rc
                            + ", underrun=" + underrunCount
                            + ", dropped=" + droppedCount
                            + ", writeErr=" + writeErrors
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

    /* ============================ ЗАПИСЬ ============================= */

    public void startRecording() {
        if (isRecording) return;

        SharedPreferences sp = appCtx.getSharedPreferences(
                PasswordActivity.PREFS, Context.MODE_PRIVATE);
        diagEnabled = sp.getBoolean(PasswordActivity.KEY_DIAG_ENABLED,
                PasswordActivity.DEFAULT_DIAG_ENABLED);

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_16K,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = REC_BUF_BYTES * 4;
        if (bufSize < minBuf) bufSize = minBuf;

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_16K,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
            recorder.startRecording();
            isRecording = true;
        } catch (Exception e) {
            AppLog.add("AudioEngine: AudioRecord init FAIL — " + e);
            recorder = null;
            return;
        }

        AppLog.add("AudioEngine: startRecording, minBuf=" + minBuf
                + ", bufSize=" + bufSize);

        recordThread = new Thread(this::recordLoop, "pmr-rec");
        recordThread.start();
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
        recordThread = null;
        AppLog.add("AudioEngine: stopRecording, totalSent=" + totalSent);
    }

    private void recordLoop() {
        byte[] pcm = new byte[REC_BUF_BYTES];
        byte[] g711buf = new byte[REC_G711_BYTES];
        int diagTick = 0;

        while (isRecording) {
            int read;
            try {
                read = recorder.read(pcm, 0, pcm.length);
            } catch (Exception e) {
                AppLog.add("AudioEngine: read FAIL — " + e);
                break;
            }
            if (read <= 0) continue;

            SharedPreferences sp = appCtx.getSharedPreferences(
                    PasswordActivity.PREFS, Context.MODE_PRIVATE);
            int gain = sp.getInt(PasswordActivity.KEY_MIC_GAIN,
                    PasswordActivity.DEFAULT_MIC_GAIN);
            byte[] amplified = applyGain(pcm, read, gain);

            int rms = calcRms(amplified, read);
            lastTxRms = rms;

            g711.encode(amplified, 0, read, g711buf);
            int payloadLen = read / 2;
            int secret = (pmrSocket != null) ? pmrSocket.getKanalSecretInstance() : 0;

            byte[] main = new byte[4 + payloadLen];
            main[0] = (byte) CMD_G711_16K;
            main[1] = 0;
            main[2] = (byte) (PmrSocket.Priznak_pmr & 0xFF);
            main[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 0xFF);
            System.arraycopy(g711buf, 0, main, 4, payloadLen);

            byte[] reserve = new byte[6 + payloadLen];
            reserve[0] = (byte) CMD_G711_16K;
            reserve[1] = 0;
            reserve[2] = (byte) (PmrSocket.Priznak_pmr & 0xFF);
            reserve[3] = (byte) ((PmrSocket.Priznak_pmr >> 8) & 0xFF);
            reserve[4] = (byte) (secret & 0xFF);
            reserve[5] = (byte) ((secret >> 8) & 0xFF);
            System.arraycopy(g711buf, 0, reserve, 6, payloadLen);

            if (pmrSocket != null) {
                pmrSocket.sendVoice(main, reserve);
                totalSent++;
            }

            if (diagEnabled) {
                diagTick++;
                if (diagTick >= 50) {
                    AppLog.add("AudioEngine: rec rms=" + rms
                            + ", gain=" + gain
                            + ", sent=" + totalSent);
                    diagTick = 0;
                }
            }
        }
    }

    /* 0..100 → 1.0..2.0. 0 → 1.0. */
    private byte[] applyGain(byte[] pcm, int len, int gain) {
        if (gain <= 0) return pcm;
        double g = (double) gain / 50.0;
        if (g <= 1.0) return pcm;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i += 2) {
            short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int a = (int) (s * g);
            if (a > 32767) a = 32767;
            if (a < -32768) a = -32768;
            out[i] = (byte) (a & 0xFF);
            out[i + 1] = (byte) ((a >> 8) & 0xFF);
        }
        return out;
    }

    private static int calcRms(byte[] pcm, int len) {
        long sum = 0;
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            sum += (long) s * s;
        }
        if (n == 0) return 0;
        double mean = (double) sum / n;
        double rms = Math.sqrt(mean);
        int scaled = (int) (rms / RMS_DIVISOR);
        if (scaled > 1000) scaled = 1000;
        return scaled;
    }

    private boolean isUsbPresent() {
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
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
        AudioDeviceInfo[] in = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo d : in) {
            CharSequence pn = d.getProductName();
            String name = (pn != null) ? pn.toString() : "?";
            AppLog.add(tag + ": input device type=" + d.getType()
                    + ", name=" + name);
        }
        AudioDeviceInfo[] out = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : out) {
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
        push(pcm, client + 10, SAMPLE_RATE_8K);
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
        push(pcm, client + 10, SAMPLE_RATE_8K);
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
        push(out, client + 10, SAMPLE_RATE_8K);
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
}