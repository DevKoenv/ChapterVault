package dev.koenv.chaptervault.extensions.connectors

// Connector-specific context (subset of ExtensionContext + network utilities)
// Actual network utilities (HttpClient, RateLimiter) injected by infrastructure layer at runtime
interface ConnectorContext {
    val connectorId: String
}
