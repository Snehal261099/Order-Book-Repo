package com.example.orderbook;

/**
 * GTC rests; IOC cancels any residual; FOK executes all quantity or none.
 */
public enum TimeInForce {GTC, IOC, FOK}
