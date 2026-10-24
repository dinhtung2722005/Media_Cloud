package com.cloudmedia.api.service;

public class ProcessFixture {
    public static void main(String[] args) throws Exception {
        System.out.println("ready");
        System.out.flush();
        if (args.length > 0 && args[0].equals("sleep")) Thread.sleep(60000);
    }
}
