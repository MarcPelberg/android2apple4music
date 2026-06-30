package airplay

import (
	"context"
	"crypto/rand"
	"fmt"
	"io"
	"net"
	"regexp"
	"strconv"
	"sync"
	"time"

	"howett.net/plist"
)

/*
This sets the video key SHA-512 derivation to 16 zero bytes as its session key input instead of the encKey derived from FairPlay. This is needed to support Apple receivers where real FairPlay SAP is used, so we intentionally keep this value bzero'd in the setup phase in order to skip FairPlay in no audio mode.
*/
var AppleReceiver bool

func patchAppleReceiverKey(k []byte) []byte {
	if AppleReceiver {
		return make([]byte, 16)
	}
	return k
}

/*
parses server video data port from the transport header
*/
var portRegex = regexp.MustCompile(`server_port=(\d+)`)

func parseTransportServerPort(transport string) int {
	match := portRegex.FindStringSubmatch(transport)
	if len(match) > 1 {
		port, _ := strconv.Atoi(match[1])
		return port
	}
	return 0
}

type pcmReadCloser struct {
	io.Reader
	close func()
}

func (r *pcmReadCloser) Close() error {
	r.close()
	return nil
}

func NewPCMAudioCapture(r io.Reader) *AudioCapture {
	waitCh := make(chan struct{})
	var closeOnce sync.Once
	closeCapture := func() {
		closeOnce.Do(func() {
			close(waitCh)
		})
	}
	return &AudioCapture{
		pcmPipe: &pcmReadCloser{
			Reader: r,
			close:  closeCapture,
		},
		cancel: closeCapture,
		waitCh: waitCh,
	}
}

func (c *AirPlayClient) SetupAudioOnly(ctx context.Context, cfg StreamConfig) (*MirrorSession, error) {
	sessionUUID := generateUUID()
	clientDeviceID := uuidToMAC(c.sessionID)
	selectedAudioCodec := AudioCodecALAC
	audioStreamConnectionID := int64(time.Now().UnixNano() & 0x7FFFFFFFFFFFFFFF)
	audioURI := fmt.Sprintf("rtsp://%s:%d/%d", c.host, c.port, audioStreamConnectionID)
	audioMode := selectAudioSecurityMode(c.encrypted)
	var audioKey, audioIV, audioChaChaKey []byte
	if audioMode == audioSecurityChaCha {
		var err error
		audioChaChaKey, err = generateAudioChaChaKey(rand.Reader)
		if err != nil {
			dbg("[AUDIO-ONLY] chacha key generation failed: %v; falling back to AES-CBC", err)
			audioMode = audioSecurityLegacyAES
		}
	}
	if audioMode == audioSecurityLegacyAES && c.fpKey != nil && c.fpIV != nil {
		audioKey = c.fpKey
		audioIV = c.fpIV
	}

	audioPorts, err := allocateConsecutiveUDPPorts(3)
	if err != nil {
		return nil, fmt.Errorf("allocate audio ports: %w", err)
	}
	timingConn := audioPorts[0]
	audioCtrlConn := audioPorts[1]
	audioDataConn := audioPorts[2]
	timingPort := timingConn.LocalAddr().(*net.UDPAddr).Port
	audioControlLPort := audioCtrlConn.LocalAddr().(*net.UDPAddr).Port
	go ntpTimingResponder(ctx, timingConn)
	closeAudioPorts := func() {
		timingConn.Close()
		audioCtrlConn.Close()
		audioDataConn.Close()
	}

	audioCT, audioSPF, audioFmt, latMin, latMax, _ := selectedAudioCodec.Info()
	audioRedundant := int64(0)
	if useAudioFEC(audioMode == audioSecurityChaCha) {
		audioRedundant = 2
	}

	setupPhase1Plist := map[string]interface{}{
		"deviceID":       clientDeviceID,
		"sessionUUID":    sessionUUID,
		"timingProtocol": "NTP",
		"timingPort":     int64(timingPort),
	}
	setupPhase1Body, err := plist.Marshal(setupPhase1Plist, plist.BinaryFormat)
	if err != nil {
		closeAudioPorts()
		return nil, fmt.Errorf("marshal audio-only setup phase 1: %w", err)
	}
	setupPhase1RespBody, setupPhase1Headers, err := c.rtspRequest("SETUP", audioURI, "application/x-apple-binary-plist", setupPhase1Body, nil)
	if err != nil {
		closeAudioPorts()
		return nil, fmt.Errorf("SETUP audio-only phase 1: %w", err)
	}

	rtspSessionID := sessionUUID
	if headerSession := setupPhase1Headers["session"]; headerSession != "" {
		rtspSessionID = headerSession
	}

	var setupPhase1Resp map[string]interface{}
	var receiverEventConn net.Conn
	if len(setupPhase1RespBody) > 0 {
		if _, err := plist.Unmarshal(setupPhase1RespBody, &setupPhase1Resp); err != nil {
			closeAudioPorts()
			return nil, fmt.Errorf("unmarshal audio-only setup phase 1 response: %w", err)
		}
		if receiverEventPort := plistInt(setupPhase1Resp["eventPort"]); receiverEventPort > 0 {
			eventAddr := net.JoinHostPort(c.host, strconv.Itoa(receiverEventPort))
			if conn, dialErr := net.DialTimeout("tcp", eventAddr, 3*time.Second); dialErr == nil {
				receiverEventConn = conn
			} else {
				dbg("[AUDIO-ONLY] event connection to %s failed: %v", eventAddr, dialErr)
			}
		}
	}

	audioStreamDesc := map[string]interface{}{
		"type":                    int64(96),
		"streamConnectionID":      audioStreamConnectionID,
		"ct":                      audioCT,
		"spf":                     audioSPF,
		"sr":                      int64(44100),
		"audioFormat":             audioFmt,
		"controlPort":             int64(audioControlLPort),
		"audioMode":               "default",
		"isMedia":                 true,
		"supportsDynamicStreamID": true,
		"latencyMin":              latMin,
		"latencyMax":              latMax,
		"redundantAudio":          audioRedundant,
	}
	if audioRedundant == 0 {
		audioStreamDesc["disableRetransmits"] = true
	}

	audioSetupPlist := map[string]interface{}{"streams": []interface{}{audioStreamDesc}}

	if audioMode == audioSecurityChaCha && len(audioChaChaKey) == 32 {
		audioStreamDesc["shk"] = audioChaChaKey
		audioStreamDesc["streamConnections"] = map[string]interface{}{
			"streamConnectionTypeRTP": map[string]interface{}{
				"streamConnectionKeyUseStreamEncryptionKey": true,
			},
			"streamConnectionTypeRTCP": map[string]interface{}{
				"streamConnectionKeyPort": int64(audioControlLPort),
			},
		}
	} else if c.FpEkey != nil && c.fpIV != nil {
		audioSetupPlist["et"] = int64(32)
		audioSetupPlist["ekey"] = c.FpEkey
		audioSetupPlist["eiv"] = c.fpIV
	}

	audioSetupBody, err := plist.Marshal(audioSetupPlist, plist.BinaryFormat)
	if err != nil {
		closeAudioPorts()
		if receiverEventConn != nil {
			receiverEventConn.Close()
		}
		return nil, fmt.Errorf("marshal audio-only setup: %w", err)
	}

	audioRespBody, _, err := c.rtspRequest("SETUP", audioURI, "application/x-apple-binary-plist", audioSetupBody, map[string]string{
		"Session": rtspSessionID,
	})
	if err != nil {
		closeAudioPorts()
		if receiverEventConn != nil {
			receiverEventConn.Close()
		}
		return nil, fmt.Errorf("SETUP audio-only phase 2: %w", err)
	}

	var audioResp map[string]interface{}
	if _, err := plist.Unmarshal(audioRespBody, &audioResp); err != nil {
		closeAudioPorts()
		if receiverEventConn != nil {
			receiverEventConn.Close()
		}
		return nil, fmt.Errorf("unmarshal audio-only setup response: %w", err)
	}

	audioDataPort := 0
	audioControlPort := 0
	if streams, ok := audioResp["streams"].([]interface{}); ok {
		for _, s := range streams {
			stream, ok := s.(map[string]interface{})
			if !ok || plistInt(stream["type"]) != 96 {
				continue
			}
			audioDataPort, audioControlPort = plistStreamPorts(stream)
		}
	}
	if audioDataPort == 0 {
		closeAudioPorts()
		if receiverEventConn != nil {
			receiverEventConn.Close()
		}
		return nil, fmt.Errorf("audio-only receiver did not provide audio data port")
	}

	audioLatencySamples := uint32(0)
	recordHeaders := map[string]string{
		"Session":  rtspSessionID,
		"Range":    "npt=0-",
		"RTP-Info": "seq=0;rtptime=0",
	}
	_, recordRespHeaders, err := c.rtspRequest("RECORD", audioURI, "", nil, recordHeaders)
	if err != nil {
		closeAudioPorts()
		if receiverEventConn != nil {
			receiverEventConn.Close()
		}
		return nil, fmt.Errorf("RECORD audio-only: %w", err)
	}
	if value, ok := recordRespHeaders["audio-latency"]; ok {
		if parsed, parseErr := strconv.ParseUint(value, 10, 32); parseErr == nil && parsed > 0 {
			audioLatencySamples = uint32(parsed)
		}
	}

	volumeBody := []byte("volume: 0.000000\r\n")
	_, _, _ = c.rtspRequest("SET_PARAMETER", audioURI, "text/parameters", volumeBody, nil)
	_, _, _ = c.rtspRequest("SET_PARAMETER", audioURI, "text/parameters", volumeBody, nil)

	firstFrameSent := make(chan struct{})
	close(firstFrameSent)
	session := &MirrorSession{
		client:         c,
		firstFrameSent: firstFrameSent,
		noAudio:        cfg.NoAudio,
		eventConn:      receiverEventConn,
		sessionURI:     audioURI,
		timingConn:     timingConn,
	}
	audioStream, err := session.setupAudioStream(audioDataPort, audioControlPort, audioKey, audioIV, audioChaChaKey, audioMode, byte(selectedAudioCodec), audioLatencySamples, audioCtrlConn, audioDataConn)
	if err != nil {
		closeAudioPorts()
		if receiverEventConn != nil {
			receiverEventConn.Close()
		}
		return nil, fmt.Errorf("setup audio-only RTP: %w", err)
	}
	session.audioStream = audioStream
	go session.heartbeatLoop(ctx, audioURI, rtspSessionID)
	go session.feedbackLoop(ctx, audioURI)
	return session, nil
}

// SetAudioVolume updates the receiver volume from a 0-100 Android-style percentage.
// AirPlay volume is expressed in dB: 0 is max, and -144 is effectively muted.
func (s *MirrorSession) SetAudioVolume(percent int) error {
	if s == nil || s.client == nil || s.sessionURI == "" {
		return fmt.Errorf("audio control unavailable")
	}
	if percent < 0 {
		percent = 0
	}
	if percent > 100 {
		percent = 100
	}

	db := -144.0
	if percent > 0 {
		db = -30.0 + (float64(percent) * 0.30)
	}
	body := []byte(fmt.Sprintf("volume: %.6f\r\n", db))
	if _, _, err := s.client.rtspRequest("SET_PARAMETER", s.sessionURI, "text/parameters", body, nil); err != nil {
		return fmt.Errorf("set audio volume=%d: %w", percent, err)
	}
	return nil
}
