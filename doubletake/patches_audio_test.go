package airplay

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"howett.net/plist"
)

func TestNewPCMAudioCaptureEncodesOneAndroidPCMFrame(t *testing.T) {
	const (
		spf             = 352
		channels        = 2
		bytesPerSample  = 2
		wantFramePrefix = "200012000002c0"
		wantFrameSize   = 1416
	)

	pcm := make([]byte, spf*channels*bytesPerSample)
	for sample := 0; sample < spf; sample++ {
		value := int16(sample * 8)
		for ch := 0; ch < channels; ch++ {
			offset := (sample*channels + ch) * bytesPerSample
			pcm[offset] = byte(value)
			pcm[offset+1] = byte(value >> 8)
		}
	}

	capture := NewPCMAudioCapture(bytes.NewReader(pcm))
	defer capture.Stop()

	frame := make([]byte, 4096)
	n, err := capture.ReadFrame(frame)
	if err != nil {
		t.Fatalf("ReadFrame returned error: %v", err)
	}
	if n != wantFrameSize {
		t.Fatalf("ALAC frame size = %d, want %d", n, wantFrameSize)
	}
	if got := hex.EncodeToString(frame[:7]); got != wantFramePrefix {
		t.Fatalf("ALAC frame prefix = %s, want %s", got, wantFramePrefix)
	}
}

func TestSetupAudioOnlySkipsVideoSetup(t *testing.T) {
	rtspListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen rtsp: %v", err)
	}
	defer rtspListener.Close()

	requests := make(chan rtspTestRequest, 8)
	serverErr := make(chan error, 1)
	go func() {
		conn, err := rtspListener.Accept()
		if err != nil {
			serverErr <- err
			return
		}
		defer conn.Close()

		reader := bufio.NewReader(conn)
		setupCount := 0
		for {
			req, err := readRTSPTestRequest(reader)
			if err != nil {
				if err == io.EOF || strings.Contains(err.Error(), "closed") {
					serverErr <- nil
					return
				}
				serverErr <- err
				return
			}
			requests <- req

			switch req.method {
			case "SETUP":
				setupCount++
				var setup map[string]interface{}
				if _, err := plist.Unmarshal(req.body, &setup); err != nil {
					serverErr <- fmt.Errorf("decode setup plist: %w", err)
					return
				}
				var respBody []byte
				var headers map[string]string
				switch setupCount {
				case 1:
					if _, ok := setup["streams"]; ok {
						serverErr <- fmt.Errorf("phase 1 SETUP should not include streams")
						return
					}
					if got, _ := setup["timingProtocol"].(string); got != "NTP" {
						serverErr <- fmt.Errorf("expected timingProtocol NTP in phase 1, got %q", got)
						return
					}
					if got := plistInt(setup["timingPort"]); got <= 0 {
						serverErr <- fmt.Errorf("expected positive timingPort in phase 1, got %d", got)
						return
					}
					respBody, err = plist.Marshal(map[string]interface{}{
						"eventPort":  int64(6200),
						"timingPort": int64(6201),
					}, plist.BinaryFormat)
					headers = map[string]string{"Session": "audio-session-1"}
				case 2:
					streams, _ := setup["streams"].([]interface{})
					if len(streams) != 1 {
						serverErr <- fmt.Errorf("expected one stream in phase 2 setup, got %d", len(streams))
						return
					}
					stream, _ := streams[0].(map[string]interface{})
					if streamType := plistInt(stream["type"]); streamType != 96 {
						serverErr <- fmt.Errorf("audio-only phase 2 sent stream type %d, want 96", streamType)
						return
					}
					if _, ok := stream["isMedia"].(bool); !ok {
						serverErr <- fmt.Errorf("audio-only phase 2 should mark stream as media")
						return
					}
					respBody, err = plist.Marshal(map[string]interface{}{
						"streams": []interface{}{
							map[string]interface{}{
								"type":        int64(96),
								"dataPort":    int64(6100),
								"controlPort": int64(6101),
							},
						},
					}, plist.BinaryFormat)
				default:
					serverErr <- fmt.Errorf("unexpected SETUP request %d", setupCount)
					return
				}
				if err != nil {
					serverErr <- err
					return
				}
				if err := writeRTSPTestResponse(conn, 200, headers, respBody); err != nil {
					serverErr <- err
					return
				}
			case "RECORD":
				if req.headers["session"] != "audio-session-1" {
					serverErr <- fmt.Errorf("RECORD Session header = %q, want audio-session-1", req.headers["session"])
					return
				}
				if err := writeRTSPTestResponse(conn, 200, map[string]string{"Audio-Latency": "11025"}, nil); err != nil {
					serverErr <- err
					return
				}
			case "SET_PARAMETER":
				if string(req.body) != "volume: 0.000000\r\n" {
					serverErr <- fmt.Errorf("unexpected SET_PARAMETER body %q", string(req.body))
					return
				}
				if err := writeRTSPTestResponse(conn, 200, nil, nil); err != nil {
					serverErr <- err
					return
				}
			case "TEARDOWN":
				if err := writeRTSPTestResponse(conn, 200, nil, nil); err != nil {
					serverErr <- err
					return
				}
				serverErr <- nil
				return
			default:
				serverErr <- fmt.Errorf("unexpected RTSP method %s", req.method)
				return
			}
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	client := NewAirPlayClient("127.0.0.1", rtspListener.Addr().(*net.TCPAddr).Port)
	if err := client.Connect(ctx); err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer client.Close()

	session, err := client.SetupAudioOnly(ctx, StreamConfig{})
	if err != nil {
		t.Fatalf("SetupAudioOnly: %v", err)
	}
	if !session.HasAudio() {
		t.Fatal("expected audio-only session to have audio")
	}
	if session.DataPort != 0 {
		t.Fatalf("audio-only session DataPort = %d, want 0", session.DataPort)
	}
	if err := session.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	var got []rtspTestRequest
	collectTimeout := time.After(250 * time.Millisecond)
	collecting := true
	for collecting {
		select {
		case req := <-requests:
			got = append(got, req)
		case <-collectTimeout:
			collecting = false
		}
	}
	wantMethods := []string{"SETUP", "SETUP", "RECORD", "SET_PARAMETER", "SET_PARAMETER", "TEARDOWN"}
	if len(got) != len(wantMethods) {
		t.Fatalf("got %d RTSP requests, want %d", len(got), len(wantMethods))
	}
	for index, want := range wantMethods {
		if got[index].method != want {
			t.Fatalf("request %d = %s, want %s", index, got[index].method, want)
		}
	}
	for _, req := range got {
		if req.method == "SETUP" && len(got) > 1 && req.uri != got[0].uri {
			t.Fatal("audio-only should not send a second video SETUP URI")
		}
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func TestSetupAudioOnlyStartsFeedbackKeepalive(t *testing.T) {
	rtspListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen rtsp: %v", err)
	}
	defer rtspListener.Close()

	requests := make(chan rtspTestRequest, 12)
	serverErr := make(chan error, 1)
	go func() {
		conn, err := rtspListener.Accept()
		if err != nil {
			serverErr <- err
			return
		}
		defer conn.Close()

		reader := bufio.NewReader(conn)
		setupCount := 0
		for {
			req, err := readRTSPTestRequest(reader)
			if err != nil {
				if err == io.EOF || strings.Contains(err.Error(), "closed") {
					serverErr <- nil
					return
				}
				serverErr <- err
				return
			}
			requests <- req

			switch req.method {
			case "SETUP":
				setupCount++
				var setup map[string]interface{}
				if _, err := plist.Unmarshal(req.body, &setup); err != nil {
					serverErr <- fmt.Errorf("decode setup plist: %w", err)
					return
				}
				var respBody []byte
				switch setupCount {
				case 1:
					respBody, err = plist.Marshal(map[string]interface{}{}, plist.BinaryFormat)
				case 2:
					respBody, err = plist.Marshal(map[string]interface{}{
						"streams": []interface{}{
							map[string]interface{}{
								"type":        int64(96),
								"dataPort":    int64(6100),
								"controlPort": int64(6101),
							},
						},
					}, plist.BinaryFormat)
				default:
					serverErr <- fmt.Errorf("unexpected SETUP request %d", setupCount)
					return
				}
				if err != nil {
					serverErr <- err
					return
				}
				if err := writeRTSPTestResponse(conn, 200, map[string]string{"Session": "audio-session-1"}, respBody); err != nil {
					serverErr <- err
					return
				}
			case "RECORD", "SET_PARAMETER", "POST", "GET_PARAMETER":
				if err := writeRTSPTestResponse(conn, 200, nil, nil); err != nil {
					serverErr <- err
					return
				}
			case "TEARDOWN":
				if err := writeRTSPTestResponse(conn, 200, nil, nil); err != nil {
					serverErr <- err
					return
				}
				serverErr <- nil
				return
			default:
				serverErr <- fmt.Errorf("unexpected RTSP method %s", req.method)
				return
			}
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	client := NewAirPlayClient("127.0.0.1", rtspListener.Addr().(*net.TCPAddr).Port)
	if err := client.Connect(ctx); err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer client.Close()

	session, err := client.SetupAudioOnly(ctx, StreamConfig{})
	if err != nil {
		t.Fatalf("SetupAudioOnly: %v", err)
	}

	foundFeedback := false
	deadline := time.After(750 * time.Millisecond)
	for !foundFeedback {
		select {
		case req := <-requests:
			if req.method == "POST" && req.uri == "/feedback" {
				foundFeedback = true
			}
		case <-deadline:
			t.Fatal("audio-only session did not start feedback keepalive")
		}
	}

	if err := session.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func TestSetAudioVolumeSendsAirPlayVolumeParameter(t *testing.T) {
	rtspListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen rtsp: %v", err)
	}
	defer rtspListener.Close()

	serverErr := make(chan error, 1)
	go func() {
		conn, err := rtspListener.Accept()
		if err != nil {
			serverErr <- err
			return
		}
		defer conn.Close()

		req, err := readRTSPTestRequest(bufio.NewReader(conn))
		if err != nil {
			serverErr <- err
			return
		}
		if req.method != "SET_PARAMETER" {
			serverErr <- fmt.Errorf("method = %s, want SET_PARAMETER", req.method)
			return
		}
		if string(req.body) != "volume: -15.000000\r\n" {
			serverErr <- fmt.Errorf("body = %q, want volume -15 dB", string(req.body))
			return
		}
		serverErr <- writeRTSPTestResponse(conn, 200, nil, nil)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	client := NewAirPlayClient("127.0.0.1", rtspListener.Addr().(*net.TCPAddr).Port)
	if err := client.Connect(ctx); err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer client.Close()

	session := &MirrorSession{
		client:     client,
		sessionURI: "rtsp://127.0.0.1/session",
	}
	if err := session.SetAudioVolume(50); err != nil {
		t.Fatalf("SetAudioVolume: %v", err)
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}
