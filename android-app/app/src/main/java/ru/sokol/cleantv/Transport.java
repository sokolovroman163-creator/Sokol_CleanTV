package ru.sokol.cleantv;

interface Transport {
  String shell(String command) throws Exception;

  void push(byte[] bytes, String remote) throws Exception;

  byte[] pull(String remote, int limit) throws Exception;
}
