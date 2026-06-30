package io.github.jqssun.displaymirror.job;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class AirPlayServiceTest {
  @Test
  public void musicVolumeToPercentMapsStreamRangeToPercent() {
    assertEquals(0, AirPlayVolume.musicVolumeToPercent(0, 25));
    assertEquals(50, AirPlayVolume.musicVolumeToPercent(5, 10));
    assertEquals(100, AirPlayVolume.musicVolumeToPercent(25, 25));
  }

  @Test
  public void musicVolumeToPercentHandlesInvalidMaxVolume() {
    assertEquals(0, AirPlayVolume.musicVolumeToPercent(10, 0));
    assertEquals(0, AirPlayVolume.musicVolumeToPercent(10, -1));
  }

  @Test
  public void shouldSyncFromPhoneVolumeIgnoresMutedLocalStream() {
    assertEquals(true, AirPlayVolume.shouldSyncFromPhoneVolume(true, false));
    assertEquals(false, AirPlayVolume.shouldSyncFromPhoneVolume(true, true));
    assertEquals(false, AirPlayVolume.shouldSyncFromPhoneVolume(false, false));
  }

  @Test
  public void shouldSyncMutedPhoneVolumeOnlyFromRealVolumeChanges() {
    assertEquals(true, AirPlayVolume.shouldSyncMutedPhoneVolume(true, 4, 10));
    assertEquals(false, AirPlayVolume.shouldSyncMutedPhoneVolume(true, 0, 10));
    assertEquals(false, AirPlayVolume.shouldSyncMutedPhoneVolume(false, 4, 10));
    assertEquals(false, AirPlayVolume.shouldSyncMutedPhoneVolume(true, 4, 0));
  }

  @Test
  public void volumeStepPercentMatchesOnePhoneMediaStep() {
    assertEquals(4, AirPlayVolume.volumeStepPercent(25));
    assertEquals(10, AirPlayVolume.volumeStepPercent(10));
    assertEquals(1, AirPlayVolume.volumeStepPercent(0));
  }

  @Test
  public void captureBufferBytesKeepsSeveralAirPlayFramesQueued() {
    assertEquals(45056, AirPlayAudioQuality.captureBufferBytes(1024, 1408));
    assertEquals(80000, AirPlayAudioQuality.captureBufferBytes(20000, 1408));
    assertEquals(0, AirPlayAudioQuality.captureBufferBytes(0, 1408));
  }

  @Test
  public void outputHeadroomScalesLittleEndianPcm() {
    byte[] pcm =
        new byte[] {
          0x00, 0x00,
          0x64, 0x00,
          (byte) 0x9c, (byte) 0xff,
          (byte) 0xff, 0x7f,
          0x00, (byte) 0x80
        };

    AirPlayAudioQuality.applyOutputHeadroom(pcm, pcm.length);

    assertArrayEquals(
        new byte[] {
          0x00, 0x00,
          0x5a, 0x00,
          (byte) 0xa6, (byte) 0xff,
          (byte) 0x32, 0x73,
          (byte) 0xcd, (byte) 0x8c
        },
        pcm);
  }

  @Test
  public void outputHeadroomIgnoresTrailingPartialSample() {
    byte[] pcm = new byte[] {0x64, 0x00, 0x7f};

    AirPlayAudioQuality.applyOutputHeadroom(pcm, pcm.length);

    assertArrayEquals(new byte[] {0x5a, 0x00, 0x7f}, pcm);
  }
}
