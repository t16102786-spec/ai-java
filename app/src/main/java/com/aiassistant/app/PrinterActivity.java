
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

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private BluetoothGatt btGatt;
    private BluetoothGattCharacteristic printChar;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private boolean isConnected = false;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_printer);

        tvStatus = findViewById(R.id.tvStatus);
        tvTranscript = findViewById(R.id.tvTranscript);
        tvResponse = findViewById(R.id.tvResponse);
        tvPrinter = findViewById(R.id.tvPrinter);
        btnMic = findViewById(R.id.btnMic);
        btnConnect = findViewById(R.id.btnConnect);

        requestPermissionsIfNeeded();

        btnConnect.setOnClickListener(v -> scanAndConnect());

        btnMic.setOnClickListener(v -> {
            if (!isConnected) {
                toast("Сначала подключи принтер");
                return;
            }
            printText(tvResponse.getText().toString());
        });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void requestPermissionsIfNeeded() {
        List<String> permissions = new ArrayList<>();

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissions.toArray(new String[0]), 1);
        }
    }

    private void scanAndConnect() {
        if (android.os.Build.VERSION.SDK_INT >= 31
                && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            toast("Разреши доступ к Bluetooth");
            requestPermissionsIfNeeded();
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) getSystemService(
                        Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            toast("Включи Bluetooth");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            toast("BLE-сканирование недоступно");
            return;
        }

        tvPrinter.setText("Поиск принтера...");

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();

                if (name == null) return;

                String n = name.toLowerCase();

                if (n.contains("print") || n.contains("cat")
                        || n.contains("mx") || n.contains("gb")
                        || n.contains("fun") || n.contains("peripage")) {
                    stopScan();
                    handler.post(() ->
                            tvPrinter.setText("Найден: " + name));
                    connectGatt(device);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                handler.post(() ->
                        tvPrinter.setText(
                                "Ошибка поиска: " + errorCode));
            }
        };

        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException e) {
            toast("Нет разрешения Bluetooth");
            return;
        }

        handler.postDelayed(() -> {
            stopScan();
            if (!isConnected) {
                tvPrinter.setText("Принтер не найден");
            }
        }, 10000);
    }

    private void stopScan() {
        if (scanner == null || scanCallback == null) return;

        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
        }

        scanCallback = null;
    }

    private void connectGatt(BluetoothDevice device) {
        if (android.os.Build.VERSION.SDK_INT >= 31
                && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded();
            return;
        }

        try {
            btGatt = device.connectGatt(this, false,
                    new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(
                        BluetoothGatt gatt, int status, int state) {
                    if (status == BluetoothGatt.GATT_SUCCESS
                            && state == BluetoothProfile.STATE_CONNECTED) {
                        isConnected = true;
                        gatt.discoverServices();
                        handler.post(() ->
                                tvPrinter.setText("Подключён"));
                    } else {
                        isConnected = false;
                        printChar = null;
                        handler.post(() ->
                                tvPrinter.setText("Отключён"));
                        gatt.close();
                    }
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt gatt, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) return;

                    for (BluetoothGattService service
                            : gatt.getServices()) {
                        if (!SERVICE_UUIDS.contains(service.getUuid())) {
                            continue;
                        }

                        for (BluetoothGattCharacteristic c
                                : service.getCharacteristics()) {
                            if (CHAR_UUIDS.contains(c.getUuid())) {
                                printChar = c;
                                handler.post(() ->
                                        tvPrinter.setText(
                                                "Принтер готов к печати"));
                                return;
                            }
                        }
                    }

                    handler.post(() ->
                            tvPrinter.setText(
                                    "Характеристика печати не найдена"));
                }
            });
        } catch (SecurityException e) {
            toast("Нет разрешения Bluetooth");
        }
    }

    private void printText(String text) {
        if (!isConnected || printChar == null || btGatt == null) {
            toast("Принтер не готов");
            return;
        }

        if (text == null || text.trim().isEmpty()) {
            toast("Нет текста для печати");
            return;
        }

        executor.execute(() -> {
            try {
                Bitmap bitmap = textToBitmap(text, 384);
                List<byte[]> packets = buildPrintPackets(bitmap);

                for (byte[] packet : packets) {
                    if (isFinishing() || isDestroyed()) return;

                    if (android.os.Build.VERSION.SDK_INT >= 31
                            && ActivityCompat.checkSelfPermission(this,
                            Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }

                    BluetoothGatt gatt = btGatt;
                    BluetoothGattCharacteristic characteristic = printChar;

                    if (gatt == null || characteristic == null) return;

                    characteristic.setValue(packet);
                    boolean accepted = gatt.writeCharacteristic(
                            characteristic);

                    if (!accepted) {
                        throw new IllegalStateException(
                                "Bluetooth не принял пакет");
                    }

                    Thread.sleep(30);
                }

                handler.post(() ->
                        tvStatus.setText("Команды отправлены принтеру"));

            } catch (Exception e) {
                handler.post(() ->
                        tvStatus.setText("Ошибка печати: "
                                + e.getMessage()));
            }
        });
    }

    private Bitmap textToBitmap(String text, int width) {
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(24);
        paint.setAntiAlias(false);

        String[] lines = text.split("\\n");
        int lineHeight = 30;
        int height = Math.max(lineHeight * lines.length + 20, 60);

        Bitmap bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], 5,
                    24 + i * lineHeight, paint);
        }

        return bitmap;
    }

    private List<byte[]> buildPrintPackets(Bitmap bitmap) {
        List<byte[]> packets = new ArrayList<>();
        int width = 384;
        int bytesPerRow = width / 8;

        packets.add(makeCmd((byte) 0xA3, new byte[]{0x00}));
        packets.add(makeCmd((byte) 0xA4, new byte[]{50}));
        packets.add(makeCmd((byte) 0xBD, new byte[]{0x02}));
        packets.add(makeCmd((byte) 0xAF,
                new byte[]{0x00, 0x35}));

        // Исправлено: явное преобразование 0xAA в byte.
        packets.add(makeCmd((byte) 0xA6,
                new byte[]{(byte) 0xAA, (byte) 0x55,
                        0x17, 0x38, 0x44, 0x5F,
                        0x5F, 0x5F, 0x44, 0x38, 0x2C}));

        for (int y = 0; y < bitmap.getHeight(); y++) {
            byte[] row = new byte[bytesPerRow];

            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                int luminance = (Color.red(pixel)
                        + Color.green(pixel)
                        + Color.blue(pixel)) / 3;

                if (luminance < 128) {
                    int byteIndex = x / 8;
                    int bitIndex = 7 - (x % 8);
                    row[byteIndex] |= (byte) (1 << bitIndex);
                }
            }

            for (int i = 0; i < row.length; i++) {
                row[i] = reverseBits(row[i]);
            }

            packets.add(makeCmd((byte) 0xA2, row));
        }

        packets.add(makeCmd((byte) 0xA6,
                new byte[]{(byte) 0xAA, (byte) 0x55,
                        0x17, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x17}));

        packets.add(makeCmd((byte) 0xA0,
                new byte[]{0x00, 0x70}));

        return packets;
    }

    private byte[] makeCmd(byte command, byte[] payload) {
        byte[] packet = new byte[8 + payload.length];

        packet[0] = 0x51;
        packet[1] = 0x78;
        packet[2] = command;
        packet[3] = 0x00;
        packet[4] = (byte) payload.length;
        packet[5] = 0x00;

        System.arraycopy(payload, 0, packet, 6, payload.length);
        packet[6 + payload.length] = crc8(payload);
        packet[7 + payload.length] = (byte) 0xFF;

        return packet;
    }

    private byte crc8(byte[] data) {
        int crc = 0;

        for (byte value : data) {
            crc ^= value & 0xFF;

            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = (crc << 1) ^ 0x07;
                } else {
                    crc <<= 1;
                }
            }
        }

        return (byte) (crc & 0xFF);
    }

    private byte reverseBits(byte value) {
        int v = value & 0xFF;
        int result = 0;

        for (int i = 0; i < 8; i++) {
            result = (result << 1) | (v & 1);
            v >>= 1;
        }

        return (byte) result;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        executor.shutdownNow();

        if (btGatt != null) {
            try {
                btGatt.close();
            } catch (Exception ignored) {
            }
            btGatt = null;
        }
    }
                }
