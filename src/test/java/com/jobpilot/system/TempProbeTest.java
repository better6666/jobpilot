package com.jobpilot.system;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import static org.assertj.core.api.Assertions.assertThat;

class TempProbeTest {
  @Test
  void probe() throws Exception {
    // 完全照抄失败用例的写法
    try (ServerSocket ignored = new ServerSocket(0)) {
      int occupied = ignored.getLocalPort();
      System.out.println("PROBE occupied=" + occupied);
      System.out.println("  isBound=" + ignored.isBound());
      // 关键：occupied 本身的三道探测
      boolean cb, cbl, ls;
      try (ServerSocket s2 = new ServerSocket()) { s2.bind(new InetSocketAddress(occupied)); cb = true; }
      catch (IOException e) { cb = false; }
      try (ServerSocket s2 = new ServerSocket()) { s2.bind(new InetSocketAddress("127.0.0.1", occupied)); cbl = true; }
      catch (IOException e) { cbl = false; }
      try (Socket s2 = new Socket()) { s2.connect(new InetSocketAddress("127.0.0.1", occupied), 500); ls = true; }
      catch (IOException e) { ls = false; }
      System.out.println("  occupied 三道: canBind=" + cb + " canBindLoop=" + cbl + " isListening=" + ls);
      System.out.println("  resolvePort=" + PortFallbackListener.resolvePort(occupied));
      // 逐个探测 requested+1 .. +5
      for (int i = 1; i <= 5; i++) {
        int port = occupied + i;
        boolean canBind, canBindLoop, listening;
        try (ServerSocket s = new ServerSocket()) { s.bind(new InetSocketAddress(port)); canBind = true; }
        catch (IOException e) { canBind = false; }
        try (ServerSocket s = new ServerSocket()) { s.bind(new InetSocketAddress("127.0.0.1", port)); canBindLoop = true; }
        catch (IOException e) { canBindLoop = false; }
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", port), 500); listening = true; }
        catch (IOException e) { listening = false; }
        System.out.println("    +" + i + " port=" + port
          + " canBind=" + canBind + " canBindLoop=" + canBindLoop + " listening=" + listening
          + " => available=" + (canBind && canBindLoop && !listening));
      }
      assertThat(true).isTrue();
    }
  }
}
