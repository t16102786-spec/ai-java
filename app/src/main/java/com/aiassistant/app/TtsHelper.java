package com.aiassistant.app;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.widget.TextView;
import okhttp3.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TtsHelper {

    public interface ErrorSaver   { void save(String msg); }
    public interface PlayerSetter { void set(MediaPlayer player); }

    public static void speak(
            String text,
            String apiKey,
            File cacheDir,
            Handler handler,
            AudioManager audioManager,
            TextView tvStatus,
            ErrorSaver errorSaver,
            MediaPlayer currentPlayer,
            PlayerSetter playerSetter,
            int colorIdle) {

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60,  java.util.concurrent.TimeUnit.SECONDS)
                .build();

            JSONObject body = new JSONObject();
            body.put("text",  text);
            body.put("voice", "ru-RU-DmitryNeural");
            body.put("rate",  "+0%");
            body.put("pitch", "+0Hz");

            Response synth = client.newCall(new Request.Builder()
                .url("https://freetts.org/api/v1/tts")
                .header("x-api-key", apiKey)
                .post(RequestBody.create(body.toString(),
                    MediaType.parse("application/json")))
                .build()).execute();

            String responseText = synth.body() != null
                ? synth.body().string() : "";
            int code = synth.code();
            synth.close();

            if (code < 200 || code >= 300) {
                errorSaver.save("FreeTTS HTTP " + code + ": " + responseText);
                handler.post(() ->
                    tvStatus.setText("Ошибка FreeTTS: " + code));
                return;
            }

            String fileId = new JSONObject(responseText).getString("file_id");

            Response audio = client.newCall(new Request.Builder()
                .url("https://freetts.org/api/audio/" + fileId)
                .get().build()).execute();

            if (!audio.isSuccessful() || audio.body() == null) {
                int ac = audio.code();
                audio.close();
                throw new Exception("Ошибка загрузки MP3: " + ac);
            }

            File audioFile = new File(cacheDir, "freetts_reply.mp3");
            try (InputStream in = audio.body().byteStream();
                 FileOutputStream out = new FileOutputStream(audioFile)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
            audio.close();

            final String filePath = audioFile.getAbsolutePath();

            handler.post(() -> {
                try {
                    if (currentPlayer != null) {
                        currentPlayer.release();
                        playerSetter.set(null);
                    }

                    MediaPlayer player = new MediaPlayer();
                    playerSetter.set(player);
                    player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                    player.setDataSource(filePath);

                    player.setOnPreparedListener(p -> {
                        tvStatus.setText("Говорю...");
                        p.start();
                    });

                    player.setOnCompletionListener(p -> {
                        p.release();
                        playerSetter.set(null);
                        tvStatus.setText("Нажмите для начала");
                        tvStatus.setTextColor(colorIdle);
                    });

                    player.setOnErrorListener((p, what, extra) -> {
                        p.release();
                        playerSetter.set(null);
                        tvStatus.setText("Ошибка воспроизведения");
                        errorSaver.save("MediaPlayer: " + what + ", " + extra);
                        return true;
                    });

                    player.prepareAsync();

                } catch (Exception e) {
                    errorSaver.save("Player: " + e.getMessage());
                    tvStatus.setText("Ошибка воспроизведения");
                }
            });

        } catch (Exception e) {
            errorSaver.save("FreeTTS: " + e.getMessage());
            handler.post(() ->
                tvStatus.setText("Ошибка озвучки. Проверь ключ и интернет."));
        }
    }
}
