package com.aiassistant.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText etAssembly, etGroq, etFreeTts, etModel;
    private EditText etPrompt1, etName1, etPrompt2, etName2;
    private CheckBox cbAutoPrint;
    private TextView tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etAssembly = findViewById(R.id.etAssemblyKey);
        etGroq     = findViewById(R.id.etGroqKey);
        etFreeTts  = findViewById(R.id.etFreeTtsKey);
        etModel    = findViewById(R.id.etModel);
        etName1    = findViewById(R.id.etBranchName1);
        etPrompt1  = findViewById(R.id.etBranchPrompt1);
        etName2    = findViewById(R.id.etBranchName2);
        etPrompt2  = findViewById(R.id.etBranchPrompt2);
        cbAutoPrint = findViewById(R.id.cbAutoPrint);
        tvLog      = findViewById(R.id.tvLog);

        SharedPreferences prefs =
            getSharedPreferences("ai_prefs", MODE_PRIVATE);

        loadPrefs(prefs);

        findViewById(R.id.btnSave).setOnClickListener(v -> save(prefs));

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            prefs.edit().putString("last_error", "Очищено").apply();
            tvLog.setText("Журнал:\nОчищено");
        });

        findViewById(R.id.btnHelpAssembly).setOnClickListener(v ->
            openUrl("https://www.assemblyai.com/dashboard/signup"));

        findViewById(R.id.btnHelpGroq).setOnClickListener(v ->
            openUrl("https://console.groq.com/keys"));

        findViewById(R.id.btnHelpFreeTts).setOnClickListener(v ->
            openUrl("https://freetts.org"));

        findViewById(R.id.btnReset).setOnClickListener(v ->
            confirmReset(prefs));
    }

    private void loadPrefs(SharedPreferences prefs) {
        etAssembly.setText(prefs.getString("assembly_key", ""));
        etGroq.setText(prefs.getString("groq_key", ""));
        etFreeTts.setText(prefs.getString("freetts_key", ""));
        etModel.setText(prefs.getString("groq_model", "llama3-8b-8192"));
        etName1.setText(prefs.getString("branch1_name", "Ветка 1"));
        etPrompt1.setText(prefs.getString("branch1_prompt",
            "Ты голосовой ИИ-ассистент. Отвечай кратко по-русски."));
        etName2.setText(prefs.getString("branch2_name", "Ветка 2"));
        etPrompt2.setText(prefs.getString("branch2_prompt",
            "Ты помощник на производстве. Отвечай кратко по-русски."));
        cbAutoPrint.setChecked(prefs.getBoolean("auto_print", false));
        tvLog.setText("Журнал:\n" +
            prefs.getString("last_error", "Ошибок нет"));
    }

    private void save(SharedPreferences prefs) {
        String assemblyKey = etAssembly.getText().toString().trim();
        String groqKey     = etGroq.getText().toString().trim();
        String freeTtsKey  = etFreeTts.getText().toString().trim();
        String model       = etModel.getText().toString().trim();
        String name1       = etName1.getText().toString().trim();
        String prompt1     = etPrompt1.getText().toString().trim();
        String name2       = etName2.getText().toString().trim();
        String prompt2     = etPrompt2.getText().toString().trim();

        if (TextUtils.isEmpty(assemblyKey)) {
            etAssembly.setError("Обязательное поле");
            etAssembly.requestFocus(); return;
        }
        if (TextUtils.isEmpty(groqKey)) {
            etGroq.setError("Обязательное поле");
            etGroq.requestFocus(); return;
        }
        if (!groqKey.startsWith("gsk_")) {
            showWarning("Groq-ключ обычно начинается с «gsk_». Проверь правильность.");
        }

        if (TextUtils.isEmpty(model))   model   = "llama3-8b-8192";
        if (TextUtils.isEmpty(name1))   name1   = "Ветка 1";
        if (TextUtils.isEmpty(prompt1)) prompt1 = "Ты голосовой ИИ-ассистент. Отвечай кратко по-русски.";
        if (TextUtils.isEmpty(name2))   name2   = "Ветка 2";
        if (TextUtils.isEmpty(prompt2)) prompt2 = "Ты помощник на производстве. Отвечай кратко по-русски.";

        prefs.edit()
            .putString("assembly_key",   assemblyKey)
            .putString("groq_key",       groqKey)
            .putString("freetts_key",    freeTtsKey)
            .putString("groq_model",     model)
            .putString("branch1_name",   name1)
            .putString("branch1_prompt", prompt1)
            .putString("branch2_name",   name2)
            .putString("branch2_prompt", prompt2)
            .putBoolean("auto_print",    cbAutoPrint.isChecked())
            .apply();

        Toast.makeText(this, "✅ Сохранено!", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmReset(SharedPreferences prefs) {
        new AlertDialog.Builder(this)
            .setTitle("Сбросить настройки?")
            .setMessage("Все ключи и промпты будут удалены.")
            .setPositiveButton("Сбросить", (d, w) -> {
                prefs.edit().clear().apply();
                loadPrefs(prefs);
                Toast.makeText(this, "Сброшено", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void showWarning(String msg) {
        new AlertDialog.Builder(this)
            .setTitle("Внимание")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show();
    }
}
