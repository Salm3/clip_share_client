package com.tw.clipshare;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SettingsActivity extends AppCompatActivity {

    private static final byte CLIENT = 10;
    private static final byte CA = 11;
    private Switch secureSwitch;
    private Intent intent;
    private AtomicInteger id;
    private LinearLayout trustList;
    private EditText editPass;
    private TextView cnTxt;
    private TextView caCnTxt;
    private volatile byte certType;

    private void addRowToTrusList(boolean addToList, String name) {
        try {
            View trustServer = View.inflate(getApplicationContext(), R.layout.trusted_server, null);
            ImageButton delBtn = trustServer.findViewById(R.id.delBtn);
            TextView cnTxt = trustServer.findViewById(R.id.cnTxt);
            EditText cnEdit = trustServer.findViewById(R.id.cnEdit);
            trustServer.setId(id.getAndIncrement());
            Settings st = Settings.getInstance(null);
            List<String> servers = st.getTrustedList();
            if (name != null) cnTxt.setText(name);
            if (addToList) servers.add(cnTxt.getText().toString());
            trustList.addView(trustServer);
            delBtn.setOnClickListener(view1 -> {
                try {
                    if (servers.remove(cnTxt.getText().toString())) {
                        trustList.removeView(trustServer);
                        if (servers.isEmpty()) {
                            st.setSecure(false);
                            SettingsActivity.this.secureSwitch.setChecked(false);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            cnTxt.setOnClickListener(view1 -> {
                cnEdit.setText(cnTxt.getText());
                cnTxt.setVisibility(View.GONE);
                cnEdit.setVisibility(View.VISIBLE);
                cnEdit.requestFocus();
            });
            cnEdit.setOnFocusChangeListener((view1, hasFocus) -> {
                if (!hasFocus) {
                    CharSequence oldText = cnTxt.getText();
                    CharSequence newText = cnEdit.getText();
                    if (newText.length() > 0) cnTxt.setText(newText);
                    cnEdit.setVisibility(View.GONE);
                    cnTxt.setVisibility(View.VISIBLE);
                    if (newText.length() > 0 && servers.remove(oldText.toString()))
                        servers.add(newText.toString());
                }
            });
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        Settings st = Settings.getInstance(null);
        this.intent = getIntent();
        this.id = new AtomicInteger();
        this.trustList = findViewById(R.id.trustedList);
        ImageButton addBtn = findViewById(R.id.addServerBtn);
        Button browseBtn = findViewById(R.id.btnImportCert);
        Button caBrowseBtn = findViewById(R.id.btnImportCACert);
        this.secureSwitch = findViewById(R.id.secureSwitch);
        this.editPass = findViewById(R.id.editCertPass);
        this.caCnTxt = findViewById(R.id.txtCACertName);
        this.cnTxt = findViewById(R.id.txtCertName);
        this.secureSwitch.setOnClickListener(view -> {
            if (!SettingsActivity.this.secureSwitch.isChecked()) {
                st.setSecure(secureSwitch.isChecked());
                return;
            }
            if (st.getCACertCN() == null) {
                Toast.makeText(SettingsActivity.this, "No CA certificate", Toast.LENGTH_SHORT).show();
                SettingsActivity.this.secureSwitch.setChecked(false);
                return;
            }
            if (st.getCertCN() == null) {
                Toast.makeText(SettingsActivity.this, "No client key and certificate", Toast.LENGTH_SHORT).show();
                SettingsActivity.this.secureSwitch.setChecked(false);
                return;
            }
            if (st.getTrustedList().isEmpty()) {
                Toast.makeText(SettingsActivity.this, "No trusted servers", Toast.LENGTH_SHORT).show();
                SettingsActivity.this.secureSwitch.setChecked(false);
                return;
            }
            st.setSecure(secureSwitch.isChecked());
        });
        this.secureSwitch.setChecked(st.getSecure());

        this.caCnTxt.setMovementMethod(new ScrollingMovementMethod());
        this.caCnTxt.setHorizontallyScrolling(true);
        String caCertCN = st.getCACertCN();
        if (caCertCN != null) this.caCnTxt.setText(caCertCN);

        this.cnTxt.setMovementMethod(new ScrollingMovementMethod());
        this.cnTxt.setHorizontallyScrolling(true);
        String certCN = st.getCertCN();
        if (certCN != null) this.cnTxt.setText(certCN);

        List<String> servers = st.getTrustedList();

        for (String server : servers) {
            addRowToTrusList(false, server);
        }

        addBtn.setOnClickListener(view -> addRowToTrusList(true, null));

        certType = 0;
        ActivityResultLauncher<Intent> activityLauncherForResult = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        return;
                    }
                    Intent intent1 = result.getData();
                    if (intent1 == null) {
                        return;
                    }
                    try {
                        byte type = SettingsActivity.this.certType;
                        SettingsActivity.this.certType = 0;
                        if (type == CLIENT) {
                            Uri uri = intent1.getData();
                            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                            if (cursor.getCount() <= 0) {
                                cursor.close();
                                return;
                            }
                            cursor.moveToFirst();
                            String fileSizeStr = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                            int size = Integer.parseInt(fileSizeStr);
                            cursor.close();
                            InputStream fileInputStream = getContentResolver().openInputStream(uri);
                            char[] passwd = editPass.getText().toString().toCharArray();
                            String cn = st.setCertPass(passwd, fileInputStream, size);
                            if (cn != null) {
                                cnTxt.setText(cn);
                            } else {
                                Toast.makeText(SettingsActivity.this, "Invalid", Toast.LENGTH_SHORT).show();
                            }
                        } else if (type == CA) {
                            Uri uri = intent1.getData();
                            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                            if (cursor.getCount() <= 0) {
                                cursor.close();
                                return;
                            }
                            cursor.moveToFirst();
                            String fileSizeStr = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                            int size = Integer.parseInt(fileSizeStr);
                            cursor.close();
                            InputStream fileInputStream = getContentResolver().openInputStream(uri);
                            String caCn = st.setCACert(fileInputStream, size);
                            if (caCn != null) {
                                caCnTxt.setText(caCn);
                            } else {
                                Toast.makeText(SettingsActivity.this, "Invalid", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });

        caBrowseBtn.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            String[] mimeTypes = {"application/x-x509-ca-cert", "application/x-pem-file", "application/pkix-cert+pem", "application/pkix-cert"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            SettingsActivity.this.certType = CA;
            activityLauncherForResult.launch(intent);
        });

        browseBtn.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/x-pkcs12");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            SettingsActivity.this.certType = CLIENT;
            activityLauncherForResult.launch(intent);
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            if (v instanceof EditText) {
                Rect outRect = new Rect();
                v.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int) event.getRawX(), (int) event.getRawY())) {
                    v.clearFocus();
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (this.intent != null) {
            this.setResult(Activity.RESULT_OK, intent);
        }
        this.finish();
    }
}
