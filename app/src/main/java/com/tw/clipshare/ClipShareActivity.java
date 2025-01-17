/*
 * MIT License
 *
 * Copyright (c) 2022-2023 H. Thevindu J. Wijesekera
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tw.clipshare;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.tw.clipshare.netConnection.*;
import com.tw.clipshare.platformUtils.AndroidStatusNotifier;
import com.tw.clipshare.platformUtils.AndroidUtils;
import com.tw.clipshare.platformUtils.FSUtils;
import com.tw.clipshare.platformUtils.StatusNotifier;
import com.tw.clipshare.protocol.Proto;
import com.tw.clipshare.protocol.ProtocolSelector;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClipShareActivity extends AppCompatActivity {
  public static final int WRITE_IMAGE = 222;
  public static final int WRITE_FILE = 223;
  public static final String CHANNEL_ID = "upload_channel";
  private static final Object fileGetCntLock = new Object();
  private static final Object fileSendCntLock = new Object();
  private static final Object settingsLock = new Object();
  private static int fileGettingCount = 0;
  private static int fileSendingCount = 0;
  private static boolean isSettingsLoaded = false;
  public TextView output;
  private ActivityResultLauncher<Intent> activityLauncherForResult;
  private EditText editAddress;
  private Context context;
  private ArrayList<Uri> fileURIs;
  private Menu menu;
  private SwitchCompat tunnelSwitch = null;

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.action_bar, menu);
    this.menu = menu;
    try {
      synchronized (settingsLock) {
        while (!isSettingsLoaded) {
          try {
            settingsLock.wait();
          } catch (InterruptedException ignored) {
          }
        }
      }
      Settings st = Settings.getInstance(null);
      int icon_id = st.getSecure() ? R.drawable.ic_secure : R.drawable.ic_insecure;
      menu.findItem(R.id.action_secure)
          .setIcon(ContextCompat.getDrawable(ClipShareActivity.this, icon_id));

      MenuItem tunnelSwitch = menu.findItem(R.id.action_tunnel_switch);
      tunnelSwitch.setActionView(R.layout.tunnel_switch);
      this.tunnelSwitch = tunnelSwitch.getActionView().findViewById(R.id.tunnelSwitch);
      this.tunnelSwitch.setOnCheckedChangeListener(
          (switchView, isChecked) -> {
            if (isChecked) {
              TunnelManager.start();
            } else {
              TunnelManager.stop();
            }
          });
    } catch (Exception ignored) {
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int itemID = item.getItemId();
    if (itemID == R.id.action_settings) {
      Intent settingsIntent = new Intent(ClipShareActivity.this, SettingsActivity.class);
      settingsIntent.putExtra("settingsResult", 1);
      activityLauncherForResult.launch(settingsIntent);
    } else if (itemID == R.id.action_secure) {
      Toast.makeText(ClipShareActivity.this, "Change this in settings", Toast.LENGTH_SHORT).show();
    }
    return true;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    this.editAddress = findViewById(R.id.hostTxt);
    this.output = findViewById(R.id.txtOutput);
    this.context = getApplicationContext();

    Intent intent = getIntent();
    if (intent != null) extractIntent(intent);

    SharedPreferences sharedPref = ClipShareActivity.this.getPreferences(Context.MODE_PRIVATE);

    this.activityLauncherForResult =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() != Activity.RESULT_OK) {
                return;
              }
              Intent intent1 = result.getData();
              if (intent1 == null) {
                return;
              }
              if (intent1.hasExtra("settingsResult")) {
                Settings st = Settings.getInstance(null);
                boolean sec = st.getSecure();
                int icon_id = sec ? R.drawable.ic_secure : R.drawable.ic_insecure;
                menu.findItem(R.id.action_secure)
                    .setIcon(ContextCompat.getDrawable(ClipShareActivity.this, icon_id));
                SharedPreferences.Editor editor = sharedPref.edit();
                try {
                  editor.putString("settings", Settings.toString(st));
                  editor.apply();
                } catch (Exception ignored) {
                }
              } else {
                try {
                  ClipData clipData = intent1.getClipData();
                  ArrayList<Uri> uris;
                  if (clipData != null) {
                    int itemCount = clipData.getItemCount();
                    uris = new ArrayList<>(itemCount);
                    for (int count = 0; count < itemCount; count++) {
                      Uri uri = clipData.getItemAt(count).getUri();
                      uris.add(uri);
                    }
                  } else {
                    Uri uri = intent1.getData();
                    uris = new ArrayList<>(1);
                    uris.add(uri);
                  }
                  this.fileURIs = uris;
                  clkSendFile();
                } catch (Exception e) {
                  outputAppend("Error " + e.getMessage());
                }
              }
            });

    output.setMovementMethod(new ScrollingMovementMethod());
    Button btnGet = findViewById(R.id.btnGetTxt);
    btnGet.setOnClickListener(view -> clkGetTxt());
    Button btnImg = findViewById(R.id.btnGetImg);
    btnImg.setOnClickListener(view -> clkGetImg());
    Button btnGetFile = findViewById(R.id.btnGetFile);
    btnGetFile.setOnClickListener(view -> clkGetFile());
    Button btnSendTxt = findViewById(R.id.btnSendTxt);
    btnSendTxt.setOnClickListener(view -> clkSendTxt());
    Button btnSendFile = findViewById(R.id.btnSendFile);
    btnSendFile.setOnClickListener(view -> clkSendFile());
    Button btnScanHost = findViewById(R.id.btnScanHost);
    btnScanHost.setOnClickListener(this::clkScanBtn);
    editAddress.setText(sharedPref.getString("serverIP", ""));
    try {
      Settings.getInstance(sharedPref.getString("settings", null));
    } catch (Exception ignored) {
    }
    isSettingsLoaded = true;
    synchronized (settingsLock) {
      settingsLock.notifyAll();
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
        channel.setDescription(description);
        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
      }
    } catch (Exception ignored) {
    }
  }

  /**
   * Extract the file URIs or shared text from an intent.
   *
   * @param intent to extract data from
   */
  private void extractIntent(Intent intent) {
    String type = intent.getType();
    if (type != null) {
      try {
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
          Uri extra;
          if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
            extra = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
          } else {
            extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
          }
          if (extra != null) {
            this.fileURIs = new ArrayList<>(1);
            this.fileURIs.add(extra);
            output.setText(R.string.fileSelectedTxt);
            return;
          }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
          ArrayList<Uri> uris;
          if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
            uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
          } else {
            uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
          }
          if (uris != null && uris.size() > 0) {
            this.fileURIs = uris;
            int count = this.fileURIs.size();
            output.setText(
                context.getResources().getQuantityString(R.plurals.filesSelectedTxt, count, count));
            return;
          }
        }
        if (type.startsWith("text/")) {
          String text = intent.getStringExtra(Intent.EXTRA_TEXT);
          if (text != null) {
            AndroidUtils utils = new AndroidUtils(context, ClipShareActivity.this);
            utils.setClipboardText(text);
            output.setText(R.string.textSelected);
          }
        } else {
          this.fileURIs = null;
          output.setText(R.string.noFilesTxt);
        }
      } catch (Exception e) {
        output.setText(e.getMessage());
      }
    } else {
      this.fileURIs = null;
    }
  }

  /**
   * Opens a ServerConnection. Returns null on error.
   *
   * @param addressStr IPv4 address of the server as a String in dotted decimal notation.
   * @return opened ServerConnection or null
   */
  @Nullable
  ServerConnection getServerConnection(@NonNull String addressStr) {
    int retries = 2;
    do {
      try {
        Settings settings = Settings.getInstance(null);
        if (tunnelSwitch != null && tunnelSwitch.isChecked()) {
          return new TunnelConnection(addressStr);
        } else if (settings.getSecure()) {
          InputStream caCertIn = settings.getCACertInputStream();
          InputStream clientCertKeyIn = settings.getCertInputStream();
          char[] clientPass = settings.getPasswd();
          if (clientCertKeyIn == null || clientPass == null) {
            return null;
          }
          String[] acceptedServers = settings.getTrustedList().toArray(new String[0]);
          return new SecureConnection(
              Inet4Address.getByName(addressStr),
              settings.getPortSecure(),
              caCertIn,
              clientCertKeyIn,
              clientPass,
              acceptedServers);
        } else {
          return new PlainConnection(Inet4Address.getByName(addressStr), settings.getPort());
        }
      } catch (Exception ignored) {
      }
    } while (retries-- > 0);
    return null;
  }

  /**
   * Wrapper to get connection and protocol selector
   *
   * @param address of the server
   * @param utils object or null
   * @param notifier object or null
   * @return a Proto object if success, or null otherwise
   */
  @Nullable
  private Proto getProtoWrapper(
      @NonNull String address, AndroidUtils utils, StatusNotifier notifier) {
    int retries = 1;
    do {
      try {
        ServerConnection connection = getServerConnection(address);
        if (connection == null) continue;
        Proto proto = ProtocolSelector.getProto(connection, utils, notifier);
        if (proto != null) return proto;
        connection.close();
      } catch (ProtocolException ex) {
        outputAppend(ex.getMessage());
        return null;
      } catch (Exception ignored) {
      }
    } while (retries-- > 0);
    outputAppend("Couldn't connect");
    return null;
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    extractIntent(intent);
  }

  private void clkScanBtn(View parent) {
    new Thread(
            () -> {
              try {
                Settings settings = Settings.getInstance(null);
                List<InetAddress> serverAddresses =
                    ServerFinder.find(settings.getPort(), settings.getPortUDP());
                if (!serverAddresses.isEmpty()) {
                  if (serverAddresses.size() == 1) {
                    InetAddress serverAddress = serverAddresses.get(0);
                    runOnUiThread(() -> editAddress.setText(serverAddress.getHostAddress()));
                  } else {
                    LayoutInflater inflater =
                        (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
                    View popupView =
                        inflater.inflate(R.layout.popup, findViewById(R.id.main_layout), false);
                    popupView
                        .findViewById(R.id.popupLinearWrap)
                        .setOnClickListener(v -> popupView.performClick());

                    int width = LinearLayout.LayoutParams.MATCH_PARENT;
                    int height = LinearLayout.LayoutParams.MATCH_PARENT;
                    boolean focusable = true; // lets taps outside the popup also dismiss it
                    final PopupWindow popupWindow =
                        new PopupWindow(popupView, width, height, focusable);
                    runOnUiThread(() -> popupWindow.showAtLocation(parent, Gravity.CENTER, 0, 0));

                    LinearLayout popupLayout = popupView.findViewById(R.id.popupLayout);
                    if (popupLayout == null) return;
                    View popupElemView;
                    TextView txtView;
                    for (InetAddress serverAddress : serverAddresses) {
                      popupElemView = View.inflate(this, R.layout.popup_elem, null);
                      txtView = popupElemView.findViewById(R.id.popElemTxt);
                      txtView.setText(serverAddress.getHostAddress());
                      txtView.setOnClickListener(
                          view -> {
                            runOnUiThread(() -> editAddress.setText(((TextView) view).getText()));
                            popupView.performClick();
                          });
                      popupLayout.addView(popupElemView);
                    }
                    popupView.setOnClickListener(v -> popupWindow.dismiss());
                  }
                } else {
                  runOnUiThread(
                      () ->
                          Toast.makeText(context, "No servers found!", Toast.LENGTH_SHORT).show());
                }
              } catch (Exception ignored) {
              }
            })
        .start();
  }

  /**
   * Gets the server's IPv4 address from the address input box
   *
   * @return IPv4 address of the server in dotted decimal notation as a String, or null
   */
  @Nullable
  private String getServerAddress() {
    try {
      String address = editAddress.getText().toString();
      if (!address.matches("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)(\\.(?!$)|$)){4}$")) {
        Toast.makeText(ClipShareActivity.this, "Invalid address", Toast.LENGTH_SHORT).show();
        return null;
      }
      SharedPreferences sharedPref = ClipShareActivity.this.getPreferences(Context.MODE_PRIVATE);
      SharedPreferences.Editor editor = sharedPref.edit();
      editor.putString("serverIP", address);
      editor.apply();
      return address;
    } catch (Exception ignored) {
      return null;
    }
  }

  private void clkSendTxt() {
    try {
      runOnUiThread(() -> output.setText(""));
      String address = this.getServerAddress();
      if (address == null) return;
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      Runnable sendClip =
          () -> {
            try {
              AndroidUtils utils = new AndroidUtils(context, ClipShareActivity.this);
              String clipDataString = utils.getClipboardText();
              if (clipDataString == null) return;
              Proto proto = getProtoWrapper(address, utils, null);
              if (proto == null) return;
              boolean status = proto.sendText(clipDataString);
              proto.close();
              if (!status) return;
              if (clipDataString.length() < 16384) outputAppend("Text: " + clipDataString);
              else outputAppend("Text: " + clipDataString.substring(0, 1024) + " ... (truncated)");
            } catch (Exception e) {
              outputAppend("Error " + e.getMessage());
            }
          };
      executorService.submit(sendClip);
    } catch (Exception e) {
      outputAppend("Error " + e.getMessage());
    }
  }

  private void clkSendFile() {
    if (this.fileURIs == null || this.fileURIs.isEmpty()) {
      Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
      intent.setType("*/*");
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      activityLauncherForResult.launch(intent);
    } else {
      ArrayList<Uri> tmp = this.fileURIs;
      this.fileURIs = null;
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      Runnable sendURIs = () -> sendFromURIs(tmp);
      executorService.submit(sendURIs);
    }
  }

  /**
   * Sends files from a list of Uris
   *
   * @param uris of files
   */
  private void sendFromURIs(ArrayList<Uri> uris) {
    try {
      runOnUiThread(() -> output.setText(""));
      String address = this.getServerAddress();
      if (address == null) return;

      LinkedList<PendingFile> pendingFiles = new LinkedList<>();
      for (Uri uri : uris) {
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor.getCount() <= 0) {
          cursor.close();
          continue;
        }
        cursor.moveToFirst();
        String fileName =
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
        String fileSizeStr = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
        cursor.close();

        InputStream fileInputStream = getContentResolver().openInputStream(uri);
        long fileSize = fileSizeStr != null ? Long.parseLong(fileSizeStr) : -1;
        PendingFile pendingFile = new PendingFile(fileInputStream, fileName, fileSize);
        pendingFiles.add(pendingFile);
      }
      FSUtils utils = new FSUtils(context, ClipShareActivity.this, pendingFiles);

      int notificationId;
      {
        Random rnd = new Random();
        notificationId = Math.abs(rnd.nextInt(Integer.MAX_VALUE - 1)) + 1;
      }
      NotificationManagerCompat notificationManager = null;
      synchronized (fileSendCntLock) {
        while (fileSendingCount > 1) {
          try {
            fileSendCntLock.wait();
          } catch (InterruptedException ignored) {
          }
        }
        fileSendingCount++;
      }
      try {
        runOnUiThread(() -> output.setText(R.string.sendingFiles));
        notificationManager = NotificationManagerCompat.from(context);
        NotificationCompat.Builder builder =
            new NotificationCompat.Builder(context, ClipShareActivity.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_upload_icon)
                .setContentTitle("Sending files");
        StatusNotifier notifier =
            new AndroidStatusNotifier(
                ClipShareActivity.this, notificationManager, builder, notificationId);
        boolean status = true;
        while (utils.getRemainingFileCount() > 0) {
          Proto proto = getProtoWrapper(address, utils, notifier);
          if (proto == null) return;
          status &= proto.sendFile();
          proto.close();
        }
        if (status) {
          runOnUiThread(
              () -> {
                try {
                  output.setText(R.string.sentAllFiles);
                } catch (Exception ignored) {
                }
              });
        }
      } catch (Exception e) {
        outputAppend("Error " + e.getMessage());
      } finally {
        synchronized (fileSendCntLock) {
          fileSendingCount--;
          fileSendCntLock.notifyAll();
        }
        try {
          if (notificationManager != null) {
            NotificationManagerCompat finalNotificationManager = notificationManager;
            runOnUiThread(
                () -> {
                  try {
                    finalNotificationManager.cancel(notificationId);
                  } catch (Exception ignored) {
                  }
                });
          }
        } catch (Exception ignored) {
        }
      }
    } catch (Exception e) {
      outputAppend("Error " + e.getMessage());
    }
  }

  private void clkGetTxt() {
    try {
      runOnUiThread(() -> output.setText(""));
      String address = this.getServerAddress();
      if (address == null) return;
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      Runnable getClip =
          () -> {
            try {
              AndroidUtils utils = new AndroidUtils(context, ClipShareActivity.this);
              Proto proto = getProtoWrapper(address, utils, null);
              if (proto == null) return;
              String text = proto.getText();
              proto.close();
              if (text == null) return;
              utils.setClipboardText(text);
              if (text.length() < 16384) outputAppend("Text: " + text);
              else outputAppend("Text: " + text.substring(0, 1024) + " ... (truncated)");
            } catch (Exception e) {
              outputAppend("Error " + e.getMessage());
            }
          };
      executorService.submit(getClip);
    } catch (Exception e) {
      outputAppend("Error " + e.getMessage());
    }
  }

  private void clkGetImg() {
    try {
      if (needsPermission(WRITE_IMAGE)) return;

      runOnUiThread(() -> output.setText(""));
      String address = this.getServerAddress();
      if (address == null) return;

      ExecutorService executorService = Executors.newSingleThreadExecutor();
      Runnable getImg =
          () -> {
            try {
              FSUtils utils = new FSUtils(context, ClipShareActivity.this);
              Proto proto = getProtoWrapper(address, utils, null);
              if (proto == null) return;
              boolean status = proto.getImage();
              proto.close();
              if (!status)
                runOnUiThread(
                    () ->
                        Toast.makeText(
                                ClipShareActivity.this, "Getting image failed", Toast.LENGTH_SHORT)
                            .show());
            } catch (Exception e) {
              outputAppend("Error " + e.getMessage());
            }
          };
      executorService.submit(getImg);
    } catch (Exception e) {
      outputAppend("Error " + e.getMessage());
    }
  }

  private void clkGetFile() {
    try {
      if (needsPermission(WRITE_FILE)) return;

      runOnUiThread(() -> output.setText(""));
      String address = this.getServerAddress();
      if (address == null) return;

      ExecutorService executorService = Executors.newSingleThreadExecutor();
      Runnable getFile =
          () -> {
            int notificationId;
            {
              Random rnd = new Random();
              notificationId = Math.abs(rnd.nextInt(Integer.MAX_VALUE - 1)) + 1;
            }
            NotificationManagerCompat notificationManager = null;
            synchronized (fileGetCntLock) {
              while (fileGettingCount > 1) {
                try {
                  fileGetCntLock.wait();
                } catch (InterruptedException ignored) {
                }
              }
              fileGettingCount++;
            }
            try {
              notificationManager = NotificationManagerCompat.from(context);
              NotificationCompat.Builder builder =
                  new NotificationCompat.Builder(context, ClipShareActivity.CHANNEL_ID)
                      .setSmallIcon(R.drawable.ic_download_icon)
                      .setContentTitle("Getting file");

              FSUtils utils = new FSUtils(context, ClipShareActivity.this);
              StatusNotifier notifier =
                  new AndroidStatusNotifier(
                      ClipShareActivity.this, notificationManager, builder, notificationId);
              Proto proto = getProtoWrapper(address, utils, notifier);
              if (proto == null) return;
              boolean status = proto.getFile();
              proto.close();
              if (status) {
                runOnUiThread(
                    () -> {
                      try {
                        output.setText(R.string.receiveAllFiles);
                      } catch (Exception ignored) {
                      }
                    });
              }
            } catch (Exception e) {
              outputAppend("Error " + e.getMessage());
            } finally {
              synchronized (fileGetCntLock) {
                fileGettingCount--;
                fileGetCntLock.notifyAll();
              }
              try {
                if (notificationManager != null) {
                  NotificationManagerCompat finalNotificationManager = notificationManager;
                  runOnUiThread(
                      () -> {
                        try {
                          finalNotificationManager.cancel(notificationId);
                        } catch (Exception ignored) {
                        }
                      });
                }
              } catch (Exception ignored) {
              }
            }
          };
      executorService.submit(getFile);
    } catch (Exception e) {
      outputAppend("Error " + e.getMessage());
    }
  }

  /**
   * Checks if the app needs permission to write a file to storage. If the permission is not already
   * granted, this will request the permission from the user.
   *
   * @param requestCode to check if permission is needed
   * @return true if permission is required or false otherwise
   */
  private boolean needsPermission(int requestCode) {
    String permission = Manifest.permission.WRITE_EXTERNAL_STORAGE;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false;
    if (ContextCompat.checkSelfPermission(ClipShareActivity.this, permission)
        == PackageManager.PERMISSION_DENIED) {
      ActivityCompat.requestPermissions(
          ClipShareActivity.this, new String[] {permission}, requestCode);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode == WRITE_IMAGE || requestCode == WRITE_FILE) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        switch (requestCode) {
          case WRITE_IMAGE:
            {
              clkGetImg();
              break;
            }
          case WRITE_FILE:
            {
              clkGetFile();
              break;
            }
        }
      } else {
        Toast.makeText(ClipShareActivity.this, "Storage Permission Denied", Toast.LENGTH_SHORT)
            .show();
      }
    }
  }

  private void outputAppend(CharSequence text) {
    runOnUiThread(
        () -> {
          String newText = output.getText().toString() + text;
          output.setText(newText);
        });
  }
}
