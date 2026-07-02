package xtunnel

import (
	"bytes"
	"io"
	"log"
	"os"
	"sync"
)

const maxLogBytes = 256 * 1024

type ringLogBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (r *ringLogBuffer) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if len(p) >= maxLogBytes {
		r.b.Reset()
		_, _ = r.b.Write(p[len(p)-maxLogBytes:])
		return len(p), nil
	}

	if r.b.Len()+len(p) > maxLogBytes {
		drop := r.b.Len() + len(p) - maxLogBytes
		if drop > 0 {
			buf := r.b.Bytes()
			if drop >= len(buf) {
				r.b.Reset()
			} else {
				remaining := append([]byte(nil), buf[drop:]...)
				r.b.Reset()
				_, _ = r.b.Write(remaining)
			}
		}
	}

	_, _ = r.b.Write(p)
	return len(p), nil
}

func (r *ringLogBuffer) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.b.String()
}

func (r *ringLogBuffer) Reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.b.Reset()
}

var capturedLogs ringLogBuffer

func init() {
	log.SetOutput(io.MultiWriter(os.Stderr, &capturedLogs))
}

func GetRecentLogs() string {
	return capturedLogs.String()
}

func ClearRecentLogs() {
	capturedLogs.Reset()
}
