package com.datacube.config;

/** Test-only cipher with no profile, platform credential service or filesystem access. */
public final class DraftTestCipher {
    private DraftTestCipher() {}

    public static CredentialCipher create() {
        CredentialProtector inert = new CredentialProtector() {
            public String scheme() { return "synthetic"; }
            public String protect(String plain) { throw new AssertionError("No credentials in recovery fixture"); }
            public String unprotect(String payload) { throw new AssertionError("No credentials in recovery fixture"); }
        };
        return new CredentialCipher(inert, inert, inert);
    }
}
