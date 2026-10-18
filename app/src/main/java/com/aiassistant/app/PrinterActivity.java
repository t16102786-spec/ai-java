package com.aiassistant.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PrinterActivity extends AppCompatActivity {

    private TextView tvStatus, tvTranscript, tvResponse, tvPrinter;
    private Button btnMic, btnConnect;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGatt btGatt;
    private BluetoothGattCharacteristic printChar;
    private boolean isConnected = false;

    // Cat Printer BLE UUIDs
    private static final List<UUID> SERVICE_UUIDS = Arrays.asList(
        UUID.fromString("0000AE00-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000AB00-0000-1000-8000-00805F9B34FB")
    );
    private static final List<UUID> CHAR_UUIDS = Arrays.asList(
        UUID.fromString("0000AE01-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000AB01-0000-1000-8000-00805F9B34FB")
    );

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

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

        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        }, 1);

        btnConnect.setOnClickListener(v -> scanAndConnect());
        btnMic.setOnClickListener(v -> {
            if (!isConnected) { toast("Сначала подключи принтер"); return; }
            printText(tvResponse.getText().toString());
        });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void scanAndConnect() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            toast("Нет разрешения Bluetooth"); return;
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bt = bm.getAdapter();
        if (bt == null || !bt.isEnabled()) { toast("Включите Bluetooth"); return; }

        tvPrinter.setText("🔍 Сканирую BLE устройства...");
        scanner = bt.getBluetoothLeScanner();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                String name = result.getDevice().getName();
                if (name == null) return;
                String n = name.toLowerCase();
                if (n.contains("print") || n.contains("cat") || n.contains("mx")
                        || n.contains("gb") || n.contains("fun") || n.contains("peripage")) {
                    stopScan();
                    handler.post(() -> tvPrinter.setText("📡 Найден: " + name + ", подключаюсь..."));
                    connectGatt(result.getDevice());
                }
            }
        };

        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            stopScan();
            if (!isConnected)
                handler.post(() -> tvPrinter.setText("❌ Принтер не найден"));
        }, 10000);
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                scanner.stopScan(scanCallback);
            }
        }
    }

    private void connectGatt(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;

        btGatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true;
                    gatt.discoverServices();
                    handler.post(() -> tvPrinter.setText("✅ Подключён"));
                } else {
                    isConnected = false;
                    printChar = null;
                    handler.post(() -> tvPrinter.setText("❌ Отключён"));
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                for (BluetoothGattService service : gatt.getServices()) {
                    if (SERVICE_UUIDS.contains(service.getUuid())) {
                        for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                            if (CHAR_UUIDS.contains(c.getUuid())) {
                                printChar = c;
                                handler.post(() -> tvPrinter.setText("🖨 Готов к печати"));
                                return;
                            }
                        }
                    }
                }
                handler.post(() -> tvPrinter.setText("⚠️ Характеристика не найдена"));
            }
        });
    }

    void printText(String text) {
        if (!isConnected || printChar == null || btGatt == null) {
            toast("Принтер не готов"); return;
        }
        if (text == null || text.trim().isEmpty()) {
            toast("Нет текста для печати"); return;
        }

        executor.execute(() -> {
            try {
                // Конвертируем текст в bitmap 384px шириной
                Bitmap bmp = textToBitmap(text, 384);
                List<byte[]> packets = buildPrintPackets(bmp);

                for (byte[] packet : packets) {
                    if (ActivityCompat.checkSelfPermission(PrinterActivity.this,
                            Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                    printChar.setValue(packet);
                    btGatt.writeCharacteristic(printChar);
                    Thread.sleep(20);
                }

                handler.post(() -> {
                    tvStatus.setText("✅ Напечатано!");
                    tvStatus.setTextColor(getColor(R.color.status_idle));
                });
            } catch (Exception e) {
                handler.post(() -> tvStatus.setText("❌ Ошибка печати: " + e.getMessage()));
            }
        });
    }

    private Bitmap textToBitmap(String text, int width) {
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(24);
        paint.setAntiAlias(false);

        String[] lines = text.split("\n");
        int lineH = 30;
        int height = Math.max(lineH * lines.length + 20, 60);

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], 5, 24 + i * lineH, paint);
        }
        return bmp;
    }

    private List<byte[]> buildPrintPackets(Bitmap bmp) {
        List<byte[]> packets = new ArrayList<>();
        int width = 384;
        int bytesPerRow = width / 8;

        // Init
        packets.add(makeCmd((byte) 0xa3, new byte[]{0x00}));      // start printing
        packets.add(makeCmd((byte) 0xa4, new byte[]{50}));         // set DPI 200
        packets.add(makeCmd((byte) 0xbd, new byte[]{0x02}));       // set speed
        packets.add(makeCmd((byte) 0xaf, new byte[]{0x00, 0x35})); // set energy
        packets.add(makeCmd((byte) 0xa6, new byte[]{0xaa, (byte)0x55, 0x17, 0x38,
            0x44, 0x5f, 0x5f, 0x5f, 0x44, 0x38, 0x2c}));          // start lattice

        // Bitmap rows
        for (int y = 0; y < bmp.getHeight(); y++) {
            byte[] row = new byte[bytesPerRow];
            for (int x = 0; x < width; x++) {
                int pixel = bmp.getPixel(x, y);
                int lum = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                if (lum < 128) {
                    int byteIdx = x / 8;
                    int bitIdx = 7 - (x % 8);
                    row[byteIdx] |= (byte)(1 << bitIdx);
                }
            }
            // Reverse bits per byte (Cat Printer protocol)
            for (int i = 0; i < row.length; i++) {
                row[i] = reverseBits(row[i]);
            }
            packets.add(makeCmd((byte) 0xa2, row));
        }

        // End
        packets.add(makeCmd((byte) 0xa6, new byte[]{(byte)0xaa, (byte)0x55, 0x17, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x17}));           // end lattice
        packets.add(makeCmd((byte) 0xa0, new byte[]{0x00, 0x70})); // feed paper

        return packets;
    }

    private byte[] makeCmd(byte cmd, byte[] payload) {
        byte[] pkt = new byte[8 + payload.length];
        pkt[0] = 0x51; pkt[1] = 0x78;
        pkt[2] = cmd;
        pkt[3] = 0x00;
        pkt[4] = (byte) payload.length;
        pkt[5] = 0x00;
        System.arraycopy(payload, 0, pkt, 6, payload.length);
        pkt[6 + payload.length] = crc8(payload);
        pkt[7 + payload.length] = (byte) 0xff;
        return pkt;
    }

    private byte crc8(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xff);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) crc = (crc << 1) ^ 0x07;
                else crc <<= 1;
            }
        }
        return (byte)(crc & 0xff);
    }

    private byte reverseBits(byte b) {
        int v = b & 0xff;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>= 1;
        }
        return (byte) r;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        executor.shutdown();
        if (btGatt != null) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                btGatt.close();
            }
        }
    }
}
