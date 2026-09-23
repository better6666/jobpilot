package com.jobpilot.system;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import static org.assertj.core.api.Assertions.assertThat;

class TempProbeTest {
  @Test
  void probe() throws IOException {
    int p;
    try (ServerSocket s = new ServerSocket(0)) { p = s.getLocalPort(); }
    // 场景A：显式绑 127.0.0.1
    try (ServerSocket holder = new ServerSocket()) {
      holder.bind(new InetSocketAddress("127.0.0.1", p));
      System.out.println("PROBE-A loopback p=" + p + " resolvePort=" + PortFallbackListener.resolvePort(p));
    }
    // 场景B：裸通配绑定
    int q;
    try (ServerSocket s = new ServerSocket(0)) { q = s.getLocalPort(); }
    try (ServerSocket holder = new ServerSocket(q)) {
      System.out.println("PROBE-B wildcard p=" + q + " resolvePort=" + PortFallbackListener.resolvePort(q));
    }
    // 场景C：绑 0.0.0.0
    int r;
    try (ServerSocket s = new ServerSocket(0)) { r = s.getLocalPort(); }
    try (ServerSocket holder = new ServerSocket()) {
      holder.bind(new InetSocketAddress("0.0.0.0", r));
      System.out.println("PROBE-C anylocal p=" + r + " resolvePort=" + PortFallbackListener.resolvePort(r));
    }
    assertThat(true).isTrue();
  }
}
