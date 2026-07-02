package bridge

import (
	"encoding/json"
	"sync"

	"xtunnel"
)

var (
	runtimeMu sync.Mutex
	runtime   *xtunnel.ClientRuntime
)

func Start(configJSON string) error {
	var config xtunnel.ClientConfig
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return err
	}

	rt, err := xtunnel.StartClient(config)
	if err != nil {
		return err
	}

	runtimeMu.Lock()
	runtime = rt
	runtimeMu.Unlock()
	return nil
}

func Stop() {
	runtimeMu.Lock()
	rt := runtime
	runtime = nil
	runtimeMu.Unlock()
	if rt != nil {
		rt.Stop()
	}
}

func StatusJSON() string {
	runtimeMu.Lock()
	rt := runtime
	runtimeMu.Unlock()

	status := xtunnel.ClientStatus{}
	if rt != nil {
		status = rt.Status()
	}

	data, err := json.Marshal(status)
	if err != nil {
		return `{"running":false}`
	}
	return string(data)
}

func Logs() string {
	return xtunnel.GetRecentLogs()
}

func ClearLogs() {
	xtunnel.ClearRecentLogs()
}
