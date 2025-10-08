/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.webkit.URLUtil;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Random;
import android.text.TextWatcher;
import android.text.Editable;

import org.appspot.apprtc.util.IniFileHandler;
import org.appspot.apprtc.util.SocketManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;

/**
 * Handles the initial setup where the user selects which room to join.
 */
public class ConnectActivity extends Activity {
  private static final String TAG = "ConnectActivity";
  private static final int CONNECTION_REQUEST = 1;
  private static final int PERMISSION_REQUEST = 2;
  private static final int REMOVE_FAVORITE_INDEX = 0;
  private static boolean commandLineRun;
  private Toast logToast;
  private Button callerButton;
  private Button receiverButton;
  private Button howlButton;
  private Button agcButton;
  private TextView receiverView;
  private TextView hintView;
  private TextView howlHintView;
  private TextView agcHintView;
  private EditText roomEditText;
  private Spinner howlSpinner;
  private EditText howlTargeEditText;
  private EditText howlGainEditText;

  private EditText roomEditTextDescription;
  private Button connectButton;
  private Button loopbackButton;

  //private ListView roomListView;
  private SharedPreferences sharedPref;
  private String keyprefResolution;
  private String keyprefFps;
  private String keyprefVideoBitrateType;
  private String keyprefVideoBitrateValue;
  private String keyprefAudioBitrateType;
  private String keyprefAudioBitrateValue;
  private String keyprefRoomServerUrl;
  private String keyprefRoom;
  private String keyprefRoomList;
  private ArrayList<String> roomList;
  private ArrayAdapter<String> adapter;
  private static Socket incomingSocket;
  private static String incomingMessage;
  private Socket mSocket;
  public static boolean sIsServer=true;
  public static String localIp;

  private boolean IsHowlOpened=false;
  private boolean IsAgcOpened=false;
  private float suppress_level=0;
  private float target_level_dbfs=5;
  private float compression_gain_db=20;
  private float enable_limiter=1;
  private float enable_agc=0;
  private String log_folder_path="/data/data/org.appspot.apprtc/";

  private Thread restartThread;

  private IniFileHandler IniHandler=null;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Get setting keys.
    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    keyprefResolution = getString(R.string.pref_resolution_key);
    keyprefFps = getString(R.string.pref_fps_key);
    keyprefVideoBitrateType = getString(R.string.pref_maxvideobitrate_key);
    keyprefVideoBitrateValue = getString(R.string.pref_maxvideobitratevalue_key);
    keyprefAudioBitrateType = getString(R.string.pref_startaudiobitrate_key);
    keyprefAudioBitrateValue = getString(R.string.pref_startaudiobitratevalue_key);
    keyprefRoomServerUrl = getString(R.string.pref_room_server_url_key);
    keyprefRoom = getString(R.string.pref_room_key);
    keyprefRoomList = getString(R.string.pref_room_list_key);

    setContentView(R.layout.activity_connect);

    roomEditText = findViewById(R.id.room_edittext);
    roomEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
      @Override
      public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
        if (i == EditorInfo.IME_ACTION_DONE) {
          //addFavoriteButton.performClick();
          return true;
        }
        return false;
      }
    });
    //roomEditText.setVisibility(View.INVISIBLE);
    roomEditText.setEnabled(false);
    //roomEditText.requestFocus();

    howlTargeEditText = findViewById(R.id.agc_target_edittext);
    howlTargeEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // 文本改变前调用
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        // 文本改变时调用
      }

      @Override
      public void afterTextChanged(Editable s) {
        // 文本改变后调用 - 主要在这里处理
        onNumberTargetInputChanged(s.toString());
      }
    });

    howlGainEditText = findViewById(R.id.agc_gain_edittext);
    howlGainEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // 文本改变前调用
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        // 文本改变时调用
      }

      @Override
      public void afterTextChanged(Editable s) {
        // 文本改变后调用 - 主要在这里处理
        onNumberGainInputChanged(s.toString());
      }
    });

    //roomListView = findViewById(R.id.room_listview);
    //roomListView.setEmptyView(findViewById(android.R.id.empty));
    //roomListView.setOnItemClickListener(roomListClickListener);
    //registerForContextMenu(roomListView);
    ImageButton connectButton = findViewById(R.id.connect_button);

    connectButton.setOnClickListener(connectListener);
    callerButton = findViewById(R.id.caller_button);
    callerButton.setOnClickListener(callerListener);

    receiverButton = findViewById(R.id.reciever_button);
    receiverButton.setOnClickListener(receiverListener);

    receiverView = findViewById(R.id.reciever_description_view);
    hintView = findViewById(R.id.room_edittext_description);
    hintView.setTextColor(Color.RED);

    howlHintView = findViewById(R.id.howl_testview_status);
    howlHintView.setTextColor(Color.RED);

    howlButton = findViewById(R.id.howl_button);
    howlButton.setOnClickListener(howlListener);

    agcHintView = findViewById(R.id.agc_testview_status);
    agcHintView.setTextColor(Color.RED);

    agcButton = findViewById(R.id.agc_button);
    agcButton.setOnClickListener(agcListener);


    howlSpinner = findViewById(R.id.howl_spinner);
    ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
            this,
            R.array.spinner_items,
            android.R.layout.simple_spinner_item
    );
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    howlSpinner.setAdapter(adapter);

    howlSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        String selectedItem = parent.getItemAtPosition(position).toString();
        suppress_level=(float)position;
        if(IsHowlOpened)
        {
          if(position==0)
             howlHintView.setText("啸叫抑制已打开,级别是" + Integer.toString(position)+",0没有任何效果");
          else if(position==1)
            howlHintView.setText("啸叫抑制已打开,级别是" + Integer.toString(position)+",1的效果很差");
          else
            howlHintView.setText("啸叫抑制已打开,级别是" + Integer.toString(position));
        }
        saveConfig();
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
        // 当没有选择任何项时调用
      }
    });

    howlSpinner.setEnabled(false);
    howlSpinner.setSelection(2);
    IsHowlOpened=false;
    howlSpinner.setEnabled(false);
    howlButton.setText("开启啸叫抑制");

    saveConfig();
    requestPermissions();
    restartThread = new Thread(() -> {
        while (!restartThread.interrupted()) {
          try {
            Thread.sleep(100);
          } catch (Exception e)
          {
          }

          if(CallActivity.serverRestart)
          {
            CallActivity.serverRestart = false;
            if(sIsServer)
            {
              stopGlobalListener();
              startGlobalListener();
            }
          }
        }
    });
    restartThread.start();
  }

  private void saveConfig()
  {
      if(IniHandler==null)
        IniHandler = new IniFileHandler(log_folder_path+"iniconfig.ini");

    IniHandler.setValue("log_folder_path",log_folder_path);
    IniHandler.setValue("suppress_level",String.valueOf(suppress_level));
    IniHandler.setValue("target_level_dbfs",String.valueOf(target_level_dbfs));
    IniHandler.setValue("compression_gain_db",String.valueOf(compression_gain_db));
    IniHandler.setValue("enable_limiter",String.valueOf(enable_limiter));
    IniHandler.setValue("enable_agc",String.valueOf(enable_agc));

    try {
      IniHandler.save();
    } catch (IOException e) {
        throw new RuntimeException(e);
    }

  }
  private void logAndToast(String msg) {
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.connect_menu, menu);
    return true;
  }

  private void onNumberTargetInputChanged(String inputText) {
    if (inputText.isEmpty()) {
      // 输入为空时的处理
      //Log.d("Input", "输入为空");
      return;
    }

    try {
      float number = Float.parseFloat(inputText);
      target_level_dbfs=number;
      agcHintView.setText("AGC已打开,target_dbfs是"+Integer.toString((int)target_level_dbfs)+",gain_db是"+Integer.toString((int)compression_gain_db));
      saveConfig();
    } catch (NumberFormatException e) {
    }
  }

  private void onNumberGainInputChanged(String inputText) {
    if (inputText.isEmpty()) {
      // 输入为空时的处理
      //Log.d("Input", "输入为空");
      return;
    }

    try {
      // 将字符串转换为数字
      float number = Float.parseFloat(inputText);
      compression_gain_db=number;
      agcHintView.setText("AGC已打开,target_dbfs是"+Integer.toString((int)target_level_dbfs)+",gain_db是"+Integer.toString((int)compression_gain_db));
      saveConfig();
    } catch (NumberFormatException e) {
    }
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
      super.onCreateContextMenu(menu, v, menuInfo);
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    if (item.getItemId() == REMOVE_FAVORITE_INDEX) {
      AdapterView.AdapterContextMenuInfo info =
          (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
      roomList.remove(info.position);
      adapter.notifyDataSetChanged();
      return true;
    }

    return super.onContextItemSelected(item);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle presses on the action bar items.
    if (item.getItemId() == R.id.action_settings) {
      Intent intent = new Intent(this, SettingsActivity.class);
      startActivity(intent);
      return true;
    } else if (item.getItemId() == R.id.action_loopback) {
      connectToRoom(null, false, true, false, 0);
      return true;
    } else {
      return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    String room = roomEditText.getText().toString();
    String roomListJson = new JSONArray(roomList).toString();
    SharedPreferences.Editor editor = sharedPref.edit();
    editor.putString(keyprefRoom, room);
    editor.putString(keyprefRoomList, roomListJson);
    editor.commit();
  }

  @Override
  public void onResume() {
    super.onResume();
    String room = sharedPref.getString(keyprefRoom, "");
    roomEditText.setText(room);
    roomList = new ArrayList<>();
    String roomListJson = sharedPref.getString(keyprefRoomList, null);
    if (roomListJson != null) {
      try {
        JSONArray jsonArray = new JSONArray(roomListJson);
        for (int i = 0; i < jsonArray.length(); i++) {
          roomList.add(jsonArray.get(i).toString());
        }
      } catch (JSONException e) {
        Log.e(TAG, "Failed to load room list: " + e.toString());
      }
    }
/*    adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, roomList);
    roomListView.setAdapter(adapter);
    if (adapter.getCount() > 0) {
      roomListView.requestFocus();
      roomListView.setItemChecked(0, true);
    }*/
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == CONNECTION_REQUEST && commandLineRun) {
      Log.d(TAG, "Return: " + resultCode);
      setResult(resultCode);
      commandLineRun = false;
      finish();
    }
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == PERMISSION_REQUEST) {
      String[] missingPermissions = getMissingPermissions();
      if (missingPermissions.length != 0) {
        // User didn't grant all the permissions. Warn that the application might not work
        // correctly.
/*        new AlertDialog.Builder(this)
            .setMessage(R.string.missing_permissions_try_again)
            .setPositiveButton(R.string.yes,
                (dialog, id) -> {
                  // User wants to try giving the permissions again.
                  dialog.cancel();
                  requestPermissions();
                })
            .setNegativeButton(R.string.no,
                (dialog, id) -> {
                  // User doesn't want to give the permissions.
                  dialog.cancel();
                  onPermissionsGranted();
                })
            .show();*/
      } else {
        // All permissions granted.
        onPermissionsGranted();
      }
    }
  }

  private void onPermissionsGranted() {
    // If an implicit VIEW intent is launching the app, go directly to that URL.
    final Intent intent = getIntent();
    if ("android.intent.action.VIEW".equals(intent.getAction()) && !commandLineRun) {
      boolean loopback = intent.getBooleanExtra(CallActivity.EXTRA_LOOPBACK, false);
      int runTimeMs = intent.getIntExtra(CallActivity.EXTRA_RUNTIME, 0);
      boolean useValuesFromIntent =
          intent.getBooleanExtra(CallActivity.EXTRA_USE_VALUES_FROM_INTENT, false);
      String room = sharedPref.getString(keyprefRoom, "");
      connectToRoom(room, true, loopback, useValuesFromIntent, runTimeMs);
    }
  }

  @TargetApi(Build.VERSION_CODES.M)
  private void requestPermissions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      // Dynamic permissions are not required before Android M.
      onPermissionsGranted();
      return;
    }

    String[] missingPermissions = getMissingPermissions();
    Log.d("zzy", "missingPermissions: " + Arrays.toString(missingPermissions)); //[]
    requestPermissions(new String[] { Manifest.permission.BLUETOOTH, Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
    if (missingPermissions.length != 0) {
      requestPermissions(missingPermissions, PERMISSION_REQUEST);
    } else {
      onPermissionsGranted();
    }
  }

  @TargetApi(Build.VERSION_CODES.M)
  private String[] getMissingPermissions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return new String[0];
    }

    PackageInfo info;
    try {
      info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
    } catch (PackageManager.NameNotFoundException e) {
      Log.w(TAG, "Failed to retrieve permissions.");
      return new String[0];
    }

    if (info.requestedPermissions == null) {
      Log.w(TAG, "No requested permissions.");
      return new String[0];
    }

    ArrayList<String> missingPermissions = new ArrayList<>();
    for (int i = 0; i < info.requestedPermissions.length; i++) {
      if ((info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) {
        missingPermissions.add(info.requestedPermissions[i]);
      }
    }
    Log.d(TAG, "Missing permissions: " + missingPermissions);

    return missingPermissions.toArray(new String[missingPermissions.size()]);
  }

  /**
   * Get a value from the shared preference or from the intent, if it does not
   * exist the default is used.
   */
  @Nullable
  private String sharedPrefGetString(
      int attributeId, String intentName, int defaultId, boolean useFromIntent) {
    String defaultValue = getString(defaultId);
    if (useFromIntent) {
      String value = getIntent().getStringExtra(intentName);
      if (value != null) {
        return value;
      }
      return defaultValue;
    } else {
      String attributeName = getString(attributeId);
      return sharedPref.getString(attributeName, defaultValue);
    }
  }

  /**
   * Get a value from the shared preference or from the intent, if it does not
   * exist the default is used.
   */
  private boolean sharedPrefGetBoolean(
      int attributeId, String intentName, int defaultId, boolean useFromIntent) {
    boolean defaultValue = Boolean.parseBoolean(getString(defaultId));
    if (useFromIntent) {
      return getIntent().getBooleanExtra(intentName, defaultValue);
    } else {
      String attributeName = getString(attributeId);
      return sharedPref.getBoolean(attributeName, defaultValue);
    }
  }

  /**
   * Get a value from the shared preference or from the intent, if it does not
   * exist the default is used.
   */
  private int sharedPrefGetInteger(
      int attributeId, String intentName, int defaultId, boolean useFromIntent) {
    String defaultString = getString(defaultId);
    int defaultValue = Integer.parseInt(defaultString);
    if (useFromIntent) {
      return getIntent().getIntExtra(intentName, defaultValue);
    } else {
      String attributeName = getString(attributeId);
      String value = sharedPref.getString(attributeName, defaultString);
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        Log.e(TAG, "Wrong setting for: " + attributeName + ":" + value);
        return defaultValue;
      }
    }
  }

  @SuppressWarnings("StringSplitter")
  private void connectToRoom(String roomId, boolean commandLineRun, boolean loopback,
      boolean useValuesFromIntent, int runTimeMs) {
    ConnectActivity.commandLineRun = commandLineRun;

    // roomId is random for loopback.
    if (loopback) {
      roomId = Integer.toString(new Random().nextInt(100000000));
    }

    String roomUrl = sharedPref.getString(
        keyprefRoomServerUrl, getString(R.string.pref_room_server_url_default));

    // Video call enabled flag.
    boolean videoCallEnabled = sharedPrefGetBoolean(R.string.pref_videocall_key,
        CallActivity.EXTRA_VIDEO_CALL, R.string.pref_videocall_default, useValuesFromIntent);

    // Use screencapture option.
    boolean useScreencapture = sharedPrefGetBoolean(R.string.pref_screencapture_key,
        CallActivity.EXTRA_SCREENCAPTURE, R.string.pref_screencapture_default, useValuesFromIntent);

    // Use Camera2 option.
    boolean useCamera2 = sharedPrefGetBoolean(R.string.pref_camera2_key, CallActivity.EXTRA_CAMERA2,
        R.string.pref_camera2_default, useValuesFromIntent);

    // Get default codecs.
    String videoCodec = sharedPrefGetString(R.string.pref_videocodec_key,
        CallActivity.EXTRA_VIDEOCODEC, R.string.pref_videocodec_default, useValuesFromIntent);
    String audioCodec = sharedPrefGetString(R.string.pref_audiocodec_key,
        CallActivity.EXTRA_AUDIOCODEC, R.string.pref_audiocodec_default, useValuesFromIntent);

    // Check HW codec flag.
    boolean hwCodec = sharedPrefGetBoolean(R.string.pref_hwcodec_key,
        CallActivity.EXTRA_HWCODEC_ENABLED, R.string.pref_hwcodec_default, useValuesFromIntent);

    // Check Capture to texture.
    boolean captureToTexture = sharedPrefGetBoolean(R.string.pref_capturetotexture_key,
        CallActivity.EXTRA_CAPTURETOTEXTURE_ENABLED, R.string.pref_capturetotexture_default,
        useValuesFromIntent);

    // Check FlexFEC.
    boolean flexfecEnabled = sharedPrefGetBoolean(R.string.pref_flexfec_key,
        CallActivity.EXTRA_FLEXFEC_ENABLED, R.string.pref_flexfec_default, useValuesFromIntent);

    // Check Disable Audio Processing flag.
    boolean noAudioProcessing = sharedPrefGetBoolean(R.string.pref_noaudioprocessing_key,
        CallActivity.EXTRA_NOAUDIOPROCESSING_ENABLED, R.string.pref_noaudioprocessing_default,
        useValuesFromIntent);

    boolean aecDump = sharedPrefGetBoolean(R.string.pref_aecdump_key,
        CallActivity.EXTRA_AECDUMP_ENABLED, R.string.pref_aecdump_default, useValuesFromIntent);

    boolean saveInputAudioToFile =
        sharedPrefGetBoolean(R.string.pref_enable_save_input_audio_to_file_key,
            CallActivity.EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED,
            R.string.pref_enable_save_input_audio_to_file_default, useValuesFromIntent);

    // Check OpenSL ES enabled flag.
    boolean useOpenSLES = sharedPrefGetBoolean(R.string.pref_opensles_key,
        CallActivity.EXTRA_OPENSLES_ENABLED, R.string.pref_opensles_default, useValuesFromIntent);

    // Check Disable built-in AEC flag.
    boolean disableBuiltInAEC = sharedPrefGetBoolean(R.string.pref_disable_built_in_aec_key,
        CallActivity.EXTRA_DISABLE_BUILT_IN_AEC, R.string.pref_disable_built_in_aec_default,
        useValuesFromIntent);

    // Check Disable built-in AGC flag.
    boolean disableBuiltInAGC = sharedPrefGetBoolean(R.string.pref_disable_built_in_agc_key,
        CallActivity.EXTRA_DISABLE_BUILT_IN_AGC, R.string.pref_disable_built_in_agc_default,
        useValuesFromIntent);

    // Check Disable built-in NS flag.
    boolean disableBuiltInNS = sharedPrefGetBoolean(R.string.pref_disable_built_in_ns_key,
        CallActivity.EXTRA_DISABLE_BUILT_IN_NS, R.string.pref_disable_built_in_ns_default,
        useValuesFromIntent);

    // Check Disable gain control
    boolean disableWebRtcAGCAndHPF = sharedPrefGetBoolean(
        R.string.pref_disable_webrtc_agc_and_hpf_key, CallActivity.EXTRA_DISABLE_WEBRTC_AGC_AND_HPF,
        R.string.pref_disable_webrtc_agc_and_hpf_key, useValuesFromIntent);

    // Get video resolution from settings.
    int videoWidth = 0;
    int videoHeight = 0;
    if (useValuesFromIntent) {
      videoWidth = getIntent().getIntExtra(CallActivity.EXTRA_VIDEO_WIDTH, 0);
      videoHeight = getIntent().getIntExtra(CallActivity.EXTRA_VIDEO_HEIGHT, 0);
    }
    if (videoWidth == 0 && videoHeight == 0) {
      String resolution =
          sharedPref.getString(keyprefResolution, getString(R.string.pref_resolution_default));
      String[] dimensions = resolution.split("[ x]+");
      if (dimensions.length == 2) {
        try {
          videoWidth = Integer.parseInt(dimensions[0]);
          videoHeight = Integer.parseInt(dimensions[1]);
        } catch (NumberFormatException e) {
          videoWidth = 0;
          videoHeight = 0;
          Log.e(TAG, "Wrong video resolution setting: " + resolution);
        }
      }
    }

    // Get camera fps from settings.
    int cameraFps = 0;
    if (useValuesFromIntent) {
      cameraFps = getIntent().getIntExtra(CallActivity.EXTRA_VIDEO_FPS, 0);
    }
    if (cameraFps == 0) {
      String fps = sharedPref.getString(keyprefFps, getString(R.string.pref_fps_default));
      String[] fpsValues = fps.split("[ x]+");
      if (fpsValues.length == 2) {
        try {
          cameraFps = Integer.parseInt(fpsValues[0]);
        } catch (NumberFormatException e) {
          cameraFps = 0;
          Log.e(TAG, "Wrong camera fps setting: " + fps);
        }
      }
    }

    // Check capture quality slider flag.
    boolean captureQualitySlider = sharedPrefGetBoolean(R.string.pref_capturequalityslider_key,
        CallActivity.EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED,
        R.string.pref_capturequalityslider_default, useValuesFromIntent);

    // Get video and audio start bitrate.
    int videoStartBitrate = 0;
    if (useValuesFromIntent) {
      videoStartBitrate = getIntent().getIntExtra(CallActivity.EXTRA_VIDEO_BITRATE, 0);
    }
    if (videoStartBitrate == 0) {
      String bitrateTypeDefault = getString(R.string.pref_maxvideobitrate_default);
      String bitrateType = sharedPref.getString(keyprefVideoBitrateType, bitrateTypeDefault);
      if (!bitrateType.equals(bitrateTypeDefault)) {
        String bitrateValue = sharedPref.getString(
            keyprefVideoBitrateValue, getString(R.string.pref_maxvideobitratevalue_default));
        videoStartBitrate = Integer.parseInt(bitrateValue);
      }
    }

    int audioStartBitrate = 0;
    if (useValuesFromIntent) {
      audioStartBitrate = getIntent().getIntExtra(CallActivity.EXTRA_AUDIO_BITRATE, 0);
    }
    if (audioStartBitrate == 0) {
      String bitrateTypeDefault = getString(R.string.pref_startaudiobitrate_default);
      String bitrateType = sharedPref.getString(keyprefAudioBitrateType, bitrateTypeDefault);
      if (!bitrateType.equals(bitrateTypeDefault)) {
        String bitrateValue = sharedPref.getString(
            keyprefAudioBitrateValue, getString(R.string.pref_startaudiobitratevalue_default));
        audioStartBitrate = Integer.parseInt(bitrateValue);
      }
    }

    // Check statistics display option.
    boolean displayHud = sharedPrefGetBoolean(R.string.pref_displayhud_key,
        CallActivity.EXTRA_DISPLAY_HUD, R.string.pref_displayhud_default, useValuesFromIntent);

    boolean tracing = sharedPrefGetBoolean(R.string.pref_tracing_key, CallActivity.EXTRA_TRACING,
        R.string.pref_tracing_default, useValuesFromIntent);

    // Check Enable RtcEventLog.
    boolean rtcEventLogEnabled = sharedPrefGetBoolean(R.string.pref_enable_rtceventlog_key,
        CallActivity.EXTRA_ENABLE_RTCEVENTLOG, R.string.pref_enable_rtceventlog_default,
        useValuesFromIntent);

    // Get datachannel options
    boolean dataChannelEnabled = sharedPrefGetBoolean(R.string.pref_enable_datachannel_key,
        CallActivity.EXTRA_DATA_CHANNEL_ENABLED, R.string.pref_enable_datachannel_default,
        useValuesFromIntent);
    boolean ordered = sharedPrefGetBoolean(R.string.pref_ordered_key, CallActivity.EXTRA_ORDERED,
        R.string.pref_ordered_default, useValuesFromIntent);
    boolean negotiated = sharedPrefGetBoolean(R.string.pref_negotiated_key,
        CallActivity.EXTRA_NEGOTIATED, R.string.pref_negotiated_default, useValuesFromIntent);
    int maxRetrMs = sharedPrefGetInteger(R.string.pref_max_retransmit_time_ms_key,
        CallActivity.EXTRA_MAX_RETRANSMITS_MS, R.string.pref_max_retransmit_time_ms_default,
        useValuesFromIntent);
    int maxRetr =
        sharedPrefGetInteger(R.string.pref_max_retransmits_key, CallActivity.EXTRA_MAX_RETRANSMITS,
            R.string.pref_max_retransmits_default, useValuesFromIntent);
    int id = sharedPrefGetInteger(R.string.pref_data_id_key, CallActivity.EXTRA_ID,
        R.string.pref_data_id_default, useValuesFromIntent);
    String protocol = sharedPrefGetString(R.string.pref_data_protocol_key,
        CallActivity.EXTRA_PROTOCOL, R.string.pref_data_protocol_default, useValuesFromIntent);

    // Start AppRTCMobile activity.
    Log.d(TAG, "Connecting to room " + roomId + " at URL " + roomUrl);
    if (validateUrl(roomUrl)) {
      Uri uri = Uri.parse(roomUrl);
      Intent intent = new Intent(this, CallActivity.class);
      intent.setData(uri);
      intent.putExtra(CallActivity.EXTRA_ROOMID, roomId);
      intent.putExtra(CallActivity.EXTRA_LOOPBACK, loopback);
      intent.putExtra(CallActivity.EXTRA_VIDEO_CALL, videoCallEnabled);
      intent.putExtra(CallActivity.EXTRA_SCREENCAPTURE, useScreencapture);
      intent.putExtra(CallActivity.EXTRA_CAMERA2, useCamera2);
      intent.putExtra(CallActivity.EXTRA_VIDEO_WIDTH, videoWidth);
      intent.putExtra(CallActivity.EXTRA_VIDEO_HEIGHT, videoHeight);
      intent.putExtra(CallActivity.EXTRA_VIDEO_FPS, cameraFps);
      intent.putExtra(CallActivity.EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED, captureQualitySlider);
      intent.putExtra(CallActivity.EXTRA_VIDEO_BITRATE, videoStartBitrate);
      intent.putExtra(CallActivity.EXTRA_VIDEOCODEC, videoCodec);
      intent.putExtra(CallActivity.EXTRA_HWCODEC_ENABLED, hwCodec);
      intent.putExtra(CallActivity.EXTRA_CAPTURETOTEXTURE_ENABLED, captureToTexture);
      intent.putExtra(CallActivity.EXTRA_FLEXFEC_ENABLED, flexfecEnabled);
      intent.putExtra(CallActivity.EXTRA_NOAUDIOPROCESSING_ENABLED, noAudioProcessing);
      intent.putExtra(CallActivity.EXTRA_AECDUMP_ENABLED, aecDump);
      intent.putExtra(CallActivity.EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED, saveInputAudioToFile);
      intent.putExtra(CallActivity.EXTRA_OPENSLES_ENABLED, useOpenSLES);
      intent.putExtra(CallActivity.EXTRA_DISABLE_BUILT_IN_AEC, disableBuiltInAEC);
      intent.putExtra(CallActivity.EXTRA_DISABLE_BUILT_IN_AGC, disableBuiltInAGC);
      intent.putExtra(CallActivity.EXTRA_DISABLE_BUILT_IN_NS, disableBuiltInNS);
      intent.putExtra(CallActivity.EXTRA_DISABLE_WEBRTC_AGC_AND_HPF, disableWebRtcAGCAndHPF);
      intent.putExtra(CallActivity.EXTRA_AUDIO_BITRATE, audioStartBitrate);
      intent.putExtra(CallActivity.EXTRA_AUDIOCODEC, audioCodec);
      intent.putExtra(CallActivity.EXTRA_DISPLAY_HUD, displayHud);
      intent.putExtra(CallActivity.EXTRA_TRACING, tracing);
      intent.putExtra(CallActivity.EXTRA_ENABLE_RTCEVENTLOG, rtcEventLogEnabled);
      intent.putExtra(CallActivity.EXTRA_CMDLINE, commandLineRun);
      intent.putExtra(CallActivity.EXTRA_RUNTIME, runTimeMs);
      intent.putExtra(CallActivity.EXTRA_DATA_CHANNEL_ENABLED, dataChannelEnabled);

      if (dataChannelEnabled) {
        intent.putExtra(CallActivity.EXTRA_ORDERED, ordered);
        intent.putExtra(CallActivity.EXTRA_MAX_RETRANSMITS_MS, maxRetrMs);
        intent.putExtra(CallActivity.EXTRA_MAX_RETRANSMITS, maxRetr);
        intent.putExtra(CallActivity.EXTRA_PROTOCOL, protocol);
        intent.putExtra(CallActivity.EXTRA_NEGOTIATED, negotiated);
        intent.putExtra(CallActivity.EXTRA_ID, id);
      }

      if (useValuesFromIntent) {
        if (getIntent().hasExtra(CallActivity.EXTRA_VIDEO_FILE_AS_CAMERA)) {
          String videoFileAsCamera =
              getIntent().getStringExtra(CallActivity.EXTRA_VIDEO_FILE_AS_CAMERA);
          intent.putExtra(CallActivity.EXTRA_VIDEO_FILE_AS_CAMERA, videoFileAsCamera);
        }

        if (getIntent().hasExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE)) {
          String saveRemoteVideoToFile =
              getIntent().getStringExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE);
          intent.putExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE, saveRemoteVideoToFile);
        }

        if (getIntent().hasExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH)) {
          int videoOutWidth =
              getIntent().getIntExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH, 0);
          intent.putExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH, videoOutWidth);
        }

        if (getIntent().hasExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT)) {
          int videoOutHeight =
              getIntent().getIntExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT, 0);
          intent.putExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT, videoOutHeight);
        }
      }

      startActivityForResult(intent, CONNECTION_REQUEST);
    }
  }

  private boolean validateUrl(String url) {
    if (URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url)) {
      return true;
    }

    new AlertDialog.Builder(this)
        .setTitle(getText(R.string.invalid_url_title))
        .setMessage(getString(R.string.invalid_url_text, url))
        .setCancelable(false)
        .setNeutralButton(R.string.ok,
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
              }
            })
        .create()
        .show();
    return false;
  }

/*  private final AdapterView.OnItemClickListener roomListClickListener =
      new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
          String roomId = ((TextView) view).getText().toString();
          connectToRoom(roomId, false, false, false, 0);
        }
      };*/

  private final OnClickListener callerListener = new OnClickListener() {
    @Override
    public void onClick(View view) {
      roomEditText.setEnabled(true);
      roomEditText.requestFocus();
      hintView.setText("你现在是呼叫方,请输入对方ip后呼叫");
      hintView.setTextColor(Color.RED);
      stopGlobalListener();
      sIsServer=false;
    }
  };

  private final OnClickListener agcListener = new OnClickListener() {
    @Override
    public void onClick(View view) {
      if(IsAgcOpened)
      {
        IsAgcOpened=false;
        agcButton.setText("打开AGC");
        agcHintView.setText("AGC已经关闭");
        enable_agc=0;
      }
      else
      {
        IsAgcOpened=true;
        agcButton.setText("关闭AGC");
        agcHintView.setText("AGC已打开,target_dbfs是"+Integer.toString((int)target_level_dbfs)+",gain_db是"+Integer.toString((int)compression_gain_db));
        enable_agc=1;
      }
      saveConfig();
    }
  };

  private final OnClickListener howlListener = new OnClickListener() {
    @Override
    public void onClick(View view) {
      if(IsHowlOpened)
      {
        IsHowlOpened=false;
        howlSpinner.setEnabled(false);
        howlButton.setText("打开啸叫抑制");
        howlHintView.setText("啸叫抑制已经关闭");
        suppress_level=0;
      }
      else
      {
        IsHowlOpened=true;
        howlSpinner.setEnabled(true);
        suppress_level=(float)howlSpinner.getSelectedItemPosition();
        howlButton.setText("关闭啸叫抑制");
        howlHintView.setText("啸叫抑制已经打开，请选择一个级别，不同级别会有不同效果");
      }
      saveConfig();
    }
  };

  private final OnClickListener receiverListener = new OnClickListener() {
    @Override
    public void onClick(View view) {
      roomEditText.setEnabled(false);
      hintView.setText("你现在是接收方,请对方呼叫");
      hintView.setTextColor(Color.RED);
      try {
        localIp = getLocalIPAddress();
      } catch (SocketException e) {
        Log.e(TAG, "获取本地IP失败", e);
      }

      if(localIp.isEmpty())
        logAndToast("获取不到ip地址，要先连接wifi");
      else
      {
        receiverView.setText("本机IP:"+localIp+",请对方输入");
        sIsServer=true;
        // 启动全局TCP监听服务
        startGlobalListener();
      }

    }
  };

  private final OnClickListener connectListener = new OnClickListener() {
    @Override
    public void onClick(View view) {
      if(sIsServer)
      {
        logAndToast("你不是呼叫方，不能呼叫");
        return;
      }

      connectToRoom(roomEditText.getText().toString(), false, false, false, 0);
    }
  };

  private final OnClickListener loopbackListener = new OnClickListener() {
    @Override
    public void onClick(View view) {
      connectToRoom(null, false, true, false, 0);
    }
  };
  private void stopGlobalListener()
  {
    if (CallActivity.serverTCP != null&&sIsServer) {
      CallActivity.serverTCP.stopServer();
      CallActivity.serverTCP=null;
    }
  }
  private void startGlobalListener() {
    if (CallActivity.serverTCP == null) {
      Log.d(TAG, "[socket]启动全局TCP监听服务");
      CallActivity.serverTCP = new TCPChannelClient(
          java.util.concurrent.Executors.newSingleThreadExecutor(),
          new TCPChannelClient.TCPChannelEvents() {
            @Override
            public void onTCPConnected(Socket socket, boolean isServer) {
              sIsServer = isServer;
              Log.d(TAG, "[socket]连接from: " + socket.getInetAddress().getHostAddress() + "，isServer: " + isServer);
              mSocket = socket;
              SocketManager.getInstance().setSocket(mSocket);
              // 保存连接，等待Offer消息
            }

            @Override
            public void onTCPMessage(String rawMessage) {
              Log.d(TAG, "[socket]#startGlobalListener$onTCPMessage收到消息: " + rawMessage);

              String remoteIp = mSocket.getInetAddress().getHostAddress(); // 获取真实连接方IP
              Log.d(TAG, "[socket]remoteIp: " + remoteIp);

              if(CallActivity.callCreated)
              {
                CallActivity.sendIncomingMessage(rawMessage);
              }else {
                // 在主线程中启动CallActivity
                runOnUiThread(() -> {
                  Intent intent = new Intent(ConnectActivity.this, CallActivity.class);
                  intent.putExtra(CallActivity.EXTRA_ROOMID, remoteIp);
                  intent.putExtra(CallActivity.EXTRA_LOOPBACK, false);
                  intent.putExtra(CallActivity.EXTRA_IS_SERVER, sIsServer);
                  intent.putExtra(CallActivity.EXTRA_TCP_MSG, rawMessage);
                  intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                  //CallActivity.callCreated=true;
                  startActivity(intent);
                });
              }
            }

            @Override
            public void onTCPError(String description) {
              Log.w(TAG, "监听服务错误: " + description);
            }

            @Override
            public void onTCPClose() {
              Log.d(TAG, "监听服务关闭");
            }
          },
          "0.0.0.0",  // 监听所有IP
          38888       // 固定端口
      );
    }
  }


  public static String getLocalIPAddress() throws SocketException {
    for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
         en.hasMoreElements();) {
      NetworkInterface intf = en.nextElement();
      for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
           enumIpAddr.hasMoreElements();) {
        InetAddress inetAddress = enumIpAddr.nextElement();
        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
          return inetAddress.getHostAddress();
        }
      }
    }
    return "";
  }
  private boolean isSelfConnection(String remoteIp) {
    if (remoteIp.equals("127.0.0.1") || remoteIp.equals("::1")) {
      return true;
    }

    try {
      String localIp = getLocalIPAddress();
      return remoteIp.equals(localIp);
    } catch (SocketException e) {
      Log.e(TAG, "获取本地IP失败", e);
    }
    return false;
  }


  private void handleCandidateMessage(JSONObject json) throws JSONException {

    String sdpMid = json.optString("sdpMid");
    int sdpMLineIndex = Integer.parseInt(json.optString("sdpMLineIndex"));
    String sdp = json.optString("sdp");
    IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);

  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (logToast != null) {
      logToast.cancel();
    }
    restartThread.interrupt();
    // 不要在这里关闭全局监听器，让它保持运行
    // if (globalListener != null) {
    //   globalListener.disconnect();
    //   globalListener = null;
    // }
  }
}
