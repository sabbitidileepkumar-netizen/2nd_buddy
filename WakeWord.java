package com.buddy.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WakeWord {
  public interface Callback {
    void onStatus(String s);
    void onReady();
    void onWake();
  }

  static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
  static final String GRAMMAR = "[\"hey buddy\", \"hi buddy\", \"ok buddy\", \"okay buddy\", \"wake up buddy\", \"buddy wake up\", \"buddy\", \"[unk]\"]";

  final Context ctx;
  final Callback cb;
  final Handler h = new Handler(Looper.getMainLooper());
  Model model;
  SpeechService svc;
  volatile boolean loading = false;
  volatile boolean fired = false;

  WakeWord(Context c, Callback cb) {
    this.ctx = c;
    this.cb = cb;
  }

  boolean ready() { return model != null; }
  boolean running() { return svc != null; }

  void prepare() {
    if (model != null || loading) return;
    loading = true;
    new Thread(() -> {
      try {
        File dir = new File(ctx.getFilesDir(), "vosk");
        File ok = new File(dir, "ready");
        File mdl = new File(dir, "vosk-model-small-en-us-0.15");
        if (!ok.exists()) {
          h.post(() -> cb.onStatus("Downloading wake word model (40 MB)..."));
          dir.mkdirs();
          HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
          c.setConnectTimeout(15000);
          c.setReadTimeout(30000);
          ZipInputStream zin = new ZipInputStream(new BufferedInputStream(c.getInputStream()));
          ZipEntry e;
          byte[] buf = new byte[65536];
          String root = dir.getCanonicalPath();
          while ((e = zin.getNextEntry()) != null) {
            File f = new File(dir, e.getName());
            if (!f.getCanonicalPath().startsWith(root)) continue;
            if (e.isDirectory()) { f.mkdirs(); continue; }
            f.getParentFile().mkdirs();
            FileOutputStream fo = new FileOutputStream(f);
            int n;
            while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
          }
          zin.close();
          ok.createNewFile();
        }
        model = new Model(mdl.getAbsolutePath());
        h.post(() -> cb.onStatus("Wake word ready"));
        h.post(() -> cb.onReady());
      } catch (Exception e) {
        model = null;
        h.post(() -> cb.onStatus("Wake word model failed. Using normal listening."));
      }
      loading = false;
    }).start();
  }

  void start() {
    if (model == null || svc != null) return;
    fired = false;
    try {
      Recognizer rec = new Recognizer(model, 16000.0f, GRAMMAR);
      svc = new SpeechService(rec, 16000.0f);
      svc.startListening(new org.vosk.android.RecognitionListener() {
        void check(String s) {
          if (fired) return;
          if (s != null && s.toLowerCase().contains("buddy")) {
            fired = true;
            h.post(() -> cb.onWake());
          }
        }
        public void onPartialResult(String s) { check(s); }
        public void onResult(String s) { check(s); }
        public void onFinalResult(String s) {}
        public void onError(Exception e) {}
        public void onTimeout() {}
      });
    } catch (Exception e) {
      svc = null;
    }
  }

  void stop() {
    SpeechService s = svc;
    svc = null;
    if (s != null) {
      try { s.stop(); } catch (Exception e) {}
      try { s.shutdown(); } catch (Exception e) {}
    }
  }

  void close() {
    stop();
    if (model != null) {
      try { model.close(); } catch (Exception e) {}
      model = null;
    }
  }
               }
