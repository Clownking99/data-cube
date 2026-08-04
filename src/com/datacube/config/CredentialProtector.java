package com.datacube.config;

/** Protects one credential payload; version framing remains in {@link CredentialCipher}. */
interface CredentialProtector {

    String scheme();

    String protect(String plain);

    String unprotect(String payload);
}
