package com.aravind.flazr;

import java.util.logging.Handler;

import pl.brightinventions.slf4android.LogRecord;
import pl.brightinventions.slf4android.MessageValueSupplier;

public class CrashlyticsLoggerHandler extends Handler {
  MessageValueSupplier messageValueSupplier = new MessageValueSupplier();

  @Override
  public void publish(java.util.logging.LogRecord record) {
//    LogRecord logRecord = pl.brightinventions.slf4android.LogRecord.fromRecord(record);
//    StringBuilder messageBuilder = new StringBuilder();
//    messageValueSupplier.append(logRecord, messageBuilder);
//    String tag = record.getLoggerName();
//    int androidLogLevel = logRecord.getLogLevel().getAndroidLevel();
//    Crashlytics.log(androidLogLevel, tag, messageBuilder.toString());
  }

  @Override
  public void close() {}
  @Override
  public void flush() {}
}