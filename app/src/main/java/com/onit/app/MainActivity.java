
package com.onit.app;

import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.concurrent.TimeUnit;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.CallLog;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.content.SharedPreferences;
import org.json.JSONObject;
import android.os.Environment;
import android.content.Intent;
import android.provider.Settings;
import android.os.Build;
import java.io.File;
import okhttp3.MediaType;
import okhttp3.MultipartBody;


public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "onit_prefs";
    private static final String KEY_LAST_CALL_TIME = "last_call_time";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // אם יש צורך בהרשאת גישה לכל הקבצים (לקריאת הקלטות – שלב עתידי)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }

        // בדיקת הרשאות: אנשי קשר + יומן שיחות
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(new String[]{
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.READ_CALL_LOG
            }, 1);

        } else {
            // אם כבר יש הרשאות – מפעיל את הכל מיידית
            loadContacts();  // לצורכי לוגים וזיהוי
            loadCallLog();   // שולח לשרת את השיחות מאנשי קשר מאז הפעם האחרונה
        }

        // אם רוצים גם לעשות משהו עם העיצוב
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                loadContacts();    // ✅ יטען אנשי קשר
                loadCallLog();     // ✅ יטען את יומן השיחות וישלח לשרת
            } else {
                Toast.makeText(this, "הרשאות נדרשות להפעלת האפליקציה", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadContacts() {
        Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String number = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));

                Log.d("OnIT_CONTACT", name + " - " + number);
            }
            cursor.close();
        }
    }


    private void sendToTranscribeServer(String number, String name, String duration, String date) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .callTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .build();

                JSONObject json = new JSONObject();
                json.put("number", number);
                json.put("name", name);
                json.put("duration", duration);
                json.put("date", date);

                RequestBody body = RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));

                Request request = new Request.Builder()
                        .url("https://onit-server.onrender.com/transcribe")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseText = response.body().string();

                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "שרת ענה: " + responseText, Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "שגיאה: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }



    private long getLastCallTime() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getLong(KEY_LAST_CALL_TIME, 0); // ברירת מחדל: 0 (אין שיחות קודמות)
    }

    private void setLastCallTime(long time) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(KEY_LAST_CALL_TIME, time);
        editor.apply(); // שומר ברקע
    }


    private boolean isNumberInContacts(String callLogNumber) {
        Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null);

        if (cursor != null) {
            int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            if (numberIndex == -1) {
                Log.e("OnIT_CALL", "📛 לא נמצא טור NUMBER באנשי קשר");
                cursor.close();
                return false;
            }

            String normalizedCallNumber = normalizeNumber(callLogNumber);

            while (cursor.moveToNext()) {
                String contactNumber = cursor.getString(numberIndex);
                if (contactNumber == null) continue;

                String normalizedContact = normalizeNumber(contactNumber);

                Log.d("OnIT_DEBUG", "השוואה בין " + normalizedCallNumber + " ל-" + normalizedContact);

                if (normalizedCallNumber.endsWith(normalizedContact) ||
                        normalizedContact.endsWith(normalizedCallNumber)) {
                    cursor.close();
                    return true;
                }
            }
            cursor.close();
        }
        return false;
    }

    private String normalizeNumber(String number) {
        return number.replaceAll("[^0-9]", "")  // מסיר כל דבר שהוא לא מספר
                .replaceFirst("^972", "0"); // 972 ל־0
    }

    private void saveLastProcessedCallTime(long time) {
        getSharedPreferences("onit_prefs", MODE_PRIVATE)
                .edit()
                .putLong("last_call_time", time)
                .apply();
    }

    private long getLastProcessedCallTime() {
        return getSharedPreferences("onit_prefs", MODE_PRIVATE)
                .getLong("last_call_time", 0); // אם אין, מחזיר 0
    }

    private void sendCallToServer(String transcript) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .callTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .build();

                RequestBody body = RequestBody.create(
                        "{\"transcript\":\"" + transcript + "\"}",
                        okhttp3.MediaType.parse("application/json"));

                Request request = new Request.Builder()
                        .url("https://onit-server.onrender.com/transcribe")
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String responseText = response.body().string();

                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "משימות מהשרת: " + responseText, Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "שגיאה בשליחה לשרת: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private void loadCallLog() {
        Cursor cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                CallLog.Calls.DATE + " DESC");

        if (cursor != null) {
            int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
            int nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
            int typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);
            int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);
            int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);

            long lastCallTime = getLastCallTime();
            long maxProcessedTime = lastCallTime;

            while (cursor.moveToNext()) {
                long callTime = cursor.getLong(dateIndex);
                if (callTime <= lastCallTime) continue;

                String number = cursor.getString(numberIndex);
                String name = cursor.getString(nameIndex);
                String type = cursor.getString(typeIndex);
                String duration = cursor.getString(durationIndex);

                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault());
                String readableDate = sdf.format(new Date(callTime));

                if (isNumberInContacts(number)) {
                    Log.d("OnIT_CALL", "✅ שיחה מאיש קשר: " + name + " | " + number +
                            " | סוג: " + type + " | תאריך: " + readableDate + " | משך: " + duration + " שניות");

                    // קריאה לשרת
                    sendToTranscribeServer(number, name, duration, readableDate);
                } else {
                    Log.d("OnIT_CALL", "❌ שיחה ממספר לא מאושר: " + number);
                }

                if (callTime > maxProcessedTime) {
                    maxProcessedTime = callTime;
                }
            }

            setLastCallTime(maxProcessedTime);
            cursor.close();
        }
    }

    private void sendAudioFileToServer(File audioFile) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder().build();

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("audio", audioFile.getName(),
                                RequestBody.create(audioFile, MediaType.parse("audio/*")))
                        .build();

                Request request = new Request.Builder()
                        .url("https://onit-server.onrender.com/transcribe")
                        .post(requestBody)
                        .build();

                Response response = client.newCall(request).execute();
                String responseText = response.body().string();

                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "תשובת השרת: " + responseText, Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "שגיאה בשליחת קובץ: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private File findClosestRecordingFile(long callTime) {
        File dir = new File(Environment.getExternalStorageDirectory(), "CallRecordings");
        if (!dir.exists() || !dir.isDirectory()) {
            Log.d("OnIT_AUDIO", "📛 תיקיית הקלטות לא קיימת");
            return null;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        File closestFile = null;
        long minDiff = Long.MAX_VALUE;

        for (File file : files) {
            long diff = Math.abs(file.lastModified() - callTime);
            if (diff < minDiff) {
                minDiff = diff;
                closestFile = file;
            }
        }

        return closestFile;
    }



}