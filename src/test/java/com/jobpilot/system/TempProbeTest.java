package com.jobpilot.system;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import static org.assertj.core.api.Assertions.assertThat;

class TempProbeTest {
  static boolean conn(String host, int port, int to) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(host, port), to);
      return true;
    } catch (IOException e) { return false; }
  }
  static boolean connResolved(InetAddress addr, int port, int to) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(addr, port), to);
      return true;
    } catch (IOException e) { return false; }
  }
  @Test
  void probe() throws Exception {
    int p;
    try (ServerSocket s = new ServerSocket(0)) { p = s.getLocalPort(); }
    try (ServerSocket holder = new ServerSocket()) {
      holder.bind(new InetSocketAddress("127.0.0.1", p));
      System.out.println("PROBE p=" + p);
      System.out.println("  conn(127.0.0.1)      = " + conn("127.0.0.1", p, 500));
      System.out.println("  conn(localhost)      = " + conn("localhost", p, 500));
      System.out.println("  connResolved(loopback) = " + connResolved(InetAddress.getLoopbackAddress(), p, 500));
      System.out.println("  conn(0.0.0.0)        = " + conn("0.0.0.0", p, 500));
      Socket direct = new Socket();
      try { direct.connect(new InetSocketAddress("127.0.0.1", p), 500); System.out.println("  raw connect ok"); }
      catch (IOException e) { System.out.println("  raw connect fail: " + e); }
      finally { direct.close(); }
      assertThat(true).isTrue();
    }
  }
}
