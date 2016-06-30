package com.aravind.flazr;

import android.app.Application;

import org.slf4j.LoggerFactory;

import pl.brightinventions.slf4android.LoggerConfiguration;

/**
 * Created by aravind on 6/30/2016.
 */
public class CustomApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        LoggerConfiguration.configuration().addHandlerToLogger("DemoLog", LoggerConfiguration.fileLogHandler(this));

        LoggerFactory.getLogger(CustomApplication.class).debug("Hello World");

//        LoggerConfiguration.configuration()
//                .removeRootLogcatHandler()
//                .addHandlerToRootLogger(new CrashlyticsLoggerHandler());
    }
}
