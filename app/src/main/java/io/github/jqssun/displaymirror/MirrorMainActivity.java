package io.github.jqssun.displaymirror;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.topjohnwu.superuser.Shell;
import io.github.jqssun.displaymirror.job.AcquireShizuku;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import rikka.shizuku.Shizuku;

public class MirrorMainActivity extends AppCompatActivity {
  public static final String ACTION_OPEN_OVERVIEW =
      "io.github.jqssun.displaymirror.action.OPEN_OVERVIEW";
  public static final String ACTION_OPEN_SCREEN =
      "io.github.jqssun.displaymirror.action.OPEN_SCREEN";
  public static final String EXTEND_PACKAGE_NAME = "io.github.jqssun.displayextend";
  public static final String ACTION_OPEN_EXTEND_OVERVIEW =
      "io.github.jqssun.displayextend.action.OPEN_OVERVIEW";
  public static final String ACTION_OPEN_EXTEND_DISPLAY_DETAIL =
      "io.github.jqssun.displayextend.action.OPEN_DISPLAY_DETAIL";
  public static final String ACTION_OPEN_EXTEND_SETTINGS =
      "io.github.jqssun.displayextend.action.OPEN_SETTINGS";
  public static final String EXTRA_DISPLAY_ID = "display_id";
  public static final String EXTRA_SCREEN = "screen";
  public static final String EXTRA_SOURCE_SCREEN = "source_screen";
  public static final String SCREEN_OVERVIEW = "overview";
  public static final String SCREEN_MOONLIGHT = "moonlight";
  public static final String SCREEN_AIRPLAY = "airplay";
  public static final String SCREEN_DISPLAYLINK = "displaylink";
  public static final String SCREEN_SETTINGS = "settings";
  public static final String SOURCE_EXTEND_OVERVIEW = "extend_overview";
  private static final Uri EXTEND_MARKET_URI =
      Uri.parse("market://details?id=" + EXTEND_PACKAGE_NAME);
  private static final Uri EXTEND_PROJECT_URI =
      Uri.parse("https://github.com/jqssun/android-display-extend");

  static {
    Shell.enableVerboseLogging = BuildConfig.DEBUG;
    Shell.setDefaultBuilder(
        Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(10));
  }

  public static final int REQUEST_RECORD_AUDIO_PERMISSION = 1002;

  private NavController navController;
  private OnBackPressedCallback crossAppBackCallback;
  private String crossAppLandingScreen;
  private long lastCheckTime = 0;

  private final ActivityResultLauncher<Intent> mediaProjectionLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
              Intent data = result.getData();
              State.log("User granted screen projection permission");
              lastCheckTime = System.currentTimeMillis();
              if (SunshineService.instance == null) {
                Intent svc = new Intent(this, SunshineService.class);
                svc.putExtra("data", data);
                startForegroundService(svc);
                State.log("Starting SunshineService");
              } else {
                MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mpm == null) return;
                State.setMediaProjection(mpm.getMediaProjection(RESULT_OK, data));
                if (State.getMediaProjection() == null) {
                  State.resumeJob();
                  return;
                }
                State.getMediaProjection()
                    .registerCallback(
                        new MediaProjection.Callback() {
                          @Override
                          public void onStop() {
                            super.onStop();
                            State.log("MediaProjection onStop callback");
                          }
                        },
                        null);
                State.resumeJob();
              }
            } else {
              State.log("User denied screen projection permission");
              refresh();
              State.resumeJob();
            }
          });

  private final ActivityResultLauncher<Intent> airplayProjectionLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
              Intent svc = new Intent(this, AirPlayForegroundService.class);
              svc.putExtra("data", result.getData());
              startForegroundService(svc);
            }
          });

  private final ActivityResultLauncher<Intent> importApkLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
              Uri uri = result.getData().getData();
              if (uri != null) {
                try {
                  String err = ApkImporter.importFromApk(this, uri);
                  if (err == null) {
                    Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show();
                    State.log("DisplayLink APK imported successfully");
                  } else {
                    Toast.makeText(this, getString(R.string.import_failed, err), Toast.LENGTH_LONG)
                        .show();
                    State.log("APK import error: " + err);
                  }
                } catch (Exception e) {
                  Toast.makeText(
                          this,
                          getString(R.string.import_failed, e.getMessage()),
                          Toast.LENGTH_LONG)
                      .show();
                  State.log("APK import exception: " + e.getMessage());
                }
                refresh();
              }
            }
          });

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
      State.resumeJob();
    } else {
      State.log("Unknown permission request code: " + requestCode);
    }
  }

  private void _onRequestShizukuPermissionsResult(int requestCode, int grantResult) {
    if (requestCode == AcquireShizuku.SHIZUKU_PERMISSION_REQUEST_CODE) {
      State.log(
          "Shizuku permission result: "
              + (grantResult == PackageManager.PERMISSION_GRANTED ? "granted" : "denied"));
      State.resumeJob();
    } else {
      State.log("Unknown Shizuku request code: " + requestCode);
    }
  }

  private final Shizuku.OnRequestPermissionResultListener REQUEST_PERMISSION_RESULT_LISTENER =
      this::_onRequestShizukuPermissionsResult;

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    if (!BuildConfig.ANDROID2APPLE4MUSIC_ONLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        HiddenApiBypass.addHiddenApiExemptions("");
      } catch (Exception e) {
        android.util.Log.e("MainActivity", "Failed to add hidden API exemption: " + e.getMessage());
      }
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    EdgeToEdge.enable(this);
    super.onCreate(savedInstanceState);
    State.setCurrentActivity(this);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    if (!BuildConfig.ANDROID2APPLE4MUSIC_ONLY) {
      Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
      Shizuku.addBinderReceivedListenerSticky(_binderReceivedListener);
      Shizuku.addBinderDeadListener(_binderDeadListener);
    }

    setContentView(R.layout.activity_main);
    TvFocus.attach(getWindow());
    _setupTopNotice();

    // setup navigation
    NavHostFragment navHostFragment =
        (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
    navController = navHostFragment.getNavController();
    MaterialToolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    AppBarConfiguration appBarConfig =
        new AppBarConfiguration.Builder(R.id.airplay_fragment).build();
    NavigationUI.setupActionBarWithNavController(this, navController, appBarConfig);
    crossAppBackCallback =
        new OnBackPressedCallback(false) {
          @Override
          public void handleOnBackPressed() {
            _returnToExtendOverview();
          }
        };
    getOnBackPressedDispatcher().addCallback(this, crossAppBackCallback);
    navController.addOnDestinationChangedListener(
        (controller, destination, arguments) -> _updateCrossAppBackState());
    _handleLaunchIntent(getIntent());

    State.uiState.observe(this, state -> {});
  }

  private void _setupTopNotice() {
    TextView topNotice = findViewById(R.id.topDedication);
    if (topNotice == null) {
      return;
    }
    SpannableString notice = new SpannableString(getString(R.string.top_notice));
    _linkSponsor(notice, "PelPush.com Accounting", "https://pelpush.com");
    _linkSponsor(notice, "MarkCompass.com", "https://markcompass.com");
    _linkSponsor(notice, "RealArb.com", "https://realarb.com");
    _linkSponsor(notice, "MarcPelberg.com", "https://marcpelberg.com");
    topNotice.setText(notice);
    topNotice.setMovementMethod(LinkMovementMethod.getInstance());
    topNotice.setLinksClickable(true);
  }

  private void _linkSponsor(SpannableString notice, String label, String url) {
    String text = notice.toString();
    int start = text.indexOf(label);
    if (start < 0) {
      return;
    }
    notice.setSpan(new URLSpan(url), start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    _handleLaunchIntent(intent);
  }

  @Override
  public boolean onSupportNavigateUp() {
    NavHostFragment navHostFragment =
        (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
    return NavigationUI.navigateUp(
            navHostFragment.getNavController(),
            new AppBarConfiguration.Builder(R.id.airplay_fragment).build())
        || super.onSupportNavigateUp();
  }

  @Override
  protected void onResume() {
    super.onResume();
    State.setCurrentActivity(this);
    refresh();
  }

  private final Shizuku.OnBinderReceivedListener _binderReceivedListener =
      () -> {
        State.log("Shizuku binder received");
      };

  private final Shizuku.OnBinderDeadListener _binderDeadListener =
      () -> {
        State.log("Shizuku binder DIED");
        io.github.jqssun.displaymirror.shizuku.ServiceUtils.invalidate();
      };

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (!BuildConfig.ANDROID2APPLE4MUSIC_ONLY) {
      Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);
      Shizuku.removeBinderReceivedListener(_binderReceivedListener);
      Shizuku.removeBinderDeadListener(_binderDeadListener);
    }
    State.setCurrentActivity(null);
  }

  public void startMirroring() {
    if (BuildConfig.ANDROID2APPLE4MUSIC_ONLY) {
      requestAirPlayProjection();
      return;
    }
    AcquireShizuku.notifyIfUidDropped();
    if (SunshineService.instance == null) {
      startMediaProjectionService();
    } else {
      State.log("SunshineService already running");
      refresh();
    }
  }

  public void requestAirPlayProjection() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.airplay_share_music_title)
        .setMessage(R.string.airplay_share_music_message)
        .setPositiveButton(
            R.string.airplay_share_music_continue, (dialog, which) -> _launchAirPlayProjection())
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void _launchAirPlayProjection() {
    MediaProjectionManager mpm =
        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    if (mpm != null) {
      Intent captureIntent;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        captureIntent =
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
      } else {
        captureIntent = mpm.createScreenCaptureIntent();
      }
      airplayProjectionLauncher.launch(captureIntent);
    }
  }

  public void startMediaProjectionService() {
    MediaProjectionManager mpm =
        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    if (mpm != null) {
      Intent captureIntent;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        captureIntent =
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
      } else {
        captureIntent = mpm.createScreenCaptureIntent();
      }
      mediaProjectionLauncher.launch(captureIntent);
    } else {
      throw new RuntimeException("Failed to get MediaProjectionManager service");
    }
  }

  private void _handleLaunchIntent(Intent intent) {
    if (intent == null) {
      return;
    }

    boolean doNotAutoStartMoonlight = intent.getBooleanExtra("DoNotAutoStartMoonlight", false);
    if (doNotAutoStartMoonlight) {
      Pref.doNotAutoStartMoonlight = true;
    }

    String action = intent.getAction();
    String sourceScreen = intent.getStringExtra(EXTRA_SOURCE_SCREEN);
    if (ACTION_OPEN_OVERVIEW.equals(action)) {
      _navigateToOverview();
      if (SOURCE_EXTEND_OVERVIEW.equals(sourceScreen)) {
        crossAppLandingScreen = SCREEN_OVERVIEW;
      } else {
        crossAppLandingScreen = null;
      }
    } else if (ACTION_OPEN_SCREEN.equals(action)) {
      _openMirrorScreen(intent.getStringExtra(EXTRA_SCREEN));
      if (SOURCE_EXTEND_OVERVIEW.equals(sourceScreen)) {
        crossAppLandingScreen = _normalizeMirrorScreen(intent.getStringExtra(EXTRA_SCREEN));
      } else {
        crossAppLandingScreen = null;
      }
    } else {
      crossAppLandingScreen = null;
    }
    _updateCrossAppBackState();
  }

  public void refresh() {
    MirrorUiState current = State.uiState.getValue();
    if (current != null && current.errorStatusText != null) {
      return;
    }
    boolean isScreenMirroring =
        State.mirrorVirtualDisplay != null
            || State.displaylinkState.getVirtualDisplay() != null
            || State.lastSingleAppDisplay != 0;

    MirrorUiState newUiState = new MirrorUiState();

    if (SunshineService.instance == null) {
      newUiState.startBtnVisibility = true;
    } else if (isScreenMirroring) {
      newUiState.stopBtnVisibility = true;
    } else {
      newUiState.stopBtnVisibility = true;
    }

    State.uiState.setValue(newUiState);
  }

  public void downloadDisplayLink(MaterialButton downloadBtn) {
    downloadBtn.setEnabled(false);
    downloadBtn.setText(R.string.downloading_displaylink);
    String url = Pref.getDisplaylinkApkUrl();
    new Thread(
            () -> {
              try {
                String err =
                    ApkImporter.downloadAndImport(
                        this,
                        url,
                        hundredths ->
                            runOnUiThread(
                                () ->
                                    downloadBtn.setText(
                                        String.format("%.2f MB", hundredths / 100.0))));
                runOnUiThread(
                    () -> {
                      downloadBtn.setEnabled(true);
                      downloadBtn.setText(R.string.auto_import_displaylink_libs);
                      if (err == null) {
                        Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show();
                        State.log("DisplayLink libraries downloaded and imported successfully");
                      } else {
                        Toast.makeText(
                                this, getString(R.string.import_failed, err), Toast.LENGTH_LONG)
                            .show();
                        State.log("Download import error: " + err);
                      }
                      refresh();
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      downloadBtn.setEnabled(true);
                      downloadBtn.setText(R.string.auto_import_displaylink_libs);
                      Toast.makeText(
                              this,
                              getString(R.string.import_failed, e.getMessage()),
                              Toast.LENGTH_LONG)
                          .show();
                      State.log("Download exception: " + e.getMessage());
                    });
              }
            })
        .start();
  }

  public void importApk() {
    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    pick.setType("application/vnd.android.package-archive");
    pick.addCategory(Intent.CATEGORY_OPENABLE);
    importApkLauncher.launch(pick);
  }

  public void manageDisplayInExtend(int displayId, String sourceScreen) {
    if (displayId < 0) {
      return;
    }

    Intent intent = new Intent(ACTION_OPEN_EXTEND_DISPLAY_DETAIL);
    intent.setPackage(EXTEND_PACKAGE_NAME);
    intent.addCategory(Intent.CATEGORY_DEFAULT);
    intent.putExtra(EXTRA_DISPLAY_ID, displayId);
    intent.putExtra(EXTRA_SOURCE_SCREEN, sourceScreen);
    _startExtendIntentOrFallback(intent);
  }

  public void openExtendSettings() {
    Intent intent = new Intent(ACTION_OPEN_EXTEND_SETTINGS);
    intent.setPackage(EXTEND_PACKAGE_NAME);
    intent.addCategory(Intent.CATEGORY_DEFAULT);
    intent.putExtra(EXTRA_SOURCE_SCREEN, SCREEN_SETTINGS);
    _startExtendIntentOrFallback(intent);
  }

  private void _navigateToOverview() {
    if (navController != null
        && navController.getCurrentDestination() != null
        && navController.getCurrentDestination().getId() != R.id.airplay_fragment) {
      navController.popBackStack(R.id.airplay_fragment, false);
    }
  }

  private void _navigateToSettings() {
    if (navController != null
        && navController.getCurrentDestination() != null
        && navController.getCurrentDestination().getId() != R.id.airplay_fragment) {
      navController.popBackStack(R.id.airplay_fragment, false);
    }
  }

  private void _openMirrorScreen(String screen) {
    String normalizedScreen = _normalizeMirrorScreen(screen);
    if (SCREEN_OVERVIEW.equals(normalizedScreen)) {
      _navigateToOverview();
      return;
    }
    if (SCREEN_SETTINGS.equals(normalizedScreen)) {
      _navigateToSettings();
      return;
    }
    if (_isOnMirrorScreen(normalizedScreen)) {
      return;
    }

    _navigateToOverview();
    if (navController == null) {
      return;
    }

    int destinationId = _getMirrorDestinationId(normalizedScreen);
    if (destinationId != -1 && !_isCurrentDestination(destinationId)) {
      navController.navigate(destinationId);
    }
  }

  private String _normalizeMirrorScreen(String screen) {
    return SCREEN_AIRPLAY;
  }

  private int _getMirrorDestinationId(String screen) {
    return R.id.airplay_fragment;
  }

  private boolean _isOnMirrorScreen(String screen) {
    return _isCurrentDestination(_getMirrorDestinationId(screen));
  }

  private boolean _isCurrentDestination(int destinationId) {
    return navController != null
        && navController.getCurrentDestination() != null
        && navController.getCurrentDestination().getId() == destinationId;
  }

  private void _updateCrossAppBackState() {
    if (crossAppBackCallback == null) {
      return;
    }
    crossAppBackCallback.setEnabled(
        crossAppLandingScreen != null && _isOnMirrorScreen(crossAppLandingScreen));
  }

  private void _returnToExtendOverview() {
    Intent intent = new Intent(ACTION_OPEN_EXTEND_OVERVIEW);
    intent.setPackage(EXTEND_PACKAGE_NAME);
    intent.addCategory(Intent.CATEGORY_DEFAULT);

    crossAppLandingScreen = null;
    _updateCrossAppBackState();

    if (intent.resolveActivity(getPackageManager()) != null) {
      startActivity(intent);
    }
    moveTaskToBack(true);
  }

  private void _startExtendIntentOrFallback(Intent intent) {
    if (intent.resolveActivity(getPackageManager()) == null) {
      Toast.makeText(this, R.string.extend_app_not_installed, Toast.LENGTH_SHORT).show();
      Intent market = new Intent(Intent.ACTION_VIEW, EXTEND_MARKET_URI);
      if (market.resolveActivity(getPackageManager()) != null) {
        startActivity(market);
      } else {
        startActivity(new Intent(Intent.ACTION_VIEW, EXTEND_PROJECT_URI));
      }
      return;
    }
    startActivity(intent);
  }

  public String getCurrentScreen() {
    return SCREEN_AIRPLAY;
  }
}
