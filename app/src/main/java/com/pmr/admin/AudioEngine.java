package com.pmr.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/* Звуковой движок Admin PMR V4.4.
 *
 * Архитектура — повтор C-логики из pmr.c (SetSound / GetSound):
 *   - кольцевой буфер из 100 пакетов (2 секунды);
 *   - udpLoop только кладёт пакеты в буфер, воспроизведение в отдельном потоке;
 *   - порог запуска — 2 пакета (40 мс), как Uprevdenie=1 в C;
 *   - буфер AudioTrack = 2560 байт (80 мс), как BUF_FRAMES * 4 в C;
 *   - при отсутствии данных — короткий пакет (1/8), чтобы быстро освободить буфер.
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

    /* Кольцевой буфер — 100 пакетов (как KOLSTRUCT_MAX_WAVE_OUT в C). */
    private static final int RING_SIZE = 100;

    private final Context appCtx;
    private final PmrSocket pmrSocket;
    private final G711Ua g711 = new G711Ua();

    /* Кольцевой буфер PCM 16 бит, моно. */
    private final byte[][] ringBuf = new byte[RING_SIZE][];
    private final int[] ringClient = new int[RING_SIZE];
    private final int[] ringRate = new int[RING_SIZE];
    private volatile int writeIdx = 0;
    private volatile int readIdx = 0;
    private volatile int ringCount = 0;
    private final Object ringLock = new Object();

    /* Текущий активный клиент. */
    private volatile int activeClient = -1;

    /* Поток воспроизведения. */
    private Thread playThread = null;
    private volatile boolean running = false;
    private AudioTrack track = null;
    private int trackClient = -1;
    private int trackRate = 0;

    private volatile boolean diagEnabled = true;
    private volatile int lastRxRms = 0;
    private volatile boolean isPlaying = false;

    /* Метрики. */
    private int totalPlayed = 0;
    private int underrunCount = 0;
    private int droppedCount = 0;

    public AudioEngine(Context ctx, PmrSocket sock) {
        this.appCtx = ctx;
        this.pmrSocket = sock;
    }

    public boolean isPlaying() { return isPlaying; }
    public int getLastRxRms()  { return lastRxRms; }

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
                + ", ringSize=" + RING_SIZE);
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
                track.flush();
                track.stop();
                track.release();
            } catch (Exception ignored) {}
            track = null;
        }
        trackClient = -1;
        trackRate = 0;
    }

    /* Создать AudioTrack: буфер = 2560 байт (80 мс при 16 кГц), как в C. */
    private void ensureTrack(int client, int rate) {
        if (track != null && trackClient == client && trackRate == rate) {
            return;
        }
        releaseTrack();
        int minBuf = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = 2560;   // 80 мс для 16 кГц моно 16 бит — как BUF_FRAMES*4 в C
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

    /* Поток воспроизведения — как SetSound в C. */
    private void playLoop() {
        byte[] shortSilence = new byte[320];   // BUF_FRAMES / 8
        int startThreshold = 2;                 // Uprevdenie + 1 = 2
        int currentClient = -1;
        int currentRate = 0;

        int diagTick = 0;
        int checkTick = 0;

        while (running) {
            int count;
            synchronized (ringLock) { count = ringCount; }

            /* Пока буфера меньше порога — ждём, не начинаем play. */
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
                /* Берём пакет — определяем client и rate. */
                synchronized (ringLock) {
                    if (ringCount > 0) {
                        currentClient = ringClient[readIdx];
                        currentRate = ringRate[readIdx];
                    }
                }
                ensureTrack(currentClient, currentRate);
            }

            /* Читаем пакет из буфера. */
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

            /* Пакет есть — пишем в AudioTrack. */
            if (pcm != null) {
                if (pcmClient != currentClient || pcmRate != currentRate) {
                    /* Сменился клиент — пересоздаём AudioTrack. */
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
                /* Пакетов нет — пишем короткий пакет тишины (BUF_FRAMES/8),
                 * чтобы звуковая карта быстрее освободила буфер. */
                if (track != null) {
                    try {
                        track.write(shortSilence, 0, shortSilence.length);
                        underrunCount++;
                    } catch (Exception ignored) {}
                }
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }

            /* Диагностика раз в секунду. */
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

    /* Положить пакет в кольцевой буфер. */
    private void push(byte[] pcm, int client, int rate) {
        synchronized (ringLock) {
            if (ringCount >= RING_SIZE) {
                /* Буфер переполнен — сдвигаем readIdx (теряем старый пакет). */
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