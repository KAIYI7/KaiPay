package com.lky.experimental.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A separate executable with no KaiPay payment, ledger, Kafka or database dependencies. */
@SpringBootApplication
public class BridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}
