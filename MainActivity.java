package com.buddy.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
  static final String API = "https://mypersonalautomation.vercel.app/api/chat";
  static final String ORB = "https://mypersonalautomation.vercel.app/orb.html";
  static final int REQ_PHOTO = 7;
  static final Pattern WAKE = Pattern.compile(
    "\\b(hey|hi|ok|okay|hello|wake up)\\s+(buddy|buddi|body)\\b|\\b(buddy|buddi|body)\\s+wake up\\b");
  Ball ball;
  WebView orb;
  TextView txt;
  Button btn;
  SpeechRecognizer sr;
  TextToSpeech tts;
  BroadcastReceiver rc, rb;
  Handler h = new Handler(Looper.getMainLooper());
  WakeWord wake;
  boolean busy = false, on = false, awake = false, torch = false, use3d = false, orbReady = false;
  long lastActive = 0;
  String pin = "";
  String lang = "en-IN";
  JSONObject pending = null;
  JSONObject confirmAct = null;
  Uri photoUri = null;
  String photoQ = "";
  ArrayList<String> hist = new ArrayList<>();
  volatile ArrayList<String> contactNames = new ArrayList<>();

  class Ball extends View {
    float level = 0;
    boolean talk = false;
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    Ball(Context c) { super(c); }
    @Override protected void onDraw(Canvas c) {
      float d = getResources().getDisplayMetrics().density;
      float r = (60 + level * 5) * d;
      float cx = getWidth() / 2f, cy = getHeight() / 2f;
      int a = talk ? 0xFFFFDD88 : 0xFF77DDFF;
      int b = talk ? 0xFFCC6600 : 0xFF0066CC;
      p.setShader(new RadialGradient(cx, cy, r, a, b, Shader.TileMode.CLAMP));
      c.drawCircle(cx, cy, r, p);
    }
  }

  void lvl(float v) {
    ball.level = v;
    ball.invalidate();
    if (use3d && orbReady) orb.evaluateJavascript("setLevel(" + v + ")", null);
  }

  void setTalk(boolean t) {
    ball.talk = t;
    ball.invalidate();
    if (use3d && orbReady) orb.evaluateJavascript("setMode(" + t + ")", null);
  }

  Runnable pulser = new Runnable() {
    public void run() {
      if (ball.talk) {
        lvl(2 + (float) Math.random() * 6);
        h.postDelayed(this, 100);
      }
    }
  };

  void applyLook() {
    if (use3d) {
      orbReady = false;
      orb.setVisibility(View.VISIBLE);
      ball.setVisibility(View.GONE);
      orb.loadUrl(ORB);
    } else {
      orb.setVisibility(View.GONE);
      ball.setVisibility(View.VISIBLE);
    }
  }

  @Override protected void onCreate(Bundle s) {
    super.onCreate(s);
    SharedPreferences sp = getSharedPreferences("b", 0);
    pin = sp.getString("pin", "");
    use3d = sp.getBoolean("d3", false);
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setBackgroundColor(0xFF05080F);
    l.setPadding(0, 0, 0, 80);
    FrameLayout stage = new FrameLayout(this);
    ball = new Ball(this);
    stage.addView(ball, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    orb = new WebView(this);
    orb.setBackgroundColor(0xFF05080F);
    orb.getSettings().setJavaScriptEnabled(true);
    orb.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String u) {
        orbReady = true;
        if (ball.talk) v.evaluateJavascript("setMode(true)", null);
      }
      @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (r.isForMainFrame()) {
          use3d = false;
          orbReady = false;
          orb.setVisibility(View.GONE);
          ball.setVisibility(View.VISIBLE);
        }
      }
    });
    stage.addView(orb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    l.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    txt = new TextView(this);
    txt.setTextColor(0xFF99CCFF);
    txt.setTextSize(18);
    txt.setGravity(Gravity.CENTER);
    txt.setPadding(40, 20, 40, 20);
    txt.setText("Tap to start");
    l.addView(txt);
    btn = new Button(this);
    btn.setText("Tap to start");
    btn.setOnClickListener(v -> { if (on) stopAll(); else start(); });
    l.addView(btn);
    Button lb = new Button(this);
    lb.setText("Language: English");
    lb.setOnClickListener(v -> {
      if (lang.equals("en-IN")) { lang = "te-IN"; lb.setText("Language: Telugu"); }
      else if (lang.equals("te-IN")) { lang = "hi-IN"; lb.setText("Language: Hindi"); }
      else { lang = "en-IN"; lb.setText("Language: English"); }
      if (on && !busy && sr != null) { sr.cancel(); listen(); }
    });
    l.addView(lb);
    Button db = new Button(this);
    db.setText(use3d ? "Look: 3D" : "Look: 2D");
    db.setOnClickListener(v -> {
      use3d = !use3d;
      getSharedPreferences("b", 0).edit().putBoolean("d3", use3d).apply();
      db.setText(use3d ? "Look: 3D" : "Look: 2D");
      applyLook();
    });
    l.addView(db);
    Button vb = new Button(this);
    vb.setText("Devices");
    vb.setOnClickListener(v -> editDevices());
    l.addView(vb);
    setContentView(l);
    applyLook();
    wake = new WakeWord(this, new WakeWord.Callback() {
      public void onStatus(String s) { txt.setText(s); }
      public void onReady() { if (on && !awake) listen(); }
      public void onWake() {
        if (awake || !on) return;
        wake.stop();
        awake = true;
        lastActive = System.currentTimeMillis();
        speakLocal("Yes?");
      }
    });
    tts = new TextToSpeech(this, st -> {
      tts.setLanguage(new Locale("en", "IN"));
      tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        public void onStart(String id) {}
        public void onDone(String id) { h.post(() -> doneTalking()); }
        public void onError(String id) { h.post(() -> doneTalking()); }
      });
    });
    rc = new BroadcastReceiver() {
      public void onReceive(Context c, Intent i) { h.post(() -> stopAll()); }
    };
    IntentFilter f = new IntentFilter("com.buddy.app.STOP");
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, f, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(rc, f);
    rb = new BroadcastReceiver() {
      public void onReceive(Context c, Intent i) {
        h.post(() -> { if (on && !busy) speakLocal("Battery is low. Please charge me."); });
      }
    };
    registerReceiver(rb, new IntentFilter(Intent.ACTION_BATTERY_LOW));
  }

  void editDevices() {
    EditText e = new EditText(this);
    e.setSingleLine(false);
    e.setMinLines(4);
    e.setGravity(Gravity.TOP | Gravity.START);
    e.setHint("robot arm=192.168.1.50");
    e.setText(getSharedPreferences("b", 0).getString("devices", ""));
    new AlertDialog.Builder(this).setTitle("Devices: name=address, one per line").setView(e)
      .setPositiveButton("Save", (d, w) -> getSharedPreferences("b", 0).edit()
        .putString("devices", e.getText().toString().trim()).apply())
      .setNegativeButton("Cancel", null).show();
  }

  ArrayList<String[]> deviceList() {
    ArrayList<String[]> out = new ArrayList<>();
    for (String ln : getSharedPreferences("b", 0).getString("devices", "").split("\n")) {
      int i = ln.indexOf('=');
      if (i > 0 && i < ln.length() - 1) out.add(new String[]{ln.substring(0, i).trim(), ln.substring(i + 1).trim()});
    }
    return out;
  }

  void deviceCmd(String name, String cmd) {
    String base = null;
    String n = name.toLowerCase();
    for (String[] d : deviceList()) {
      String dn = d[0].toLowerCase();
      if (dn.equals(n) || dn.contains(n) || n.contains(dn)) { base = d[1]; break; }
    }
    if (base == null) { txt.setText("I do not know the device " + name); return; }
    if (!base.startsWith("http")) base = "http://" + base;
    final String url = base.replaceAll("/+$", "") + "/cmd?c=" + Uri.encode(cmd);
    new Thread(() -> {
      String res;
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
        String line = br.readLine();
        res = line == null ? "Sent" : line;
        if (res.length() > 60) res = res.substring(0, 60);
      } catch (Exception e) {
        res = "Could not reach the device";
      }
      final String fr = res;
      h.post(() -> txt.setText(name + ": " + fr));
    }).start();
  }

  void loadContacts() {
    if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
    new Thread(() -> {
      LinkedHashSet<String> set = new LinkedHashSet<>();
      try {
        Cursor c = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
          new String[]{ContactsContract.Contacts.DISPLAY_NAME}, null, null, null);
        if (c != null) {
          while (c.moveToNext() && set.size() < 100) {
            String n = c.getString(0);
            if (n == null) continue;
            n = n.replaceAll("[^\\p{L}\\p{N} ]", "").trim();
            if (n.length() > 0 && n.length() <= 24) set.add(n);
          }
          c.close();
        }
      } catch (Exception e) {}
      contactNames = new ArrayList<>(set);
    }).start();
  }

  void start() {
    if (pin.isEmpty()) {
      EditText e = new EditText(this);
      e.setHint("Secret code");
      new AlertDialog.Builder(this).setTitle("Enter your secret code").setView(e)
        .setPositiveButton("OK", (d, w) -> {
          pin = e.getText().toString().trim();
          getSharedPreferences("b", 0).edit().putString("pin", pin).apply();
          if (!pin.isEmpty()) start();
        }).show();
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
        "android.permission.POST_NOTIFICATIONS"}, 1);
      return;
    }
    if (!Settings.canDrawOverlays(this)) {
      txt.setText("Turn on Display over other apps for Buddy, then come back and tap start");
      startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + getPackageName())));
      return;
    }
    loadContacts();
    wake.prepare();
    on = true;
    awake = true;
    lastActive = System.currentTimeMillis();
    Intent si = new Intent(this, BuddyService.class);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
    btn.setText("Stop");
    txt.setText("Listening...");
    listen();
  }

  void stopAll() {
    on = false;
    awake = false;
    busy = false;
    pending = null;
    confirmAct = null;
    h.removeCallbacksAndMessages(null);
    if (sr != null) sr.cancel();
    if (tts != null) tts.stop();
    wake.stop();
    setTalk(false);
    lvl(0);
    stopService(new Intent(this, BuddyService.class));
    btn.setText("Tap to start");
    txt.setText("Stopped");
  }

  @Override public void onRequestPermissionsResult(int c, String[] p, int[] g) {
    if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) start();
  }

  @Override public void onBackPressed() {
    if (on) moveTaskToBack(true); else super.onBackPressed();
  }

  boolean accessOn() {
    String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    return s != null && s.contains(getPackageName() + "/");
  }

  void applyVoice(Locale loc) {
    tts.setLanguage(loc);
    SharedPreferences sp = getSharedPreferences("b", 0);
    tts.setSpeechRate(sp.getFloat("rate", 1f));
    tts.setPitch(sp.getFloat("pitch", 1f));
    String n = sp.getString("v_" + loc.getLanguage(), "");
    if (n.isEmpty()) return;
    try {
      for (Voice v : tts.getVoices()) {
        if (v.getName().equals(n)) { tts.setVoice(v); break; }
      }
    } catch (Exception e) {}
  }

  void speakLocal(String r) {
    busy = true;
    pending = null;
    txt.setText(r);
    setTalk(true);
    h.post(pulser);
    applyVoice(new Locale("en", "IN"));
    tts.speak(r, TextToSpeech.QUEUE_FLUSH, null, "b");
  }

  void changeVoice() {
    String lc = lang.substring(0, 2);
    try {
      List<Voice> vs = new ArrayList<>();
      for (Voice v : tts.getVoices()) {
        if (v.getLocale().getLanguage().equals(lc)) vs.add(v);
      }
      if (vs.isEmpty()) { speakLocal("I have no other voices for this language."); return; }
      Collections.sort(vs, (x, y) -> x.getName().compareTo(y.getName()));
      SharedPreferences sp = getSharedPreferences("b", 0);
      String cur = sp.getString("v_" + lc, "");
      int idx = 0;
      for (int i = 0; i < vs.size(); i++) {
        if (vs.get(i).getName().equals(cur)) idx = i + 1;
      }
      Voice nv = vs.get(idx % vs.size());
      sp.edit().putString("v_" + lc, nv.getName()).apply();
      String msg = "Okay, this is my new voice.";
      if (lc.equals("te")) msg = "\u0C07\u0C26\u0C3F \u0C28\u0C3E \u0C15\u0C4A\u0C24\u0C4D\u0C24 \u0C17\u0C4A\u0C02\u0C24\u0C41";
      if (lc.equals("hi")) msg = "\u092F\u0939 \u092E\u0947\u0930\u0940 \u0928\u0908 \u0906\u0935\u093E\u091C\u093C \u0939\u0948";
      busy = true;
      pending = null;
      txt.setText(msg);
      setTalk(true);
      h.post(pulser);
      applyVoice(new Locale(lc, "IN"));
      tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "b");
    } catch (Exception e) {
      speakLocal("I could not change the voice.");
    }
  }

  boolean voiceStyle(String s) {
    SharedPreferences sp = getSharedPreferences("b", 0);
    float rate = sp.getFloat("rate", 1f);
    float pitch = sp.getFloat("pitch", 1f);
    boolean ch = true;
    if (s.matches(".*\\b(speak|talk) (slower|slowly)\\b.*") || s.contains("slow down")) rate = Math.max(0.5f, rate - 0.15f);
    else if (s.matches(".*\\b(speak|talk) (faster|quicker)\\b.*") || s.contains("speed up")) rate = Math.min(2f, rate + 0.15f);
    else if (s.contains("deeper voice") || s.contains("lower voice") || s.contains("lower pitch")) pitch = Math.max(0.5f, pitch - 0.15f);
    else if (s.contains("higher voice") || s.contains("higher pitch") || s.contains("sweeter voice")) pitch = Math.min(2f, pitch + 0.15f);
    else if (s.contains("normal voice") || s.contains("reset voice")) { rate = 1f; pitch = 1f; }
    else ch = false;
    if (ch) {
      sp.edit().putFloat("rate", rate).putFloat("pitch", pitch).apply();
      speakLocal("Okay, how is this?");
    }
    return ch;
  }

  boolean isYes(String s) {
    return s.matches(".*\\b(yes|yeah|yep|ok|okay|sure|send|do it|go ahead|haan|ha|avunu|avnu)\\b.*")
      || s.contains("\u0C05\u0C35\u0C41\u0C28\u0C41") || s.contains("\u0939\u093E\u0901") || s.contains("\u0939\u093E\u0902");
  }

  boolean isNo(String s) {
    return s.matches(".*\\b(no|nope|cancel|stop|don't|dont|nahi|vaddu)\\b.*")
      || s.contains("\u0C35\u0C26\u0C4D\u0C26\u0C41") || s.contains("\u0928\u0939\u0940\u0902") || s.contains("\u0928\u0939\u0940");
  }

  void media(String c) {
    try {
      AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
      int key = -1;
      if (c.contains("pause")) key = KeyEvent.KEYCODE_MEDIA_PAUSE;
      else if (c.equals("play") || c.contains("resume")) key = KeyEvent.KEYCODE_MEDIA_PLAY;
      else if (c.contains("next")) key = KeyEvent.KEYCODE_MEDIA_NEXT;
      else if (c.contains("previous")) key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
      if (key != -1) {
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
      } else if (c.contains("volumeup")) {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
      } else if (c.contains("volumedown")) {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
      } else if (c.contains("mute")) {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
      } else if (c.contains("flashlight")) {
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        torch = c.contains("on");
        cm.setTorchMode(cm.getCameraIdList()[0], torch);
      }
    } catch (Exception e) {
      txt.setText("I could not do that.");
    }
  }

  boolean localMedia(String s) {
    String c = null;
    if (s.matches("(pause|pause music|pause song|stop music)")) c = "pause";
    else if (s.matches("(play|resume|resume music|play music)")) c = "play";
    else if (s.matches("(next|next song|skip)")) c = "next";
    else if (s.matches("(previous|previous song)")) c = "previous";
    else if (s.matches("(volume up|louder|increase volume)")) c = "volumeup";
    else if (s.matches("(volume down|quieter|decrease volume|lower volume)")) c = "volumedown";
    else if (s.matches("(mute|unmute)")) c = "mute";
    else if (s.matches("(flashlight on|torch on|turn on flashlight|turn on torch)")) c = "flashlighton";
    else if (s.matches("(flashlight off|torch off|turn off flashlight|turn off torch)")) c = "flashlightoff";
    if (c == null) return false;
    media(c);
    return true;
  }

  String settingsAction(String s) {
    s = s.toLowerCase();
    if (s.contains("wifi") || s.contains("wi-fi") || s.contains("wi fi")) return Settings.ACTION_WIFI_SETTINGS;
    if (s.contains("bluetooth")) return Settings.ACTION_BLUETOOTH_SETTINGS;
    if (s.contains("display") || s.contains("brightness")) return Settings.ACTION_DISPLAY_SETTINGS;
    if (s.contains("sound") || s.contains("volume")) return Settings.ACTION_SOUND_SETTINGS;
    if (s.contains("battery")) return Settings.ACTION_BATTERY_SAVER_SETTINGS;
    if (s.contains("location")) 
        if (s.contains("location")) return Settings.ACTION_LOCATION_SOURCE_SETTINGS;
    if (s.contains("airplane") || s.contains("flight")) return Settings.ACTION_AIRPLANE_MODE_SETTINGS;
    if (s.contains("apps") || s.contains("application")) return Settings.ACTION_APPLICATION_SETTINGS;
    if (s.contains("date") || s.contains("time")) return Settings.ACTION_DATE_SETTINGS;
    if (s.contains("accessibility")) return Settings.ACTION_ACCESSIBILITY_SETTINGS;
    return Settings.ACTION_SETTINGS;
  }

  void closeApp(String t) {
    String pkg = null;
    if (!t.isEmpty()) {
      Intent m = new Intent(Intent.ACTION_MAIN);
      m.addCategory(Intent.CATEGORY_LAUNCHER);
      for (ResolveInfo r : getPackageManager().queryIntentActivities(m, 0)) {
        String label = r.loadLabel(getPackageManager()).toString().toLowerCase();
        String p = r.activityInfo.packageName;
        if ((label.equals(t) || label.contains(t)) && !p.equals(getPackageName())) { pkg = p; break; }
      }
    }
    Intent home = new Intent(Intent.ACTION_MAIN);
    home.addCategory(Intent.CATEGORY_HOME);
    home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(home);
    if (pkg != null) {
      final String kp = pkg;
      h.postDelayed(() -> {
        try { ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(kp); } catch (Exception e) {}
      }, 1500);
    }
    txt.setText(t.isEmpty() ? "Going home" : "Closing " + t);
  }

  boolean openApp(String t) {
    if (t.length() < 3) return false;
    Intent m = new Intent(Intent.ACTION_MAIN);
    m.addCategory(Intent.CATEGORY_LAUNCHER);
    for (ResolveInfo r : getPackageManager().queryIntentActivities(m, 0)) {
      String label = r.loadLabel(getPackageManager()).toString().toLowerCase();
      if (label.equals(t) || label.contains(t)) {
        Intent li = getPackageManager().getLaunchIntentForPackage(r.activityInfo.packageName);
        if (li != null) {
          try { startActivity(li); return true; } catch (Exception e) { return false; }
        }
      }
    }
    return false;
  }

  void listen() {
    if (awake && System.currentTimeMillis() - lastActive > 60000) awake = false;
    if (!awake && wake.ready()) {
      if (sr != null) sr.cancel();
      if (!wake.running()) wake.start();
      txt.setText("Sleeping. Say: Hey Buddy");
      return;
    }
    if (awake && System.currentTimeMillis() - lastActive > 60000) {
      awake = false;
      txt.setText("Sleeping. Say: Hey Buddy");
    }
    if (sr == null) {
      sr = SpeechRecognizer.createSpeechRecognizer(this);
      sr.setRecognitionListener(new RecognitionListener() {
        public void onReadyForSpeech(Bundle b) {}
        public void onBeginningOfSpeech() {}
        public void onRmsChanged(float v) { lvl(Math.max(0, v)); }
        public void onBufferReceived(byte[] b) {}
        public void onEndOfSpeech() {}
        public void onPartialResults(Bundle b) {
          ArrayList<String> p = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
          if (awake && p != null && !p.isEmpty()) txt.setText("You: " + p.get(0));
        }
        public void onEvent(int i, Bundle b) {}
        public void onError(int e) {
          lvl(0);
          if (on && !busy) h.postDelayed(() -> listen(), 600);
        }
        public void onResults(Bundle b) {
          ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
          if (r != null && !r.isEmpty()) ask(r.get(0));
          else if (on) listen();
        }
      });
    }
    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
    i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
    i.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1800);
    i.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1500);
    ArrayList<String> cn = contactNames;
    if (Build.VERSION.SDK_INT >= 33 && !cn.isEmpty()) {
      i.putStringArrayListExtra("android.speech.extra.BIAS_STRINGS",
        new ArrayList<>(cn.subList(0, Math.min(100, cn.size()))));
    }
    sr.startListening(i);
  }

  void takePhoto(String q) {
    photoQ = q;
    try {
      ContentValues cv = new ContentValues();
      cv.put(MediaStore.Images.Media.DISPLAY_NAME, "buddy_" + System.currentTimeMillis() + ".jpg");
      cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      photoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
      Intent ci = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
      ci.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
      busy = true;
      if (sr != null) sr.cancel();
      startActivityForResult(ci, REQ_PHOTO);
    } catch (Exception e) {
      speakLocal("I could not open the camera.");
    }
  }

  @Override protected void onActivityResult(int rq, int rs, Intent data) {
    super.onActivityResult(rq, rs, data);
    if (rq != REQ_PHOTO) return;
    final Uri u = photoUri;
    photoUri = null;
    if (rs != RESULT_OK || u == null) {
      try { if (u != null) getContentResolver().delete(u, null, null); } catch (Exception e) {}
      busy = false;
      if (on) listen();
      return;
    }
    final String pq = photoQ;
    txt.setText("Looking...");
    new Thread(() -> {
      String b64 = null;
      try {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream i1 = getContentResolver().openInputStream(u);
        BitmapFactory.decodeStream(i1, null, o);
        i1.close();
        int ss = 1;
        while (Math.max(o.outWidth, o.outHeight) / ss > 1600) ss *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = ss;
        InputStream i2 = getContentResolver().openInputStream(u);
        Bitmap bm = BitmapFactory.decodeStream(i2, null, o2);
        i2.close();
        float sc = Math.min(1f, 800f / Math.max(bm.getWidth(), bm.getHeight()));
        Bitmap sm = Bitmap.createScaledBitmap(bm,
          Math.max(1, Math.round(bm.getWidth() * sc)), Math.max(1, Math.round(bm.getHeight() * sc)), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        sm.compress(Bitmap.CompressFormat.JPEG, 70, bo);
        b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
      } catch (Exception e) {}
      try { getContentResolver().delete(u, null, null); } catch (Exception e) {}
      final String fb = b64;
      h.post(() -> {
        if (fb == null) speakLocal("I could not read the photo.");
        else callBrain(pq, fb);
      });
    }).start();
  }

  void ask(String heard) {
    String q = heard;
    String ql = q.toLowerCase().trim();
    long now = System.currentTimeMillis();
    if (awake && now - lastActive > 60000) awake = false;
    if (!awake) {
      Matcher m = WAKE.matcher(ql);
      if (!m.find()) {
        if (on) h.postDelayed(() -> listen(), 300);
        return;
      }
      awake = true;
      lastActive = now;
      String rest = ql.substring(m.end()).replaceFirst("^[\\s,.!?]+", "")
        .replaceFirst("^(wake up|wake)\\s*", "").trim();
      if (rest.isEmpty()) { speakLocal("Yes?"); return; }
      q = rest;
      ql = rest;
    }
    lastActive = now;
    if (confirmAct != null) {
      JSONObject ca = confirmAct;
      confirmAct = null;
      if (isNo(ql)) { speakLocal("Okay, cancelled."); return; }
      if (isYes(ql)) { speakLocal("Okay."); pending = ca; return; }
    }
    if (ql.matches("(go to sleep|sleep|sleep now|stop listening|good night)")) {
      awake = false;
      speakLocal("Okay, sleeping. Say Hey Buddy to wake me.");
      return;
    }
    if (ql.equals("close") || ql.equals("go home") || ql.equals("home") || ql.startsWith("close ") || ql.startsWith("exit ")) {
      closeApp(ql.replaceFirst("^(close|exit|go home|home)\\s*", "").replaceFirst("^(the )?", "").trim());
      if (on) h.postDelayed(() -> listen(), 800);
      return;
    }
    if (ql.startsWith("open ") && openApp(ql.substring(5).trim())) {
      txt.setText("Opening " + ql.substring(5).trim());
      if (on) h.postDelayed(() -> listen(), 800);
      return;
    }
    if (ql.contains("change voice") || ql.contains("change your voice") || ql.contains("different voice")) {
      changeVoice();
      return;
    }
    if (voiceStyle(ql)) return;
    if (ql.contains("battery") || ql.contains("charge left") || ql.contains("how much charge")) {
      BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
      Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      int lv = bi == null ? -1 : bi.getIntExtra("level", -1);
      int sc = bi == null ? 100 : bi.getIntExtra("scale", 100);
      int pct = lv < 0 ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : Math.round(lv * 100f / sc);
      int st = bi == null ? -1 : bi.getIntExtra("status", -1);
      boolean chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
      speakLocal("Battery is " + pct + " percent" + (chg ? " and charging." : "."));
      return;
    }
    if (ql.matches(".*\\b(what time|time now|current time|what is the time|what's the time)\\b.*")) {
      speakLocal("It is " + new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date()) + ".");
      return;
    }
    if (ql.matches(".*\\b(what date|today's date|todays date|what day|which day|what is the date|what's the date)\\b.*")) {
      speakLocal("Today is " + new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date()) + ".");
      return;
    }
    if (localMedia(ql)) {
      txt.setText("OK");
      if (on) h.postDelayed(() -> listen(), 600);
      return;
    }
    if (ql.matches(".*\\b(what is this|what's this|what is that|what's that|look at this|identify this|what am i looking at|scan this|read this)\\b.*")) {
      takePhoto(q);
      return;
    }
    callBrain(q, null);
  }

  void callBrain(String q, String img) {
    busy = true;
    txt.setText("You: " + q);
    new Thread(() -> {
      String reply;
      JSONObject act = null;
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(45000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-pin", pin);
        c.setDoOutput(true);
        JSONObject o = new JSONObject();
        o.put("q", q);
        o.put("lang", lang);
        o.put("history", new JSONArray(hist.subList(Math.max(0, hist.size() - 6), hist.size())));
        ArrayList<String> cn = contactNames;
        o.put("contacts", new JSONArray(cn.subList(0, Math.min(80, cn.size()))));
        JSONArray dv = new JSONArray();
        for (String[] d : deviceList()) dv.put(d[0]);
        o.put("devices", dv);
        if (img != null) o.put("image", img);
        c.getOutputStream().write(o.toString().getBytes("UTF-8"));
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        JSONObject res = new JSONObject(sb.toString());
        reply = res.getString("reply");
        act = res.optJSONObject("action");
        if (code == 401) {
          getSharedPreferences("b", 0).edit().remove("pin").apply();
          pin = "";
        }
      } catch (Exception e) {
        reply = "I could not connect.";
      }
      final String r = reply;
      final JSONObject a = act;
      h.post(() -> say(r, q, a));
    }).start();
  }

  void say(String r, String q, JSONObject a) {
    String type = a == null ? "none" : a.optString("type", "none");
    if (type.equals("call") || type.equals("whatsapp")) {
      confirmAct = a;
      String t = a.optString("target", "");
      String x = a.optString("text", "");
      if (type.equals("call")) r = "Call " + t + "? Say yes or no.";
      else if (x.isEmpty()) r = "Open WhatsApp chat with " + t + "? Say yes or no.";
      else r = "Send " + x + " to " + t + " on WhatsApp? Say yes or no.";
      pending = null;
    } else {
      pending = a;
    }
    hist.add("User: " + q);
    hist.add("Buddy: " + r);
    txt.setText(r);
    setTalk(true);
    h.post(pulser);
    Locale loc = new Locale("en", "IN");
    for (char ch : r.toCharArray()) {
      if (ch >= 0x0C00 && ch <= 0x0C7F) { loc = new Locale("te", "IN"); break; }
      if (ch >= 0x0900 && ch <= 0x097F) { loc = new Locale("hi", "IN"); break; }
    }
    applyVoice(loc);
    tts.speak(r, TextToSpeech.QUEUE_FLUSH, null, "b");
  }

  void doneTalking() {
    setTalk(false);
    lvl(0);
    busy = false;
    if (awake) lastActive = System.currentTimeMillis();
    if (pin.isEmpty()) {
      stopAll();
      txt.setText("Wrong code. Tap start to try again.");
      return;
    }
    if (pending != null) {
      JSONObject a = pending;
      pending = null;
      run(a);
    }
    if (on) listen();
  }

  String findNumber(String name) {
    if (name.matches("[+0-9 ()-]+")) return name.replaceAll("[^+0-9]", "");
    if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
    String want = name.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
    String[] words = want.split("\\s+");
    String best = null;
    int bestScore = 0;
    Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
    if (c != null) {
      while (c.moveToNext()) {
        String dn = c.getString(0) == null ? "" : c.getString(0).toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", " ");
        int score = 0;
        if (dn.trim().equals(want)) score = 100;
        else if (dn.contains(want)) score = 50;
        else for (String w : words) if (w.length() > 1 && dn.contains(w)) score += 10;
        if (score > bestScore) { bestScore = score; best = c.getString(1); }
      }
      c.close();
    }
    return best == null ? null : best.replaceAll("[^+0-9]", "");
  }

  void run(JSONObject a) {
    String type = a.optString("type", "none");
    String t = a.optString("target", "");
    String x = a.optString("text", "");
    try {
      if (type.equals("google")) {
        startActivity(new Intent(Intent.ACTION_VIEW,
          Uri.parse("https://www.google.com/search?q=" + Uri.encode(t))));
      } else if (type.equals("youtube")) {
        startActivity(new Intent(Intent.ACTION_VIEW,
          Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(t))));
      } else if (type.equals("call")) {
        String n = findNumber(t);
        if (n == null || n.isEmpty()) { txt.setText("I could not find " + t); return; }
        boolean ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        startActivity(new Intent(ok ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + n)));
      } else if (type.equals("whatsapp")) {
        String n = t.isEmpty() ? null : findNumber(t);
        if (!t.isEmpty() && n == null) txt.setText("I could not find " + t + " in your contacts");
        if (n != null) {
          n = n.replace("+", "");
          if (n.length() == 11 && n.startsWith("0")) n = n.substring(1);
          if (n.length() == 10) n = "91" + n;
          if (!x.isEmpty()) {
            if (accessOn()) BuddyAccess.armedUntil = System.currentTimeMillis() + 15000;
            else txt.setText("Turn on Buddy in Accessibility to send automatically");
          }
        }
        Uri u = Uri.parse("https://wa.me/" + (n == null ? "" : n) + "?text=" + Uri.encode(x));
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        i.setPackage("com.whatsapp");
        try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, u)); }
      } else if (type.equals("open") && !t.isEmpty()) {
        if (!openApp(t.toLowerCase())) txt.setText("I could not find the app " + t);
      } else if (type.equals("alarm")) {
        String[] hm = t.split(":");
        int hh = Integer.parseInt(hm[0].trim());
        int mm = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
        Intent ai = new Intent(AlarmClock.ACTION_SET_ALARM);
        ai.putExtra(AlarmClock.EXTRA_HOUR, hh);
        ai.putExtra(AlarmClock.EXTRA_MINUTES, mm);
        ai.putExtra(AlarmClock.EXTRA_MESSAGE, x);
        ai.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        startActivity(ai);
      } else if (type.equals("timer")) {
        int sec = Integer.parseInt(t.replaceAll("[^0-9]", ""));
        Intent ti = new Intent(AlarmClock.ACTION_SET_TIMER);
        ti.putExtra(AlarmClock.EXTRA_LENGTH, sec);
        ti.putExtra(AlarmClock.EXTRA_MESSAGE, x);
        ti.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        startActivity(ti);
      } else if (type.equals("alarms")) {
        startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
      } else if (type.equals("playstore")) {
        try {
          startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("market://search?q=" + Uri.encode(t) + "&c=apps")));
        } catch (Exception e) {
          startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(t) + "&c=apps")));
        }
      } else if (type.equals("settings")) {
        startActivity(new Intent(settingsAction(t)));
      } else if (type.equals("play")) {
        Intent pi = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        pi.putExtra(SearchManager.QUERY, t);
        pi.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        try { startActivity(pi); } catch (Exception e) {
          startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(t))));
        }
      } else if (type.equals("media")) {
        media(t.toLowerCase());
      } else if (type.equals("device")) {
        deviceCmd(t, x);
      }
    } catch (Exception e) {
      txt.setText("I could not do that.");
    }
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    h.removeCallbacksAndMessages(null);
    try { unregisterReceiver(rc); } catch (Exception e) {}
    try { unregisterReceiver(rb); } catch (Exception e) {}
    stopService(new Intent(this, BuddyService.class));
    if (sr != null) sr.destroy();
    if (tts != null) tts.shutdown();
    wake.close();
  }
        }
