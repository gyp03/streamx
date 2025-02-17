package com.streamxhub.streamx.plugin.profiling.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Base64;
import java.util.Map;
import java.util.zip.Deflater;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** @author benjobs */
public class Utils {

  protected static ObjectMapper mapper = new ObjectMapper();

  static {
    mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
    mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    mapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  public static ObjectMapper getMapper() {
    return mapper;
  }

  public static String toJsonString(Object obj) {
    if (obj == null) {
      return "";
    }
    try {
      return mapper.writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(
          String.format("Failed to serialize %s (%s)", obj, obj.getClass()), e);
    }
  }

  public static byte[] toByteArray(InputStream input) {
    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
      int readByteCount;
      byte[] data = new byte[16 * 1024];
      while ((readByteCount = input.read(data, 0, data.length)) != -1) {
        byteArrayOutputStream.write(data, 0, readByteCount);
      }
      byteArrayOutputStream.flush();
      return byteArrayOutputStream.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static String zipString(String text) {
    // 使用指定的压缩级别创建一个新的压缩器。
    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
    // 设置压缩输入数据。
    deflater.setInput(text.getBytes());
    // 当被调用时，表示压缩应该以输入缓冲区的当前内容结束。
    deflater.finish();
    final byte[] bytes = new byte[256];
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(256);

    while (!deflater.finished()) {
      // 压缩输入数据并用压缩数据填充指定的缓冲区。
      int length = deflater.deflate(bytes);
      outputStream.write(bytes, 0, length);
    }
    // 关闭压缩器并丢弃任何未处理的输入。
    deflater.end();
    return Base64.getEncoder().encodeToString(outputStream.toByteArray());
  }

  public static String getLocalHostName() {
    try {
      Map<String, String> env = System.getenv();
      if (env.containsKey("COMPUTERNAME")) {
        return env.get("COMPUTERNAME");
      } else if (env.containsKey("HOSTNAME")) {
        return env.get("HOSTNAME");
      } else {
        return InetAddress.getLocalHost().getHostName();
      }
    } catch (Throwable e) {
      return "unknown_localhost_name";
    }
  }
}
