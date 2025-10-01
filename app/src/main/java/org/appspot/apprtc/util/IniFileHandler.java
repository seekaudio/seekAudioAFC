package org.appspot.apprtc.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.Set;

public class IniFileHandler {
  private Properties properties;
  private String filename;

  public IniFileHandler(String filename) {
    this.filename = filename;
    this.properties = new Properties();
  }

  // 读取INI文件
  public void load() throws IOException {
    try (FileInputStream input = new FileInputStream(filename)) {
      properties.load(input);
    }
  }

  // 写入INI文件
  public void save() throws IOException {
    try (FileOutputStream output = new FileOutputStream(filename)) {
      properties.store(output, "INI Configuration File");
    }
  }

  // 获取值
  public String getValue(String key) {
    return properties.getProperty(key);
  }

  // 获取值（带默认值）
  public String getValue(String key, String defaultValue) {
    return properties.getProperty(key, defaultValue);
  }

  // 设置值
  public void setValue(String key, String value) {
    properties.setProperty(key, value);
  }

  // 获取所有键
  public Set<String> getAllKeys() {
    return properties.stringPropertyNames();
  }

}
