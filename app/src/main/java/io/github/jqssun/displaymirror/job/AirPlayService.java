package io.github.jqssun.displaymirror.job;

import static io.github.jqssun.displaymirror.MirrorMainActivity.REQUEST_RECORD_AUDIO_PERMISSION;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import io.github.jqssun.displaymirror.AirPlayForegroundService;
import io.github.jqssun.displaymirror.MirrorMainActivity;
import io.github.jqssun.displaymirror.Pref;
import io.github.jqssun.displaymirror.State;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

public class AirPlayService {
  private static final String TAG = "AirPlayService";
  private static AirPlayService instance;
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());

  public interface AirPlayListener {
    void onDeviceFound(String name, String ip, int port);

    void onConnected();

    void onDisconnected(String error);

    void onError(String error);

    void onPinRequired();

    void onVolumeChanged(int percent);
  }

  private airplaylib.Session session;
  private AirPlayListener listener;
  private final List<AirPlayDevice> devices = new ArrayList<>();
  private boolean connected;
  private AirPlayEncoder encoder;
  private AirPlayAudioCapture audioCapture;
  private MediaProjection pendingProjection;
  // pending connect params, used after projection is granted
  private String pendingHost;
  private int pendingPort;
  private String pendingPin;
  private int pendingWidth, pendingHeight, pendingFps;
  private boolean pendingAudioOnly;
  private int volumePercent = 100;
  private boolean phoneVolumeSyncEnabled = true;
  private boolean mutePhoneSpeakerEnabled = true;
  private boolean localPhoneMuteActive;
  private ContentObserver phoneVolumeObserver;

  public static class AirPlayDevice {
    public String name;
    public String ip;
    public int port;

    public AirPlayDevice(String name, String ip, int port) {
      this.name = name;
      this.ip = ip;
      this.port = port;
    }

    @Override
    public String toString() {
      return name + " [" + ip + "]";
    }
  }

  public static AirPlayService getInstance() {
    if (instance == null) {
      instance = new AirPlayService();
    }
    return instance;
  }

  public void setListener(AirPlayListener listener) {
    this.listener = listener;
  }

  public List<AirPlayDevice> getDevices() {
    return devices;
  }

  public boolean isConnected() {
    return connected;
  }

  public int getVolumePercent() {
    return volumePercent;
  }

  public void setVolumePercent(int percent) {
    int clamped = Math.max(0, Math.min(100, percent));
    boolean changed = volumePercent != clamped;
    volumePercent = clamped;
    if (session != null && connected) {
      session.setVolumePercent(clamped);
    }
    if (changed) {
      _notifyVolumeChanged(clamped);
    }
  }

  public int adjustVolume(int direction) {
    if (direction == AudioManager.ADJUST_MUTE) {
      setVolumePercent(0);
      return volumePercent;
    }
    int step = _phoneVolumeStepPercent();
    if (direction == AudioManager.ADJUST_RAISE) {
      setVolumePercent(volumePercent + step);
    } else if (direction == AudioManager.ADJUST_LOWER) {
      setVolumePercent(volumePercent - step);
    }
    return volumePercent;
  }

  public boolean isPhoneVolumeSyncEnabled() {
    return phoneVolumeSyncEnabled;
  }

  public void setPhoneVolumeSyncEnabled(boolean enabled) {
    phoneVolumeSyncEnabled = true;
    Pref.setAirPlayPhoneVolumeSync(true);
    _updatePhoneVolumeObserver();
    if (AirPlayVolume.shouldSyncFromPhoneVolume(phoneVolumeSyncEnabled, localPhoneMuteActive)) {
      syncVolumeFromPhone();
    }
  }

  public boolean isMutePhoneSpeakerEnabled() {
    return mutePhoneSpeakerEnabled;
  }

  public void setMutePhoneSpeakerEnabled(boolean enabled) {
    mutePhoneSpeakerEnabled = true;
    Pref.setAirPlayMutePhoneSpeaker(true);
    _applyLocalPhoneMute();
    _updatePhoneVolumeObserver();
  }

  public static int musicVolumeToPercent(int volume, int maxVolume) {
    return AirPlayVolume.musicVolumeToPercent(volume, maxVolume);
  }

  public void syncVolumeFromPhone() {
    Context ctx = State.getContext();
    if (ctx == null) {
      return;
    }
    AudioManager audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager == null) {
      return;
    }
    setVolumePercent(
        musicVolumeToPercent(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
  }

  private void _ensureSession() {
    if (session != null) return;
    airplaylib.Session s =
        airplaylib.Airplaylib.newSession(
            new airplaylib.EventHandler() {
              @Override
              public void onDeviceFound(String deviceJSON) {}

              @Override
              public void onConnected() {
                connected = true;
                phoneVolumeSyncEnabled = true;
                mutePhoneSpeakerEnabled = true;
                Pref.setAirPlayPhoneVolumeSync(true);
                Pref.setAirPlayMutePhoneSpeaker(true);
                if (AirPlayVolume.shouldSyncFromPhoneVolume(
                    phoneVolumeSyncEnabled, localPhoneMuteActive)) {
                  syncVolumeFromPhone();
                } else {
                  setVolumePercent(volumePercent);
                }
                _updatePhoneVolumeObserver();
                State.log("AirPlay connected, requesting projection...");
                // now request projection: only after AirPlay handshake succeeded
                mainHandler.post(
                    () -> {
                      MirrorMainActivity activity = State.getCurrentActivity();
                      if (activity != null) {
                        activity.requestAirPlayProjection();
                      } else {
                        State.log("AirPlay: no activity for projection request");
                      }
                      if (listener != null) listener.onConnected();
                    });
              }

              @Override
              public void onDisconnected(String err) {
                connected = false;
                _unregisterPhoneVolumeObserver();
                _restoreLocalPhoneAudio();
                _stopEncoder();
                _stopForegroundService();
                mainHandler.post(
                    () -> {
                      if (listener != null) listener.onDisconnected(err);
                    });
                State.log("AirPlay disconnected: " + err);
              }

              @Override
              public void onPinRequired() {
                State.log("AirPlay PIN required");
                session = null;
                mainHandler.post(
                    () -> {
                      if (listener != null) listener.onPinRequired();
                    });
              }

              @Override
              public void onError(String err) {
                // reset session so next connect attempt starts fresh
                if (!connected) {
                  session = null;
                }
                mainHandler.post(
                    () -> {
                      if (listener != null) listener.onError(err);
                    });
                State.log("AirPlay error: " + err);
              }

              @Override
              public void onLog(String msg) {
                State.log(msg);
              }
            });
    session = s;
  }

  public void discover() {
    devices.clear();
    State.log("AirPlay: scanning for devices...");
    new Thread(
            () -> {
              try {
                InetAddress addr = _getWifiAddress();
                if (addr == null) {
                  State.log("AirPlay: no network address found");
                  return;
                }
                JmDNS jmdns = JmDNS.create(addr);
                jmdns.addServiceListener(
                    "_airplay._tcp.local.",
                    new ServiceListener() {
                      @Override
                      public void serviceAdded(ServiceEvent event) {
                        jmdns.requestServiceInfo(event.getType(), event.getName(), 3000);
                      }

                      @Override
                      public void serviceResolved(ServiceEvent event) {
                        String name = event.getName();
                        int port = event.getInfo().getPort();
                        String ip = null;
                        for (InetAddress a : event.getInfo().getInetAddresses()) {
                          if (a instanceof java.net.Inet4Address) {
                            ip = a.getHostAddress();
                            break;
                          }
                        }
                        if (ip == null) return;
                        String key = ip + ":" + port;
                        for (AirPlayDevice d : devices) {
                          if ((d.ip + ":" + d.port).equals(key)) return;
                        }
                        AirPlayDevice dev = new AirPlayDevice(name, ip, port);
                        devices.add(dev);
                        State.log("AirPlay: found " + name + " at " + ip + ":" + port);
                        final String devIp = ip;
                        mainHandler.post(
                            () -> {
                              if (listener != null) listener.onDeviceFound(name, devIp, port);
                            });
                      }

                      @Override
                      public void serviceRemoved(ServiceEvent event) {}
                    });

                Thread.sleep(5000);
                jmdns.close();
              } catch (Exception e) {
                Log.e(TAG, "discover failed", e);
                State.log("AirPlay discover error: " + e.getMessage());
              }
            })
        .start();
  }

  // step 1: User hits connect → start AirPlay handshake (no projection yet)
  public void connect(String host, int port, String pin, int width, int height, int fps) {
    // tear down any previous session/attempt
    if (session != null) {
      session.disconnect();
      session = null;
    }
    connected = false;
    pendingAudioOnly = false;

    if (!_ensureRecordAudioPermission()) {
      return;
    }

    pendingHost = host;
    pendingPort = port;
    pendingPin = pin;
    pendingFps = fps;
    pendingAudioOnly = !Pref.getAirPlay1Mode() && (width <= 0 || height <= 0);

    if (pendingAudioOnly) {
      pendingWidth = 0;
      pendingHeight = 0;
    } else {
      pendingWidth = width;
      pendingHeight = height;
      if (pendingWidth <= 0 || pendingHeight <= 0) {
        // get screen dimensions now (doesn't need projection)
        Context ctx = State.getContext();
        if (ctx != null) {
          android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
          ((android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE))
              .getDefaultDisplay()
              .getRealMetrics(dm);
          pendingWidth = dm.widthPixels;
          pendingHeight = dm.heightPixels;
        }
      }
    }
    State.log(
        "AirPlay: connecting to "
            + host
            + ":"
            + port
            + (pendingAudioOnly
                ? " (audio only)"
                : " (" + pendingWidth + "x" + pendingHeight + ")"));

    airplaylib.Airplaylib.setAppleReceiver(Pref.getAirPlayAppleReceiver());
    _ensureSession();
    session.connect(host, port, pin, pendingWidth, pendingHeight, pendingFps);
  }

  // step 2: Called from AirPlayForegroundService after projection is granted
  public void onProjectionReady(MediaProjection projection) {
    State.log(
        pendingAudioOnly
            ? "AirPlay: projection granted, starting audio capture"
            : "AirPlay: projection granted, starting encoder");
    pendingProjection = projection;
    _startEncoder();
  }

  private void _startEncoder() {
    if (pendingProjection == null) {
      State.log("AirPlay: no projection for encoder");
      return;
    }
    MediaProjection projection = pendingProjection;
    if (audioCapture != null) {
      audioCapture.stop();
    }
    audioCapture = new AirPlayAudioCapture();
    if (!audioCapture.start(State.getContext(), projection, session)) {
      State.log("AirPlay: audio capture failed, stopping route");
      disconnect();
      _stopForegroundService();
      return;
    }
    _applyLocalPhoneMute();
    if (pendingAudioOnly) {
      pendingProjection = null;
      State.log("AirPlay: audio-only mode, video encoder skipped");
      return;
    }
    encoder = new AirPlayEncoder();
    encoder.start(projection, pendingFps);
    pendingProjection = null;
    if (session != null && encoder.screenWidth > 0 && encoder.screenHeight > 0) {
      session.setAirPlay1FrameSize(encoder.screenWidth, encoder.screenHeight);
    }
  }

  public void sendFrame(byte[] annexBData, boolean isKeyframe) {
    if (session == null || !connected) return;
    session.sendFrame(annexBData, isKeyframe);
  }

  public void disconnect() {
    _stopEncoder();
    pendingProjection = null;
    _unregisterPhoneVolumeObserver();
    _restoreLocalPhoneAudio();
    if (session != null) {
      session.disconnect();
      session = null;
    }
    pendingAudioOnly = false;
    connected = false;
  }

  private void _notifyVolumeChanged(int percent) {
    AirPlayForegroundService.syncRemoteVolume(percent);
    mainHandler.post(
        () -> {
          if (listener != null) listener.onVolumeChanged(percent);
        });
  }

  private void _updatePhoneVolumeObserver() {
    if (!connected || (!phoneVolumeSyncEnabled && !localPhoneMuteActive)) {
      _unregisterPhoneVolumeObserver();
      return;
    }
    if (phoneVolumeObserver != null) {
      return;
    }
    Context ctx = State.getContext();
    if (ctx == null) {
      return;
    }
    phoneVolumeObserver =
        new ContentObserver(mainHandler) {
          @Override
          public void onChange(boolean selfChange) {
            if (localPhoneMuteActive) {
              _maintainLocalPhoneMute();
              return;
            }
            if (AirPlayVolume.shouldSyncFromPhoneVolume(
                phoneVolumeSyncEnabled, localPhoneMuteActive)) {
              syncVolumeFromPhone();
            }
          }
        };
    ctx.getContentResolver()
        .registerContentObserver(Settings.System.CONTENT_URI, true, phoneVolumeObserver);
    State.log(
        localPhoneMuteActive
            ? "AirPlay: phone speaker mute guard on"
            : "AirPlay: phone volume sync on");
  }

  private AudioManager _getAudioManager() {
    Context ctx = State.getContext();
    if (ctx == null) {
      return null;
    }
    return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
  }

  private int _phoneVolumeStepPercent() {
    AudioManager audioManager = _getAudioManager();
    if (audioManager == null) {
      return 5;
    }
    return AirPlayVolume.volumeStepPercent(
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
  }

  private void _applyLocalPhoneMute() {
    if (!mutePhoneSpeakerEnabled || !connected || audioCapture == null) {
      return;
    }
    AudioManager audioManager = _getAudioManager();
    if (audioManager == null) {
      return;
    }
    try {
      int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
      int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      if (AirPlayVolume.shouldSyncMutedPhoneVolume(
          phoneVolumeSyncEnabled, current, maxVolume)) {
        setVolumePercent(musicVolumeToPercent(current, maxVolume));
      }
      if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
      }
      localPhoneMuteActive = true;
      _updatePhoneVolumeObserver();
      State.log("AirPlay: phone speaker muted while HomePod plays");
    } catch (Exception e) {
      State.log("AirPlay: phone speaker mute failed: " + e.getMessage());
    }
  }

  private void _maintainLocalPhoneMute() {
    if (!mutePhoneSpeakerEnabled || !localPhoneMuteActive) {
      return;
    }
    AudioManager audioManager = _getAudioManager();
    if (audioManager == null) {
      return;
    }
    try {
      int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
      int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      if (AirPlayVolume.shouldSyncMutedPhoneVolume(
          phoneVolumeSyncEnabled, current, maxVolume)) {
        setVolumePercent(musicVolumeToPercent(current, maxVolume));
      }
      if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
      }
    } catch (Exception e) {
      State.log("AirPlay: phone speaker re-mute failed: " + e.getMessage());
    }
  }

  private void _restoreLocalPhoneAudio() {
    if (!localPhoneMuteActive) {
      return;
    }
    AudioManager audioManager = _getAudioManager();
    localPhoneMuteActive = false;
    if (audioManager == null) {
      return;
    }
    try {
      if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
      }
      State.log("AirPlay: phone speaker volume restored");
    } catch (Exception e) {
      State.log("AirPlay: phone speaker restore failed: " + e.getMessage());
    }
  }

  private void _unregisterPhoneVolumeObserver() {
    if (phoneVolumeObserver == null) {
      return;
    }
    Context ctx = State.getContext();
    if (ctx != null) {
      ctx.getContentResolver().unregisterContentObserver(phoneVolumeObserver);
    }
    phoneVolumeObserver = null;
  }

  private void _stopEncoder() {
    if (audioCapture != null) {
      audioCapture.stop();
      audioCapture = null;
    }
    if (encoder != null) {
      encoder.stop();
      encoder = null;
    }
  }

  // called from native C++ via JNI for each Sunshine-encoded video frame
  // this is the piggyback path when Moonlight is also connected
  public static void onNativeVideoFrame(byte[] annexBData, boolean isKeyframe) {
    if (instance != null && instance.connected && instance.session != null) {
      instance.session.sendFrame(annexBData, isKeyframe);
    }
  }

  private static InetAddress _getWifiAddress() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface ni = interfaces.nextElement();
        if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress a = addrs.nextElement();
          if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
            return a;
          }
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "getWifiAddress", e);
    }
    return null;
  }

  private boolean _ensureRecordAudioPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return true;
    }
    Context ctx = State.getContext();
    if (ctx != null
        && ActivityCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
      return true;
    }

    MirrorMainActivity activity = State.getCurrentActivity();
    if (activity != null) {
      ActivityCompat.requestPermissions(
          activity,
          new String[] {Manifest.permission.RECORD_AUDIO},
          REQUEST_RECORD_AUDIO_PERMISSION);
    }
    State.log("AirPlay audio permission required; grant it and connect again");
    if (listener != null) {
      listener.onError("audio permission required; grant it and connect again");
    }
    return false;
  }

  private void _stopForegroundService() {
    Context ctx = State.getContext();
    if (ctx != null) {
      ctx.stopService(new Intent(ctx, AirPlayForegroundService.class));
    }
  }
}
