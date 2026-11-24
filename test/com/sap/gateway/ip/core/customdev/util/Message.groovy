package com.sap.gateway.ip.core.customdev.util
// Minimal stand-in for the CPI Message API, for local tests only.
class Message {
    def body; Map props = [:]; Map headers = [:]
    def <T> T getBody(Class<T> c) { body as T }
    void setBody(b) { body = b }
    def getProperty(String k) { props[k] }
    void setProperty(String k, v) { props[k] = v }
    void setHeader(String k, v) { headers[k] = v }
}
