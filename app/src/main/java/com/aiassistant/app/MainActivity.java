package com.aiassistant.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    TextView tvStatus, tvTranscript, tvResponse, tvBluetooth;
    Button btnMic, btnBt, btnBranch1, btnBranch2;
    AudioRecord audioRecord;
    boolean isRecording = false;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Handler handler = new Handler(Looper.getMainLooper());
    AudioManager audioManager;
    BluetoothAdapter bluetoothAdapter;
    android.media.MediaPlayer ttsPlayer;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int PERM_REQUEST = 1;

    private int activeBranch = 1;
    private UpdateHelper updateHelper;

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkBluetooth();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus     = findViewById(R.id.tvStatus);
        tvTranscript = findViewById(R.id.tvTranscript);
        tvResponse   = findViewById(R.id.tvResponse);
        tvBluetooth  = findViewById(R.id.tvBluetooth);
        btnMic       = findViewById(R.id.btnMic);
        btnBt        = findViewById(R.id.btnBt);
        btnBranch1   = findViewById(R.id.btnBranch1);
        btnBranch2   = findViewById(R.id.btnBranch2);

        audioManager  = (AudioManager) getSystemService(AUDIO_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        updateHelper  = new UpdateHelper(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(btReceiver, filter);
        checkBluetooth();

        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.RECORD_AUDIO,
                         Manifest.permission.BLUETOOTH_CONNECT},
            PERM_REQUEST);

        updateBranchButtons();

        btnBranch1.setOnClickListener(v -> {
            activeBranch = 1;
            updateBranchButtons();
            Toast.makeText(this, "Активна: " + getBranchName(1),
                Toast.LENGTH_SHORT).show();
        });

        btnBranch2.setOnClickListener(v -> {
            activeBranch = 2;
            updateBranchButtons();
            Toast.makeText(this, "Активна: " + getBranchName(2),
                Toast.LENGTH_SHORT).show();
        });

        btnBt.setOnClickListener(v -> checkBluetooth());
        btnMic.setOnClickListener(v -> {
            if (!isRecording) startRecording();
            else stopRecording();
        });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btnPrinter).setOnClickListener(v ->
            startActivity(new Intent(this, PrinterActivity.class)));

        findViewById(R.id.btnUpdate).setOnClickListener(v ->
            updateHelper.checkForUpdate());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBranchButtons();
    }

    private void updateBranchButtons() {
        SharedPreferences prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE);
        btnBranch1.setText(prefs.getString("branch1_name", "Ветка 1"));
        btnBranch2.setText(prefs.getString("branch2_name", "Ветка 2"));
        btnBranch1.setAlpha(activeBranch == 1 ? 1.0f : 0.5f);
        btnBranch2.setAlpha(activeBranch == 2 ? 1.0f : 0.5f);
    }

    private String getBranchName(int branch) {
        SharedPreferences prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE);
        return prefs.getString("branch" + branch + "_name", "Ветка " + branch);
    }

    private String getActivePrompt() {
        SharedPreferences prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE);
        return prefs.getString("branch" + activeBranch + "_prompt",
            "Ты голосовой ИИ-ассистент. Отвечай кратко по-русски.");
    }

    private void checkBluetooth() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            tvBluetooth.setText("🔇 Bluetooth выключен");
            return;
        }
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            tvBluetooth.setText("🔇 Нет разрешения");
            return;
        }
        tvBluetooth.setText("🔍 Поиск...");
        bluetoothAdapter.getProfileProxy(this,
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    java.util.List<BluetoothDevice> devices =
                        proxy.getConnectedDevices();
                    if (!devices.isEmpty()) {
                        handler.post(() ->
                            tvBluetooth.setText("🎧 " + devices.get(0).getName()));
                    } else {
                        handler.post(() ->
                            tvBluetooth.setText("🔇 Наушники не подключены"));
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy);
                }
                @Override
                public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.HEADSET);
    }

    private void startRecording() {
        SharedPreferences prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE);
        String aKey = prefs.getString("assembly_key", "");
        String gKey = prefs.getString("groq_key", "");

        if (aKey.isEmpty() || gKey.isEmpty()) {
            Toast.makeText(this, "Укажите API ключи в настройках",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERM_REQUEST);
            return;
        }

        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, FORMAT, bufSize * 4);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            saveError("AudioRecord не инициализирован");
            return;
        }

        audioRecord.startRecording();
        isRecording = true;
        btnMic.setText("⏹");
        tvStatus.setText("Слушаю... [" + getBranchName(activeBranch) + "]");
        tvStatus.setTextColor(getColor(R.color.status_listening));

        final String prompt = getActivePrompt();

        executor.execute(() -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[bufSize];
            while (isRecording) {
                int r = audioRecord.read(buf, 0, buf.length);
                if (r > 0) baos.write(buf, 0, r);
            }
            handler.post(() -> {
                tvStatus.setText("Обрабатываю...");
                tvStatus.setTextColor(getColor(R.color.status_processing));
            });
            ApiHelper.transcribeAndRespond(
                pcmToWav(baos.toByteArray()), prefs,
                handler, tvStatus, tvTranscript, tvResponse,
                this::saveError, this::speak,
                getColor(R.color.status_idle),
                getColor(R.color.status_speaking),
                prompt);
        });
    }

    private void stopRecording() {
        isRecording = false;
        btnMic.setText("🎤");
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    void saveError(String e) {
        getSharedPreferences("ai_prefs", MODE_PRIVATE)
            .edit().putString("last_error", e).apply();
    }

    private byte[] pcmToWav(byte[] pcm) {
        int dl = pcm.length, tl = dl + 44;
        byte[] w = new byte[tl];
        w[0]='R'; w[1]='I'; w[2]='F'; w[3]='F'; intB(tl-8, w, 4);
        w[8]='W'; w[9]='A'; w[10]='V'; w[11]='E';
        w[12]='f'; w[13]='m'; w[14]='t'; w[15]=' '; intB(16, w, 16);
        w[20]=1; w[22]=1; intB(SAMPLE_RATE, w, 24); intB(SAMPLE_RATE*2, w, 28);
        w[32]=2; w[34]=16;
        w[36]='d'; w[37]='a'; w[38]='t'; w[39]='a'; intB(dl, w, 40);
        System.arraycopy(pcm, 0, w, 44, dl);
        return w;
    }

    private void intB(int v, byte[] b, int o) {
        b[o]=(byte)v; b[o+1]=(byte)(v>>8);
        b[o+2]=(byte)(v>>16); b[o+3]=(byte)(v>>24);
    }

    void speak(String text) {
        SharedPreferences prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE);
        String apiKey = prefs.getString("freetts_key", "").trim();
        if (apiKey.isEmpty()) {
            tvStatus.setText("Добавь FreeTTS API-ключ в настройки");
            return;
        }
        executor.execute(() ->
            TtsHelper.speak(text, apiKey, getCacheDir(), handler,
                audioManager, tvStatus, this::saveError, ttsPlayer,
                p -> { ttsPlayer = p; },
                getColor(R.color.status_idle)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        if (ttsPlayer != null) { ttsPlayer.release(); ttsPlayer = null; }
        executor.shutdown();
    }
        }
