package com.jobpilot.system;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.*;
import static org.assertj.core.api.Assertions.assertThat;

class TempProbeTest {
  @Test
  void probe() throws Exception {
    Method isListening = PortFallbackListener.class
        .getDeclaredMethod("isListening", int.class);
    isListening.setAccessible(true);
    Method canBindLoopback = PortFallbackListener.class
        .getDeclaredMethod("canBindLoopback", int.class);
    canBindLoopback.setAccessible(true);

    int p;
    try (ServerSocket s = new ServerSocket(0)) { p = s.getLocalPort(); }
    try (ServerSocket holder = new ServerSocket()) {
      holder.bind(new InetSocketAddress("127.0.0.1", p));
      System.out.println("PROBE occupied=" + p);
      System.out.println("  private isListening    = " + isListening.invoke(null, p));
      System.out.println("  private canBindLoopback= " + canBindLoopback.invoke(null, p));
      System.out.println("  resolvePort            = " + PortFallbackListener.resolvePort(p));
      // 空闲端口对照
      int free;
      try (ServerSocket s = new ServerSocket(0)) { free = s.getLocalPort(); }
      System.out.println("  free port " + free);
      System.out.println("  private isListening    = " + isListening.invoke(null, free));
      System.out.println("  private canBindLoopback= " + canBindLoopback.invoke(null, free));
      System.out.println("  resolvePort            = " + PortFallbackListener.resolvePort(free));
      assertThat(true).isTrue();
    }
  }
}
