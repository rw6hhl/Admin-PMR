package com.pmr.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.util.List;

/* Звуковой движок Android Link PMR V1.9.
 *
 * Изменения V1.9:
 *   - в recLoop() pkt[2..3] = свой client (it.i из chanList по Id == Priznak_pmr),
 *     а не MyMailIndex — исправлен отвал клиента 26000 при передаче;
 *   - добавлен метод getMyClient() для поиска своего номера в канале.
 *   - всё остальное как в V1.8: усиление (onUsilDin=1, DinUsildouble=2.0,
 *     onUsilMic=1, MicUsildouble=2.0), Mic/Spk из SharedPreferences.
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

    /* Усиление приёма. */
    private static final int    onUsilDin     = 1;
    private static final double DinUsildouble = 2.0;

    /* Усиление микрофона. */
    private static final int    onUsilMic     = 1;
    private static final double MicUsildouble = 2.0;

    private final Context appCtx;
    private final PmrSocket pmrSocket;
    private final G711Ua g711 = new G711Ua();

    private AudioTrack[] tracks = new AudioTrack[40];
    private volatile boolean isPlaying = false;
    private volatile int lastRxRms = 0;

    private AudioRecord recorder;
    private volatile boolean isRecording = false;
    private Thread recThread;

    private int micGain = 70;
    private int spkGain = 70;
    private float volumeBoost = 1.4f;

    public AudioEngine(Context ctx, PmrSocket sock) {
        this.appCtx = ctx;
        this.pmrSocket = sock;
        reloadGains();
    }

    /* Чтение Mic и Spk из SharedPreferences. */
    public void reloadGains() {
        SharedPreferences sp = appCtx.getSharedPreferences(
                PasswordActivity.PREFS, Context.MODE_PRIVATE);
        micGain = sp.getInt(PasswordActivity.KEY_MIC_GAIN,
                PasswordActivity.DEFAULT_MIC_GAIN);
        spkGain = sp.getInt(PasswordActivity.KEY_SPK_GAIN,
                PasswordActivity.DEFAULT_SPK_GAIN);
        if (micGain < 0) micGain = 0;
        if (micGain > 100) micGain = 100;
        if (spkGain < 0) spkGain = 0;
        if (spkGain > 200) spkGain = 200;
        volumeBoost = (float) (spkGain / 100.0 * 2.0);
    }

    public boolean isPlaying() { return isPlaying; }
    public int getLastRxRms()  { return lastRxRms; }
    public int getMicGain()    { return micGain; }
    public int getSpkGain()    { return spkGain; }

    /* Поиск своего номера в канале по Id == Priznak_pmr.
     * Возвращает it.i, если найден; иначе 0. */
    private int getMyClient() {
        if (pmrSocket == null) return 0;
        if (PmrService.chanList == null) return 0;
        List<ChanList.Item> items = PmrService.chanList.snapshot();
        for (ChanList.Item it : items) {
            if (it.Id == PmrSocket.Priznak_pmr) return it.i;
        }
        return 0;
    }

    public void startPlaying() {
        if (isPlaying) return;

        reloadGains();
        logCurrentAudioDevice("AudioEngine");
        AppLog.add("AudioEngine: использование " +
                (isUsbPresent() ? "USB-аудио" : "встроенного динамика"));

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
                try { tracks[i].setVolume(volumeBoost); } catch (Exception ignored) {}
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
                try { tracks[i].setVolume(volumeBoost); } catch (Exception ignored) {}
            }
        }
        isPlaying = true;
        AppLog.add("AudioEngine: startPlaying, volumeBoost=" + volumeBoost
                + ", micGain=" + micGain + ", spkGain=" + spkGain
                + ", onUsilDin=" + onUsilDin + ", DinUsildouble=" + DinUsildouble
                + ", onUsilMic=" + onUsilMic + ", MicUsildouble=" + MicUsildouble);
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

    /* Запуск записи с микрофона. */
    public void startRecording() {
        if (isRecording) return;
        reloadGains();
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_16K,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf < 1280) minBuf = 1280;
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_16K,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                AppLog.add("AudioEngine: AudioRecord не инициализирован");
                recorder = null;
                return;
            }
            recorder.startRecording();
            isRecording = true;
            AppLog.add("AudioEngine: startRecording, minBuf=" + minBuf
                    + ", micGain=" + micGain + ", MicUsildouble=" + MicUsildouble);
            recThread = new Thread(this::recLoop, "pmr-rec");
            recThread.start();
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
        AppLog.add("AudioEngine: stopRecording");
    }

    private void recLoop() {
        byte[] pcm = new byte[1280];
        byte[] ulaw = new byte[640];
        int totalSent = 0;
        while (isRecording) {
            AudioRecord r = recorder;
            if (r == null) break;
            int n = r.read(pcm, 0, pcm.length);
            if (n <= 0) continue;

            /* Усиление микрофона. */
            double usil = (onUsilMic != 0) ? MicUsildouble : 1.0;
            double micNorm = micGain / 100.0;
            double totalGain = usil * micNorm * MicUsildouble;

            for (int i = 0; i < n; i += 2) {
                short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                int v = (int) (s * totalGain);
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                pcm[i] = (byte) (v & 0xFF);
                pcm[i + 1] = (byte) ((v >> 8) & 0xFF);
            }

            int rms = calcRms(pcm, n);
            g711.encode(pcm, 0, n, ulaw);

            /* Формирование пакета cmd=22.
             * pkt[1]    = канал (MyPChannel)
             * pkt[2..3] = свой client (it.i из chanList по Id == Priznak_pmr) */
            int myClient = getMyClient();
            byte[] pkt = new byte[324];
            pkt[0] = (byte) CMD_G711_16K;
            pkt[1] = (byte) (PmrSocket.MyPChannel & 0xFF);
            pkt[2] = (byte) (myClient & 0xFF);
            pkt[3] = (byte) ((myClient >> 8) & 0xFF);
            int copy = Math.min(ulaw.length, 320);
            System.arraycopy(ulaw, 0, pkt, 4, copy);

            pmrSocket.sendVoice(pkt, null);
            totalSent++;
            if (totalSent % 50 == 0) {
                AppLog.add("AudioEngine: rec rms=" + rms
                        + ", gain=" + micGain + ", client=" + myClient
                        + ", sent=" + totalSent);
            }
        }
    }

    private int calcRms(byte[] pcm, int len) {
        long sum = 0;
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            sum += (long) s * s;
        }
        if (n == 0) return 0;
        double mean = (double) sum / n;
        double rms = Math.sqrt(mean);
        double usil = (onUsilDin != 0) ? DinUsildouble : 1.0;
        int scaled = (int) ((rms * usil) / RMS_DIVISOR);
        if (scaled > 1000) scaled = 1000;
        return scaled;
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

    private boolean isValid16(int client) {
        return client >= 0 && client < SLOTS_PER_FORMAT;
    }

    private boolean isValid8(int client) {
        return client >= 0 && client < SLOTS_PER_FORMAT;
    }

    private void updateRxRms(byte[] pcm, int len) {
        lastRxRms = calcRms(pcm, len);
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