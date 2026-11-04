package com.example.orderbook;

/** Invalid or impossible order-book command. */

public class BookException extends RuntimeException {
    public BookException(String message) { super(message); }
}
