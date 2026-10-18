package com.aiassistant.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrinterActivity extends AppCompatActivity {

    private TextView tvStatus, tvTranscript, tvResponse, tvPrinter;
    private Button btnMic, btnConnect;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothSocket btSocket;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL  = AudioFormat.CHANNEL_IN_MONO;
    private static final int FORMAT   = AudioFormat.ENCODING_PCM_16BIT;
    private static final UUID SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer);

        tvStatus     = findViewById(R.id.tvStatus);
        tvTranscript = findViewById(R.id.tvTranscript);
        tvResponse   = findViewById(R.id.tvResponse);
        tvPrinter    = findViewById(R.id.tvPrinter);
        btnMic       = findViewById(R.id.btnMic);
        btnConnect   = findViewById(R.id.btnConnect);

        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.RECORD_AUDIO,
                         Manifest.permission.BLUETOOTH_CONNECT,
                         Manifest.permission.BLUETOOTH_SCAN}, 1);

        btnConnect.setOnClickListener(v -> connectPrinter());
        btnMic.setOnClickListener(v -> {
            if (!isRecording) startRecording();
            else stopRecording();
        });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void connectPrinter() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null || !bt.isEnabled()) {
            toast("Включите Bluetooth"); return;
        }
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            toast("Нет разрешения Bluetooth"); return;
        }

        Set<BluetoothDevice> paired = bt.getBondedDevices();
        BluetoothDevice target = null;
        for (BluetoothDevice d : paired) {
            String name = d.getName() != null ? d.getName().toLowerCase() : "";
            if (name.contains("print") || name.contains("cat")
                    || name.contains("peripage") || name.contains("mtp")) {
                target = d; break;
            }
        }

        if (target == null) {
            tvPrinter.setText("🖨 Принтер не найден среди спаренных устройств");
            return;
        }

        final BluetoothDevice device = target;
        tvPrinter.setText("🔗 Подключаюсь к " + device.getName() + "...");

        executor.execute(() -> {
            try {
                if (btSocket != null) btSocket.close();
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                btSocket.connect();
                handler.post(() ->
                    tvPrinter.setText("🖨 " + device.getName() + " — подключён"));
            } catch (Exception e) {
                handler.post(() ->
                    tvPrinter.setText("❌ Ошибка подключения: " + e.getMessage()));
            }
        });
    }

    private void startRecording() {
        SharedPreferences prefs = getSharedPreferences("ai_prefs", MODE_PRIVATE);
        if (prefs.getString("assembly_key", "").isEmpty()
                || prefs.getString("groq_key", "").isEmpty()) {
            toast("Укажите API ключи в настройках");
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, FORMAT, bufSize * 4);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            saveError("AudioRecord не инициализирован"); return;
        }

        audioRecord.startRecording();
        isRecording = true;
        btnMic.setText("⏹");
        tvStatus.setText("Слушаю...");
        tvStatus.setTextColor(getColor(R.color.status_listening));

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
                this::saveError,
                answer -> {
                    if (answer.toLowerCase().contains("напечатай")
                            || prefs.getBoolean("auto_print", false)) {
                        printText(answer);
                    }
                },
                getColor(R.color.status_idle),
                getColor(R.color.status_printing));
        });
    }

    void printText(String text) {
        if (btSocket == null || !btSocket.isConnected()) {
            handler.post(() -> toast("Принтер не подключён")); return;
        }
        executor.execute(() -> {
            try {
                OutputStream out = btSocket.getOutputStream();
                out.write(new byte[]{0x1B, 0x40});
                out.write(new byte[]{0x1B, 0x61, 0x00});
                out.write(("AI: " + text + "\n\n").getBytes("UTF-8"));
                out.write(new byte[]{0x0A, 0x0A, 0x0A});
                out.write(new byte[]{0x1D, 0x56, 0x42, 0x00});
                out.flush();
                handler.post(() -> {
                    tvStatus.setText("✅ Напечатано!");
                    tvStatus.setTextColor(getColor(R.color.status_idle));
                });
            } catch (Exception e) {
                saveError("Print: " + e.getMessage());
                handler.post(() -> tvStatus.setText("❌ Ошибка печати"));
            }
        });
    }

    private void stopRecording() {
        isRecording = false;
        btnMic.setText("🎤");
        if (audioRecord != null) {
            audioRecord.stop(); audioRecord.release(); audioRecord = null;
        }
    }

    private void saveError(String e) {
        getSharedPreferences("ai_prefs", MODE_PRIVATE)
            .edit().putString("last_error", e).apply();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}
    }
}
