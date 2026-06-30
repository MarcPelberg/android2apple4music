package io.github.jqssun.displaymirror.job;

final class AirPlayVolume {
  private AirPlayVolume() {}

  static int musicVolumeToPercent(int volume, int maxVolume) {
    if (maxVolume <= 0) {
      return 0;
    }
    int clamped = Math.max(0, Math.min(maxVolume, volume));
    return Math.round((clamped * 100f) / maxVolume);
  }

  static boolean shouldSyncFromPhoneVolume(
      boolean phoneVolumeSyncEnabled, boolean localMuteActive) {
    return phoneVolumeSyncEnabled && !localMuteActive;
  }

  static boolean shouldSyncMutedPhoneVolume(
      boolean phoneVolumeSyncEnabled, int volume, int maxVolume) {
    return phoneVolumeSyncEnabled && maxVolume > 0 && volume > 0;
  }

  static int volumeStepPercent(int maxVolume) {
    if (maxVolume <= 0) {
      return 1;
    }
    return Math.max(1, Math.round(100f / maxVolume));
  }
}
