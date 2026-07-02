package xtunnel

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/url"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/google/uuid"
)

const (
	defaultUDPBlockPort = 443
	defaultDNSServer    = "https://doh.pub/dns-query"
	defaultECHDomain    = "cloudflare-ech.com"
	defaultConnectionN  = 3
)

type ClientConfig struct {
	ListenAddrs   []string `json:"listen_addrs"`
	ForwardAddr   string   `json:"forward_addr"`
	TargetIPs     []string `json:"target_ips,omitempty"`
	UDPBlockPorts []int    `json:"udp_block_ports,omitempty"`
	Token         string   `json:"token,omitempty"`
	ConnectionNum int      `json:"connection_num,omitempty"`
	Insecure      bool     `json:"insecure,omitempty"`
	DNSServer     string   `json:"dns_server,omitempty"`
	ECHDomain     string   `json:"ech_domain,omitempty"`
	Fallback      bool     `json:"fallback,omitempty"`
	IPStrategy    string   `json:"ip_strategy,omitempty"`
}

type ClientStatus struct {
	Running        bool      `json:"running"`
	ClientID       string    `json:"client_id,omitempty"`
	ForwardAddr    string    `json:"forward_addr,omitempty"`
	ListenAddrs    []string  `json:"listen_addrs,omitempty"`
	StartedAt      time.Time `json:"started_at,omitempty"`
	ActiveChannels int       `json:"active_channels"`
}

type ClientRuntime struct {
	config    ClientConfig
	pool      *ECHPool
	ctx       context.Context
	cancel    context.CancelFunc
	startedAt time.Time

	stopOnce sync.Once

	mu        sync.RWMutex
	listeners []net.Listener
	running   bool
}

var (
	activeClientMu sync.Mutex
	activeClient   *ClientRuntime
)

func DefaultClientConfig() ClientConfig {
	return ClientConfig{
		UDPBlockPorts: []int{defaultUDPBlockPort},
		ConnectionNum: defaultConnectionN,
		DNSServer:     defaultDNSServer,
		ECHDomain:     defaultECHDomain,
	}
}

func RunCLI() error {
	registerFlags()
	flag.Parse()

	if strings.TrimSpace(listenAddr) == "" {
		flag.Usage()
		return nil
	}

	listeners := splitAndTrim(strings.Split(listenAddr, ","))
	serverAddr, isServer := detectServerListener(listeners)
	if isServer {
		if forwardAddr != "" {
			config, err := parseSOCKS5Addr(forwardAddr)
			if err != nil {
				return fmt.Errorf("[服务端] 解析SOCKS5代理地址失败: %w", err)
			}
			socks5Config = config
			log.Printf("[服务端] 使用SOCKS5前置代理: %s", config.Host)
			if config.Username != "" {
				log.Printf("[服务端] SOCKS5代理认证已启用")
			}
		} else {
			log.Printf("[服务端] 直连模式（未配置SOCKS5代理）")
		}
		return runWebSocketServer(serverAddr)
	}

	rt, err := StartClient(ClientConfig{
		ListenAddrs:   listeners,
		ForwardAddr:   forwardAddr,
		TargetIPs:     splitAndTrim(strings.Split(ipAddr, ",")),
		UDPBlockPorts: parsePortList(udpBlockPortsStr),
		Token:         token,
		ConnectionNum: connectionNum,
		Insecure:      insecure,
		DNSServer:     dnsServer,
		ECHDomain:     echDomain,
		Fallback:      fallback,
		IPStrategy:    ips,
	})
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	rt.Stop()
	return nil
}

func StartClient(config ClientConfig) (*ClientRuntime, error) {
	cfg := normalizeClientConfig(config)
	targetIPs := append([]string(nil), cfg.TargetIPs...)

	activeClientMu.Lock()
	defer activeClientMu.Unlock()
	if activeClient != nil {
		return nil, fmt.Errorf("客户端已在运行")
	}

	resetClientGlobals()
	applyClientConfig(cfg)

	if err := prepareClientRuntime(targetIPs); err != nil {
		resetClientGlobals()
		return nil, err
	}

	ctx, cancel := context.WithCancel(context.Background())
	rt := &ClientRuntime{
		config:    cfg,
		ctx:       ctx,
		cancel:    cancel,
		startedAt: time.Now(),
		running:   true,
	}

	pool := NewECHPool(forwardAddr, connectionNum, targetIPs, clientID)
	echPool = pool
	rt.pool = pool
	pool.Start()

	for _, rule := range cfg.ListenAddrs {
		var err error
		switch {
		case strings.HasPrefix(rule, "tcp://"):
			err = startTCPListener(rule, rt)
		case strings.HasPrefix(rule, "socks5://"):
			err = startSOCKS5Listener(rule, rt)
		case strings.HasPrefix(rule, "http://"):
			err = startHTTPListener(rule, rt)
		case strings.HasPrefix(rule, "ws://"), strings.HasPrefix(rule, "wss://"):
			err = fmt.Errorf("StartClient 不支持服务端监听地址: %s", rule)
		default:
			log.Printf("[客户端] 忽略未知协议的监听地址: %s", rule)
		}
		if err != nil {
			rt.stopLocked()
			resetClientGlobals()
			return nil, err
		}
	}

	activeClient = rt
	return rt, nil
}

func (r *ClientRuntime) Stop() {
	if r == nil {
		return
	}
	activeClientMu.Lock()
	if activeClient == r {
		activeClient = nil
	}
	activeClientMu.Unlock()
	r.stopLocked()
}

func (r *ClientRuntime) stopLocked() {
	r.stopOnce.Do(func() {
		r.cancel()

		r.mu.Lock()
		listeners := append([]net.Listener(nil), r.listeners...)
		r.listeners = nil
		r.running = false
		r.mu.Unlock()

		for _, l := range listeners {
			_ = l.Close()
		}
		if r.pool != nil {
			r.pool.Stop()
		}
	})
}

func (r *ClientRuntime) Status() ClientStatus {
	if r == nil {
		return ClientStatus{}
	}
	r.mu.RLock()
	status := ClientStatus{
		Running:     r.running,
		ClientID:    clientID,
		ForwardAddr: r.config.ForwardAddr,
		ListenAddrs: append([]string(nil), r.config.ListenAddrs...),
		StartedAt:   r.startedAt,
	}
	r.mu.RUnlock()
	if r.pool != nil {
		status.ActiveChannels = r.pool.ActiveSessions()
	}
	return status
}

func (r *ClientRuntime) addListener(l net.Listener) {
	r.mu.Lock()
	r.listeners = append(r.listeners, l)
	r.mu.Unlock()
}

func (r *ClientRuntime) isStopping() bool {
	select {
	case <-r.ctx.Done():
		return true
	default:
		return false
	}
}

func startTCPListener(rule string, rt *ClientRuntime) error {
	rule = strings.TrimPrefix(rule, "tcp://")
	parts := strings.Split(rule, "/")
	if len(parts) != 2 {
		return fmt.Errorf("[客户端] TCP监听规则无效: %s", rule)
	}
	lAddr, tAddr := strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1])
	l, err := net.Listen("tcp", lAddr)
	if err != nil {
		return fmt.Errorf("[客户端] TCP监听失败: %w", err)
	}
	rt.addListener(l)
	log.Printf("[客户端] TCP转发: %s -> %s", lAddr, tAddr)
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				if rt.isStopping() {
					return
				}
				continue
			}
			go handleLocalTCP(c, tAddr)
		}
	}()
	return nil
}

func startSOCKS5Listener(addr string, rt *ClientRuntime) error {
	h, u, p, err := parseAuthAndAddr(strings.TrimPrefix(addr, "socks5://"))
	if err != nil {
		return fmt.Errorf("[客户端] SOCKS5地址解析失败: %w", err)
	}
	l, err := net.Listen("tcp", h)
	if err != nil {
		return fmt.Errorf("[客户端] SOCKS5监听失败: %w", err)
	}
	rt.addListener(l)
	log.Printf("[客户端] SOCKS5 代理: %s", h)
	cfgp := &ProxyConfig{u, p, h}
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				if rt.isStopping() {
					return
				}
				continue
			}
			go handleSOCKS5(c, cfgp)
		}
	}()
	return nil
}

func startHTTPListener(addr string, rt *ClientRuntime) error {
	h, u, p, err := parseAuthAndAddr(strings.TrimPrefix(addr, "http://"))
	if err != nil {
		return fmt.Errorf("[客户端] HTTP地址解析失败: %w", err)
	}
	l, err := net.Listen("tcp", h)
	if err != nil {
		return fmt.Errorf("[客户端] HTTP监听失败: %w", err)
	}
	rt.addListener(l)
	log.Printf("[客户端] HTTP 代理: %s", h)
	cfgp := &ProxyConfig{u, p, h}
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				if rt.isStopping() {
					return
				}
				continue
			}
			go handleHTTP(c, cfgp)
		}
	}()
	return nil
}

func prepareClientRuntime(targetIPs []string) error {
	if len(splitAndTrim(strings.Split(listenAddr, ","))) == 0 {
		return fmt.Errorf("[客户端] 至少需要一个监听地址")
	}
	if strings.TrimSpace(forwardAddr) == "" {
		return fmt.Errorf("[客户端] 客户端模式必须指定服务地址 (-f ws:// 或 -f wss://)")
	}
	if connectionNum <= 0 {
		return fmt.Errorf("[客户端] 参数 connection_num 必须大于 0 (当前: %d)", connectionNum)
	}

	ipStrategy = parseIPStrategy(ips)
	if ips != "" {
		log.Printf("[客户端] IP 访问策略: %s (code: %d)", ips, ipStrategy)
	}

	forwardURL, err := url.Parse(forwardAddr)
	if err != nil {
		return fmt.Errorf("[客户端] 无效的服务地址: %w", err)
	}
	scheme := strings.ToLower(forwardURL.Scheme)
	if scheme != "wss" && scheme != "ws" {
		return fmt.Errorf("[客户端] 仅支持 ws:// 或 wss:// 协议 (当前: %s)", forwardURL.Scheme)
	}

	if scheme == "wss" {
		if insecure {
			if !fallback {
				fallback = true
				log.Printf("[客户端] wss 模式且启用不校验证书（insecure）：已自动禁用 ECH（fallback）")
			} else {
				log.Printf("[客户端] wss 模式且启用不校验证书（insecure）")
			}
		}
		if !fallback {
			if err := prepareECH(); err != nil {
				return fmt.Errorf("[客户端] 获取 ECH 公钥失败: %w", err)
			}
		} else {
			log.Printf("[客户端] fallback 模式已启用：禁用 ECH，使用标准 TLS 1.3")
		}
	} else {
		if insecure {
			log.Printf("[客户端] ws 模式已忽略 insecure 参数")
		}
		if fallback {
			log.Printf("[客户端] ws 模式已忽略 fallback/ECH 参数")
		}
	}

	udpBlockPorts = make(map[int]struct{})
	for _, port := range parsePortList(udpBlockPortsStr) {
		if port > 0 && port < 65536 {
			udpBlockPorts[port] = struct{}{}
		}
	}

	clientID = uuid.NewString()
	log.Printf("[客户端] 客户端ID: %s", clientID)
	if len(targetIPs) > 0 {
		log.Printf("[客户端] 指定连接IP: %s", strings.Join(targetIPs, ","))
	}
	return nil
}

func normalizeClientConfig(config ClientConfig) ClientConfig {
	cfg := DefaultClientConfig()
	if len(config.ListenAddrs) > 0 {
		cfg.ListenAddrs = splitAndTrim(config.ListenAddrs)
	}
	cfg.ForwardAddr = strings.TrimSpace(config.ForwardAddr)
	if len(config.TargetIPs) > 0 {
		cfg.TargetIPs = splitAndTrim(config.TargetIPs)
	}
	if len(config.UDPBlockPorts) > 0 {
		cfg.UDPBlockPorts = append([]int(nil), config.UDPBlockPorts...)
	}
	if config.Token != "" {
		cfg.Token = config.Token
	}
	if config.ConnectionNum > 0 {
		cfg.ConnectionNum = config.ConnectionNum
	}
	cfg.Insecure = config.Insecure
	if config.DNSServer != "" {
		cfg.DNSServer = strings.TrimSpace(config.DNSServer)
	}
	if config.ECHDomain != "" {
		cfg.ECHDomain = strings.TrimSpace(config.ECHDomain)
	}
	cfg.Fallback = config.Fallback
	if config.IPStrategy != "" {
		cfg.IPStrategy = strings.TrimSpace(config.IPStrategy)
	}
	return cfg
}

func applyClientConfig(config ClientConfig) {
	listenAddr = strings.Join(config.ListenAddrs, ",")
	forwardAddr = config.ForwardAddr
	ipAddr = strings.Join(config.TargetIPs, ",")
	udpBlockPortsStr = formatPortList(config.UDPBlockPorts)
	token = config.Token
	connectionNum = config.ConnectionNum
	insecure = config.Insecure
	dnsServer = config.DNSServer
	echDomain = config.ECHDomain
	fallback = config.Fallback
	ips = config.IPStrategy
	certFile = ""
	keyFile = ""
	cidrs = "0.0.0.0/0,::/0"
	socks5Config = nil
}

func resetClientGlobals() {
	listenAddr = ""
	forwardAddr = ""
	ipAddr = ""
	udpBlockPortsStr = ""
	token = ""
	connectionNum = 0
	insecure = false
	dnsServer = ""
	echDomain = ""
	fallback = false
	ips = ""
	socks5Config = nil
	ipStrategy = IPStrategyDefault
	clientID = ""
	udpBlockPorts = nil
	echPool = nil
	echListMu.Lock()
	echList = nil
	echListMu.Unlock()
}

func detectServerListener(listeners []string) (string, bool) {
	for _, l := range listeners {
		switch {
		case strings.HasPrefix(l, "ws://"), strings.HasPrefix(l, "wss://"):
			return l, true
		}
	}
	return "", false
}

func splitAndTrim(parts []string) []string {
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func parsePortList(raw string) []int {
	items := splitAndTrim(strings.Split(raw, ","))
	ports := make([]int, 0, len(items))
	for _, item := range items {
		port, err := strconv.Atoi(item)
		if err == nil {
			ports = append(ports, port)
		}
	}
	return ports
}

func formatPortList(ports []int) string {
	if len(ports) == 0 {
		return ""
	}
	items := make([]string, 0, len(ports))
	for _, port := range ports {
		items = append(items, strconv.Itoa(port))
	}
	return strings.Join(items, ",")
}

func sleepWithContext(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-ctx.Done():
		return false
	}
}
