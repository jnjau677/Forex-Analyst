package com.devcode940.ForexAnalyst.data.model

@JvmInline value class SignalId(val value: String) {
    companion object { fun generate(): SignalId = SignalId(java.util.UUID.randomUUID().toString()) }
}
@JvmInline value class PairSymbol(val value: String) {
    init { require(value.isNotBlank()) { "PairSymbol cannot be blank" } }
}
@JvmInline value class AlertId(val value: String) {
    companion object { fun generate(): AlertId = AlertId(java.util.UUID.randomUUID().toString()) }
}
@JvmInline value class Timeframe(val value: String) {
    init { require(value in VALID_TIMEFRAMES) { "Invalid timeframe: $value" } }
    companion object { val VALID_TIMEFRAMES = setOf("M1","M5","M15","H1","H4","D1","W1") }
}