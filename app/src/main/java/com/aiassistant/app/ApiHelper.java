package com.aiassistant.app;

import android.content.SharedPreferences;
import android.os.Handler;
import android.widget.TextView;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ApiHelper {

    public interface ErrorSaver { void save(String msg); }
    public interface Speaker    { void speak(String text); }

    public static void transcribeAndRespond(
            byte[] wav,
            SharedPreferences prefs,
            Handler handler,
            TextView tvStatus,
            TextView tvTranscript,
            TextView tvResponse,
            ErrorSaver errorSaver,
            Speaker speaker,
            int colorIdle,
            int colorSpeaking) {

        String aKey = prefs.getString("assembly_key", "");
        String gKey = prefs.getString("groq_key", "");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60,  java.util.concurrent.TimeUnit.SECONDS)
                .build();

            // 1. Загрузить аудио
            Response upR = client.newCall(new Request.Builder()
                .url("https://api.assemblyai.com/v2/upload")
                .header("authorization", aKey)
                .post(RequestBody.create(wav, MediaType.parse("audio/wav")))
                .build()).execute();

            String upRaw = upR.body() != null ? upR.body().string() : "";
            if (!upR.isSuccessful()) {
                errorSaver.save("Upload: " + upRaw);
                handler.post(() -> tvStatus.setText("Ошибка загрузки"));
                return;
            }
            String upUrl = new JSONObject(upRaw).getString("upload_url");

            // 2. Запустить транскрипцию
            JSONObject tj = new JSONObject();
            tj.put("audio_url", upUrl);
            tj.put("language_code", "ru");

            Response tR = client.newCall(new Request.Builder()
                .url("https://api.assemblyai.com/v2/transcript")
                .header("authorization", aKey)
                .post(RequestBody.create(tj.toString(),
                    MediaType.parse("application/json")))
                .build()).execute();

            String tRaw = tR.body() != null ? tR.body().string() : "";
            if (!tR.isSuccessful()) {
                errorSaver.save("STT: " + tRaw);
                handler.post(() -> tvStatus.setText("Ошибка распознавания"));
                return;
            }
            String tid = new JSONObject(tRaw).getString("id");

            // 3. Ждать результата
            String recognizedText = pollTranscript(client, tid, aKey, errorSaver);
            final String question = recognizedText;
            handler.post(() -> tvTranscript.setText(question));

            if (question.trim().isEmpty()) {
                handler.post(() -> {
                    tvStatus.setText("Не удалось распознать речь");
                    tvStatus.setTextColor(colorIdle);
                });
                return;
            }

            // 4. Отправить в Groq
            String answer = askGroq(client, prefs, gKey, question, errorSaver);
            if (answer == null) {
                handler.post(() -> tvStatus.setText("Ошибка Groq"));
                return;
            }

            errorSaver.save("OK");
            handler.post(() -> {
                tvResponse.setText(answer);
                tvStatus.setText("Создаю голос...");
                tvStatus.setTextColor(colorSpeaking);
                speaker.speak(answer);
            });

        } catch (Exception e) {
            errorSaver.save(e.getClass().getSimpleName() + ": " + e.getMessage());
            handler.post(() -> tvStatus.setText("Ошибка: " + e.getMessage()));
        }
    }

    private static String pollTranscript(
            OkHttpClient client, String tid, String aKey,
            ErrorSaver errorSaver) throws Exception {

        for (int i = 0; i < 30; i++) {
            Thread.sleep(2000);
            Response pR = client.newCall(new Request.Builder()
                .url("https://api.assemblyai.com/v2/transcript/" + tid)
                .header("authorization", aKey)
                .get().build()).execute();

            String pRaw = pR.body() != null ? pR.body().string() : "";
            pR.close();

            JSONObject pJ  = new JSONObject(pRaw);
            String status  = pJ.getString("status");

            if ("completed".equals(status)) return pJ.optString("text", "");
            if ("error".equals(status)) {
                errorSaver.save("STT error: " + pJ.optString("error", ""));
                return "";
            }
        }
        return "";
    }

    private static String askGroq(
            OkHttpClient client, SharedPreferences prefs,
            String gKey, String question,
            ErrorSaver errorSaver) throws Exception {

        JSONObject req = new JSONObject();
        req.put("model", prefs.getString("groq_model", "llama3-8b-8192"));

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", prefs.getString("system_prompt",
            "Ты голосовой ИИ-ассистент. Отвечай кратко по-русски."));
        messages.put(sys);

        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", question);
        messages.put(usr);
        req.put("messages", messages);

        Response gR = client.newCall(new Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer " + gKey)
            .post(RequestBody.create(req.toString(),
                MediaType.parse("application/json")))
            .build()).execute();

        String gRaw = gR.body() != null ? gR.body().string() : "";
        if (!gR.isSuccessful()) {
            errorSaver.save("Groq: " + gRaw);
            return null;
        }

        return new JSONObject(gRaw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content");
    }
              }
